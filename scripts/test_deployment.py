"""Offline behavior tests; fixtures are test inputs, never cloud evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("cloud_smoke", ROOT / "scripts/cloud-smoke.py")
cloud = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cloud)

class PreflightTests(unittest.TestCase):
    def setUp(self):
        self.plan = {"accountPlanType": "FREE", "accountPlanStatus": "ACTIVE",
                     "accountPlanRemainingCredits": {"amount": 10, "unit": "USD"},
                     "accountPlanExpirationDate": "2099-01-01T00:00:00Z"}
        self.quota = {"AccountLimit": {"ConcurrentExecutions": 1000, "UnreservedConcurrentExecutions": 101}}
    def test_valid_free_account_has_one_reservable_unit(self):
        cloud.validate_account(self.plan, self.quota)
    def test_fail_closed_on_missing_expired_paid_zero_credits_and_quota(self):
        for key, value in [("accountPlanType", "PAID"), ("accountPlanStatus", "EXPIRED"),
                           ("accountPlanExpirationDate", "2000-01-01T00:00:00Z"),
                           ("accountPlanRemainingCredits", {"amount": 0, "unit": "USD"}),
                           ("accountPlanRemainingCredits", {"amount": "NaN", "unit": "USD"})]:
            candidate = dict(self.plan, **{key: value})
            with self.subTest(key=key, value=value), self.assertRaises(cloud.CheckFailure):
                cloud.validate_account(candidate, self.quota)
        for missing in self.plan:
            candidate = dict(self.plan); del candidate[missing]
            with self.assertRaises(cloud.CheckFailure):
                cloud.validate_account(candidate, self.quota)
        for value in [100, 0, None]:
            with self.assertRaises(cloud.CheckFailure):
                cloud.validate_account(self.plan, {"AccountLimit": {"UnreservedConcurrentExecutions": value}})
    def test_cli_failure_redacts_command_and_stderr(self):
        import subprocess
        with patch.object(cloud.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "", "secret-value")):
            with self.assertRaises(cloud.CheckFailure) as cm:
                cloud.aws("s3api", "head-object", "--key", "secret-value")
        self.assertNotIn("secret-value", str(cm.exception))

class SecretsTests(unittest.TestCase):
    def test_render_escapes_systemd_and_worker_has_only_worker_token(self):
        env, worker = cloud.runtime_files({"demoToken": "demo$'quote", "workerToken": 'worker"\\token',
                                          "mongoUri": "mongodb+srv://u:p@cluster.example/reconciliation"})
        self.assertIn("RECONCILIATION_SECURITY_DEMOTOKEN=", env)
        self.assertIn("RECONCILIATION_SECURITY_WORKERTOKEN=", env)
        self.assertIn("SPRING_MONGODB_URI=", env)
        self.assertEqual({"workerToken": 'worker"\\token'}, json.loads(worker))
        self.assertNotIn("mongoUri", worker)
        self.assertNotIn("demo", worker)
    def test_reject_invalid_or_equal_tokens_and_non_tls_mongo_without_echo(self):
        valid = {"demoToken": "demo", "workerToken": "worker", "mongoUri": "mongodb+srv://u:p@c.example/db"}
        for change in [{"workerToken": "demo"}, {"demoToken": "with space"},
                       {"workerToken": "bad\nline"}, {"workerToken": ""},
                       {"mongoUri": "mongodb://u:secret@host/db"},
                       {"mongoUri": "mongodb+srv://u:secret@host/"},
                       {"extra": "secret"}, {"mongoUri": "mongodb+srv://u:p@host/db?TLS=FALSE"},
                       {"mongoUri": "mongodb+srv://u:p@host/db?ssl=True&tls=false"}]:
            with self.assertRaises(cloud.CheckFailure) as cm:
                cloud.runtime_files(dict(valid, **change))
            self.assertNotIn("secret", str(cm.exception))

    def test_native_systemd_parser_preserves_all_printable_token_characters(self):
        import ctypes
        import glob
        libraries = glob.glob("/usr/lib/*/systemd/libsystemd-shared-*.so")
        if not libraries:
            self.skipTest("Native systemd shared library not available")
        library = ctypes.CDLL(libraries[0])
        parse = library.parse_env_file_sentinel
        parse.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        parse.restype = ctypes.c_int
        libc = ctypes.CDLL(None)
        libc.free.argtypes = [ctypes.c_void_p]
        # Every permitted character, including quotes, slash, $, backtick, = and #.
        demo = "".join(chr(c) for c in range(33, 127))
        values = {"demoToken": demo, "workerToken": "worker" + demo,
                  "mongoUri": "mongodb+srv://user:percent%24@cluster.example/reconciliation"}
        env, _ = cloud.runtime_files(values)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime.env"
            path.write_text(env)
            for key, expected in [("RECONCILIATION_SECURITY_DEMOTOKEN", demo),
                                  ("RECONCILIATION_SECURITY_WORKERTOKEN", "worker" + demo),
                                  ("SPRING_MONGODB_URI", values["mongoUri"])]:
                output = ctypes.c_void_p()
                result = parse(None, str(path).encode(), key.encode(), ctypes.byref(output), None)
                try:
                    self.assertGreaterEqual(result, 0)
                    self.assertEqual(expected, ctypes.string_at(output).decode())
                finally:
                    libc.free(output)

    def test_protected_input_and_output_modes_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime.json"
            path.write_text('{"fake":"fixture"}')
            path.chmod(0o644)
            with self.assertRaises(cloud.CheckFailure):
                cloud.load_protected(path)
            path.chmod(0o600)
            self.assertEqual({"fake": "fixture"}, cloud.load_protected(path))
            output = Path(directory) / "output"
            cloud.write_protected(output, "fixture")
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                cloud.write_protected(output, "replacement")

class PlanGuardTests(unittest.TestCase):
    def fixture(self):
        # Synthetic unit-test input ONLY; never saved or cited as real plan evidence.
        values = {
            "aws_vpc_endpoint": {"vpc_endpoint_type": "Gateway"},
            "aws_instance": {},
            "aws_lambda_function": {"timeout": 300, "memory_size": 512, "runtime": "java21",
                "architectures": ["x86_64"], "reserved_concurrent_executions": 1,
                "environment": [{"variables": {"RECONCILIATION_API_URI": "http://10.42.1.2:8080",
                    "RECONCILIATION_CONFIG_BUCKET": "test", "RECONCILIATION_CONFIG_KEY": "runtime-config/worker.json"}}]},
            "aws_cloudwatch_log_group": {"retention_in_days": 7},
            "aws_lambda_function_event_invoke_config": {"maximum_event_age_in_seconds": 3600, "maximum_retry_attempts": 2},
        }
        return {"resource_changes": [{"mode": "managed", "type": k, "change": {"after": v}} for k, v in values.items()]}
    def test_guard_accepts_bounded_fixture_and_rejects_network_and_lambda_drift(self):
        valid = self.fixture()
        cloud.terraformPlanHasNoUnapprovedChargeableNetworkComponents(valid)
        for kind in ["aws_nat_gateway", "aws_lb", "aws_sqs_queue", "aws_s3_object"]:
            changed = copy.deepcopy(valid)
            changed["resource_changes"].append({"mode": "managed", "type": kind, "change": {"after": {}}})
            with self.assertRaises(cloud.CheckFailure):
                cloud.terraformPlanHasNoUnapprovedChargeableNetworkComponents(changed)
        for kind, key, value in [("aws_vpc_endpoint", "vpc_endpoint_type", "Interface"),
                                 ("aws_lambda_function", "timeout", 301),
                                 ("aws_lambda_function", "reserved_concurrent_executions", -1),
                                 ("aws_cloudwatch_log_group", "retention_in_days", 0)]:
            changed = copy.deepcopy(valid)
            next(r for r in changed["resource_changes"] if r["type"] == kind)["change"]["after"][key] = value
            with self.assertRaises(cloud.CheckFailure):
                cloud.terraformPlanHasNoUnapprovedChargeableNetworkComponents(changed)
        with self.assertRaises(cloud.CheckFailure):
            cloud.terraformPlanHasNoUnapprovedChargeableNetworkComponents({})
    def test_guard_rejects_secret_environment_and_accepts_known_keys_unknown_addresses(self):
        valid = self.fixture()
        change = next(r for r in valid["resource_changes"] if r["type"] == "aws_lambda_function")["change"]
        change["after"]["environment"][0]["variables"].pop("RECONCILIATION_API_URI")
        change["after_unknown"] = {"environment": [{"variables": {"RECONCILIATION_API_URI": True}}]}
        cloud.terraformPlanHasNoUnapprovedChargeableNetworkComponents(valid)
        change["after"]["environment"][0]["variables"]["WORKER_TOKEN"] = "dummy"
        with self.assertRaises(cloud.CheckFailure):
            cloud.terraformPlanHasNoUnapprovedChargeableNetworkComponents(valid)

class SmokeTests(unittest.TestCase):
    def test_exact_identity_rejects_version_checksum_and_bucket_changes(self):
        expected = {"bucket": "b", "key": "k", "versionId": "original", "sha256": "a" * 64}
        cloud.check_identity({"objectIdentity": expected, "sha256": "a" * 64}, expected)
        for key in expected:
            bad = dict(expected, **{key: "changed"})
            with self.assertRaises(cloud.CheckFailure):
                cloud.check_identity({"objectIdentity": bad, "sha256": "a" * 64}, expected)
    def test_replay_requires_complete_identical_snapshot_and_single_run(self):
        before = {"status": "COMPLETED", "updatedAt": "before", "summary": {"totalResultCount": 1}}
        results = {"totalResults": 1, "results": [{"reference": "A"}]}
        cloud.check_replay(before, before, results, results, [{"runId": "r"}], "r")
        for after, after_results, runs in [
            (dict(before, updatedAt="changed"), results, [{"runId": "r"}]),
            (before, {"totalResults": 2}, [{"runId": "r"}]),
            (before, results, [{"runId": "r"}, {"runId": "r"}]),
            (dict(before, status="FAILED"), results, [{"runId": "r"}]),
        ]:
            with self.assertRaises(cloud.CheckFailure):
                cloud.check_replay(before, after, results, after_results, runs, "r")
    def test_logs_require_all_correlated_fields_and_new_successful_attempt(self):
        event = dict(runId="r", businessDate="2026-01-01", versionId="v", rulesVersion="v1",
                     attemptId="attempt-1", durationMillis=3, errorCode=None)
        logs = {"events": [{"message": json.dumps(event)}]}
        self.assertEqual({"attempt-1"}, cloud.correlated_attempts(logs, "r", "2026-01-01", "v"))
        for key in event:
            bad = dict(event); del bad[key]
            self.assertEqual(set(), cloud.correlated_attempts({"events": [{"message": json.dumps(bad)}]}, "r", "2026-01-01", "v"))

class SmokeWorkflowTests(unittest.TestCase):
    def test_offline_maximum_then_canonical_direct_delivery_and_correlated_replay(self):
        import argparse
        import base64
        import hashlib
        import io
        from contextlib import redirect_stdout
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            fixture = work / "acceptance-tests/src/test/resources/fixtures"
            fixture.mkdir(parents=True)
            real_fixture = ROOT / "acceptance-tests/src/test/resources/fixtures"
            for name in ("canonical.csv", "canonical-expected.json"):
                (fixture / name).write_bytes((real_fixture / name).read_bytes())
            jar = work / "scenario-generator/target/scenario-generator-0.0.1-SNAPSHOT-exec.jar"
            jar.parent.mkdir(parents=True); jar.write_bytes(b"offline fixture")
            runtime = work / "runtime.json"
            runtime.write_text(json.dumps({"demoToken": "demo", "workerToken": "worker",
                                          "mongoUri": "mongodb+srv://u:p@cluster.example/db"}))
            runtime.chmod(0o600)
            out = {"account_id": "123456789012", "region": "us-east-1", "bucket": "b",
                   "function_name": "f", "log_group": "g", "ssh_host": "host",
                   "api_security_group_id": "api-sg", "worker_security_group_id": "worker-sg",
                   "worker_subnet_id": "worker-subnet"}
            state = work / "outputs.json"
            state.write_text(json.dumps({k: {"value": v} for k, v in out.items()}))
            args = argparse.Namespace(outputs=str(state), runtime=str(runtime), business_date="2020-01-02",
                benchmark=True, benchmark_date=None, output=str(work / "evidence.json"),
                base_url="http://127.0.0.1:8080")
            maximum = "12345678-1234-1234-1234-123456789001"
            canonical = "12345678-1234-1234-1234-123456789002"
            captured = {"purchases": [], "runs": {}, "replayed": set(), "current": maximum}
            fault = {"mode": None}
            def install(run_id, date, raw, expected):
                digest = hashlib.sha256(raw).hexdigest()
                captured["runs"][run_id] = {"raw": raw, "expected": expected, "metadata": {
                    "runId": run_id, "status": "COMPLETED", "sha256": digest, "summary": expected["summary"],
                    "objectIdentity": {"bucket": "b", "key": "settlements/" + date + "/" + run_id + ".csv",
                                       "versionId": "original", "sha256": digest}}, "date": date}
                captured["current"] = run_id
            def fake_api(base, path, bearer, method="GET", body=None, expected=(200,)):
                if path.endswith("/transactions"):
                    captured["purchases"].append(body)
                if path == "/api/v1/reconciliation-runs" and method == "POST":
                    return {"runId": maximum, "objectKey": "settlements/2020-01-01/" + maximum + ".csv",
                            "uploadInstructions": {"url": "https://b.example/upload",
                                "requiredHeaders": {"x-amz-checksum-sha256":
                                    base64.b64encode(bytes.fromhex(body["sha256"])).decode()}}}
                if "?businessDate=" in path:
                    date = path.split("=")[1]
                    return [{"runId": rid} for rid, r in captured["runs"].items() if r["date"] == date]
                for rid, r in captured["runs"].items():
                    if path.endswith("/" + rid):
                        return r["metadata"]
                    if "/" + rid + "/results?" in path:
                        page = int(path.split("page=")[1].split("&")[0])
                        results = r["expected"]["results"]
                        return {"totalResults": len(results), "results": results[page*100:(page+1)*100]}
                return {}
            def fake_http(method, url, bearer=None, body=None, headers=None):
                if method == "PUT":
                    ns = captured["purchases"][0]["transactionReference"].rsplit("_I", 1)[0]
                    purchases, raw, expected = cloud.benchmark.maximum_workload("2020-01-01", ns)
                    self.assertEqual(raw, body)
                    self.assertEqual(purchases, captured["purchases"])
                    install(maximum, "2020-01-01", body, expected)
                    return 200, b"", {"x-amz-version-id": "original"}
                if "amazonaws.com" in url:
                    return 403, b"", {}
                if "/swagger-ui/" in url:
                    return 200, b"", {}
                return (401 if bearer is None else 403), b"", {}
            def log_events(rid, replay=False):
                r = captured["runs"][rid]
                request = rid + ("-replay" if replay else "-direct")
                diagnostic = json.dumps({"runId": rid, "businessDate": r["date"], "versionId": "original",
                    "rulesVersion": "v1", "attemptId": request, "durationMillis": 1, "errorCode": None})
                report = ("REPORT RequestId: " + request + "\tDuration: 10 ms\tBilled Duration: 11 ms"
                          "\tMemory Size: 512 MB\tMax Memory Used: 200 MB"
                          + ("\tInit Duration: 20 ms" if rid == maximum and not replay else ""))
                if rid == maximum and not replay:
                    if fault["mode"] == "missing-cold":
                        report = report.replace("\tInit Duration: 20 ms", "")
                    if fault["mode"] == "slow-cold":
                        report = report.replace("Duration: 10 ms", "Duration: 120000 ms")
                return [{"message": line, "logStreamName": "same-environment", "timestamp": 1}
                        for line in (diagnostic, report)]
            def fake_aws(*call, **kwargs):
                if call[:2] == ("sts", "get-caller-identity"):
                    return {"Account": out["account_id"]}
                if call[:2] == ("s3api", "head-object"):
                    r = captured["runs"][captured["current"]]
                    return {"VersionId": "original", "ContentLength": len(r["raw"]),
                            "ChecksumSHA256": base64.b64encode(hashlib.sha256(r["raw"]).digest()).decode()}
                if call[:2] == ("lambda", "invoke"):
                    rid = captured["current"]
                    payload = json.loads(Path(call[call.index("--payload") + 1][8:]).read_text())
                    self.assertEqual("original", payload["Records"][0]["s3"]["object"]["versionId"])
                    captured["replayed"].add(rid)
                    return {"StatusCode": 200, "LogResult": base64.b64encode(
                        "\n".join(e["message"] for e in log_events(rid, True)).encode()).decode()}
                if call[:2] == ("logs", "filter-log-events"):
                    pattern = call[-1].strip('"')
                    rid = next(rid for rid in captured["runs"] if pattern.startswith(rid))
                    return {"events": log_events(rid, pattern.endswith("-replay"))}
                raise AssertionError(call)
            def generator(jar, base, date, demo):
                self.assertIn(maximum, captured["replayed"])  # max really precedes generator
                expected = json.loads((fixture / "canonical-expected.json").read_text())
                raw = (fixture / "canonical.csv").read_bytes().replace(b"2026-10-01", date.encode())
                install(canonical, date, raw, expected)
                return canonical
            with patch.object(cloud, "ROOT", work), patch.object(cloud, "cloud_topology"), \
                 patch.object(cloud, "api", side_effect=fake_api), patch.object(cloud, "http", side_effect=fake_http), \
                 patch.object(cloud, "aws", side_effect=fake_aws), \
                 patch.object(cloud.benchmark, "run_generator", side_effect=generator), redirect_stdout(io.StringIO()):
                cloud.smoke_evidence(args)
            report = json.loads(Path(args.output).read_text())
            self.assertEqual("AUTOMATED_CHECKS_PASSED", report["status"])
            self.assertEqual("warm", report["runs"]["maximum"]["replay"]["environment"])
            self.assertEqual(3000, report["runs"]["maximum"]["summary"]["totalResultCount"])
            self.assertEqual(5, report["runs"]["canonical"]["summary"]["totalResultCount"])
            self.assertEqual("PENDING", report["checks"]["signatureAndExpiry"])
            self.assertEqual({maximum, canonical}, captured["replayed"])
            self.assertNotIn("mongoUri", Path(args.output).read_text())
            self.assertNotIn("https://b.example/upload", Path(args.output).read_text())
            for mode in ("missing-cold", "slow-cold"):
                fault["mode"] = mode
                captured.update(purchases=[], runs={}, replayed=set(), current=maximum)
                args.output = str(work / (mode + ".json"))
                with patch.object(cloud, "ROOT", work), patch.object(cloud, "cloud_topology"), \
                     patch.object(cloud, "api", side_effect=fake_api), patch.object(cloud, "http", side_effect=fake_http), \
                     patch.object(cloud, "aws", side_effect=fake_aws), \
                     patch.object(cloud.benchmark, "run_generator") as generator_call:
                    with self.assertRaises(cloud.CheckFailure):
                        cloud.smoke_evidence(args)
                    generator_call.assert_not_called()
                failed = json.loads(Path(args.output).read_text())
                self.assertEqual("FAILED", failed["status"])
                self.assertNotIn("canonical", failed["runs"])
                self.assertEqual("PENDING", failed["checks"]["maximumColdWarmBudget"])
                self.assertEqual(120020 if mode == "slow-cold" else 10,
                                 failed["runs"]["maximum"]["direct"]["processingMillis"])

    def test_polling_fails_closed_at_deadline(self):
        with patch.object(cloud.time, "monotonic", side_effect=[0, 2]), patch.object(cloud.time, "sleep"):
            with self.assertRaises(cloud.CheckFailure):
                cloud.wait_for(lambda: {"status": "FAILED"}, lambda r: r["status"] == "COMPLETED", seconds=1)

class RoleRestrictionTests(unittest.TestCase):
    def test_worker_role_rejects_write_wildcard_and_foreign_resource(self):
        read = {"Effect": "Allow", "Action": ["s3:GetObject", "s3:GetObjectVersion"],
                "Resource": "arn:aws:s3:::bucket/settlements/*"}
        config = {"Effect": "Allow", "Action": "s3:GetObject",
                  "Resource": "arn:aws:s3:::bucket/runtime-config/worker.json"}
        cloud.check_worker_permissions([read, config], "arn:aws:s3:::bucket", "arn:aws:logs:r:a:log-group:g")
        for bad in [dict(read, Action="s3:PutObject"), dict(read, Resource="*"),
                    dict(config, Action="s3:GetObjectVersion"),
                    {"Effect": "Allow", "Action": "lambda:InvokeFunction", "Resource": "*"}]:
            with self.assertRaises(cloud.CheckFailure):
                cloud.check_worker_permissions([read, config, bad], "arn:aws:s3:::bucket", "arn:aws:logs:r:a:log-group:g")

class WorkerEniDenyTests(unittest.TestCase):
    def test_wildcard_eni_permissions_require_exact_function_deny(self):
        arn = "arn:aws:lambda:us-east-1:123456789012:function:demo-worker"
        actions = ["ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets",
                   "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses"]
        allow = {"Effect": "Allow", "Action": actions, "Resource": "*"}
        # Regression: the previous guard accepted unrestricted code-level ENI access.
        with self.assertRaises(cloud.CheckFailure):
            cloud.check_worker_permissions([allow], "b", "l")
        deny = {"Effect": "Deny", "Action": actions, "Resource": "*",
                "Condition": {"ArnEquals": {"lambda:SourceFunctionArn": arn}}}
        cloud.check_worker_permissions([allow, deny], "b", "l", arn)
        for change in [{"Action": actions[:-1]}, {"Resource": "different"},
                       {"Condition": {"ArnEquals": {"lambda:SourceFunctionArn": arn + "-other"}}}]:
            with self.assertRaises(cloud.CheckFailure):
                cloud.check_worker_permissions([allow, dict(deny, **change)], "b", "l", arn)

class TeardownTests(unittest.TestCase):
    def test_all_version_types_deleted_and_s3_errors_fail(self):
        calls = []
        pages = [{"Versions": [{"Key": "runtime-config/worker.json", "VersionId": "v1"}],
                  "DeleteMarkers": [{"Key": "settlements/a.csv", "VersionId": "v2"}]}, {}]
        def aws(*args):
            calls.append(args)
            return pages.pop(0) if args[1] == "list-object-versions" else {}
        cloud.empty_bucket("owned-bucket", aws)
        deleted = json.loads(next(c for c in calls if c[1] == "delete-objects")[c_index(calls)])
        self.assertEqual(2, len(deleted["Objects"]))
        def failing(*args):
            return {"Versions": [{"Key": "k", "VersionId": "v"}]} if args[1] == "list-object-versions" else {"Errors": [{"Code": "AccessDenied"}]}
        with self.assertRaises(cloud.CheckFailure):
            cloud.empty_bucket("owned-bucket", failing)

def c_index(calls):
    call = next(c for c in calls if c[1] == "delete-objects")
    return call.index("--delete") + 1

class SourceGuards(unittest.TestCase):
    def test_terraform_has_no_secret_inputs_or_unapproved_components(self):
        source = "\n".join(f.read_text() for f in (ROOT / "infra").glob("*.tf"))
        self.assertNotIn('resource "aws_nat_gateway"', source)
        self.assertNotIn('resource "aws_lb"', source)
        self.assertNotIn('resource "aws_s3_object"', source)
        self.assertNotIn('"AWS_REGION"', source)
        self.assertRegex(source, r'reserved_concurrent_executions\s*= 1')
        self.assertRegex(source, r'timeout\s*= 300')
        self.assertRegex(source, r'vpc_endpoint_type\s*= "Gateway"')
        self.assertRegex(source, r'filter_prefix\s*= "settlements/"')
        self.assertRegex(source, r'filter_suffix\s*= "\.csv"')
        self.assertNotIn("aws:SourceVpce", source)
        self.assertIn("aws_lambda_permission.settlements", source)
    def test_deploy_shell_fails_before_network_for_missing_inputs(self):
        import subprocess
        result = subprocess.run(["bash", str(ROOT / "scripts/deploy-api.sh")], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Usage:", result.stderr)

class DeploymentTransportTests(unittest.TestCase):
    def test_real_shell_sends_secrets_only_as_data_and_stops_on_upload_failure(self):
        import hashlib
        import os
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            bin_dir = work / "bin"; bin_dir.mkdir()
            runtime = work / "runtime.json"
            secret = "$(id)'\"\\$=;`"
            runtime.write_text(json.dumps({"demoToken": "demo" + secret, "workerToken": "worker" + secret,
                "mongoUri": "mongodb+srv://u:p@cluster.example/db"})); runtime.chmod(0o600)
            artifact = work / "api.jar"; artifact.write_bytes(b"synthetic jar bytes")
            key = work / "ssh-key"; key.write_text("synthetic key")
            state = {k: {"value": v} for k, v in {"ssh_host": "203.0.113.5", "bucket": "synthetic-bucket",
                                                "region": "us-east-1", "account_id": "123456789012"}.items()}
            (work / "state.json").write_text(json.dumps(state))
            stub = r"""#!/usr/bin/env python3
import json,os,pathlib,sys,hashlib
root=pathlib.Path(os.environ["TEST_DEPLOY_DIR"])
name=pathlib.Path(sys.argv[0]).name
args=sys.argv[1:]
with (root/"calls.jsonl").open("a") as f: f.write(json.dumps([name,args])+"\n")
if name=="terraform": print((root/"state.json").read_text())
elif name=="aws":
    if args[:2]==["sts","get-caller-identity"]: print('{"Account":"123456789012"}')
    elif args[:2]==["s3api","put-object"]:
        if os.environ.get("TEST_FAIL_UPLOAD"): print("synthetic private detail",file=sys.stderr); sys.exit(1)
        (root/"worker-upload.json").write_bytes(pathlib.Path(args[args.index("--body")+1]).read_bytes())
    else: sys.exit(99)
elif name=="ssh":
    command=args[-1]
    if "runtime.env.next" in command: (root/"received.env").write_bytes(sys.stdin.buffer.read())
    elif "api.jar.next" in command: (root/"received.jar").write_bytes(sys.stdin.buffer.read())
    elif "sha256sum" in command: print(hashlib.sha256((root/"received.jar").read_bytes()).hexdigest()+"  api.jar")
"""
            for name in ("terraform", "aws", "ssh"):
                path = bin_dir / name; path.write_text(stub); path.chmod(0o755)
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"], TEST_DEPLOY_DIR=directory)
            result = subprocess.run(["bash", str(ROOT/"scripts/deploy-api.sh"), str(artifact), str(runtime), str(key)],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            expected_env, expected_worker = cloud.runtime_files(json.loads(runtime.read_text()))
            self.assertEqual(expected_env, (work/"received.env").read_text())
            self.assertEqual(json.loads(expected_worker), json.loads((work/"worker-upload.json").read_text()))
            combined = result.stdout + result.stderr + (work/"calls.jsonl").read_text()
            self.assertNotIn(secret, combined)
            self.assertNotIn("worker" + secret, combined)
            self.assertIn("StrictHostKeyChecking=yes", combined)
            (work/"calls.jsonl").unlink()
            env["TEST_FAIL_UPLOAD"] = "1"
            result = subprocess.run(["bash", str(ROOT/"scripts/deploy-api.sh"), str(artifact), str(runtime), str(key)],
                                    env=env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("synthetic private detail", result.stderr)
            self.assertNotIn("runtime.env.next", (work/"calls.jsonl").read_text())

if __name__ == "__main__":
    unittest.main()
