# Local verification

Use Java 21 and the committed Maven wrapper. Full local acceptance needs Docker accessible from the invoking environment and the pinned MongoDB 8.0.14 replica-set image. The harness automatically starts Mongo, Spring HTTP and a versioned/checksum-aware S3 HTTP stub. SDK clients use explicit dummy credentials; no AWS account or real credential resolution is needed.

## Retained verified application checkpoint

Commit `0ea3358` established the reviewed Task 12 local matrix: API 139, worker 110
and generator 28 unit tests; 89 acceptance/integration cases including 40 Cucumber
scenarios when enabled; zero missed included unit lines/branches. PIT detected
374/381 API, 144/157 worker and 86/95 generator mutants, with the unchanged 80%
floor; detected includes the separately recorded healthy TIMED_OUT status.

The seven commands below and both verify skips ran as nine separate actual
builds. Six conflicting profile pairs and the isolated negative gates were also
verified. Retained operator evidence is local and untracked at
`.superpowers/sdd/2026-10-02-payment-reconciliation-implementation/task-12-logs/modes/evidence.json`
(with per-mode logs/reports) and `task-12-logs/gates-combined-evidence.json`.
Generated evidence is intentionally not part of a clone; rerun the documented
checks to obtain fresh reports. The links below identify the executable source
behind that passing checkpoint, not new cloud measurements.

No Java, Maven/POM or acceptance source changed in Tasks 13–15. Source/documentation
and Python-tooling changes reuse that application proof and run affected checks;
the full Maven matrix is not claimed to have run again during this handoff.
The subsequent final review fix adds one offline API AWS request-construction
test. The focused API verification now runs 140 unit tests with zero missed
included lines/branches and targeted AwsConfiguration PIT; the historical
277-unit-test matrix above has not been rerun at this newer test revision.
The transport test uses explicit dummy credentials and an offline HTTP transport
to check the real cloud client's host/path; it does not establish real AWS
authorization or delivery.

The negative-gate runner rejects a custom in-repository log directory unless it
is under an excluded subtree such as `.verification/`, before creating output
or building. External log directories are supported. Both verification runners
replace their owned report subtrees (including newly absent reports) when reusing
a run label; distinct log directories/labels retain previous runs. Use a new log
directory for each execution when retaining separate history.

Actual Terraform validation/provider lockfile and [required cloud/manual evidence](demo-evidence.md#required-cloud-criterion-ledger)
remain PENDING. Overall acceptance cannot pass until those observations exist.

Run from the reactor root:

| Command | Execution |
| --- | --- |
| ./mvnw clean verify | Units, local acceptance, zero-miss unit coverage, PIT |
| ./mvnw clean verify -Pall-tests | Same as default |
| ./mvnw clean verify -Punit-tests | Units and coverage; no containers/PIT |
| ./mvnw clean verify -Pacceptance-tests | Acceptance only; no units/unit gates/PIT |
| ./mvnw clean verify -Pmutation-tests | Units, coverage, PIT; no containers |
| ./mvnw clean package -DskipTests | Compile production/tests, package; unchecked artifacts |
| ./mvnw clean package -Dmaven.test.skip=true | Compile production only, package; unchecked artifacts |

Both skip properties also suppress all suites/gates with verify. Test profiles are mutually exclusive; all six conflicting pairs fail validation. A bare compile/package does not verify the application.

Repeat the mode and failure proofs sequentially:

~~~sh
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/verify-build-modes.py --phase complete
python3 scripts/check-quality-gates.py
~~~

`verify-build-modes.py --phase complete` runs all seven commands plus both skip properties with verify, inspects fresh compiled classes/artifacts/reports, and checks forbidden execution evidence is absent. Its bootstrap phase checks effective plugin execution settings, skip overrides and all conflicting profile pairs.

The gate script copies the working tree into temporary directories excluding .git, .worktrees, .superpowers, targets and verification scratch. It deliberately fails a real Cucumber assertion in default/all/acceptance modes; removes the sole GeneratorCliTest coverage in every applicable mode; raises only a temporary copy's mutation threshold to 100 against viable existing survivors in every applicable mode; and proves unchecked packaging, zero unit/scenario discovery, missing coverage data and unusable process-local Docker-client configuration. Copies are removed and the real source manifest checked even when a build/assertion fails. A failed proof returns nonzero. To rerun a selected area, repeat --group acceptance|coverage|mutation|discovery|docker; the default runs every group.

Evidence defaults to ignored .verification/build-modes and .verification/quality-gates; --log-dir PATH selects another scratch location. Every build has a log, retained reports and JSON evidence before the next clean removes targets. Do not run Maven/PIT concurrently in the same worktree.

## Reports and boundaries

| Evidence | Generated location |
| --- | --- |
| Units | Production module target/surefire-reports/TEST-*.xml |
| Integration/Cucumber | acceptance-tests/target/failsafe-reports; target/cucumber-report.json and .html |
| Unit instrumentation | Production module target/jacoco-unit.exec |
| Acceptance instrumentation | acceptance-tests/target/jacoco-acceptance.exec |
| Unit class reports/gate | Production module target/site/jacoco |
| Mutations | Production module target/pit-reports |

Unit and acceptance agents write different files. JaCoCo unit reports/checks consume only production modules' unit files. Acceptance-only mode needs no unit data. A verify Enforcer check rejects missing enabled unit coverage data before JaCoCo can silently skip it. Surefire/Failsafe require tests, ScenarioExecutionGuard independently rejects zero scenario events, and PIT requires mutations.

Cucumber suite XML root counts can be stale. Verification counts actual unskipped testcase elements, reconciles Cucumber JSON scenario events with suite cases and Failsafe summary completed count, and rejects errors/failures. Root tests attributes alone are not evidence.

PIT selects only *Test classes (worker narrows to its own package); acceptance classes never contribute to unit gates. Every module retains its 80% floor and zero missed handwritten unit lines/branches. Evidence lists each included covered class, counters, statuses and individual survivors. TIMED_OUT is a detected mutant; RUN_ERROR, MEMORY_ERROR and uncovered mutants are not healthy executions.

Exact exclusions: the Spring application's delegating main, generator's pure System.exit delegation adapter, and worker's exact empty ReconciliationModel namespace constructor. Nested records and every GeneratorCli decision remain included. Interfaces and compiler-filtered record/accessor boilerplate have no handwritten counters. No package-wide logic exclusions or threshold reductions are used.

## Requirement traceability

Every REQ-01–REQ-20 and AC-01–AC-33 has a linked entry. **LOCAL PASS** means the
retained historical application checkpoint established the local part.
**REQUIRED PENDING** means source/offline proof cannot satisfy the full requirement.
AC-13, AC-18, AC-20, AC-21, AC-23 and AC-24 retain separate cloud/manual portions.

| Requirement | Criteria and linked executable evidence | Handoff status |
| --- | --- | --- |
| <a id="req-01"></a>REQ-01 Purchase ingestion | [AC-01](#ac-01), [AC-02](#ac-02) | LOCAL PASS |
| <a id="req-02"></a>REQ-02 Date closure | [AC-03](#ac-03), [AC-04](#ac-04), [AC-05](#ac-05) | LOCAL PASS |
| <a id="req-03"></a>REQ-03 Stable inputs | [AC-06](#ac-06) | LOCAL PASS |
| <a id="req-04"></a>REQ-04 CSV contract | [AC-07](#ac-07), [AC-08](#ac-08), [AC-09](#ac-09) | LOCAL PASS |
| <a id="req-05"></a>REQ-05 Classification | [AC-10](#ac-10), [AC-11](#ac-11) | LOCAL PASS |
| <a id="req-06"></a>REQ-06 Summary | [AC-10](#ac-10) | LOCAL PASS |
| <a id="req-07"></a>REQ-07 Registered uploads | [AC-12](#ac-12), [AC-13](#ac-13) | REQUIRED PENDING — cloud/manual portion |
| <a id="req-08"></a>REQ-08 Public API | [AC-14](#ac-14) | LOCAL PASS |
| <a id="req-09"></a>REQ-09 Worker API | [AC-15](#ac-15) | LOCAL PASS |
| <a id="req-10"></a>REQ-10 Persistence | [AC-16](#ac-16), [AC-22](#ac-22) | LOCAL PASS |
| <a id="req-11"></a>REQ-11 Idempotency | [AC-02](#ac-02), [AC-16](#ac-16), [AC-17](#ac-17) | LOCAL PASS |
| <a id="req-12"></a>REQ-12 Retry/recovery | [AC-18](#ac-18), [AC-19](#ac-19) | REQUIRED PENDING — cloud/manual portion |
| <a id="req-13"></a>REQ-13 Bounded processing | [AC-20](#ac-20) | REQUIRED PENDING — cloud/manual portion |
| <a id="req-14"></a>REQ-14 Infrastructure/access | [AC-15](#ac-15), [AC-21](#ac-21) | REQUIRED PENDING — cloud/manual portion |
| <a id="req-15"></a>REQ-15 Zero spend | [AC-23](#ac-23) | REQUIRED PENDING — cloud/manual portion |
| <a id="req-16"></a>REQ-16 Repeatable scenarios | [AC-24](#ac-24) | REQUIRED PENDING — cloud/manual portion |
| <a id="req-17"></a>REQ-17 Module separation | [AC-24](#ac-24), [AC-25](#ac-25) | REQUIRED PENDING — cloud/manual portion |
| <a id="req-18"></a>REQ-18 Acceptance harness | [AC-24](#ac-24), [AC-26](#ac-26), [AC-27](#ac-27), [AC-28](#ac-28) | REQUIRED PENDING — cloud/manual portion |
| <a id="req-19"></a>REQ-19 Unit/mutation gates | [AC-29](#ac-29), [AC-30](#ac-30) | LOCAL PASS |
| <a id="req-20"></a>REQ-20 Maven modes | [AC-31](#ac-31), [AC-32](#ac-32), [AC-33](#ac-33) | LOCAL PASS |

The named `finalEvidenceCoversEveryCriterion` ID-set checks cover exactly 20
requirements and 33 criteria. Its `assert not pending_required_evidence` assertion
is **REQUIRED PENDING**, and must not be reported as passing. The
[cloud ledger](demo-evidence.md#required-cloud-criterion-ledger) and readiness table
identify the missing real observations; an offline test or
AUTOMATED_CHECKS_PASSED report cannot clear them.

## Acceptance evidence matrix

Feature paths are relative to acceptance-tests/src/test/resources/features; integration names are under acceptance-tests/src/test/java. Worker/generator units additionally verify detailed parser/comparator/generator boundaries.

| AC | Executable local evidence | Cloud portion |
| --- | --- | --- |
| <a id="ac-01"></a>AC-01 | [purchases.feature](../acceptance-tests/src/test/resources/features/purchases.feature); [PurchaseConcurrencyIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/PurchaseConcurrencyIT.java) fractional/duplicate-key/reference/merchant Unicode/unknown-field checks | — |
| <a id="ac-02"></a>AC-02 | Purchase replay/global uniqueness scenarios; concurrentGlobalReferenceRaceHasOnePurchaseAndOneGuardIncrement; [executable fixture/source](../acceptance-tests/src/test/resources/features/purchases.feature) | — |
| <a id="ac-03"></a>AC-03 | Closed/replay scenarios; fractionalAmountIsRejectedAndClosedDateIsImmutable; emptyDateCanBeClosedAndNeverReopened; [executable fixture/source](../acceptance-tests/src/test/resources/features/purchases.feature) | — |
| <a id="ac-04"></a>AC-04 | [PurchaseConcurrencyIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/PurchaseConcurrencyIT.java).closeRacingWithInsertsIncludesEveryCommittedPurchase | — |
| <a id="ac-05"></a>AC-05 | [PurchaseConcurrencyIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/PurchaseConcurrencyIT.java).concurrentRequestsNeverAdmitPurchase1001 | — |
| <a id="ac-06"></a>AC-06 | Closed previous-day fixture against fixed next-day clock; [RunPublicationIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/RunPublicationIT.java).registrationRequiresClosedPastDateAndRealMongoRejectsBadTypes rejects open/current/future | — |
| <a id="ac-07"></a>AC-07 | Quoted CRLF/LF complete-flow cases; invalid header/date/currency/amount/UTF-8 feature cases require whole-run failure/no report; [executable fixture/source](../acceptance-tests/src/test/resources/features/invalid-settlement.feature) | — |
| <a id="ac-08"></a>AC-08 | Header-only complete-flow cases; no-header failure fixture; [executable fixture/source](../acceptance-tests/src/test/resources/features/reconciliation.feature) | — |
| <a id="ac-09"></a>AC-09 | [CsvBoundaryIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/CsvBoundaryIT.java): 2,000 rows/2 MiB publish; row 2,001 fails without report; 2 MiB+1 registration fails. [SettlementCsvParserTest](../reconciliation-worker/src/test/java/org/octavio/paymentreconciliationsim/worker/csv/SettlementCsvParserTest.java).settlementLimitsAreInclusive proves raw +1 parser rejection | — |
| <a id="ac-10"></a>AC-10 | Canonical complete-flow feature and [ReconciliationFlowIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/ReconciliationFlowIT.java) compare committed independent expected JSON | — |
| <a id="ac-11"></a>AC-11 | Duplicate-unknown/unequal-duplicate complete-flow fixtures include all row evidence; [executable fixture/source](../acceptance-tests/src/test/resources/fixtures/duplicate-unknown-expected.json) | — |
| <a id="ac-12"></a>AC-12 | [RunPublicationIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/RunPublicationIT.java).simultaneousBindingsFixTheFirstVersionAndRegistrationHasUniqueLogicalIdentity; upload/replay scenarios | — |
| <a id="ac-13"></a>AC-13 | [upload-registration.feature](../acceptance-tests/src/test/resources/features/upload-registration.feature) returned URL/checksum/bounded expiry/bound versions; [S3StubIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/support/S3StubIT.java) | Real AWS signature/expiry enforcement pending Task 14; [required pending evidence](demo-evidence.md#cloud-ac-13) |
| <a id="ac-14"></a>AC-14 | [api-access.feature](../acceptance-tests/src/test/resources/features/api-access.feature); [ApiBoundaryIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/ApiBoundaryIT.java).publicOpenApiAndSwaggerRuntimeIntegration; pending/filter/pagination in [RunPublicationIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/RunPublicationIT.java) | — |
| <a id="ac-15"></a>AC-15 | [api-access.feature](../acceptance-tests/src/test/resources/features/api-access.feature); [ApiBoundaryIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/ApiBoundaryIT.java) missing/unknown/wrong-role public/internal tokens | — |
| <a id="ac-16"></a>AC-16 | [RunPublicationIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/RunPublicationIT.java).simultaneousIdenticalAndConflictingPublicationsHaveOneAtomicWinner; malformed full reports | — |
| <a id="ac-17"></a>AC-17 | Canonical replay; publicationReplayCannotDowngradeCompletedRun; completed recovery/lost completion response; [executable fixture/source](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/RunPublicationIT.java) | — |
| <a id="ac-18"></a>AC-18 | [WorkerRetryIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/WorkerRetryIT.java) exact-version outage retry; [RecoveryIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/RecoveryIT.java) callback outage/stale/manual recovery | Real AWS asynchronous scheduling pending Task 14; [required pending evidence](demo-evidence.md#cloud-ac-18) |
| <a id="ac-19"></a>AC-19 | Invalid-settlement deterministic failure/attemptId/no report; [ProcessingServiceTest](../reconciliation-worker/src/test/java/org/octavio/paymentreconciliationsim/worker/ProcessingServiceTest.java) correlated diagnostics | — |
| <a id="ac-20"></a>AC-20 | Parser/generator units and [CsvBoundaryIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/CsvBoundaryIT.java) exercise supported bounds | Cold/warm cloud runtime measurements pending Task 14; [required pending evidence](demo-evidence.md#cloud-ac-20) |
| <a id="ac-21"></a>AC-21 | Local direct real handler evidence only; [executable fixture/source](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/ReconciliationFlowIT.java) | Real S3 notification/private networking/roles pending Task 14; [required pending evidence](demo-evidence.md#cloud-ac-21) |
| <a id="ac-22"></a>AC-22 | [PurchaseConcurrencyIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/PurchaseConcurrencyIT.java).mongoSchemaRejectsFloatingPointAmountsAndInvalidGuardStatesAndDateIndexIsUsed; real run schema/index races | — |
| <a id="ac-23"></a>AC-23 | Local tests do not establish account eligibility or teardown | Free-plan/Atlas M0/usage visibility/teardown pending Tasks 13–14; [required pending evidence](demo-evidence.md#cloud-ac-23) |
| <a id="ac-24"></a>AC-24 | [generator.feature](../acceptance-tests/src/test/resources/features/generator.feature) executes packaged CLI over public API/upload against independent expected JSON | Cloud demonstration pending Task 14; [required pending evidence](demo-evidence.md#cloud-ac-24) |
| <a id="ac-25"></a>AC-25 | Fresh mode builds inspect three artifacts; generator runtime POM contains Jackson only and no application/database dependency; real CLI acceptance; [verification source](../scripts/verify-build-modes.py) | — |
| <a id="ac-26"></a>AC-26 | [reconciliation.feature](../acceptance-tests/src/test/resources/features/reconciliation.feature); committed CSV/purchases/expected fixtures; real Spring/Mongo/handler; [verification source](../acceptance-tests/src/test/resources/fixtures/canonical-expected.json) | — |
| <a id="ac-27"></a>AC-27 | [LocalEnvironmentIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/support/LocalEnvironmentIT.java)/ScenarioFailureIT/WorkerInvocationLifecycleIT success/startup/scenario/invocation cleanup; Docker-client probe | TLS-client probe does not simulate every physical daemon-absence path |
| <a id="ac-28"></a>AC-28 | Prepared [run-publication.feature](../acceptance-tests/src/test/resources/features/run-publication.feature)/RunPublicationIT; real parsing/comparison/retry features and [WorkerRetryIT](../acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/WorkerRetryIT.java) | — |
| <a id="ac-29"></a>AC-29 | Per-class zero-miss reports in three modules; removed sole-covering test fails selected coverage modes; [verification source](../scripts/check-quality-gates.py) | — |
| <a id="ac-30"></a>AC-30 | Three modules' PIT status/survivor audit; viable-survivor high-floor negative probe; [verification source](../scripts/check-quality-gates.py) | — |
| <a id="ac-31"></a>AC-31 | Complete mode script inspects fresh selected/forbidden suites/data/artifacts; [verification source](../scripts/verify-build-modes.py) | — |
| <a id="ac-32"></a>AC-32 | Gate script: Cucumber default/all/acceptance; coverage default/all/unit/mutation; mutation default/all/mutation fail; [verification source](../scripts/check-quality-gates.py) | — |
| <a id="ac-33"></a>AC-33 | Standard skips package/verify; six profile conflicts; zero-unit/zero-scenario/no-coverage probes; [verification source](../scripts/check-quality-gates.py) | — |

## Unit ownership

All included logic is checked per class, including nested decoder/exception/stream classes. Exact class/counter inventories appear in evidence.json.

| Module | Logic | Assertion owners |
| --- | --- | --- |
| API | Purchase/create/decoder/controller/repository/service | CreatePurchaseTest, PurchaseRequestDecoderTest, PurchaseControllerTest, PurchaseRepositoryTest, PurchaseServiceTest |
| API | Retry/delay; dates/models/closure | TransactionRetryTest, BusinessDateServiceTest, BusinessDateControllerTest |
| API | Run contracts/model/repository/service/controllers/decoder; validator/canonicalizer/recovery | RunRepositoryTest, RunServiceTest, RunControllerTest, RunRequestDecoderTest, ReportValidatorTest, ReportCanonicalizerTest, RecoveryServiceTest |
| API | Mongo/run schemas; transaction manager/clock; SDK config/storage/Lambda; docs/security/errors | MongoSchemaInitializerTest, RunSchemaInitializerTest, ConfigurationTest, AwsConfigurationTest, S3SettlementStorageTest, LambdaConfigurationTest, AwsLambdaInvokerTest, OpenApiConfigurationTest, DemoTokenFilterTest, ApiExceptionHandlerTest |
| Worker | Comparator/model; CSV/parser/stream/exception | ReconciliationComparatorTest, SettlementCsvParserTest |
| Worker | Event/object/attempt contracts; entry point/processing/diagnostics/errors | InvocationParserTest, ReconciliationHandlerTest, ProcessingServiceTest |
| Worker | S3 reader, HTTP client, runtime/private config | S3SettlementReaderTest, WorkerApiClientTest, WorkerConfigurationTest |
| Generator | Scenario/contracts/writer/verifier; public HTTP/workflow/CLI | ScenarioFactoryTest, SimulatorClientTest, GenerationWorkflowTest, GeneratorCliTest |

## Output hygiene and limitations

Mockito uses its supported explicit test JVM agent; test CDS is disabled to avoid bootstrap-classpath warnings. Standalone JARs omit module descriptors/signatures, append overlapping licenses/notices/dependency metadata with contents preserved, and build an explicit manifest. Generator retains its CLI main and multi-release support. The test-only module skips its empty main JAR. Deliberate scenario failure diagnostics use a removed temporary JSON report.

PIT's optional Spring-plugin suggestion is informational; no plugin was added merely to silence it. Expected injected-failure WARN/ERROR and genuine mutation timeouts remain distinguishable from runner/build errors.

The Docker proof sets only a child process's host/TLS/nonexistent temporary certificates. It proves an unusable client fails enabled acceptance startup without skipped checks or daemon/socket/host changes. The existing injected availability regression proves the friendly Docker-required guard. It does not exercise every physical daemon-absence detection path.

Task 7's cloud S3 utilities.getUrl assertion does not exercise the transport path-style flag: that viable survivor remains in the audit and the 80% floor. Local presigner/stub transport checks do not erase this narrow cloud configuration test gap. Task 11's purchase additionalProperties mismatch is corrected and checked against live docs; broader schema redesign is outside this task.
