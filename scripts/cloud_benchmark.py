"""Cloud-demo workload and evidence helpers. Tests are offline, not cloud proof."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import tempfile
import urllib.parse
import uuid

class CheckFailure(Exception):
    def __init__(self, message, run_id=None):
        super().__init__(message)
        self.run_id = run_id


def require(condition, message):
    if not condition:
        raise CheckFailure(message)

def lambda_measurement(logs, request_id):
    """Read a single actual runtime REPORT; never infer cold from invocation order."""
    reports = [e.get("message", "") for e in logs.get("events", [])
               if re.search(r"REPORT RequestId:\s*" + re.escape(request_id) + r"(?=\s|$)",
                            e.get("message", ""))]
    require(len(set(reports)) == 1, "Missing or ambiguous correlated Lambda REPORT")
    report = reports[0]
    require(not re.search(r"Status:\s*(?:error|timeout)", report), "Lambda REPORT indicates invocation failure")
    def number(label, unit, optional=False):
        match = re.search(r"(?:^|\s)" + re.escape(label) + r":\s*(\d+(?:\.\d+)?)\s*" + unit, report)
        if optional and match is None:
            return None
        require(match is not None, "Lambda REPORT lacks required measurement")
        return float(match[1])
    duration = number("Duration", "ms")
    init = number("Init Duration", "ms", optional=True)
    return {"requestId": request_id, "durationMillis": duration,
            "billedDurationMillis": number("Billed Duration", "ms"),
            "memorySizeMiB": number("Memory Size", "MB"),
            "maxMemoryUsedMiB": number("Max Memory Used", "MB"),
            "initDurationMillis": init,
            "environment": "cold" if init is not None else "warm-candidate",
            "processingMillis": duration + (init or 0),
            "underTarget120Seconds": duration + (init or 0) < 120000,
            "underConfigured300Seconds": duration < 300000}


OUTCOMES = ("MATCHED", "MISSING_IN_SETTLEMENT", "MISSING_INTERNALLY", "AMOUNT_MISMATCH", "DUPLICATE")
HEADER = "business_date,transaction_reference,amount_centavos,currency\n"


def maximum_workload(date, namespace):
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,40}", namespace), "Invalid workload namespace")
    purchases = [{"transactionReference": namespace + "_I%04d" % i,
                  "merchantId": "CLOUD-BENCHMARK", "businessDate": date,
                  "amountCentavos": 1, "currency": "ARS"} for i in range(1000)]
    references = [namespace + "_S%04d" % i for i in range(2000)]
    raw = (HEADER + "".join(date + "," + ref + ',1,"ARS"\n' for ref in references)).encode()
    # The real Java CsvBoundaryIT proves spaces after a closing quote are accepted.
    raw = raw[:-1] + b" " * (2097152 - len(raw)) + b"\n"
    counts = dict.fromkeys(OUTCOMES, 0)
    counts.update(MISSING_IN_SETTLEMENT=1000, MISSING_INTERNALLY=2000)
    expected = {"summary": {"internalPurchaseCount": 1000, "settlementRowCount": 2000,
                "distinctSettlementReferenceCount": 2000, "totalResultCount": 3000,
                "outcomeCounts": counts},
                "results": [{"reference": p["transactionReference"], "outcome": "MISSING_IN_SETTLEMENT",
                    "internalAmountCentavos": 1, "merchantId": p["merchantId"], "settlementEvidence": []}
                    for p in purchases] +
                    [{"reference": ref, "outcome": "MISSING_INTERNALLY", "internalAmountCentavos": None,
                      "merchantId": None, "settlementEvidence": [{"rowNumber": i + 1,
                       "reference": ref, "amountCentavos": 1}]} for i, ref in enumerate(references)]}
    return purchases, raw, expected


def classify_pair(cold, replay):
    proven = (cold.get("initDurationMillis") is not None
              and replay.get("initDurationMillis") is None
              and cold.get("logStream") and cold["logStream"] == replay.get("logStream")
              and cold.get("requestId") != replay.get("requestId"))
    if proven:
        replay["environment"] = "warm"
    return bool(proven)


def run_generator(jar, base, date, demo):
    require(Path(jar).is_file(), "Verified generator executable is missing")
    # The generator receives only its public token; never worker/Atlas/AWS secrets.
    env = {k: os.environ[k] for k in ("PATH", "JAVA_HOME", "LANG", "LC_ALL") if k in os.environ}
    env["RECONCILIATION_DEMO_TOKEN"] = demo
    try:
        result = subprocess.run(["java", "-jar", str(jar), "--base-url", base,
            "--business-date", date, "--seed", "0", "--scenario", "canonical"],
            env=env, capture_output=True, text=True, timeout=420)
    except (OSError, subprocess.TimeoutExpired):
        raise CheckFailure("Generator unavailable or deadline exceeded; inspect public run metadata") from None
    if result.returncode != 0:
        # Extract only the CLI's complete recovery marker; captured output stays private.
        ids = set(re.findall(
            r"; runId=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
            r"; inspect public run status and POST /api/v1/reconciliation-runs/\1/reprocess to recover(?=\s|$)",
            result.stderr))
        run_id = str(uuid.UUID(ids.pop())) if len(ids) == 1 else None
        message = "Canonical generator failed; inspect public run metadata"
        if run_id:
            message += "; runId=" + run_id + "; POST /api/v1/reconciliation-runs/" + run_id + "/reprocess to recover"
        raise CheckFailure(message, run_id)
    match = re.fullmatch(r"Verified runId=([0-9a-f-]{36}) results=5\s*", result.stdout)
    require(match is not None, "Generator did not report a verified five-result run")
    return str(uuid.UUID(match[1]))


def diagnostics(logs, run_id, date, version):
    found = {}
    for event in logs.get("events", []):
        message = event.get("message", "")
        try:
            data = json.loads(message[message.index("{"):].strip())
        except (ValueError, TypeError):
            continue
        if (isinstance(data, dict) and data.get("runId") == run_id
                and data.get("businessDate") == date and data.get("versionId") == version
                and data.get("rulesVersion") == "v1"
                and isinstance(data.get("attemptId"), str)
                and re.fullmatch(r"[A-Za-z0-9-]{1,64}", data["attemptId"])
                and type(data.get("durationMillis")) is int
                and data["durationMillis"] >= 0 and "errorCode" in data
                and (data["errorCode"] is None or
                     isinstance(data["errorCode"], str) and re.fullmatch(r"[A-Z_]{1,64}", data["errorCode"]))):
            found[data["attemptId"]] = {
                "requestId": data["attemptId"], "durationMillis": data["durationMillis"],
                "errorCode": data["errorCode"], "logStream": event.get("logStreamName"),
                "timestamp": event.get("timestamp", 0)}
    return sorted(found.values(), key=lambda a: (a["timestamp"], a["requestId"]))


def check_identity(metadata, expected):
    require(metadata.get("objectIdentity") == expected and metadata.get("sha256") == expected["sha256"],
            "Bound object identity/version/checksum changed")


def check_replay(before, after, before_results, after_results, runs, run_id):
    require(before.get("status") == "COMPLETED" and after == before, "Replay changed completed metadata")
    require(before_results == after_results, "Replay changed published report")
    require(sum(r.get("runId") == run_id for r in runs) == 1, "Expected exactly one logical run")


def correlated_attempts(logs, run_id, date, version):
    return {a["requestId"] for a in diagnostics(logs, run_id, date, version) if a["errorCode"] is None}


class CloudRun:
    def __init__(self, api, http, aws, wait):
        self.api, self.http, self.aws, self.wait = api, http, aws, wait

    def results(self, base, path, demo, count):
        result = []
        for page in range((count + 99) // 100):
            value = self.api(base, path + "/results?page=" + str(page) + "&size=100", demo)
            require(value.get("totalResults") == count
                    and len(value.get("results", [])) == min(100, count - len(result)),
                    "Incomplete or inconsistent paginated report")
            result.extend(value["results"])
        require(len({r["reference"] for r in result}) == count, "Duplicate result reference")
        return result

    def logs(self, out, start, pattern):
        return self.aws("logs", "filter-log-events", "--log-group-name", out["log_group"],
                        "--start-time", str(start), "--filter-pattern", '"' + pattern + '"')

    def measurement(self, out, start, request_id):
        logs = self.wait(lambda: self.logs(out, start, request_id),
            lambda value: any("REPORT RequestId: " + request_id in e.get("message", "")
                              for e in value.get("events", [])), seconds=120)
        measured = lambda_measurement(logs, request_id)
        streams = {e.get("logStreamName") for e in logs.get("events", [])
                   if "REPORT RequestId: " + request_id in e.get("message", "")}
        require(len(streams) == 1 and None not in streams, "Missing unique REPORT log stream")
        measured["logStream"] = streams.pop()
        return measured

    def replay(self, out, identity, run_id, date):
        event = {"Records": [{"eventSource": "aws:s3", "eventName": "ObjectCreated:Put",
                  "s3": {"bucket": {"name": identity["bucket"]},
                         "object": {"key": urllib.parse.quote_plus(identity["key"]),
                                    "versionId": identity["versionId"]}}}]}
        with tempfile.TemporaryDirectory() as directory:
            payload = Path(directory) / "replay.json"
            payload.write_text(json.dumps(event))
            response = self.aws("lambda", "invoke", "--function-name", out["function_name"],
                "--invocation-type", "RequestResponse", "--log-type", "Tail",
                "--cli-read-timeout", "330", "--payload", "fileb://" + str(payload),
                str(Path(directory) / "response.json"), timeout=360)
        require(response.get("StatusCode") == 200 and not response.get("FunctionError"),
                "Exact-version synchronous replay failed")
        try:
            tail = base64.b64decode(response["LogResult"], validate=True).decode()
        except (ValueError, KeyError, UnicodeError):
            raise CheckFailure("Replay did not return usable runtime logs") from None
        ids = re.findall(r"REPORT RequestId:\s*([A-Za-z0-9-]+)", tail)
        require(len(ids) == 1, "Replay log tail lacks a unique request ID")
        logs = {"events": [{"message": line} for line in tail.splitlines()]}
        require(ids[0] in correlated_attempts(logs, run_id, date, identity["versionId"]),
                "Replay lacks its own successful correlated diagnostic")
        lambda_measurement(logs, ids[0])
        return ids[0]

    def inspect(self, out, base, demo, date, run_id, raw, expected, start, entry):
        route = "/api/v1/reconciliation-runs"
        path = route + "/" + run_id
        run = self.wait(lambda: self.api(base, path, demo),
                        lambda r: r.get("status") in ("COMPLETED", "FAILED"))
        require(run.get("status") == "COMPLETED", "Run did not complete")
        digest = hashlib.sha256(raw).hexdigest()
        identity = run.get("objectIdentity", {})
        require(identity.get("bucket") == out["bucket"]
                and identity.get("key") == "settlements/" + date + "/" + run_id + ".csv"
                and identity.get("sha256") == digest and run.get("sha256") == digest
                and isinstance(identity.get("versionId"), str) and identity["versionId"],
                "Bound original object identity/checksum differs")
        require(run.get("summary") == expected["summary"], "Unexpected run summary")
        identity = {key: identity[key] for key in ("bucket", "key", "versionId", "sha256")}
        entry.update(runId=run_id, businessDate=date, sha256=digest, objectIdentity=identity,
                     status=run["status"], summary=expected["summary"], byteLength=len(raw))
        before_results = self.results(base, path, demo, expected["summary"]["totalResultCount"])
        require(sorted(before_results, key=lambda r: r["reference"]) ==
                sorted(expected["results"], key=lambda r: r["reference"]), "Report differs from independent expected outcomes")
        checksum = base64.b64encode(bytes.fromhex(digest)).decode()
        head = self.aws("s3api", "head-object", "--bucket", out["bucket"], "--key", identity["key"],
                        "--version-id", identity["versionId"], "--checksum-mode", "ENABLED")
        require(head.get("VersionId") == identity["versionId"] and head.get("ChecksumSHA256") == checksum
                and head.get("ContentLength") == len(raw), "Stored exact-version checksum differs")
        def attempts():
            return diagnostics(self.logs(out, start, run_id), run_id, date, identity["versionId"])
        observed = self.wait(attempts, lambda a: any(v["errorCode"] is None for v in a), seconds=120)
        entry["observedAttemptsBeforeReplay"] = observed
        success = next(a for a in observed if a["errorCode"] is None)
        entry["direct"] = self.measurement(out, start, success["requestId"])
        # S3 has completed before we explicitly invoke anything.
        replay_id = self.replay(out, identity, run_id, date)
        entry["replay"] = self.measurement(out, start, replay_id)
        after = self.api(base, path, demo)
        check_identity(after, identity)
        check_replay(run, after, before_results, self.results(base, path, demo, len(before_results)),
                     self.api(base, route + "?businessDate=" + date, demo), run_id)
        entry["checks"] = {"directS3Completion": "PASS", "exactVersionChecksum": "PASS",
                           "independentExpectedReport": "PASS", "explicitReplay": "PASS"}
        return entry

    def maximum(self, out, base, demo, date, entry):
        purchases, raw, expected = maximum_workload(date, uuid.uuid4().hex)
        entry.update(businessDate=date, byteLength=len(raw), internalPurchaseCount=len(purchases),
                     settlementRowCount=2000, expectedResultCount=3000)
        self.api(base, "/api/v1/business-dates", demo, "POST", {"businessDate": date}, (201,))
        for purchase in purchases:
            self.api(base, "/api/v1/transactions", demo, "POST", purchase, (201,))
        self.api(base, "/api/v1/business-dates/" + date + "/close", demo, "POST")
        digest = hashlib.sha256(raw).hexdigest()
        registration = self.api(base, "/api/v1/reconciliation-runs", demo, "POST",
            {"businessDate": date, "sha256": digest, "byteLength": len(raw)}, (201,))
        registration["runId"] = str(uuid.UUID(registration["runId"]))
        entry["runId"] = registration["runId"]
        upload = registration["uploadInstructions"]
        require(urllib.parse.urlsplit(upload["url"]).scheme == "https", "Presigned upload must use TLS")
        require(upload["requiredHeaders"].get("x-amz-checksum-sha256") ==
                base64.b64encode(bytes.fromhex(digest)).decode(), "Presigned checksum differs")
        start = int(time.time() * 1000) - 1000
        began = time.monotonic()
        status, _, headers = self.http("PUT", upload["url"], body=raw, headers=upload["requiredHeaders"])
        require(status == 200 and headers.get("x-amz-version-id"), "Versioned presigned upload failed")
        path = "/api/v1/reconciliation-runs/" + registration["runId"]
        run = self.wait(lambda: self.api(base, path, demo),
                        lambda r: r.get("status") in ("COMPLETED", "FAILED"))
        entry["uploadToCompletionSeconds"] = time.monotonic() - began
        require(run.get("objectIdentity", {}).get("versionId") == headers["x-amz-version-id"],
                "Direct delivery bound a different upload version")
        self.inspect(out, base, demo, date, registration["runId"], raw, expected, start, entry)
        entry["coldWarmProven"] = classify_pair(entry["direct"], entry["replay"])
        entry["targetMet"] = all(entry[k]["underTarget120Seconds"] for k in ("direct", "replay"))
        entry["configuredLimitMet"] = all(entry[k]["underConfigured300Seconds"] for k in ("direct", "replay"))
        require(entry["coldWarmProven"], "Cold/warm classification unproven; retain pending and investigate")
        require(entry["targetMet"] and entry["configuredLimitMet"], "Maximum workload missed runtime budget; investigate")


def realS3UploadPublishesReportWithinRuntimeBudget(run, maximum):
    require(run.get("status") == "COMPLETED" and run.get("summary", {}).get("totalResultCount") == 5,
            "Canonical run must complete with five results")
    require(all(maximum.get(key) is True for key in ("coldWarmProven", "targetMet", "configuredLimitMet")),
            "Maximum workload cold/warm runtime evidence is incomplete or exceeds budget")
