#!/usr/bin/env python3
"""Prove selected negative gates in disposable copies; never edit the real tree."""
import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import shutil
import shlex
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
IGNORED = {".git", ".worktrees", ".superpowers", "target", ".verification", "__pycache__"}
PRODUCTION = ("reconciliation-api", "reconciliation-worker", "scenario-generator")

def source_manifest(root):
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in root.rglob("*") if path.is_file() and not any(part in IGNORED for part in path.relative_to(root).parts)}

@dataclass(frozen=True)
class GateResult:
    exit_code: int
    output: str

def build(root, arguments, log, environment=None):
    command = ["bash", str(root / "mvnw"), *arguments, "-B", "-ntp", "-Dstyle.color=never"]
    print("RUN " + shlex.join(command), flush=True)
    start = time.monotonic()
    with log.open("w") as output:
        result = subprocess.run(command, cwd=root, env=environment, stdout=output, stderr=subprocess.STDOUT, timeout=1800)
    for module in (*PRODUCTION, "acceptance-tests"):
        for relative in ("surefire-reports", "failsafe-reports", "site/jacoco", "pit-reports"):
            source = root / module / "target" / relative
            destination = log.parent / log.stem / module / relative
            # These report subtrees belong to this runner; absent reports must
            # also remove the previous snapshot under the same run identity.
            if destination.exists():
                shutil.rmtree(destination)
            if source.exists():
                shutil.copytree(source, destination)
    print(f"EXIT {result.returncode} ({time.monotonic() - start:.1f}s) log={log}", flush=True)
    return GateResult(result.returncode, log.read_text())

def selectedGatesFailForDeliberateViolations(log_dir, groups=None):
    log_dir = log_dir.resolve()
    root = ROOT.resolve()
    if log_dir == root or root.is_relative_to(log_dir):
        raise ValueError("log directory must not be the repository or its ancestor")
    if log_dir.is_relative_to(root) and not any(part in IGNORED for part in log_dir.relative_to(root).parts):
        raise ValueError("in-repository log directory must be beneath an excluded directory such as .verification")
    groups = frozenset(groups or ('acceptance', 'coverage', 'mutation', 'discovery', 'docker'))
    log_dir.mkdir(parents=True, exist_ok=True)
    original = source_manifest(ROOT)
    evidence = {}
    temporary_paths = []
    try:
        with tempfile.TemporaryDirectory(prefix="reconciliation-gates-") as directory:
            workspace = Path(directory)
            def copy(name):
                destination = workspace / name
                shutil.copytree(ROOT, destination, ignore=lambda _path, names: [name for name in names if name in IGNORED])
                temporary_paths.append(destination)
                return destination
            def record(name, result, reason):
                assert result.exit_code != 0, name + " silently passed"
                assert reason in result.output, name + " failed for wrong reason; see logs"
                evidence[name] = {"exit_code": result.exit_code, "reason": reason}
                (log_dir / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
                print("PASS negative " + name + ": " + reason, flush=True)

            if "acceptance" in groups:
                # A genuine Cucumber assertion failure through the selected Failsafe verify goal.
                acceptance_copy = copy("acceptance-failure")
                fixture = acceptance_copy / "acceptance-tests/src/test/resources/fixtures/failing-environment.feature"
                shutil.copy2(fixture, acceptance_copy / "acceptance-tests/src/test/resources/features/task12-failure.feature")
                # Add assertions after the real teardown in the disposable test copy.
                # This avoids depending on ordering between JUnit engines.
                steps = acceptance_copy / "acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/steps/EnvironmentSteps.java"
                original_close = "if (suiteEnvironment != null) suiteEnvironment.close();"
                assert steps.read_text().count(original_close) == 1
                helper = acceptance_copy / "acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/support/BuildCleanupProbe.java"
                helper.write_text("""
    package org.octavio.paymentreconciliationsim.acceptance.support;
    public final class BuildCleanupProbe {
        public static void assertStopped(LocalEnvironment environment) {
            try {
                LocalEnvironmentIT.assertStopped(environment, environment.apiBaseUri(), environment.s3Endpoint());
            } catch (Exception exception) {
                throw new AssertionError("Owned-resource cleanup probe failed", exception);
            }
            System.out.println("TASK12_OWNED_RESOURCES_STOPPED");
        }
    }
    """)
                steps.write_text(steps.read_text().replace(original_close, """
    if (suiteEnvironment != null) {
        suiteEnvironment.close();
        org.octavio.paymentreconciliationsim.acceptance.support.BuildCleanupProbe.assertStopped(suiteEnvironment);
    }
    """))
                for mode in ("acceptance-tests", "default", "all-tests"):
                    arguments = ["clean", "verify", "-Dit.test=AcceptanceIT",
                                 "-Dcucumber.filter.name=A failing HTTP assertion still closes every process"]
                    if mode != "default":
                        arguments.append("-P" + mode)
                    acceptance_failure = build(acceptance_copy, arguments, log_dir / ("cucumber-" + mode + ".log"))
                    assert acceptance_failure.exit_code != 0
                    record("cucumber-" + mode, acceptance_failure, "expected: <418> but was: <404>")
                    assert "maven-failsafe-plugin" in acceptance_failure.output and "There are test failures" in acceptance_failure.output
                    cucumber = json.loads((acceptance_copy / "acceptance-tests/target/cucumber-report.json").read_text())
                    assert any(step["result"]["status"] == "failed" for feature in cucumber for scenario in feature["elements"] for step in scenario["steps"])
                    assert "TASK12_OWNED_RESOURCES_STOPPED" in acceptance_failure.output, "owned-resource cleanup did not complete after failing scenario"
                    evidence["cucumber-" + mode]["owned_resources_stopped"] = True
                unchecked_package = build(acceptance_copy, ["clean", "package", "-DskipTests", "-Dmutation.threshold=100"], log_dir / "unchecked-package.log")
                assert unchecked_package.exit_code == 0
                assert not list(acceptance_copy.glob("*/target/*-reports/TEST-*.xml"))
                evidence["unchecked-package"] = {"exit_code": unchecked_package.exit_code}

            if "coverage" in groups:
                # Remove a sole-covering test while keeping other unit tests present.
                coverage_copy = copy("coverage-failure")
                test = coverage_copy / "scenario-generator/src/test/java/org/octavio/paymentreconciliationsim/generator/GeneratorCliTest.java"
                test.unlink()
                for mode in ("default", "all-tests", "unit-tests", "mutation-tests"):
                    arguments = ["clean", "verify", "-pl", "scenario-generator"]
                    if mode != "default":
                        arguments.append("-P" + mode)
                    coverage_failure = build(coverage_copy, arguments, log_dir / ("coverage-" + mode + ".log"))
                    assert coverage_failure.exit_code != 0
                    record("coverage-" + mode, coverage_failure, "Coverage checks have not been met")
                    report = ET.parse(coverage_copy / "scenario-generator/target/site/jacoco/jacoco.xml").getroot()
                    assert any(klass.get("name", "").endswith("/GeneratorCli") and
                               any(int(counter.get("missed")) > 0 for counter in klass.findall("counter") if counter.get("type") in ("LINE", "BRANCH"))
                               for klass in report.findall(".//class"))

            if groups & {"mutation", "discovery", "docker"}:
                mutation_copy = copy("mutation-failure")
            if "mutation" in groups:
                # Existing viable survivors make a 100% probe fail; the real floor stays 80%.
                for mode in ("default", "all-tests", "mutation-tests"):
                    arguments = ["clean", "verify", "-pl", "scenario-generator", "-Dmutation.threshold=100"]
                    if mode != "default":
                        arguments.append("-P" + mode)
                    mutation_failure = build(mutation_copy, arguments, log_dir / ("mutation-" + mode + ".log"))
                    assert mutation_failure.exit_code != 0
                    record("mutation-" + mode, mutation_failure, "Mutation score of")
                    mutations = ET.parse(mutation_copy / "scenario-generator/target/pit-reports/mutations.xml").getroot().findall("mutation")
                    assert any(mutation.get("status") == "SURVIVED" for mutation in mutations), "no viable survivor"
                    assert not any(mutation.get("status") in ("RUN_ERROR", "MEMORY_ERROR", "NO_COVERAGE") for mutation in mutations)

            if "discovery" in groups:
                discovery_copy = copy("discovery-failure")
                shutil.rmtree(discovery_copy / "scenario-generator/src/test")
                no_tests = build(discovery_copy, ["clean", "verify", "-pl", "scenario-generator", "-Punit-tests"], log_dir / "no-unit-tests.log")
                record("no-unit-tests", no_tests, "No tests to run!")
                assert "maven-surefire-plugin" in no_tests.output
                no_coverage = build(mutation_copy, ["clean", "verify", "-pl", "scenario-generator", "-Punit-tests", "-Dtests.unit.skip=true"], log_dir / "no-coverage.log")
                record("no-coverage", no_coverage, "no unit coverage data was generated")
                zero_scenarios = build(mutation_copy, ["clean", "verify", "-Pacceptance-tests", "-Dit.test=AcceptanceIT",
                                                      "-Dcucumber.filter.tags=@task12_no_such_tag"], log_dir / "zero-scenarios.log")
                record("zero-scenarios", zero_scenarios, "No acceptance scenarios executed")

            if "docker" in groups:
                # Settings are scoped to this child process. Never stop or change Docker.
                environment = dict(os.environ, DOCKER_HOST="tcp://127.0.0.1:1",
                                   DOCKER_TLS_VERIFY="1", DOCKER_CERT_PATH=str(workspace / "missing-docker-certificates"))
                unit_without_docker = build(mutation_copy, ["clean", "verify", "-Punit-tests"], log_dir / "unit-with-unusable-docker.log", environment)
                assert unit_without_docker.exit_code == 0, "unit-only build unexpectedly needs Docker"
                assert not list(mutation_copy.glob("*/target/failsafe-reports/TEST-*.xml"))
                evidence["unit-with-unusable-docker"] = {"exit_code": unit_without_docker.exit_code, "acceptance_reports": 0}
                print("PASS unit-only with unusable process-local Docker client", flush=True)
                docker_failure = build(mutation_copy, ["clean", "verify", "-Pacceptance-tests", "-Dit.test=LocalEnvironmentIT#localEnvironmentUsesRealHttpAndReplicaSet"], log_dir / "docker-unavailable.log", environment)
                record("docker-unavailable", docker_failure, "Docker")
                assert "maven-failsafe-plugin" in docker_failure.output
                reports = list((mutation_copy / "acceptance-tests/target/failsafe-reports").glob("TEST-*.xml"))
                assert reports, "Docker probe failed before enabled acceptance"
                cases = [case for report in reports for case in ET.parse(report).getroot().findall(".//testcase")]
                assert cases and all(case.find("skipped") is None for case in cases)
                errors = [case.find("error") for case in cases if case.find("error") is not None]
                assert errors, "Docker probe did not fail in acceptance"
                assert any("LocalEnvironment.start" in (error.text or "") and "Docker" in (error.text or "") for error in errors), "failure did not reach Docker/harness startup"
                evidence["docker-unavailable"]["errors"] = [error.get("message") for error in errors]
                evidence["docker-unavailable"]["scope"] = "Unusable process-local TLS Docker client; daemon unchanged. Existing injected guard proves required-Docker message."

    finally:
        assert source_manifest(ROOT) == original, "negative probe modified the real working tree"
        assert all(not path.exists() for path in temporary_paths), "temporary copies were not cleaned"
        evidence["cleanup"] = {"real_sources_unchanged": True, "temporary_copies_removed": True}
        (log_dir / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
    return evidence

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--log-dir", type=Path, default=ROOT / ".verification/quality-gates")
    parser.add_argument("--group", action="append", choices=("acceptance", "coverage", "mutation", "discovery", "docker"),
                        help="Run only selected probe groups; repeat for multiple groups (default: all)")
    args = parser.parse_args()
    selectedGatesFailForDeliberateViolations(args.log_dir.resolve(), args.group)
