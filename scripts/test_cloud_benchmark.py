"""Offline test evidence only: synthetic logs never establish cloud measurements."""
import importlib.util
import json
import csv
import io
import tempfile
from unittest.mock import patch
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("cloud", Path(__file__).with_name("cloud-smoke.py"))
cloud = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cloud)


class MeasurementTests(unittest.TestCase):
    def report(self, extra=""):
        return {"events": [{"message": "REPORT RequestId: request-1\tDuration: 119999.50 ms"
                "\tBilled Duration: 120100 ms\tMemory Size: 512 MB\tMax Memory Used: 301 MB" + extra}]}

    def test_request_correlated_report_records_cold_init_and_separate_budgets(self):
        self.assertTrue(hasattr(cloud, "lambda_measurement"), "Lambda REPORT measurement is not implemented")
        value = cloud.lambda_measurement(self.report("\tInit Duration: 450.25 ms"), "request-1")
        self.assertEqual(119999.5, value["durationMillis"])
        self.assertEqual(450.25, value["initDurationMillis"])
        self.assertEqual(301, value["maxMemoryUsedMiB"])
        self.assertEqual("cold", value["environment"])
        self.assertFalse(value["underTarget120Seconds"])
        self.assertTrue(value["underConfigured300Seconds"])

    def test_warm_is_qualified_and_other_requests_cannot_supply_measurements(self):
        self.assertTrue(hasattr(cloud, "lambda_measurement"), "Lambda REPORT measurement is not implemented")
        value = cloud.lambda_measurement(self.report(), "request-1")
        self.assertEqual("warm-candidate", value["environment"])
        self.assertIsNone(value["initDurationMillis"])
        for logs in [{}, self.report(), self.report("\tStatus: timeout")]:
            with self.assertRaises(cloud.CheckFailure):
                cloud.lambda_measurement(logs, "different" if logs == self.report() else "request-1")

    def test_strict_target_and_configured_boundaries(self):
        self.assertTrue(hasattr(cloud, "lambda_measurement"), "Lambda REPORT measurement is not implemented")
        for duration, target, limit in [(120000, False, True), (300000, False, False)]:
            logs = self.report()
            logs["events"][0]["message"] = logs["events"][0]["message"].replace("119999.50", str(duration))
            value = cloud.lambda_measurement(logs, "request-1")
            self.assertEqual(target, value["underTarget120Seconds"])
            self.assertEqual(limit, value["underConfigured300Seconds"])

class WorkloadTests(unittest.TestCase):
    def test_exact_supported_bounds_and_independent_outcomes(self):
        self.assertTrue(hasattr(cloud, "benchmark"), "Benchmark helper is missing")
        purchases, raw, expected = cloud.benchmark.maximum_workload("2020-01-01", "test")
        self.assertEqual(1000, len(purchases))
        self.assertEqual(2097152, len(raw))
        previous_limit = csv.field_size_limit(2097152)
        try:
            rows = list(csv.reader(io.StringIO(raw.decode())))
        finally:
            csv.field_size_limit(previous_limit)
        self.assertEqual(2001, len(rows))
        self.assertEqual(["business_date", "transaction_reference", "amount_centavos", "currency"], rows[0])
        internal = {p["transactionReference"] for p in purchases}
        external = {row[1] for row in rows[1:]}
        self.assertEqual(1000, len(internal))
        self.assertEqual(2000, len(external))
        self.assertFalse(internal & external)
        self.assertTrue(all(row[0] == "2020-01-01" and row[2] == "1" and row[3].rstrip() == "ARS"
                            for row in rows[1:]))
        self.assertEqual(3000, expected["summary"]["totalResultCount"])
        self.assertEqual(1000, expected["summary"]["outcomeCounts"]["MISSING_IN_SETTLEMENT"])
        self.assertEqual(2000, expected["summary"]["outcomeCounts"]["MISSING_INTERNALLY"])
        self.assertEqual(3000, len(expected["results"]))
        self.assertTrue(all(len(r) <= 64 for r in internal | external))

    def test_warm_requires_same_stream_after_proven_cold_and_target_includes_init(self):
        self.assertTrue(hasattr(cloud, "benchmark"), "Benchmark helper is missing")
        cold = {"environment": "cold", "logStream": "one", "requestId": "first",
                "durationMillis": 119900, "initDurationMillis": 200}
        replay = {"environment": "warm-candidate", "logStream": "one", "requestId": "second",
                  "durationMillis": 1, "initDurationMillis": None}
        self.assertTrue(cloud.benchmark.classify_pair(cold, replay))
        self.assertEqual("warm", replay["environment"])
        for change in [{"logStream": "other"}, {"requestId": "first"}, {"initDurationMillis": 1}]:
            self.assertFalse(cloud.benchmark.classify_pair(cold, dict(replay, **change)))
        self.assertFalse(cloud.benchmark.classify_pair(dict(cold, initDurationMillis=None), replay))


class CanonicalGeneratorTests(unittest.TestCase):
    def test_generator_uses_public_cli_and_only_demo_secret_and_redacts_failure(self):
        self.assertTrue(hasattr(cloud, "benchmark"), "Benchmark helper is missing")
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "generator.jar"
            jar.write_bytes(b"offline fixture")
            run_id = "12345678-1234-1234-1234-123456789012"
            with patch.dict(cloud.os.environ, {"WORKER_TOKEN": "private", "AWS_SECRET_ACCESS_KEY": "private"}), \
                 patch.object(cloud.subprocess, "run", return_value=subprocess.CompletedProcess([], 0,
                              "Verified runId=" + run_id + " results=5\n", "")) as process:
                self.assertEqual(run_id, cloud.benchmark.run_generator(jar, "http://127.0.0.1:8080", "2020-01-01", "demo"))
                options = process.call_args.kwargs
                self.assertEqual("demo", options["env"]["RECONCILIATION_DEMO_TOKEN"])
                self.assertNotIn("AWS_SECRET_ACCESS_KEY", options["env"])
                self.assertNotIn("WORKER_TOKEN", options["env"])
                self.assertNotIn("demo", process.call_args.args[0])
            with patch.object(cloud.subprocess, "run", return_value=subprocess.CompletedProcess([], 1,
                              "secret-presigned-url", "private")):
                with self.assertRaises(cloud.CheckFailure) as failure:
                    cloud.benchmark.run_generator(jar, "http://127.0.0.1:8080", "2020-01-01", "demo")
                self.assertNotIn("private", str(failure.exception))


class EvidenceWorkflowTests(unittest.TestCase):
    def test_report_paging_rejects_count_drift_and_duplicate_references(self):
        self.assertTrue(hasattr(cloud.benchmark, "CloudRun"), "Cloud evidence orchestration missing")
        rows = [{"reference": str(i)} for i in range(105)]
        calls = []
        def api(base, path, token):
            calls.append(path)
            page = int(path.split("page=")[1].split("&")[0])
            return {"page": page, "size": 100, "totalResults": 105, "results": rows[page*100:(page+1)*100]}
        check = cloud.benchmark.CloudRun(api, None, None, None)
        self.assertEqual(rows, check.results("base", "path", "demo", 105))
        self.assertEqual(2, len(calls))
        with self.assertRaises(cloud.CheckFailure):
            check.results("base", "path", "demo", 104)
        rows[104] = rows[0]
        with self.assertRaises(cloud.CheckFailure):
            check.results("base", "path", "demo", 105)

    def test_explicit_replay_requires_its_own_success_and_original_version(self):
        self.assertTrue(hasattr(cloud.benchmark, "CloudRun"), "Cloud evidence orchestration missing")
        import base64
        identity = {"bucket": "bucket", "key": "settlements/2020-01-01/run.csv", "versionId": "original"}
        diagnostic = {"runId": "run", "businessDate": "2020-01-01", "versionId": "original",
                      "rulesVersion": "v1", "attemptId": "explicit", "durationMillis": 1, "errorCode": None}
        report = ("REPORT RequestId: explicit\tDuration: 1 ms\tBilled Duration: 1 ms"
                  "\tMemory Size: 512 MB\tMax Memory Used: 200 MB")
        def invoke(*args, **kwargs):
            payload = json.loads(Path(args[args.index("--payload")+1][8:]).read_text())
            self.assertEqual("original", payload["Records"][0]["s3"]["object"]["versionId"])
            self.assertIn("RequestResponse", args)
            self.assertEqual(360, kwargs["timeout"])
            return {"StatusCode": 200, "LogResult": base64.b64encode(
                (json.dumps(diagnostic) + "\n" + report).encode()).decode()}
        check = cloud.benchmark.CloudRun(None, None, invoke, None)
        self.assertEqual("explicit", check.replay({"function_name": "fn"}, identity, "run", "2020-01-01"))
        diagnostic["attemptId"] = "delayed-duplicate"
        with self.assertRaises(cloud.CheckFailure):
            check.replay({"function_name": "fn"}, identity, "run", "2020-01-01")
        diagnostic["attemptId"] = "explicit"
        diagnostic["errorCode"] = "RETRY_REQUIRED"
        with self.assertRaises(cloud.CheckFailure):
            check.replay({"function_name": "fn"}, identity, "run", "2020-01-01")

    def test_failed_smoke_retains_allowlisted_evidence_and_never_overwrites(self):
        self.assertTrue(hasattr(cloud, "smoke_evidence"), "Durable evidence wrapper missing")
        import argparse
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            args = argparse.Namespace(output=str(path), business_date="2020-01-01", benchmark=True)
            with patch.object(cloud, "smoke", side_effect=ValueError("private-token-and-url")):
                with self.assertRaises(ValueError):
                    cloud.smoke_evidence(args)
            saved = json.loads(path.read_text())
            self.assertEqual("FAILED", saved["status"])
            self.assertEqual("PENDING", saved["checks"]["teardown"])
            self.assertNotIn("private-token-and-url", path.read_text())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            with patch.object(cloud, "smoke") as workflow:
                with self.assertRaises(FileExistsError):
                    cloud.smoke_evidence(args)
                workflow.assert_not_called()


class NamedCloudCheckTests(unittest.TestCase):
    def test_named_check_needs_completed_five_results_and_proven_maximum_budgets(self):
        self.assertTrue(hasattr(cloud.benchmark, "realS3UploadPublishesReportWithinRuntimeBudget"),
                        "Named cloud verification is missing")
        run = {"status": "COMPLETED", "summary": {"totalResultCount": 5}}
        maximum = {"coldWarmProven": True, "targetMet": True, "configuredLimitMet": True}
        check = cloud.benchmark.realS3UploadPublishesReportWithinRuntimeBudget
        check(run, maximum)
        for candidate in [{}, dict(maximum, coldWarmProven=False), dict(maximum, targetMet=False),
                          dict(maximum, configuredLimitMet=False)]:
            with self.assertRaises(cloud.CheckFailure):
                check(run, candidate)
        with self.assertRaises(cloud.CheckFailure):
            check({"status": "FAILED", "summary": {"totalResultCount": 5}}, maximum)
        with self.assertRaises(cloud.CheckFailure):
            check({"status": "COMPLETED", "summary": {"totalResultCount": 1}}, maximum)
