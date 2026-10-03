#!/usr/bin/env python3
"""Assert Maven execution modes from effective plugin settings.

bootstrap checks configuration while production modules have no unit suites yet.
complete executes documented builds sequentially and inspects fresh reports.
"""
import argparse
import json
import shutil
import time
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

def executed_cases(path):
    """Count executed testcase elements, including nested suites, never stale roots."""
    root = ET.parse(path).getroot()
    assert not root.findall(".//failure") and not root.findall(".//error"), str(path)
    return sum(case.find("skipped") is None for case in root.findall(".//testcase"))


def inspect_build(root, mode, test_compile):
    evidence = {}
    for name in MODULES:
        target = root / name / "target"
        unit_reports = list((target / "surefire-reports").glob("TEST-*.xml"))
        acceptance_reports = list((target / "failsafe-reports").glob("TEST-*.xml"))
        units = sum(executed_cases(path) for path in unit_reports)
        acceptance = sum(executed_cases(path) for path in acceptance_reports)
        unit_exec = target / "jacoco-unit.exec"
        acceptance_exec = target / "jacoco-acceptance.exec"
        coverage_path = target / "site/jacoco/jacoco.xml"
        mutation_path = target / "pit-reports/mutations.xml"
        if name in MODULES[:3]:
            assert (units > 0) if mode.unit else (not unit_reports), name + ": no discovered unit tests or forbidden unit execution"
            assert not acceptance_reports and not acceptance_exec.exists(), name + ": acceptance leaked into production module"
            evidence[name] = {"units": units}
            if mode.coverage:
                assert unit_exec.is_file() and unit_exec.stat().st_size > 0, name + ": no unit coverage data"
                coverage = ET.parse(coverage_path).getroot()
                classes = []
                for klass in coverage.findall(".//class"):
                    counts = {counter.get("type"): {"missed": int(counter.get("missed")), "covered": int(counter.get("covered"))}
                              for counter in klass.findall("counter") if counter.get("type") in ("LINE", "BRANCH")}
                    assert all(count["missed"] == 0 for count in counts.values()), name + ": coverage misses " + klass.get("name")
                    if counts:
                        classes.append({"class": klass.get("name"), "counters": counts})
                assert classes, name + ": no covered logic"
                evidence[name]["classes"] = classes
            else:
                assert not unit_exec.exists() and not coverage_path.exists(), name + ": forbidden unit coverage execution"
            if mode.mutation:
                mutations = ET.parse(mutation_path).getroot().findall("mutation")
                assert mutations, name + ": no mutations"
                statuses = {}
                survivors = []
                for mutation in mutations:
                    status = mutation.get("status")
                    statuses[status] = statuses.get(status, 0) + 1
                    killer = mutation.findtext("killingTest") or ""
                    assert "acceptance" not in killer and "IT(" not in killer, name + ": PIT used acceptance tests"
                    if status == "SURVIVED":
                        survivors.append({field: mutation.findtext(field) for field in
                                          ("mutatedClass", "mutatedMethod", "lineNumber", "mutator", "description")})
                assert not any(statuses.get(status, 0) for status in ("NO_COVERAGE", "RUN_ERROR", "MEMORY_ERROR", "NON_VIABLE")), statuses
                detected = sum(mutation.get("detected") == "true" for mutation in mutations)
                assert detected * 100 // len(mutations) >= 80, name + ": mutation floor"
                evidence[name].update(mutations=len(mutations), detected=detected, statuses=statuses, survivors=survivors)
            else:
                assert not mutation_path.exists(), name + ": forbidden mutation execution"
        else:
            assert not unit_reports and not unit_exec.exists() and not coverage_path.exists() and not mutation_path.exists(), "test-only module has unit gates"
            if mode.acceptance:
                assert acceptance > 0, "no discovered acceptance checks"
                assert acceptance_exec.is_file() and acceptance_exec.stat().st_size > 0, "missing separate acceptance coverage"
                cucumber = json.loads((target / "cucumber-report.json").read_text())
                scenarios = [scenario for feature in cucumber for scenario in feature.get("elements", []) if scenario.get("type") == "scenario"]
                assert scenarios and all(step["result"]["status"] == "passed" for scenario in scenarios for step in scenario["steps"]), "no passed Cucumber scenarios"
                summary = ET.parse(target / "failsafe-reports/failsafe-summary.xml").getroot()
                completed = int(summary.findtext("completed"))
                assert int(summary.findtext("errors")) == 0 and int(summary.findtext("failures")) == 0
                assert completed == acceptance, f"Failsafe completed {completed} differs from executed testcase count {acceptance}"
                cucumber_cases = sum(executed_cases(path) for path in acceptance_reports if path.name.endswith("AcceptanceIT.xml"))
                assert cucumber_cases == len(scenarios), "Cucumber events/cases disagree"
                evidence[name] = {"acceptance": acceptance, "cucumber": len(scenarios), "failsafe_completed": completed}
            else:
                assert not acceptance_reports and not acceptance_exec.exists() and not (target / "cucumber-report.json").exists(), "forbidden acceptance execution"
        classes = list((target / "test-classes").rglob("*.class"))
        assert bool(classes) == test_compile, name + ": unexpected test compilation"
        if name in MODULES[:3]:
            assert list((target / "classes").rglob("*.class")), name + ": no production compilation"
            assert list(target.glob("*.jar")), name + ": missing packaged artifact"
    return evidence


def run_build(root, arguments, log):
    command = ["bash", str(root / "mvnw"), *arguments, "-B", "-ntp", "-Dstyle.color=never"]
    print("RUN " + " ".join(command), flush=True)
    started = time.monotonic()
    with log.open("w") as output:
        result = subprocess.run(command, cwd=root, stdout=output, stderr=subprocess.STDOUT, timeout=1800)
    print(f"EXIT {result.returncode} ({time.monotonic() - started:.1f}s) log={log}", flush=True)
    return result


def complete_modes(log_dir):
    log_dir.mkdir(parents=True, exist_ok=True)
    none = Mode(False, False, False, False)
    cases = [
        ("default", ["clean", "verify"], Mode(True, True, True, True), True),
        ("all-tests", ["clean", "verify", "-Pall-tests"], Mode(True, True, True, True), True),
        ("unit-tests", ["clean", "verify", "-Punit-tests"], Mode(True, False, True, False), True),
        ("acceptance-tests", ["clean", "verify", "-Pacceptance-tests"], Mode(False, True, False, False), True),
        ("mutation-tests", ["clean", "verify", "-Pmutation-tests"], Mode(True, False, True, True), True),
        ("skip-package", ["clean", "package", "-DskipTests"], none, True),
        ("skip-compile-package", ["clean", "package", "-Dmaven.test.skip=true"], none, False),
        ("skip-verify", ["clean", "verify", "-DskipTests"], none, True),
        ("skip-compile-verify", ["clean", "verify", "-Dmaven.test.skip=true"], none, False),
    ]
    evidence = {}
    for name, arguments, mode, test_compile in cases:
        result = run_build(ROOT, arguments, log_dir / (name + ".log"))
        assert result.returncode == 0, f"{name} failed; see {log_dir / (name + '.log')}"
        evidence[name] = inspect_build(ROOT, mode, test_compile)
        for module in MODULES:
            for relative in ("surefire-reports", "failsafe-reports", "site/jacoco", "pit-reports"):
                source = ROOT / module / "target" / relative
                destination = log_dir / name / module / relative
                # Replace only runner-owned report subtrees, even when the new
                # build no longer produces this kind of report.
                if destination.exists():
                    shutil.rmtree(destination)
                if source.exists():
                    shutil.copytree(source, destination)
            source = ROOT / module / "target/cucumber-report.json"
            destination = log_dir / name / module / source.name
            if destination.exists():
                destination.unlink()
            if source.exists():
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
        (log_dir / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
        print("PASS actual " + name + ": " + json.dumps({module: {key: value for key, value in values.items() if key in ("units", "acceptance", "cucumber", "mutations", "detected")} for module, values in evidence[name].items()}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=("bootstrap", "complete"), required=True)
    parser.add_argument("--log-dir", type=Path, default=ROOT / ".verification/build-modes")
    args = parser.parse_args()
    selectedProfilesHaveExactExecutionFlags()
    if args.phase == "complete":
        complete_modes(args.log_dir.resolve())
