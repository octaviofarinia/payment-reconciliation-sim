"""Offline regressions for runner output ownership; Maven is never invoked."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

gates = load("quality_gates_retention", "check-quality-gates.py")
modes = load("build_modes_retention", "verify-build-modes.py")

class ReportRetentionTests(unittest.TestCase):
    def test_gate_rejects_nonignored_repository_log_directory_before_work(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "source.txt").write_text("product")
            with patch.object(gates, "ROOT", root), patch.object(gates, "build") as build:
                with self.assertRaisesRegex(ValueError, "log directory"):
                    gates.selectedGatesFailForDeliberateViolations(root / "review-logs", {"none"})
                build.assert_not_called()
            self.assertFalse((root / "review-logs").exists())

    def test_gate_rejects_repository_root_and_ancestor_before_work(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            root.mkdir()
            for output in (root, root.parent):
                with self.subTest(output=output), patch.object(gates, "ROOT", root):
                    with self.assertRaisesRegex(ValueError, "log directory"):
                        gates.selectedGatesFailForDeliberateViolations(output, {"none"})

    def test_gate_accepts_ignored_and_external_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            root.mkdir()
            (root / "source.txt").write_text("product")
            for output in (root / ".verification/custom", root.parent / "outside"):
                with self.subTest(output=output), patch.object(gates, "ROOT", root):
                    result = gates.selectedGatesFailForDeliberateViolations(output, {"none"})
                self.assertTrue(result["cleanup"]["real_sources_unchanged"])
                self.assertTrue((output / "evidence.json").is_file())

    def _seed(self, root):
        report = root / "reconciliation-api/target/surefire-reports"
        report.mkdir(parents=True)
        (report / "TEST-old.xml").write_text("old")
        coverage = root / "reconciliation-api/target/site/jacoco"
        coverage.mkdir(parents=True)
        (coverage / "jacoco.xml").write_text("old coverage")
        cucumber = root / "acceptance-tests/target/cucumber-report.json"
        cucumber.parent.mkdir(parents=True)
        cucumber.write_text("old cucumber")
        return report, coverage, cucumber

    def _changed_reports(self, report, coverage, cucumber):
        (report / "TEST-old.xml").unlink()
        (report / "TEST-new.xml").write_text("new")
        (coverage / "jacoco.xml").unlink()
        coverage.rmdir()
        cucumber.unlink()

    def test_gate_snapshot_replaces_renamed_and_absent_reports_and_preserves_prior_run(self):
        with tempfile.TemporaryDirectory() as directory:
            root, logs = Path(directory) / "repo", Path(directory) / "logs"
            logs.mkdir()
            report, coverage, _ = self._seed(root)
            with patch.object(gates.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)):
                gates.build(root, [], logs / "previous.log")
                gates.build(root, [], logs / "current.log")
                (report / "TEST-old.xml").unlink()
                (report / "TEST-new.xml").write_text("new")
                (coverage / "jacoco.xml").unlink()
                coverage.rmdir()
                gates.build(root, [], logs / "current.log")
            current = logs / "current/reconciliation-api"
            self.assertFalse((current / "surefire-reports/TEST-old.xml").exists())
            self.assertEqual("new", (current / "surefire-reports/TEST-new.xml").read_text())
            self.assertFalse((current / "site/jacoco").exists())
            self.assertEqual("old", (logs / "previous/reconciliation-api/surefire-reports/TEST-old.xml").read_text())

    def test_mode_snapshot_replaces_renamed_and_absent_reports_and_preserves_prior_run(self):
        with tempfile.TemporaryDirectory() as directory:
            root, logs = Path(directory) / "repo", Path(directory) / "logs"
            report, coverage, cucumber = self._seed(root)
            with patch.object(modes, "ROOT", root), patch.object(modes, "run_build", return_value=subprocess.CompletedProcess([], 0)), patch.object(modes, "inspect_build", return_value={}):
                modes.complete_modes(logs / "previous")
                modes.complete_modes(logs / "current")
                self._changed_reports(report, coverage, cucumber)
                modes.complete_modes(logs / "current")
            current = logs / "current/default"
            self.assertFalse((current / "reconciliation-api/surefire-reports/TEST-old.xml").exists())
            self.assertEqual("new", (current / "reconciliation-api/surefire-reports/TEST-new.xml").read_text())
            self.assertFalse((current / "reconciliation-api/site/jacoco").exists())
            self.assertFalse((current / "acceptance-tests/cucumber-report.json").exists())
            self.assertEqual("old", (logs / "previous/default/reconciliation-api/surefire-reports/TEST-old.xml").read_text())

if __name__ == "__main__":
    unittest.main()
