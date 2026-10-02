#!/usr/bin/env python3
"""Assert Maven execution modes from effective plugin settings.

bootstrap checks configuration while production modules have no unit suites yet.
complete additionally requires actual unit, acceptance, coverage and PIT reports
from a preceding clean verify, once the MVP modules contain handwritten logic.
"""
import argparse
from dataclasses import dataclass
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
MODULES = ('reconciliation-api', 'reconciliation-worker', 'scenario-generator', 'acceptance-tests')
PROFILES = ('all-tests', 'unit-tests', 'acceptance-tests', 'mutation-tests')

@dataclass(frozen=True)
class Mode:
    unit: bool
    acceptance: bool
    coverage: bool
    mutation: bool

def effective_projects(profile=None, skip=None):
    with tempfile.TemporaryDirectory(prefix='reconciliation-modes-') as directory:
        output = Path(directory) / 'effective.xml'
        command = ['bash', str(ROOT / 'mvnw'), '-q', 'help:effective-pom', '-Doutput=' + str(output)]
        if profile:
            command.append('-P' + profile)
        command.extend('-D' + key + '=' + str(value).lower() for key, value in (skip or {}).items())
        result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
        assert result.returncode == 0, result.stdout + result.stderr
        root = ET.parse(output).getroot()
        projects = root.findall('m:project', NS) if root.tag.endswith('projects') else [root]
        return {p.findtext('m:artifactId', namespaces=NS): p for p in projects}

def plugin(project, artifact):
    for candidate in project.findall('m:build/m:plugins/m:plugin', NS):
        if candidate.findtext('m:artifactId', namespaces=NS) == artifact:
            return candidate
    raise AssertionError('missing active plugin ' + artifact)

# Mojo defaults checked against the managed plugins' META-INF/maven/plugin.xml.
# Bounds ensure tests/coverage have compiled inputs and gates finish by verify.
LIFECYCLE = (
    'validate', 'initialize', 'generate-sources', 'process-sources',
    'generate-resources', 'process-resources', 'compile', 'process-classes',
    'generate-test-sources', 'process-test-sources', 'generate-test-resources',
    'process-test-resources', 'test-compile', 'process-test-classes', 'test',
    'prepare-package', 'package', 'pre-integration-test', 'integration-test',
    'post-integration-test', 'verify', 'install', 'deploy',
)
GOAL_PHASES = {
    ('maven-surefire-plugin', 'test'): ('test', 'test', 'verify'),
    ('maven-failsafe-plugin', 'integration-test'): ('integration-test', 'integration-test', 'verify'),
    ('maven-failsafe-plugin', 'verify'): ('verify', 'verify', 'verify'),
    ('jacoco-maven-plugin', 'prepare-agent'): ('initialize', 'initialize', 'process-test-classes'),
    ('jacoco-maven-plugin', 'report'): ('verify', 'prepare-package', 'verify'),
    ('jacoco-maven-plugin', 'check'): ('verify', 'prepare-package', 'verify'),
    ('pitest-maven', 'mutationCoverage'): ('verify', 'prepare-package', 'verify'),
}

def enabled(project, artifact, skip_key, required_goals):
    selected = plugin(project, artifact)
    executions = selected.findall('m:executions/m:execution', NS)
    plugin_skip = selected.findtext('m:configuration/m:' + skip_key, namespaces=NS)
    flags = []
    for goal in sorted(required_goals):
        matches = [execution for execution in executions
                   for declared in execution.findall('m:goals/m:goal', NS)
                   if declared.text == goal]
        assert len(matches) == 1, f'{artifact} must bind {goal} once; found {len(matches)}'
        execution = matches[0]
        value = execution.findtext('m:configuration/m:' + skip_key, namespaces=NS)
        if value is None:
            value = plugin_skip
        assert value in ('true', 'false'), f'{artifact}.{goal}.{skip_key} is unresolved: {value}'
        assert (artifact, goal) in GOAL_PHASES, f'unknown Mojo lifecycle contract: {artifact}:{goal}'
        default, earliest, latest = GOAL_PHASES[(artifact, goal)]
        phase = execution.findtext('m:phase', namespaces=NS)
        if phase is None:
            phase = default
        scheduled = (phase in LIFECYCLE and
                     LIFECYCLE.index(earliest) <= LIFECYCLE.index(phase) <= LIFECYCLE.index(latest))
        flags.append(scheduled and value == 'false')
    assert flags and len(set(flags)) == 1, f'{artifact} has inconsistent required goal execution: {flags}'
    return flags[0]

def effective_mode(profile: str | None, skip: dict[str, bool] = {}) -> Mode:
    projects = effective_projects(profile, skip)
    assert set(MODULES) <= projects.keys(), 'expected API, worker, generator and acceptance reactor modules'
    flags = []
    for name in MODULES[:3]:
        p = projects[name]
        flags.append((enabled(p, 'maven-surefire-plugin', 'skipTests', {'test'}),
                      enabled(p, 'jacoco-maven-plugin', 'skip', {'prepare-agent', 'report', 'check'}),
                      enabled(p, 'pitest-maven', 'skip', {'mutationCoverage'})))
    assert len(set(flags)) == 1, f'production modules disagree: {flags}'
    acceptance = enabled(projects['acceptance-tests'], 'maven-failsafe-plugin', 'skipTests', {'integration-test', 'verify'})
    unit, coverage, mutation = flags[0]
    return Mode(unit, acceptance, coverage, mutation)

def selectedProfilesHaveExactExecutionFlags():
    expected = {
        None: Mode(True, True, True, True),
        'all-tests': Mode(True, True, True, True),
        'unit-tests': Mode(True, False, True, False),
        'acceptance-tests': Mode(False, True, False, False),
        'mutation-tests': Mode(True, False, True, True),
    }
    for profile, mode in expected.items():
        actual = effective_mode(profile)
        assert actual == mode, f'{profile}: expected {mode}, got {actual}'
        print(f'PASS {profile or "default"}: {actual}', flush=True)
        for standard_skip in ('skipTests', 'maven.test.skip'):
            actual = effective_mode(profile, {standard_skip: True})
            assert actual == Mode(False, False, False, False), f'{profile}/{standard_skip}: {actual}'
    for key, expected_mode in {
        'tests.unit.skip': Mode(False, True, True, True),
        'tests.acceptance.skip': Mode(True, False, True, True),
        'quality.skip': Mode(True, True, False, True),
        'mutation.skip': Mode(True, True, True, False),
    }.items():
        assert effective_mode(None, {key: True}) == expected_mode, key
    from itertools import combinations
    for left, right in combinations(PROFILES, 2):
        result = subprocess.run(['bash', str(ROOT / 'mvnw'), '-q', '-P' + left + ',' + right, 'validate'], cwd=ROOT, text=True, capture_output=True)
        assert result.returncode != 0, f'conflicting profiles accepted: {left},{right}'
        assert 'Select at most one test mode profile' in result.stdout + result.stderr, result.stdout + result.stderr
    print('PASS standard/custom skips and all six conflicting profile pairs', flush=True)

def completed_reports():
    projects = effective_projects()
    for name in MODULES[:3]:
        properties = projects[name].find('m:properties', NS)
        for key in ('tests.failIfNoTests', 'mutation.failWhenNoMutations'):
            assert properties.findtext('m:' + key, namespaces=NS) == 'true', f'{name}: bootstrap override still active: {key}'
        target = ROOT / name / 'target'
        reports = list((target / 'surefire-reports').glob('TEST-*.xml'))
        assert reports and sum(int(ET.parse(r).getroot().get('tests', '0')) - int(ET.parse(r).getroot().get('skipped', '0')) for r in reports) > 0, f'{name}: no discovered unit tests'
        coverage = ET.parse(target / 'site/jacoco/jacoco.xml').getroot()
        assert all(int(c.get('missed')) == 0 for c in coverage.findall('counter') if c.get('type') in ('LINE', 'BRANCH')), f'{name}: coverage misses'
        mutations = ET.parse(target / 'pit-reports/mutations.xml').getroot().findall('mutation')
        assert mutations, f'{name}: no mutations executed'
        detected = sum(m.get('detected') == 'true' for m in mutations)
        assert detected / len(mutations) >= 0.8, f'{name}: mutation score below 80%'
    reports = list((ROOT / 'acceptance-tests/target/failsafe-reports').glob('TEST-*.xml'))
    assert reports and sum(int(ET.parse(r).getroot().get('tests', '0')) - int(ET.parse(r).getroot().get('skipped', '0')) for r in reports) > 0, 'no discovered acceptance tests'
    for report in [*reports, *(ROOT.glob('*/target/surefire-reports/TEST-*.xml'))]:
        suite = ET.parse(report).getroot()
        assert suite.get('failures', '0') == '0' and suite.get('errors', '0') == '0', str(report)
    print('PASS complete test/coverage/mutation discovery', flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--phase', choices=('bootstrap', 'complete'), required=True)
    args = parser.parse_args()
    selectedProfilesHaveExactExecutionFlags()
    if args.phase == 'complete':
        completed_reports()
