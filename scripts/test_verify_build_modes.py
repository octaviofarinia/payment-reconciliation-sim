"""Regression fixtures for lifecycle scheduling and Maven configuration merging."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

import runpy
verification = runpy.run_path(str(Path(__file__).with_name('verify-build-modes.py')))
ROOT, enabled = verification['ROOT'], verification['enabled']


def project(plugin_skip='false', phase='verify', execution_skip=None, goals=('mutationCoverage',), artifact='pitest-maven'):
    skip = '' if execution_skip is None else '<configuration><skip>' + execution_skip + '</skip></configuration>'
    phase_xml = '' if phase is None else '<phase>' + phase + '</phase>'
    return ET.fromstring('''<project xmlns="http://maven.apache.org/POM/4.0.0"><build><plugins><plugin>
      <artifactId>''' + artifact + '''</artifactId><configuration><skip>''' + plugin_skip + '''</skip></configuration>
      <executions><execution><id>gate</id>''' + phase_xml + '<goals>' + ''.join('<goal>' + g + '</goal>' for g in goals) + '</goals>' + skip + '</execution></executions></plugin></plugins></build></project>')


class GateExecutionTests(unittest.TestCase):
    def test_disabled_phase_is_not_enabled(self):
        self.assertFalse(enabled(project(phase='none'), 'pitest-maven', 'skip', {'mutationCoverage'}))

    def test_binding_after_verify_is_not_enabled(self):
        self.assertFalse(enabled(project(phase='install'), 'pitest-maven', 'skip', {'mutationCoverage'}))

    def test_pit_uses_mojo_default_phase(self):
        self.assertTrue(enabled(project(phase=None), 'pitest-maven', 'skip', {'mutationCoverage'}))

    def test_unknown_lifecycle_phase_is_not_enabled(self):
        self.assertFalse(enabled(project(phase='nonexistent'), 'pitest-maven', 'skip', {'mutationCoverage'}))

    def test_execution_can_disable_enabled_plugin(self):
        self.assertFalse(enabled(project(execution_skip='true'), 'pitest-maven', 'skip', {'mutationCoverage'}))

    def test_execution_can_enable_skipped_plugin(self):
        self.assertTrue(enabled(project(plugin_skip='true', execution_skip='false'), 'pitest-maven', 'skip', {'mutationCoverage'}))

    def test_missing_required_goal_fails_verification(self):
        with self.assertRaises(AssertionError):
            enabled(project(goals=()), 'pitest-maven', 'skip', {'mutationCoverage'})

    def test_duplicate_required_goal_fails_verification(self):
        with self.assertRaises(AssertionError):
            enabled(project(goals=('mutationCoverage', 'mutationCoverage')), 'pitest-maven', 'skip', {'mutationCoverage'})

    def test_unresolved_execution_skip_fails_verification(self):
        with self.assertRaises(AssertionError):
            enabled(project(execution_skip='${unknown.skip}'), 'pitest-maven', 'skip', {'mutationCoverage'})

    def test_jacoco_agent_uses_mojo_default_phase(self):
        self.assertTrue(enabled(project(phase=None, goals=('prepare-agent',), artifact='jacoco-maven-plugin'), 'jacoco-maven-plugin', 'skip', {'prepare-agent'}))

    def test_jacoco_agent_after_tests_is_not_enabled(self):
        self.assertFalse(enabled(project(phase='verify', goals=('prepare-agent',), artifact='jacoco-maven-plugin'), 'jacoco-maven-plugin', 'skip', {'prepare-agent'}))

    def test_partially_skipped_coverage_fails_verification(self):
        fixture = ET.fromstring('''<project xmlns="http://maven.apache.org/POM/4.0.0"><build><plugins><plugin>
          <artifactId>jacoco-maven-plugin</artifactId><configuration><skip>false</skip></configuration><executions>
          <execution><id>agent</id><goals><goal>prepare-agent</goal></goals><configuration><skip>true</skip></configuration></execution>
          <execution><id>gate</id><phase>verify</phase><goals><goal>report</goal><goal>check</goal></goals></execution>
          </executions></plugin></plugins></build></project>''')
        with self.assertRaises(AssertionError):
            enabled(fixture, 'jacoco-maven-plugin', 'skip', {'prepare-agent', 'report', 'check'})


class MavenEffectivePomTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.effective = {}
        for name, plugin_skip, phase, execution_skip in [('disabled-phase', 'false', 'none', 'false'), ('execution-override', 'true', 'verify', 'false')]:
            with tempfile.TemporaryDirectory(prefix='reconciliation-gate-fixture-') as directory:
                fixture = Path(directory) / 'pom.xml'
                fixture.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>org.octavio.verification</groupId><artifactId>gate-fixture</artifactId><version>1</version><packaging>pom</packaging>
                  <build><plugins><plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><version>1.30.0</version>
                  <configuration><skip>''' + plugin_skip + '''</skip></configuration><executions><execution><id>gate</id><phase>''' + phase + '''</phase>
                  <goals><goal>mutationCoverage</goal></goals><configuration><skip>''' + execution_skip + '''</skip></configuration>
                  </execution></executions></plugin></plugins></build></project>''')
                output = Path(directory) / 'effective.xml'
                result = subprocess.run(['bash', str(ROOT / 'mvnw'), '-q', '-f', str(fixture), 'help:effective-pom', '-Doutput=' + str(output)], cwd=ROOT, text=True, capture_output=True)
                if result.returncode != 0:
                    raise AssertionError(result.stdout + result.stderr)
                cls.effective[name] = ET.parse(output).getroot()

    def test_actual_effective_pom_disabled_phase(self):
        self.assertFalse(enabled(self.effective['disabled-phase'], 'pitest-maven', 'skip', {'mutationCoverage'}))

    def test_actual_effective_pom_execution_override(self):
        self.assertTrue(enabled(self.effective['execution-override'], 'pitest-maven', 'skip', {'mutationCoverage'}))


if __name__ == '__main__':
    unittest.main()
