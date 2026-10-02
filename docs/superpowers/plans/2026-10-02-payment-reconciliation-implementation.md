# Payment Reconciliation Simulator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved one-week reconciliation MVP with a repeatable local Cucumber demonstration and a reproducible AWS deployment.

**Architecture:** Spring Boot owns purchases, date closure, MongoDB and report publication. A separately packaged Java Lambda reads a versioned settlement CSV, retrieves closed-day inputs through HTTP and submits its calculated report. An isolated generator uses the public API/upload contract, and an acceptance module executes the real applications with local AWS substitutes.

**Tech Stack:** Java 21, existing Spring Boot 4.1.1 baseline, Maven wrapper, Spring Data MongoDB, AWS SDK v2, Apache Commons CSV, Cucumber/JUnit Platform, Testcontainers, WireMock, JaCoCo, PIT and Terraform.

**Spec:** [Approved MVP specification](../specs/2026-10-01-payment-reconciliation-design.md), version 0.2; REQ-01 through REQ-20 and AC-01 through AC-33.

## Global Constraints

- Java 21; keep the starter's Spring Boot version unless dependency verification establishes a specific required change and that change is reviewed.
- ARS is the only currency. Amounts use integer centavos.
- References are case-sensitive strings of 1 to 64 ASCII letters, digits, underscores, or hyphens.
- Initial bounds: 1,000 internal purchases per business date, 2,000 settlement data rows, and a 2 MiB CSV.
- The raw byte size must be at most 2,097,152 bytes and data-row count at most 2,000.
- Upload registration requires a CLOSED date strictly earlier than the current business date, using America/Buenos_Aires.
- Enforce an 8 MiB maximum serialized run document before publication, including evidence.
- Configure a five-minute Lambda timeout and reserved concurrency of one initially.
- Preserve CloudWatch logs for seven days.
- Require zero missed unit-test lines and branches in included handwritten production logic; initial proposed mutation floor is 80% per production module.
- Generator access is limited to public HTTP APIs and returned presigned uploads; no implementation dependency or database access.
- Default `./mvnw clean verify` runs all local tests and gates. Cloud execution is separate, and requires verified Free account benefits and Atlas M0.
- No real card data, money movement, frontend, queue, NAT gateway, load balancer or transaction snapshots.

## Review Focus

These inputs need explicit tests in the named tasks in addition to the canonical demonstration:

1. Integer overflow and JSON decimal coercion: reject unrepresentable/fractional amounts; preserve `Long.MAX_VALUE` exactly — Tasks 2, 4 and 6.
2. Invalid UTF-8, BOM/header changes and URL-encoded S3 keys: reject changed input; decode event keys once without confusing versions — Tasks 5 and 8.
3. A publication succeeds but its HTTP response is lost: retry returns unchanged results and never downgrades COMPLETED — Tasks 6, 8 and 9.
4. An insertion commits while close/retry runs concurrently: closed inputs include committed purchases and count matches storage — Task 4.
5. Process/environment failure during a scenario: clean up resources, fail the build, and avoid a false green from zero tests — Tasks 3 and 12.

## File and interface map

The root POM is an aggregator and inherits dependency management from the existing Spring Boot parent. Module order is API, worker, generator, acceptance; the acceptance module has normal reactor dependencies with test scope.

File-list abbreviations below expand to exact repository paths:

| Prefix | Exact directory |
| --- | --- |
| A | reconciliation-api/src/main/java/org/octavio/paymentreconciliationsim |
| AT | reconciliation-api/src/test/java/org/octavio/paymentreconciliationsim |
| W | reconciliation-worker/src/main/java/org/octavio/paymentreconciliationsim/worker |
| WT | reconciliation-worker/src/test/java/org/octavio/paymentreconciliationsim/worker |
| G | scenario-generator/src/main/java/org/octavio/paymentreconciliationsim/generator |
| GT | scenario-generator/src/test/java/org/octavio/paymentreconciliationsim/generator |
| C | acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance |
| F | acceptance-tests/src/test/resources/features |
| J | acceptance-tests/src/test/resources/fixtures |

Keep the existing purchase controller/model/repository/service classes and package names. New responsibilities use business-date, run, worker and generator types alongside them. The API and worker own their wire DTOs separately; matching JSON schemas, rather than a shared implementation module, define their boundary.

Worker domain records in Task 2: `PurchaseInput(reference, merchantId, amountCentavos)`, `SettlementRow(rowNumber, reference, amountCentavos)`, `Result(reference, outcome, internalAmountCentavos, merchantId, settlementEvidence)`, `Summary(internalPurchaseCount, settlementRowCount, distinctSettlementReferenceCount, totalResultCount, outcomeCounts)`, and `ComparisonReport(summary, results)`. Optional evidence fields serialize as explicit nulls consistently across fixtures/API. Every outcome count is present, including zero.

Run identity consists of `source="SIMULATED"`, `businessDate`, lowercase SHA-256 hex and `rulesVersion="v1"`. Convert SHA-256 bytes to Base64 for S3's checksum header. UUID run IDs and `settlements/{businessDate}/{runId}.csv` keys are server generated.

Additional wire types use these fields: `ObjectIdentity(bucket,key,versionId,sha256)`, `ObjectReference(runId,bucket,key,versionId)`, `ManualInvocation(runId,bucket,key,versionId)`, `AttemptContext(attemptId,startedAt)`, `RegisterRun(businessDate,sha256,byteLength)`, `ReportSubmission(inputIdentity,summary,results)`, and `FailureSubmission(code,retriable,attemptId)`. Metadata supplies runId/source/date/hash/rules/status/objectIdentity; input adds the complete purchase list. API-created/replayed responses use the spec's 201/200 status distinction.

Use task-scoped commits after verification, with explicit paths and no blanket `git add .` or `git commit -a`. Do not include unrelated user changes. Any move involving existing user work must preserve its content and be reviewed as part of that task.

## Task 1: Establish the reactor and selectable test execution

**Requirements:** REQ-17, REQ-20; AC-25, AC-31, AC-33.

**Files:** Modify `pom.xml`, `.gitignore`; create `reconciliation-api/pom.xml`, `reconciliation-worker/pom.xml`, `scenario-generator/pom.xml`, `acceptance-tests/pom.xml`, `scripts/verify-build-modes.py`. Move current application, resources and purchase classes to matching paths under `reconciliation-api`; move process-dependent starter test scaffolding into the acceptance module.

**Interfaces:** Parent properties `tests.unit.skip`, `tests.acceptance.skip`, `quality.skip`, `mutation.skip`, `mutation.threshold=80`; profiles `all-tests`, `unit-tests`, `acceptance-tests`, `mutation-tests`. The script accepts `--phase bootstrap|complete`, and exits nonzero if selected effective POM behavior is wrong. Its `effective_mode(profile: str | None, skip: dict[str, bool] = {}) -> Mode` reads effective plugin settings and returns the unit/acceptance/coverage/mutation flags asserted below.

- [ ] Verify a Java 21 JDK and Maven wrapper execution with `java -version` and `./mvnw -version`. Verify Docker connectivity separately with `docker info`. Resolve runtime configuration before attempting the build; do not silently change project versions.
- [ ] Write mode assertions first: default enables unit/acceptance/mutation; unit profile disables acceptance/mutation; acceptance profile disables unit/quality/mutation; mutation profile enables unit/quality/mutation only. Assert both standard skip properties suppress every applicable plugin and simultaneous mode profiles fail.
- [ ] Run `python3 scripts/verify-build-modes.py --phase bootstrap`; expect failure against the current single-module configuration.
- [ ] Implement the reactor and profile validation. Keep production dependencies module-local. API retains a normal JAR for test classpaths plus a Spring Boot executable artifact; worker uses a shaded deployable artifact; generator produces an executable JAR.
- [ ] Resolve compatible stable Cucumber, WireMock, JaCoCo, PIT/JUnit plugin, AWS SDK v2 and Commons CSV versions once; pin them in the parent. Keep Spring-managed JUnit/Testcontainers versions aligned. Add springdoc 3.x during Task 11. Verify actual discovery and mutation execution before calling the dependency combination compatible.
- [ ] Run bootstrap mode assertions and `./mvnw clean package -Dmaven.test.skip=true`; expect four module artifacts/configurations and success without Docker. Empty production scaffolds have no logic to mutate; as logic arrives, enable gates for it rather than exempting the whole module.
- [ ] Commit only the reactor/migration changes reviewed for this task.

**Named verification:** `selectedProfilesHaveExactExecutionFlags`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```python
assert effective_mode("unit-tests") == Mode(unit=True, acceptance=False, coverage=True, mutation=False)
assert effective_mode("acceptance-tests") == Mode(unit=False, acceptance=True, coverage=False, mutation=False)
```

## Task 2: Implement pure comparison logic and unit gates

**Requirements:** REQ-05, REQ-06, REQ-19; AC-10, AC-11, AC-29, AC-30.

**Files:** Create `W/domain/ReconciliationModel.java` for the records/enums above, `W/domain/ReconciliationComparator.java`, `WT/domain/ReconciliationComparatorTest.java`; configure JaCoCo/PIT in the parent and worker POM.

**Interfaces:** `ComparisonReport compare(List<PurchaseInput> purchases, List<SettlementRow> settlement)`; sorted results by case-sensitive reference, evidence by rowNumber. Return immutable collections; compare amounts without subtraction or summing money.

- [ ] Write parameterized tests for the five canonical references. Assert `internalPurchaseCount=4`, `settlementRowCount=5`, `distinctSettlementReferenceCount=4`, `totalResultCount=5`, and each outcome count equals one.
- [ ] Add tests for empty sides, unknown duplicated references, unequal duplicate amounts, input ordering, preserved row evidence, and equal/different amounts at `Long.MAX_VALUE`.
- [ ] Run `./mvnw -pl reconciliation-worker -am test -Punit-tests`; expect missing comparator/failed assertions.
- [ ] Implement grouping by reference and union evaluation with DUPLICATE precedence.
- [ ] Add per-class JaCoCo zero-missed-line/branch checks for logic, isolated unit coverage data, PIT unit targets and explicit boilerplate exclusions. Use PIT DEFAULTS operators initially; inspect all survivors and require score >=80.
- [ ] Run `./mvnw -pl reconciliation-worker -am clean verify -Pmutation-tests`; expect passing unit tests, 100% logic coverage and mutation score >=80. Temporarily remove a sole-covering test to prove the gate fails, then restore it before committing.
- [ ] Commit comparator and verified gate configuration.

**Named verification:** `canonicalScenarioHasFiveOutcomes`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```java
assertEquals(4, report.summary().internalPurchaseCount());
assertEquals(5, report.summary().totalResultCount());
assertEquals(1, report.summary().outcomeCounts().get(Outcome.DUPLICATE));
```

## Task 3: Create the local Cucumber environment

**Requirements:** REQ-18, REQ-20; AC-27, AC-31, AC-32, AC-33.

**Files:** Create `C/AcceptanceIT.java`, `C/support/LocalEnvironment.java`, `C/support/ScenarioWorld.java`, `C/support/S3Stub.java`, `C/steps/EnvironmentSteps.java`, `F/environment.feature`, `acceptance-tests/src/test/resources/junit-platform.properties`.

**Interfaces:** `LocalEnvironment.start()`, `close()`, `apiBaseUri()`, `s3Endpoint()`; `S3Stub.store(bucket,key,bytes)->versionId`, `get(bucket,key,versionId)`. ScenarioWorld owns HTTP responses/runId/fixtures and is scenario-scoped. Mongo lifecycle is suite-scoped with isolated database state per scenario.

- [ ] Write a feature proving the real HTTP server responds 404 to an authenticated request for an unknown path, and a supporting integration test proving a Mongo transaction can commit/roll back against the container.
- [ ] Run `./mvnw -pl acceptance-tests -am verify -Pacceptance-tests`; expect unavailable steps/environment.
- [ ] Start a pinned compatible Mongo image as a single-node replica set, WireMock on an ephemeral port and Spring on an ephemeral port. Use fixed `2026-10-02T15:00:00Z` time, local dummy credentials and endpoint overrides. Use Cucumber PicoContainer for scenario state and manual application lifecycle to avoid coupling step DI to production services.
- [ ] Implement byte/version-aware PUT, HEAD and GET S3 substitutes. Preserve binary bytes and return version/checksum headers expected by SDK clients. Automatic test event delivery is attached in Task 8; no substitute claims to enforce real SigV4 authorization.
- [ ] Verify success, deliberate scenario failure and startup failure all stop the Spring context/stubs/container. Fail clearly when Docker is unavailable; fail Failsafe on zero discovered acceptance tests.
- [ ] Run acceptance-only verification and worker unit-only verification (`./mvnw -pl reconciliation-worker -am verify -Punit-tests`); expect the latter never to start Docker. Commit the reusable harness.

**Named verification:** `localEnvironmentUsesRealHttpAndReplicaSet`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```java
assertEquals(404, unknownPathResponse.statusCode());
assertNotNull(mongoHelloResponse.getString("setName"));
```

## Task 4: Implement purchases, closed dates and Mongo constraints

**Requirements:** REQ-01 through REQ-03, REQ-10; AC-01 through AC-06, AC-22.

**Files:** Implement `A/controller/PurchaseController.java`, `A/model/Purchase.java`, `A/repository/PurchaseRepository.java`, `A/service/PurchaseService.java`; create `A/businessdate/BusinessDateController.java`, `BusinessDateService.java`, `BusinessDay.java` in that directory, `A/config/MongoConfiguration.java`, `A/config/MongoSchemaInitializer.java`, `A/config/ClockConfiguration.java`, `AT/service/PurchaseServiceTest.java`, `AT/businessdate/BusinessDateServiceTest.java`, `C/steps/PurchaseSteps.java`, `C/PurchaseConcurrencyIT.java`, `F/purchases.feature`, `J/canonical-purchases.json`.

**Interfaces:** `PurchaseService.create(CreatePurchase request)->CreationResult<Purchase>`; `BusinessDateService.create(LocalDate)->BusinessDay`, `close(LocalDate)->BusinessDay`, `closedInputs(LocalDate)->List<Purchase>`. CreationResult carries value and created/replayed flag.

- [ ] Write unit and HTTP scenarios asserting creation 201, exact replay 200, conflicting replay 409, global reference uniqueness across dates, repeated close 200, closed additions 409 and complete stable inputs. Reject raw card fields, decimal/exponent JSON amounts that imply coercion, nonpositive/overflow amounts, bad currency/reference and invalid dates. Preserve the exact value `9223372036854775807`.
- [ ] Write concurrent create/close and cap tests using barriers: successful insertions equal stored count; closed inputs contain every committed purchase; no scenario admits purchase 1,001. Exact replay of a prior purchase remains harmless after closure.
- [ ] Run unit and acceptance profiles; expect failing contract assertions.
- [ ] Implement explicitly created OPEN dates, strict integer JSON decoding, schema validation, unique indexes and BSON integer storage. Coordinate guard increment plus insert with MongoTransactionManager/TransactionTemplate; close updates the same guard. Retry transient write conflicts at most three attempts, using injected delay behavior in unit tests.
- [ ] Install run indexes/schema incrementally in Task 6. Keep Mongo validators and Java rules consistent. Verify desired business-date index via explain in the integration suite.
- [ ] Run `./mvnw -pl reconciliation-api -am clean verify -Punit-tests` and `./mvnw clean verify -Pacceptance-tests`, then API mutation verification; expect exact contract behavior and all current logic gates passing.
- [ ] Commit purchase/date behavior and tests.

**Named verification:** `fractionalAmountIsRejectedAndClosedDateIsImmutable`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```java
assertEquals(400, fractionalAmountResponse.statusCode());
assertEquals(409, closedDateInsertionResponse.statusCode());
assertEquals(successfulInsertions, closedInputs.size());
```

## Task 5: Parse and validate bounded settlement bytes

**Requirements:** REQ-04, REQ-19; AC-07 through AC-09, AC-29, AC-30.

**Files:** Create `W/csv/SettlementCsvParser.java`, `W/csv/SettlementValidationException.java`, `WT/csv/SettlementCsvParserTest.java`, `J/canonical-settlement.json`, `J/header-only.csv`, `J/invalid-header.csv`, `J/invalid-utf8.csv`.

**Interfaces:** `List<SettlementRow> parse(InputStream bytes, LocalDate expectedDate)`; validation exception exposes stable code and logical record number. Parser limits bytes and rows while reading and does not close an externally owned stream.

- [ ] Write tests for exact header/order, UTF-8 with malformed-byte reporting, mandatory header, header-only input, LF/CRLF and quoted fields. Reject UTF-8 BOM changing the header, extra fields, mixed dates, whitespace-altered references/amounts, bad currency, overflow and invalid quoting.
- [ ] Generate valid boundary inputs: 2,000 rows pass/2,001 fail; a quoted/padded valid representation reaches exactly 2,097,152 bytes, while one additional valid padding byte fails. Assert record numbers count logical records, including malformed quoted multiline evidence.
- [ ] Run worker unit tests; expect missing parser/failing validations.
- [ ] Implement Commons CSV parsing with a strict UTF-8 decoder and bounded counting stream; do not use split-on-comma. Preserve duplicates as separate SettlementRow records.
- [ ] Run worker mutation verification; expect full parser logic coverage, score >=80, no partially returned report on invalid input.
- [ ] Commit parser and fixtures.

**Named verification:** `settlementLimitsAreInclusive`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```java
assertEquals(2000, parsedAtRowLimit.size());
assertEquals("TOO_MANY_ROWS", rowOverflow.code());
assertEquals("FILE_TOO_LARGE", byteOverflow.code());
```

## Task 6: Persist runs and publish reports atomically

**Requirements:** REQ-06 through REQ-11; AC-12, AC-14 through AC-17, AC-22.

**Files:** Create `A/run/ReconciliationRun.java`, `RunRepository.java`, `RunService.java`, `ReportValidator.java`, `ReportCanonicalizer.java`, `RunController.java`, `WorkerController.java` in that directory; `AT/run/RunServiceTest.java`, `AT/run/ReportValidatorTest.java`, `AT/run/ReportCanonicalizerTest.java`, `C/steps/RunSteps.java`, `C/RunPublicationIT.java`, `F/run-publication.feature`, `J/canonical-report.json`, `J/canonical-expected.json`.

**Interfaces:** RunService `register(RegisterRun)->RegistrationResult`, `metadata(UUID)->RunMetadata`, `input(UUID)->RunInput`, `markProcessing(UUID,ObjectIdentity)->void`, `publish(UUID,ReportSubmission)->PublicationResult`, `fail(UUID,FailureSubmission)->void`, `results(UUID,Outcome?,int page,int size)->ResultPage`. API wire DTOs mirror Task 2 business fields without importing worker types.

- [ ] Write prepared-report scenarios: date must be CLOSED and past; same logical registration returns same run; different checksum creates another; results before completion return 409; canonical report publishes exact summary/results.
- [ ] Test duplicate result references, missing evidence, invalid outcome structure, overflow/noninteger money, inconsistent summaries and an oversized serialized document. Reject entire submission without partial updates.
- [ ] Test simultaneous identical completion, conflicting completion, reordered equivalent replay, response-lost replay and late PROCESSING/FAILED callbacks. Assert one report, canonical evidence and no downgrade.
- [ ] Run API unit and acceptance suites; expect missing run contracts.
- [ ] Implement four statuses, immutable logical identity/unique index and bounded embedded results. Canonicalize business output for equality; atomically compare/update publication and object binding. Check final BSON-serialized document <=8 MiB before persistence. Implement deterministic aggregation-based filters/pagination, capped at 100; validate negative/invalid paging inputs.
- [ ] Implement GET metadata/input, PUT processing/results and POST failure under the internal run route. Input includes all purchases for the closed date. Task 7 supplies upload URLs; Task 11 supplies token enforcement.
- [ ] Run current local suites and API mutation checks; expect summary evidence/counts unchanged after replays and correct explain output.
- [ ] Commit run storage/HTTP contracts.

**Named verification:** `publicationReplayCannotDowngradeCompletedRun`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```java
assertEquals("COMPLETED", finalStatus);
assertEquals(firstPublishedReport, replayedReport);
assertEquals(409, conflictingReportResponse.statusCode());
```

## Task 7: Implement presigned upload instructions and immutable version binding

**Requirements:** REQ-07, REQ-14; local AC-12, AC-13, AC-17.

**Files:** Create `A/storage/SettlementStorage.java`, `A/storage/S3SettlementStorage.java`, `A/config/AwsConfiguration.java`, `AT/storage/S3SettlementStorageTest.java`, `F/upload-registration.feature`; extend RunService and S3Stub.

**Interfaces:** `UploadInstructions presign(UUID runId, LocalDate date, String sha256, long bytes)`; instructions contain URL, key, required checksum headers and expiresAt. `Optional<ObjectIdentity> recoverUploadedVersion(RunMetadata run)` identifies retained matching bytes when an event was lost.

- [ ] Write tests asserting registration persists before presigning, URL lifetime <=600 seconds, SHA-256/Base64 header exactness, declared length limits and no new upload URL for a completed run.
- [ ] Write local scenarios for multiple object versions: first validated version binds atomically; processing always reads that version; mismatched bytes cannot bind or replace input.
- [ ] Run targeted tests; expect missing storage implementation.
- [ ] Implement SDK v2 S3 presigner/client with region, endpoint override and path-style settings for local tests. Use roles/default credentials in cloud, dummy credentials locally, no persisted credentials or logged URLs.
- [ ] Implement recovery HEAD/list-version/download verification with bounded size; return upload-needed conflict when no matching retained input exists. Do not use ETag as SHA-256.
- [ ] Run current local verification. Reserve real signature expiry/authorization evidence for Task 14.
- [ ] Commit upload contracts/storage.

**Named verification:** `uploadChecksumAndBoundVersionAreStable`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```java
assertEquals(expectedBase64Sha256, instructions.requiredHeaders().get("x-amz-checksum-sha256"));
assertEquals(firstValidatedVersionId, storedRun.objectIdentity().versionId());
```

## Task 8: Execute the real Lambda handler through the full local flow

**Requirements:** REQ-03 through REQ-09, REQ-11 through REQ-13, REQ-18; AC-07 through AC-11, AC-17 through AC-19, AC-26, AC-28.

**Files:** Create `W/ReconciliationHandler.java`, `W/ProcessingService.java`, `W/event/InvocationParser.java`, `W/storage/S3SettlementReader.java`, `W/http/WorkerApiClient.java`, `W/config/WorkerConfiguration.java`, corresponding tests under WT; `C/steps/ReconciliationSteps.java`, `F/reconciliation.feature`, `F/invalid-settlement.feature`. Extend LocalEnvironment to invoke the handler after a local upload.

**Interfaces:** `ReconciliationHandler implements RequestHandler<Map<String,Object>,Void>`; no-arg constructor builds cloud dependencies, injected constructor accepts ProcessingService. `ProcessingService.process(ObjectReference, AttemptContext)->void`. InvocationParser supports real S3 Records events and manual `{runId,bucket,key,versionId}` invocation; record entries are processed without silently dropping others.

- [ ] Write real-handler tests for canonical processing: GET metadata, HEAD/GET exact version, checksum verification, processing binding, GET complete input, parser/comparator and PUT full report. Assert the real API returns the independently committed canonical JSON.
- [ ] Test URL-encoded object keys (including encoded plus), malformed/unregistered keys, missing version IDs, multiple records, actual bytes/HEAD size disagreement, truncated stream and wrong checksum. Reject unsupported input safely with correlated diagnostics.
- [ ] Run worker unit and Cucumber suites; expect unavailable handler/flow.
- [ ] Implement orchestration without Spring context or Mongo credentials. Configure HTTP connect timeout 3 seconds/read timeout 20 seconds; throw for transient infrastructure failure so cloud retries apply. Deterministic CSV errors POST failure with retriable=false and end normally; callback failure remains an invocation failure.
- [ ] Connect the harness upload listener to the same handler, on a controlled executor so HTTP upload completion and generator polling cannot deadlock. Preserve version/checksum identity. Add complete-flow variants for all-matched, empty internal date, header-only, duplicate unknown and unequal duplicate amounts.
- [ ] Run local suites and worker mutation verification; assert end-to-end counts/outcomes, no partial publication, required evidence and repeated-event idempotency.
- [ ] Verify shaded handler artifact has its entry point/dependencies and no Spring web runtime.
- [ ] Commit handler/complete local reconciliation.

**Named verification:** `realHandlerPublishesCanonicalFixture`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```java
assertEquals(200, resultsResponse.statusCode());
assertEquals(expectedCanonicalBusinessJson, actualCanonicalBusinessJson);
assertEquals(5, actualSummary.totalResultCount());
```

## Task 9: Add recovery, stale status and correlated failures

**Requirements:** REQ-11 through REQ-13; AC-17 through AC-19.

**Files:** Create `A/run/RecoveryService.java`, `A/worker/LambdaInvoker.java`, `A/worker/AwsLambdaInvoker.java`, `AT/run/RecoveryServiceTest.java`, `F/recovery.feature`; extend RunController/RunService/WorkerApiClient and S3Stub.

**Interfaces:** `RecoveryService.reprocess(UUID)->ReprocessResult`; `LambdaInvoker.invoke(ManualInvocation)->void`. API accepts recovery asynchronously (202); completed runs return unchanged (200). Status views expose recoveryNeeded if incomplete and last activity is older than 20 minutes.

- [ ] Test transient API/S3 outage, failure callback outage, response lost after successful publication, no activity at exactly/beyond 20 minutes and completed replay. Assert staleness does not claim AWS retries exhausted.
- [ ] Run targeted unit and recovery features; expect missing recovery route/state logic.
- [ ] Implement bounded invocation acceptance, exact original identity reuse and recovery of an unbound retained upload through Task 7. A local LambdaInvoker queues the real handler; production uses SDK asynchronous Invoke from EC2.
- [ ] Emit structured diagnostics with run/date/version/rules/attempt/duration/error; never emit credentials, presigned URLs or full sensitive requests. No worker-side endless retry loop.
- [ ] Run local retry/recovery acceptance and mutation profiles; expect one published report despite replay/failure ordering.
- [ ] Commit recovery behavior.

**Named verification:** `lostCompletionResponseIsRecoverableWithoutDuplicateReport`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```java
assertEquals("COMPLETED", recoveredRun.status());
assertEquals(1, storedReportCount);
assertFalse(recoveredRun.recoveryNeeded());
```

## Task 10: Deliver the isolated scenario generator

**Requirements:** REQ-16, REQ-17, REQ-19; AC-24, AC-25, AC-29, AC-30.

**Files:** Create `G/ScenarioGeneratorMain.java`, `G/ScenarioFactory.java`, `G/SettlementWriter.java`, `G/SimulatorClient.java`, `G/ResultVerifier.java`, `G/GenerationWorkflow.java`, matching GT tests; `scenario-generator/src/main/resources/scenarios/canonical-expected.json`, `F/generator.feature`, `C/steps/GeneratorSteps.java`.

**Interfaces:** CLI arguments `--base-url`, `--business-date`, `--seed`, `--scenario canonical|all-matched`, optional `--expected`; token from environment. `ScenarioFactory.generate(long seed,LocalDate date,ScenarioKind)->Scenario`; `GenerationWorkflow.run(Scenario,ExpectedReport)->VerifiedRun`. ResultVerifier compares canonical business output only.

- [ ] Write tests that the same seed/date gives the same scenario/CSV/checksum, every canonical outcome has its intended evidence, pagination retrieves every result, failed/pending runs are handled correctly and expected mismatches exit nonzero.
- [ ] Test polling deadline/interrupts with injected Clock/delay, invalid arguments and API conflicts. Default maximum polling window is five minutes; failure reports runId for recovery.
- [ ] Run generator unit tests; expect missing workflow/CLI.
- [ ] Implement independent Java HTTP client DTOs, public-only calls, direct presigned PUT with supplied headers and deterministic CSV writing. Do not reuse worker comparator to manufacture expectations. The canonical scenario uses the spec's exact five reference names and its committed expected fixture. Generated all-matched scenarios namespace references by seed/date; verify them against independently constructed expected amounts. Canonical reruns use an isolated/reset local dataset or reuse the same closed cloud date/file; changing date alone cannot bypass global reference uniqueness.
- [ ] Invoke the actual CLI entry point in the local generator feature with fake S3 upload/real handler delivery. Assert successful verified report and no private endpoint calls/direct Mongo access.
- [ ] Run generator unit/mutation gates and the generator acceptance feature. Verify dependency tree contains no API/worker artifact.
- [ ] Commit the generator.

**Named verification:** `generatorEntryPointVerifiesResultsThroughPublicApis`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```java
assertEquals(0, generatorExitCode);
assertEquals(0, privateWorkerRequestsFromGenerator);
assertEquals(expectedBusinessResults, retrievedBusinessResults);
```

## Task 11: Finish Swagger, access control and HTTP errors

**Requirements:** REQ-08, REQ-09, REQ-14; AC-14, AC-15.

**Files:** Create `A/security/DemoTokenFilter.java`, `A/http/ApiExceptionHandler.java`, `A/http/ApiError.java`, `A/config/OpenApiConfiguration.java`, matching AT tests; `F/api-access.feature`, `F/api-errors.feature`; update API POM.

**Interfaces:** `Authorization: Bearer <token>` with distinct demo/worker tokens; public Swagger includes admin bearer security and public /api/v1 schemas only. ApiError contains code/message/correlationId/optional fieldErrors.

- [ ] Write tests for missing/wrong tokens, demo token rejected for worker operations, unsupported content type 415, validation 400, unknown resource 404, conflict 409 and oversized payload 413. Assert stable sanitized error bodies and correlation IDs.
- [ ] Test that /v3/api-docs contains every public route/status/schema but no /internal route. Swagger assets may load through the SSH tunnel; protected API operations require the proper token.
- [ ] Run tests; expect absent access checks/docs.
- [ ] Implement narrow route-based token filter and constant-time comparison, configured nonempty distinct tokens, centralized error handling and springdoc 3.x integration. Explicitly cap report request bodies and pagination, without exposing stack traces.
- [ ] Run all local verification; expect unchanged reconciliation behavior with authenticated clients and full new logic gates.
- [ ] Commit API presentation/access behavior.

**Named verification:** `demoTokenCannotAuthorizeWorkerWrites`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```java
assertEquals(403, workerWriteWithDemoToken.statusCode());
assertFalse(openApiJson.contains("/internal/v1/"));
```

## Task 12: Prove the build gates and acceptance matrix

**Requirements:** REQ-18 through REQ-20; AC-01 through AC-19, AC-22, AC-24 through AC-33.

**Files:** Complete `scripts/verify-build-modes.py`; create `scripts/check-quality-gates.py`, `docs/testing.md`; complete feature/fixture gaps from Tasks 3–11.

**Interfaces:** build-mode script `--phase complete` runs the seven specified commands, examines exit codes/reports and checks forbidden executions. Gate script creates temporary isolated copies for deliberately failing tests/coverage/mutation; no mutation of the real working tree.

- [ ] Map every local AC to a feature or supporting integration test and every production logic class to unit coverage. Add missing executable assertions before changing production behavior.
- [ ] Run the complete mode script; expect failures until profile/gate wiring is fully correct. Check zero-test/no-coverage detection, both skip properties with package and verify, and conflicting profiles.
- [ ] Run deliberately failing Cucumber, remove a sole-covering unit test and use a viable survivor case/high threshold in temporary copies. Assert corresponding builds fail nonzero, and cleanup occurs even on failure.
- [ ] Verify unit/acceptance coverage files are separate and PIT sees only unit tests. Audit exclusions, generated reports and surviving mutants in all three production modules. Document any justified mutation threshold exception precisely.
- [ ] Run `./mvnw clean verify`; expect all suites/gates green. Record repeatable commands and report locations in testing documentation, without teaching generic SDD.
- [ ] Commit verification tooling/test evidence documentation.

**Named verification:** `selectedGatesFailForDeliberateViolations`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```python
assert acceptance_failure.exit_code != 0
assert coverage_failure.exit_code != 0
assert mutation_failure.exit_code != 0
assert unchecked_package.exit_code == 0
```

## Task 13: Define the AWS deployment and local-to-cloud configuration

**Requirements:** REQ-07, REQ-12 through REQ-15; AC-13, AC-20, AC-21, AC-23.

**Files:** Create `infra/versions.tf`, `providers.tf`, `variables.tf`, `network.tf`, `storage.tf`, `compute.tf`, `iam.tf`, `outputs.tf`, `terraform.tfvars.example` under infra; `infra/templates/api.service.tftpl`, `scripts/deploy-api.sh`, `scripts/cloud-smoke.py`, `scripts/teardown.sh`, `docs/deployment.md`.

**Interfaces:** Terraform consumes region, eligible EC2 instance type, SSH public key/developer CIDR and built worker artifact path; outputs SSH host, private API address, bucket and function name. Tokens/Mongo URI are uploaded separately to a mode-0600 runtime environment file and never enter Terraform variables/state.

- [ ] Establish Free-plan/available-credit/resource eligibility and Atlas M0 before any apply. Verify account concurrency allowance can support reserved concurrency=1; resolve a quota limitation within the approved free plan or report the specific blocker before provisioning.
- [ ] Write cloud-smoke assertions for direct S3 notification, exact bound version/checksum, private API/S3 access, one report after replay, CloudWatch correlation and public/worker access restrictions.
- [ ] Implement one VPC/public EC2/private Lambda subnet, gateway endpoint, security groups, IAM roles, encrypted storage and versioned private bucket. Configure ObjectCreated prefix settlements/ suffix .csv and Lambda invoke permission before bucket notification. No NAT/load balancer/paid interface endpoint.
- [ ] Use Java 21 Lambda runtime, x86_64 packaging, initial 512 MiB memory, 300-second timeout, concurrency=1, seven-day logs and private API URL. Set finite asynchronous invocation age/retry configuration; retain manual recovery.
- [ ] Configure EC2 systemd Java launch and SSH tunnel; deploy API artifact separately, inject runtime secrets without displaying them, set Atlas TLS/current-IP allowlist. Do not add an S3 gateway-only bucket policy blocking external presigned uploads.
- [ ] Run `terraform -chdir=infra fmt -check`, `init -backend=false`, `validate`, then review `plan` for only intended eligible resources. No cloud smoke script creates a Paid-plan upgrade.
- [ ] Commit infrastructure/configuration documentation.

**Named verification:** `terraformPlanHasNoUnapprovedChargeableNetworkComponents`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```python
assert not planned_types.intersection({"aws_nat_gateway", "aws_lb"})
assert all(e["vpc_endpoint_type"] == "Gateway" for e in planned_vpc_endpoints)
assert lambda_configuration["timeout"] == 300
```

## Task 14: Validate the cloud demonstration and processing limits

**Requirements:** REQ-13 through REQ-16; cloud portions of AC-13, AC-20, AC-21, AC-23, AC-24.

**Files:** Extend `scripts/cloud-smoke.py`; create `docs/demo-evidence.md`; update deployment documentation with measured commands/results, never credentials.

**Interfaces:** Cloud smoke CLI: `python3 scripts/cloud-smoke.py --base-url URI --business-date YYYY-MM-DD --benchmark --output PATH`; credentials/tokens come from the local AWS profile/environment. Output is a sanitized JSON evidence report with run identity, timings, checks and teardown inventory.

- [ ] Build with default full verification, deploy the reviewed Terraform stack/API, establish the SSH tunnel and run the generator against the cloud endpoint.
- [ ] Verify original S3 upload directly triggers the actual Lambda, private worker calls succeed, and Swagger exposes the canonical five outcomes. Test checksum/signature expiry and version reuse against real S3.
- [ ] Generate the maximum supported valid workload; record cold/warm duration, memory and retry observations. Require completion <120 seconds as the target and <300 seconds as the configured limit; investigate any miss instead of claiming success.
- [ ] Exercise a retriable outage and manual recovery against retained input. Verify metadata/log correlation and that no direct Lambda-to-Atlas credentials/access exists.
- [ ] Run complete teardown of instance/storage, bucket object versions/delete markers and logs; record usage/free-benefit visibility and verify no demo resources remain. Local features continue working after teardown.
- [ ] Commit only scripts and sanitized measurement evidence.

**Named verification:** `realS3UploadPublishesReportWithinRuntimeBudget`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```python
assert run["status"] == "COMPLETED"
assert run["summary"]["totalResultCount"] == 5
assert maximum_workload_duration_seconds < 120
```

## Task 15: Complete handoff and final verification

**Requirements:** All requirements/criteria.

**Files:** Update `README.md`, `docs/testing.md`, `docs/deployment.md`, `docs/demo-evidence.md`; amend the spec only for reviewed actual behavior changes.

**Interfaces:** Consumes passing reactor reports and sanitized cloud evidence from Tasks 12–14. Produces linked REQ/AC evidence in the documentation and executable copy-paste commands matching the final CLI/API.

- [ ] Document local prerequisites, actual seven Maven commands, generator usage, Swagger routes, AWS deployment/tunnel/recovery/teardown and measured architecture tradeoffs.
- [ ] Check REQ-01 through REQ-20 and AC-01 through AC-33 have linked passing evidence, including separately recorded cloud/manual checks. Keep pending cloud checks visibly pending if account setup blocks them.
- [ ] Run `./mvnw clean verify`, dependency/boundary checks, Terraform validation and documentation command checks against the final code. Repeat only checks affected by later fixes.
- [ ] Review the complete implementation for spec gaps and risks; resolve findings before final completion. Any independent reviewer is selected according to the execution method agreed with the owner.
- [ ] Commit reviewed task changes with explicit paths; leave unrelated work intact. Report final behavior, passing checks and any material unresolved limitation.

**Named verification:** `finalEvidenceCoversEveryCriterion`. Assertions below name the exact expected values; surrounding setup uses this task's interface/fixtures.

```python
assert requirement_ids == {"REQ-%02d" % i for i in range(1, 21)}
assert criterion_ids == {"AC-%02d" % i for i in range(1, 34)}
assert not pending_required_evidence
```

## One-week sequencing

| Working day | Target |
| --- | --- |
| 1 | Tasks 1–3: working reactor, comparator and local harness |
| 2 | Tasks 4–5: purchase/date invariants and strict CSV parsing |
| 3 | Tasks 6–7: atomic reports and upload/version contracts |
| 4 | Tasks 8–9: complete real-handler flow and recovery |
| 5 | Tasks 10–12: generator, Swagger/security and proven build modes |
| 6 | Tasks 13–14: AWS setup, cloud execution and measurements |
| 7 | Tasks 14–15: fixes, teardown, evidence and handoff |

This is a sequencing target, not a guarantee. Maintain gates and scope throughout; if a tool/account compatibility issue threatens the timebox, present the specific issue and its smallest concrete resolution rather than silently removing requirements.

## Coverage check

| Spec requirements | Owning tasks |
| --- | --- |
| REQ-01, REQ-02, REQ-03 | 4, 6, 8 |
| REQ-04 | 5, 8 |
| REQ-05, REQ-06 | 2, 6, 8 |
| REQ-07 | 6, 7, 8, 13, 14 |
| REQ-08, REQ-09 | 6, 8, 11 |
| REQ-10, REQ-11 | 4, 6, 8, 9 |
| REQ-12, REQ-13 | 8, 9, 13, 14 |
| REQ-14, REQ-15 | 11, 13, 14 |
| REQ-16, REQ-17 | 1, 10 |
| REQ-18 | 3, 8, 10, 12 |
| REQ-19, REQ-20 | 1, 2, 4–12 |

## Acceptance evidence map

| Criterion | Owning tasks | Evidence |
| --- | --- | --- |
| AC-01 | 4 | Local automated |
| AC-02 | 4 | Local automated |
| AC-03 | 4 | Local automated |
| AC-04 | 4 | Local automated |
| AC-05 | 4 | Local automated |
| AC-06 | 4, 6 | Local automated |
| AC-07 | 5, 8 | Local automated |
| AC-08 | 5, 8 | Local automated |
| AC-09 | 5, 8 | Local automated |
| AC-10 | 2, 8 | Local automated |
| AC-11 | 2, 8 | Local automated |
| AC-12 | 6, 7 | Local automated |
| AC-13 | 7, 14 | Local plus cloud |
| AC-14 | 6, 11 | Local automated |
| AC-15 | 11 | Local automated |
| AC-16 | 6 | Local automated |
| AC-17 | 6, 8, 9 | Local automated |
| AC-18 | 8, 9 | Local automated |
| AC-19 | 5, 8, 9 | Local automated |
| AC-20 | 14 | Cloud/manual |
| AC-21 | 13, 14 | Cloud/manual |
| AC-22 | 4, 6 | Local automated |
| AC-23 | 13, 14 | Cloud/manual |
| AC-24 | 10, 14 | Local plus cloud |
| AC-25 | 1, 10 | Local automated |
| AC-26 | 8 | Local automated |
| AC-27 | 3, 12 | Local automated |
| AC-28 | 6, 8 | Local automated |
| AC-29 | 2, 4–12 | Local automated |
| AC-30 | 2, 4–12 | Local automated |
| AC-31 | 1, 3, 12 | Local automated |
| AC-32 | 12 | Local automated |
| AC-33 | 1, 12 | Local automated |

## Dependency references

Use these primary sources during the compatibility checks, then verify the selected versions in the actual build:

- [Springdoc compatibility](https://springdoc.org/): springdoc 3.x supports Spring Boot 4.
- [Cucumber-JVM installation](https://cucumber.io/docs/installation/java/): Java, JUnit Platform and dependency injection modules.
- [PIT JUnit plugin](https://github.com/pitest/pitest-junit5-plugin): plugin/PIT constraints and platform alignment.
- [Lambda reserved concurrency](https://docs.aws.amazon.com/lambda/latest/dg/configuration-concurrency.html) and [quotas](https://docs.aws.amazon.com/lambda/latest/dg/gettingstarted-limits.html): new-account limits and capacity available for reservation.
- [Testcontainers MongoDB](https://java.testcontainers.org/modules/databases/mongodb/): replica-set container support.
- [Maven Failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/), [JaCoCo checks](https://www.jacoco.org/jacoco/trunk/doc/check-mojo.html), [PIT Maven](https://pitest.org/quickstart/maven/): suite lifecycle and enforcement.
