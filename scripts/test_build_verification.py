"""Regression checks for report discovery; run with unittest discover."""
import json
from pathlib import Path
import tempfile
import unittest
import importlib.util
import sys
spec = importlib.util.spec_from_file_location("verify_build_modes", Path(__file__).with_name("verify-build-modes.py"))
modes = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = modes
spec.loader.exec_module(modes)

class ReportEvidenceTest(unittest.TestCase):
    def test_cucumber_counts_testcases_instead_of_stale_suite_attribute(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "TEST-AcceptanceIT.xml"
            path.write_text('<testsuite tests="1"><testcase name="one"/><testcase name="two"/></testsuite>')
            self.assertEqual(2, modes.executed_cases(path))

    def test_skipped_case_is_not_execution_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "TEST-unit.xml"
            path.write_text('<testsuite tests="1"><testcase><skipped/></testcase></testsuite>')
            self.assertEqual(0, modes.executed_cases(path))

    def test_enabled_unit_mode_rejects_missing_execution_data(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(AssertionError, "no discovered unit"):
                modes.inspect_build(Path(directory), modes.Mode(True, False, True, False), True)

    def test_skip_compilation_rejects_test_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for module in modes.MODULES:
                (root / module / "target/classes").mkdir(parents=True)
                (root / module / "target/classes/Example.class").write_bytes(b"class")
                (root / module / "target/example.jar").write_bytes(b"jar")
            (root / modes.MODULES[0] / "target/test-classes").mkdir()
            (root / modes.MODULES[0] / "target/test-classes/ExampleTest.class").write_bytes(b"class")
            with self.assertRaisesRegex(AssertionError, "test compilation"):
                modes.inspect_build(root, modes.Mode(False, False, False, False), False)

if __name__ == "__main__":
    unittest.main()
