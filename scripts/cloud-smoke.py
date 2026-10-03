#!/usr/bin/env python3
"""Explicit cloud preflight/smoke and deployment helpers; standard library only.
Offline tests exercise decision logic. Only a real smoke run is cloud evidence.
"""
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
import math
import os
import cloud_benchmark as benchmark
from cloud_benchmark import (CheckFailure, require, lambda_measurement,
                             check_identity, check_replay, correlated_attempts)
from pathlib import Path
import socket
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def aws(*args, timeout=90):
    # Never print raw CLI errors, invocation payloads or object bodies.
    try:
        result = subprocess.run(["aws", *args, "--output", "json", "--no-cli-pager"],
                                capture_output=True, text=True, timeout=timeout)
        require(result.returncode == 0, "AWS operation failed: " + "/".join(args[:2]))
        return json.loads(result.stdout or "{}")
    except (OSError, subprocess.TimeoutExpired, ValueError):
        raise CheckFailure("AWS CLI unavailable, timed out or returned invalid JSON") from None


def stamp(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(parsed.tzinfo is not None, "Timestamp must contain a timezone")
        return parsed
    except (AttributeError, ValueError):
        raise CheckFailure("Missing or invalid timestamp") from None


def validate_account(plan, quota):
    require(plan.get("accountPlanType") == "FREE", "Account plan must be FREE; do not upgrade")
    require(plan.get("accountPlanStatus") == "ACTIVE", "Account plan must be ACTIVE")
    credits = plan.get("accountPlanRemainingCredits", {})
    try:
        amount = float(credits.get("amount", 0))
    except (ValueError, TypeError):
        amount = 0
    require(math.isfinite(amount) and amount > 0 and credits.get("unit") == "USD",
            "Positive available USD credits must be verified")
    require(stamp(plan.get("accountPlanExpirationDate")) > datetime.now(timezone.utc),
            "Account plan has expired")
    capacity = quota.get("AccountLimit", {}).get("UnreservedConcurrentExecutions")
    require(isinstance(capacity, int) and capacity >= 101,
            "Reserved concurrency=1 requires at least 101 currently unreserved units; keep deployment pending")


def preflight(args):
    evidence = json.loads(Path(args.eligibility).read_text())
    require(evidence.get("accountId") == args.account_id and evidence.get("region") == args.region
            and evidence.get("instanceType") == args.instance_type, "Eligibility evidence does not match deployment")
    age = datetime.now(timezone.utc) - stamp(evidence.get("confirmedAt"))
    require(timedelta(0) <= age <= timedelta(hours=24), "Eligibility evidence must be refreshed within 24 hours")
    require(evidence.get("atlasTier") == "M0" and evidence.get("atlasDatabaseRolesVerified") is True,
            "Verify Atlas M0 and database-scoped readWrite plus dbAdmin")
    resources = evidence.get("resources", {})
    for name in ("ec2", "publicIpv4", "ebsGp3", "s3", "lambda", "cloudWatch", "vpc"):
        require(isinstance(resources.get(name), str) and len(resources[name].strip()) >= 10,
                "Missing resource eligibility evidence: " + name)
    os.environ["AWS_DEFAULT_REGION"] = args.region
    identity = aws("sts", "get-caller-identity")
    require(identity.get("Account") == args.account_id, "Configured AWS identity has the wrong account")
    plan = aws("freetier", "get-account-plan-state")
    require(plan.get("accountId") == args.account_id, "Account-plan response does not match identity")
    quota = aws("lambda", "get-account-settings")
    validate_account(plan, quota)
    types = aws("ec2", "describe-instance-types", "--instance-types", args.instance_type)["InstanceTypes"]
    require(len(types) == 1 and "x86_64" in types[0]["ProcessorInfo"]["SupportedArchitectures"],
            "Instance type must support x86_64")
    print(json.dumps({"checkedAt": datetime.now(timezone.utc).isoformat(), "account": plan,
                      "lambdaAccountLimit": quota["AccountLimit"], "instanceType": args.instance_type,
                      "eligibilityEvidenceSha256": hashlib.sha256(Path(args.eligibility).read_bytes()).hexdigest()}))


ALLOWED_TYPES = {
    "aws_vpc", "aws_subnet", "aws_internet_gateway", "aws_route_table", "aws_route",
    "aws_route_table_association", "aws_vpc_endpoint", "aws_security_group",
    "aws_vpc_security_group_ingress_rule", "aws_vpc_security_group_egress_rule",
    "aws_s3_bucket", "aws_s3_bucket_public_access_block", "aws_s3_bucket_ownership_controls",
    "aws_s3_bucket_versioning", "aws_s3_bucket_server_side_encryption_configuration", "aws_s3_bucket_policy",
    "aws_lambda_permission", "aws_s3_bucket_notification", "aws_iam_role",
    "aws_iam_instance_profile", "aws_iam_role_policy", "aws_key_pair", "aws_instance",
    "aws_cloudwatch_log_group", "aws_lambda_function", "aws_lambda_function_event_invoke_config",
}


def terraformPlanHasNoUnapprovedChargeableNetworkComponents(plan):
    """Guard actual terraform show -json output; not an eligibility or cost oracle."""
    resources = [r for r in plan.get("resource_changes", []) if r.get("mode") == "managed"
                 and r["change"].get("after") is not None]
    require(resources, "Plan contains no managed resources")
    types = {r["type"] for r in resources}
    require(types <= ALLOWED_TYPES, "Plan has unapproved resource types")
    require(not types.intersection({"aws_nat_gateway", "aws_lb"}), "Unapproved network resource")
    def of(kind):
        return [r["change"]["after"] for r in resources if r["type"] == kind]
    require(len(of("aws_vpc_endpoint")) == 1 and
            all(v.get("vpc_endpoint_type") == "Gateway" for v in of("aws_vpc_endpoint")),
            "Only one S3 Gateway endpoint is allowed")
    require(len(of("aws_instance")) == 1 and len(of("aws_lambda_function")) == 1,
            "Expected exactly one EC2 instance and one Lambda")
    function = of("aws_lambda_function")[0]
    for key, expected in {"timeout": 300, "memory_size": 512, "runtime": "java21",
                          "architectures": ["x86_64"], "reserved_concurrent_executions": 1}.items():
        require(function.get(key) == expected, "Unexpected Lambda setting: " + key)
    known = function.get("environment", [{}])[0].get("variables", {})
    change = next(r["change"] for r in resources if r["type"] == "aws_lambda_function")
    unknown = change.get("after_unknown", {}).get("environment", [{}])[0].get("variables", {})
    require(isinstance(unknown, dict), "Cannot verify unknown Lambda environment keys")
    require(set(known) | set(unknown) == {"RECONCILIATION_API_URI", "RECONCILIATION_CONFIG_BUCKET",
                                       "RECONCILIATION_CONFIG_KEY"}, "Unexpected Lambda environment keys")
    require(known.get("RECONCILIATION_CONFIG_KEY") == "runtime-config/worker.json",
            "Unexpected runtime config key")
    require(len(of("aws_cloudwatch_log_group")) == 1 and
            all(v.get("retention_in_days") == 7 for v in of("aws_cloudwatch_log_group")),
            "Logs must retain seven days")
    retry = of("aws_lambda_function_event_invoke_config")
    require(len(retry) == 1 and retry[0].get("maximum_event_age_in_seconds") == 3600
            and retry[0].get("maximum_retry_attempts") == 2, "Unexpected async retry bounds")


def token(value):
    return isinstance(value, str) and 1 <= len(value) <= 1024 and all(33 <= ord(c) <= 126 for c in value)


def runtime_files(values):
    require(set(values) == {"demoToken", "workerToken", "mongoUri"}, "Invalid runtime configuration fields")
    require(token(values["demoToken"]) and token(values["workerToken"])
            and values["demoToken"] != values["workerToken"], "Invalid or equal runtime tokens")
    uri = values["mongoUri"]
    require(isinstance(uri, str) and all(32 <= ord(c) <= 126 for c in uri), "Invalid Mongo URI")
    parsed = urllib.parse.urlsplit(uri)
    query = {}
    for key, value in urllib.parse.parse_qsl(parsed.query, keep_blank_values=True):
        query.setdefault(key.lower(), []).append(value.lower())
    require(parsed.hostname and parsed.path not in ("", "/") and not parsed.fragment,
            "Mongo URI must identify an application database")
    require(parsed.scheme == "mongodb+srv" or
            (parsed.scheme == "mongodb" and query.get("tls") == ["true"]), "Mongo TLS is required")
    require(not any(query.get(k) != ["false"] for k in
                    ("tlsinsecure", "tlsallowinvalidcertificates", "tlsallowinvalidhostnames") if k in query)
            and query.get("tls", ["true"]) == ["true"] and query.get("ssl", ["true"]) == ["true"],
            "Mongo TLS verification cannot be disabled")
    def quoted(value):
        return '"' + "".join("\\" + c if c in '\\\"$`' else c for c in value) + '"'
    # systemd EnvironmentFile double quotes; no shell evaluation.
    env = "\n".join(name + "=" + quoted(value) for name, value in {
        "RECONCILIATION_SECURITY_DEMOTOKEN": values["demoToken"],
        "RECONCILIATION_SECURITY_WORKERTOKEN": values["workerToken"],
        "SPRING_MONGODB_URI": uri,
    }.items()) + "\n"
    return env, json.dumps({"workerToken": values["workerToken"]})


def load_protected(path):
    file = Path(path)
    require(file.is_file() and not file.is_symlink(), "Runtime input must be a regular file")
    require(stat.S_IMODE(file.stat().st_mode) == 0o600, "Runtime input must have mode 0600")
    require(file.stat().st_size <= 16384, "Runtime input exceeds limit")
    return json.loads(file.read_text())


def write_protected(path, content):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as file:
        file.write(content)


def empty_bucket(bucket, call=aws):
    # Re-read first page after each deletion: no invalidated pagination markers.
    for _ in range(10000):
        page = call("s3api", "list-object-versions", "--bucket", bucket,
                    "--max-keys", "1000", "--no-paginate")
        entries = [{"Key": item["Key"], "VersionId": item["VersionId"]}
                   for key in ("Versions", "DeleteMarkers") for item in page.get(key, [])]
        if not entries:
            require(not page.get("IsTruncated"), "Unexpected empty truncated version page")
            return
        result = call("s3api", "delete-objects", "--bucket", bucket, "--delete",
                      json.dumps({"Objects": entries, "Quiet": True}))
        require(not result.get("Errors"), "S3 version deletion failed; do not destroy a nonempty bucket")
    raise CheckFailure("Bucket is still changing; stop producers before teardown")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http(method, url, bearer=None, body=None, headers=None):
    headers = dict(headers or {})
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=25) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as failure:
        return failure.code, failure.read(), failure.headers
    except (OSError, urllib.error.URLError):
        raise CheckFailure("HTTP transport failed (details withheld)") from None


def api(base, path, bearer, method="GET", body=None, expected=(200,)):
    status, raw, headers = http(method, base + path, bearer, body)
    require(status in expected, "Unexpected API response status: " + str(status))
    return json.loads(raw or "{}")


def wait_for(fetch, accept, seconds=420):
    deadline = time.monotonic() + seconds
    while True:
        value = fetch()
        if accept(value):
            return value
        require(time.monotonic() < deadline, "Cloud check deadline exceeded; inspect run and recover manually")
        time.sleep(3)


def outputs(path):
    return {k: v["value"] for k, v in json.loads(Path(path).read_text()).items()}


def check_worker_permissions(statements, bucket_arn, log_arn, function_arn=None):
    eni = {"ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets",
           "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses"}
    if any(entry.get("Effect") == "Allow" and
           eni.intersection([entry["Action"]] if isinstance(entry.get("Action"), str)
                            else entry.get("Action", [])) for entry in statements):
        require(function_arn and any(
            entry.get("Effect") == "Deny" and set(entry.get("Action", [])) == eni
            and entry.get("Resource") == "*"
            and entry.get("Condition") == {"ArnEquals": {"lambda:SourceFunctionArn": function_arn}}
            and "NotAction" not in entry and "NotResource" not in entry
            for entry in statements), "Worker ENI permissions lack the exact SourceFunctionArn deny")
    for entry in statements:
        if entry.get("Effect") != "Allow":
            continue
        actions = entry.get("Action", [])
        resources = entry.get("Resource", [])
        actions = [actions] if isinstance(actions, str) else actions
        resources = [resources] if isinstance(resources, str) else resources
        require(actions and resources and "NotAction" not in entry and "NotResource" not in entry,
                "Unbounded worker permission")
        for action in actions:
            allowed = {
                "s3:GetObject": {bucket_arn + "/settlements/*", bucket_arn + "/runtime-config/worker.json"},
                "s3:GetObjectVersion": {bucket_arn + "/settlements/*"},
                "logs:CreateLogStream": {log_arn + ":*"},
                "logs:PutLogEvents": {log_arn + ":*"},
            }.get(action, {"*"} if action in eni else set())
            require(set(resources) <= allowed, "Worker role has an unapproved action or resource")


def role_statements(role):
    require(not aws("iam", "list-attached-role-policies", "--role-name", role).get("AttachedPolicies"),
            "Unexpected managed policy attached to application role")
    policies = aws("iam", "list-role-policies", "--role-name", role).get("PolicyNames", [])
    require(len(policies) == 1, "Expected one scoped inline role policy")
    document = aws("iam", "get-role-policy", "--role-name", role, "--policy-name", policies[0])["PolicyDocument"]
    return document["Statement"]


def cloud_topology(out):
    config = aws("lambda", "get-function-configuration", "--function-name", out["function_name"])
    for key, expected in {"Timeout": 300, "MemorySize": 512, "Runtime": "java21",
                          "Architectures": ["x86_64"]}.items():
        require(config.get(key) == expected, "Unexpected deployed Lambda setting: " + key)
    require(config["Environment"]["Variables"] == {
        "RECONCILIATION_API_URI": out["api_private_address"],
        "RECONCILIATION_CONFIG_BUCKET": out["bucket"],
        "RECONCILIATION_CONFIG_KEY": "runtime-config/worker.json"}, "Unexpected deployed Lambda environment")
    check_worker_permissions(role_statements(config["Role"].rsplit("/", 1)[1]),
                             out["bucket_arn"], out["log_group_arn"], out["function_arn"])
    permission = json.loads(aws("lambda", "get-policy", "--function-name", out["function_name"])["Policy"])
    statements = permission["Statement"]
    require(len(statements) == 1 and statements[0].get("Effect") == "Allow"
            and statements[0].get("Principal") == {"Service": "s3.amazonaws.com"}
            and statements[0].get("Action") == "lambda:InvokeFunction"
            and statements[0].get("Condition", {}).get("ArnLike", {}).get("AWS:SourceArn") == out["bucket_arn"]
            and statements[0].get("Condition", {}).get("StringEquals", {}).get("AWS:SourceAccount") == out["account_id"],
            "Lambda invocation permission is not restricted to the owned bucket")
    retry = aws("lambda", "get-function-event-invoke-config", "--function-name", out["function_name"])
    require(retry.get("MaximumEventAgeInSeconds") == 3600 and retry.get("MaximumRetryAttempts") == 2,
            "Unexpected deployed asynchronous retry settings")
    logs = aws("logs", "describe-log-groups", "--log-group-name-prefix", out["log_group"])["logGroups"]
    require(any(g["logGroupName"] == out["log_group"] and g.get("retentionInDays") == 7 for g in logs),
            "Worker CloudWatch retention must be seven days")
    require(config["VpcConfig"]["SubnetIds"] == [out["worker_subnet_id"]]
            and config["VpcConfig"]["SecurityGroupIds"] == [out["worker_security_group_id"]],
            "Lambda private network differs from outputs")
    quota = aws("lambda", "get-function-concurrency", "--function-name", out["function_name"])
    require(quota.get("ReservedConcurrentExecutions") == 1, "Lambda concurrency cap missing")
    notice = aws("s3api", "get-bucket-notification-configuration", "--bucket", out["bucket"])
    rules = notice.get("LambdaFunctionConfigurations", [])
    require(len(rules) == 1 and rules[0]["LambdaFunctionArn"] == out["function_arn"]
            and rules[0]["Events"] == ["s3:ObjectCreated:*"], "Direct S3 notification missing")
    filters = {r["Name"]: r["Value"] for r in rules[0]["Filter"]["Key"]["FilterRules"]}
    require(filters == {"prefix": "settlements/", "suffix": ".csv"}, "Unexpected settlement event filter")
    require(aws("s3api", "get-bucket-versioning", "--bucket", out["bucket"]).get("Status") == "Enabled",
            "S3 versioning missing")
    block = aws("s3api", "get-public-access-block", "--bucket", out["bucket"])["PublicAccessBlockConfiguration"]
    require(all(block.get(k) is True for k in ("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy",
                                              "RestrictPublicBuckets")), "S3 public access block incomplete")
    groups = aws("ec2", "describe-security-groups", "--group-ids",
                 out["api_security_group_id"], out["worker_security_group_id"])["SecurityGroups"]
    api_group = next(g for g in groups if g["GroupId"] == out["api_security_group_id"])
    worker_group = next(g for g in groups if g["GroupId"] == out["worker_security_group_id"])
    require(not worker_group["IpPermissions"], "Worker must have no ingress")
    inbound = api_group["IpPermissions"]
    require(len(inbound) == 2, "Unexpected API ingress rules")
    for rule in inbound:
        require(rule["IpProtocol"] == "tcp" and rule["FromPort"] == rule["ToPort"], "Unexpected API protocol")
        require(not rule.get("Ipv6Ranges") and not rule.get("PrefixListIds"), "Unexpected API ingress source")
        if rule["FromPort"] == 8080:
            require(not rule.get("IpRanges") and
                    [g["GroupId"] for g in rule["UserIdGroupPairs"]] == [out["worker_security_group_id"]],
                    "API port must only admit worker security group")
        else:
            require(rule["FromPort"] == 22 and len(rule["IpRanges"]) == 1 and
                    rule["IpRanges"][0]["CidrIp"].endswith("/32") and not rule["UserIdGroupPairs"],
                    "SSH must only admit one developer IPv4")
    tables = aws("ec2", "describe-route-tables", "--filters",
                 "Name=association.subnet-id,Values=" + out["worker_subnet_id"])["RouteTables"]
    require(len(tables) == 1 and not any(r.get("DestinationCidrBlock") == "0.0.0.0/0"
                                       for r in tables[0]["Routes"]), "Worker must have no internet default route")
    require(any(r.get("DestinationPrefixListId") and r.get("GatewayId", "").startswith("vpce-")
                for r in tables[0]["Routes"]), "Worker S3 gateway route missing")
    outbound = worker_group["IpPermissionsEgress"]
    require(len(outbound) == 2, "Unexpected worker egress rules")
    for rule in outbound:
        require(rule["IpProtocol"] == "tcp" and rule["FromPort"] == rule["ToPort"]
                and not rule.get("IpRanges") and not rule.get("Ipv6Ranges"), "Unbounded worker egress")
        if rule["FromPort"] == 8080:
            require([g["GroupId"] for g in rule["UserIdGroupPairs"]] == [out["api_security_group_id"]]
                    and not rule.get("PrefixListIds"), "Worker API egress is not scoped")
        else:
            require(rule["FromPort"] == 443 and len(rule["PrefixListIds"]) == 1
                    and not rule.get("UserIdGroupPairs"), "Worker S3 egress is not scoped")
    endpoints = aws("ec2", "describe-vpc-endpoints", "--filters",
                    "Name=vpc-id,Values=" + config["VpcConfig"]["VpcId"])["VpcEndpoints"]
    require(len(endpoints) == 1 and endpoints[0]["VpcEndpointType"] == "Gateway"
            and endpoints[0]["ServiceName"] == "com.amazonaws." + out["region"] + ".s3"
            and tables[0]["RouteTableId"] in endpoints[0]["RouteTableIds"], "Unexpected worker endpoint topology")
    encryption = aws("s3api", "get-bucket-encryption", "--bucket", out["bucket"])
    require(encryption["ServerSideEncryptionConfiguration"]["Rules"][0]
            ["ApplyServerSideEncryptionByDefault"]["SSEAlgorithm"] == "AES256", "S3 default encryption missing")
    try:
        with socket.create_connection((out["ssh_host"], 8080), timeout=4):
            raise CheckFailure("API unexpectedly reachable on public IPv4 port 8080")
    except (TimeoutError, ConnectionRefusedError, OSError):
        pass


def smoke(args, evidence):
    date = datetime.strptime(args.business_date, "%Y-%m-%d").date()
    require(date.isoformat() == args.business_date, "Smoke date must use ISO format")
    # The API enforces the authoritative Buenos Aires past-date rule.
    benchmark_date = args.benchmark_date or (date - timedelta(days=1)).isoformat()
    maximum_date = datetime.strptime(benchmark_date, "%Y-%m-%d").date()
    require(maximum_date.isoformat() == benchmark_date and maximum_date != date,
            "Benchmark date must be a distinct ISO date; choose a past date")
    jar = ROOT / "scenario-generator/target/scenario-generator-0.0.1-SNAPSHOT-exec.jar"
    require(jar.is_file(), "Verified generator executable is missing")
    out = outputs(args.outputs)
    os.environ["AWS_DEFAULT_REGION"] = out["region"]
    require(aws("sts", "get-caller-identity").get("Account") == out["account_id"], "Wrong AWS account")
    values = load_protected(args.runtime)
    runtime_files(values)
    base = args.base_url.rstrip("/")
    parsed = urllib.parse.urlsplit(base)
    require(parsed.scheme == "http" and parsed.hostname in ("127.0.0.1", "localhost", "::1")
            and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
            and not parsed.path, "Smoke API URL must be the local SSH tunnel")
    # Allowlist resource identifiers only; never serialize runtime/URLs/raw CLI responses.
    evidence["teardownInventory"] = {k: out[k] for k in ("bucket", "function_name", "log_group",
        "ssh_host", "api_security_group_id", "worker_security_group_id", "worker_subnet_id")}
    cloud_topology(out)
    evidence["checks"]["deployedTopologyAndRoles"] = "PASS"
    demo, worker = values["demoToken"], values["workerToken"]
    route = "/api/v1/reconciliation-runs"
    internal = "/internal/v1/reconciliation-runs/" + str(uuid.uuid4())
    for path, bearer, expected in [(route + "?businessDate=" + args.business_date, None, 401),
                                   (route + "?businessDate=" + args.business_date, worker, 403),
                                   (internal, demo, 403), (internal, None, 401)]:
        require(http("GET", base + path, bearer)[0] == expected, "Public/worker authorization restriction failed")
    require(http("GET", base + "/swagger-ui/index.html")[0] == 200, "Swagger UI unavailable")
    api(base, "/v3/api-docs", None)
    evidence["checks"]["apiAccessAndSwagger"] = "PASS"
    check = benchmark.CloudRun(api, http, aws, wait_for)
    if args.benchmark:
        evidence["runs"]["maximum"] = {}
        check.maximum(out, base, demo, benchmark_date, evidence["runs"]["maximum"])
        evidence["checks"]["maximumColdWarmBudget"] = "PASS"
    # Canonical fixed references are globally unique: a fresh demo database is required.
    api(base, "/api/v1/business-dates", demo, "POST", {"businessDate": args.business_date}, (201,))
    entry = evidence["runs"]["canonical"] = {}
    start = int(time.time() * 1000) - 1000
    began = time.monotonic()
    try:
        run_id = benchmark.run_generator(jar, base, args.business_date, demo)
    except CheckFailure as failure:
        if failure.run_id:
            entry["runId"] = failure.run_id
        entry["generatorElapsedSeconds"] = time.monotonic() - began
        raise
    entry.update(runId=run_id, generatorElapsedSeconds=time.monotonic() - began)
    print("Cloud canonical runId=" + run_id, flush=True)
    fixture = ROOT / "acceptance-tests/src/test/resources/fixtures"
    expected = json.loads((fixture / "canonical-expected.json").read_text())
    raw = (fixture / "canonical.csv").read_bytes().replace(b"2026-10-01", args.business_date.encode())
    check.inspect(out, base, demo, args.business_date, run_id, raw, expected, start, entry)
    require(entry["status"] == "COMPLETED" and entry["summary"]["totalResultCount"] == 5,
            "realS3UploadPublishesReportWithinRuntimeBudget canonical assertion failed")
    for key in (entry["objectIdentity"]["key"], "runtime-config/worker.json"):
        url = "https://" + out["bucket"] + ".s3." + out["region"] + ".amazonaws.com/" + key
        require(http("GET", url)[0] == 403, "Private S3 object is anonymously readable")
    evidence["checks"]["canonicalGeneratorAndDirectS3"] = "PASS"
    evidence["checks"]["privateObjects"] = "PASS"
    if args.benchmark:
        benchmark.realS3UploadPublishesReportWithinRuntimeBudget(entry, evidence["runs"]["maximum"])
        evidence["checks"]["realS3UploadPublishesReportWithinRuntimeBudget"] = "PASS"


def smoke_evidence(args):
    # Reserve a private output before cloud mutation; refuse overwrite/symlinks.
    report = {"schemaVersion": 1, "evidenceKind": "cloud-execution",
              "validationId": str(uuid.uuid4()), "startedAt": datetime.now(timezone.utc).isoformat(),
              "status": "IN_PROGRESS", "businessDate": args.business_date,
              "benchmarkRequested": args.benchmark, "runs": {}, "teardownInventory": {},
              "checks": dict.fromkeys(("accountEligibility", "deployedTopologyAndRoles",
                  "apiAccessAndSwagger", "canonicalGeneratorAndDirectS3", "privateObjects",
                  "maximumColdWarmBudget", "realS3UploadPublishesReportWithinRuntimeBudget", "signatureAndExpiry", "retriableOutageAndRecovery",
                  "usageVisibility", "teardown", "localAfterTeardown"), "PENDING")}
    with os.fdopen(os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as file:
        try:
            smoke(args, report)
            report["status"] = "AUTOMATED_CHECKS_PASSED"
        except BaseException:
            report["status"] = "FAILED"
            raise
        finally:
            report["finishedAt"] = datetime.now(timezone.utc).isoformat()
            json.dump(report, file, indent=2)
            file.write("\n")
    print("PASS automated cloud checks; remaining demonstration criteria are pending in " + args.output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    pre = sub.add_parser("preflight")
    for name in ("account-id", "region", "instance-type", "eligibility"):
        pre.add_argument("--" + name, required=True)
    guard = sub.add_parser("plan")
    guard.add_argument("plan_json", help="Real terraform show -json output")
    config = sub.add_parser("config")
    config.add_argument("runtime")
    config.add_argument("directory")
    account = sub.add_parser("account")
    account.add_argument("--outputs", required=True)
    empty = sub.add_parser("empty-bucket")
    empty.add_argument("--outputs", required=True)
    empty.add_argument("--confirm-bucket", required=True)
    check = sub.add_parser("smoke")
    for name in ("outputs", "runtime", "business-date"):
        check.add_argument("--" + name, required=True)
    check.add_argument("--base-url", default="http://127.0.0.1:8080")
    check.add_argument("--benchmark", action="store_true", help="Measure maximum workload before canonical")
    check.add_argument("--benchmark-date", help="Unused past date; defaults to business-date minus one day")
    check.add_argument("--output", default=".runtime/demo-evidence.json", help="New sanitized JSON evidence file; never overwritten")
    args = parser.parse_args()
    try:
        if args.command == "preflight":
            preflight(args)
        elif args.command == "plan":
            terraformPlanHasNoUnapprovedChargeableNetworkComponents(json.loads(Path(args.plan_json).read_text()))
            print("PASS plan structural guard; account eligibility and manual plan review are still required")
        elif args.command == "config":
            env, worker = runtime_files(load_protected(args.runtime))
            write_protected(Path(args.directory) / "api.env", env)
            write_protected(Path(args.directory) / "worker.json", worker)
        elif args.command == "account":
            out = outputs(args.outputs)
            os.environ["AWS_DEFAULT_REGION"] = out["region"]
            require(aws("sts", "get-caller-identity").get("Account") == out["account_id"], "Wrong AWS account")
        elif args.command == "empty-bucket":
            out = outputs(args.outputs)
            require(args.confirm_bucket == out["bucket"], "Bucket confirmation must match Terraform output")
            os.environ["AWS_DEFAULT_REGION"] = out["region"]
            require(aws("sts", "get-caller-identity").get("Account") == out["account_id"], "Wrong AWS account")
            empty_bucket(out["bucket"])
        else:
            smoke_evidence(args)
        return 0
    except (CheckFailure, OSError, ValueError, KeyError, TypeError) as failure:
        # Unknown parsing/system failures could include file contents or URLs.
        message = str(failure) if isinstance(failure, CheckFailure) else "Invalid input or operation failed; details withheld"
        print("FAIL " + message, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
