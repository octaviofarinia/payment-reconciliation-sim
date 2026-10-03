# Local verification

Use Java 21 and the committed Maven wrapper. Full local acceptance needs Docker accessible from the invoking environment and the pinned MongoDB 8.0.14 replica-set image. The harness automatically starts Mongo, Spring HTTP and a versioned/checksum-aware S3 HTTP stub. SDK clients use explicit dummy credentials; no AWS account or real credential resolution is needed.

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

The first script runs all seven commands plus both skip properties with verify, inspects fresh compiled classes/artifacts/reports, and checks forbidden execution evidence is absent. Its bootstrap phase checks effective plugin execution settings, skip overrides and all conflicting profile pairs.

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

## Acceptance evidence matrix

Feature paths are relative to acceptance-tests/src/test/resources/features; integration names are under acceptance-tests/src/test/java. Worker/generator units additionally verify detailed parser/comparator/generator boundaries.

| AC | Executable local evidence | Cloud portion |
| --- | --- | --- |
| 01 | purchases.feature; PurchaseConcurrencyIT fractional/duplicate-key/reference/merchant Unicode/unknown-field checks | — |
| 02 | Purchase replay/global uniqueness scenarios; concurrentGlobalReferenceRaceHasOnePurchaseAndOneGuardIncrement | — |
| 03 | Closed/replay scenarios; fractionalAmountIsRejectedAndClosedDateIsImmutable; emptyDateCanBeClosedAndNeverReopened | — |
| 04 | PurchaseConcurrencyIT.closeRacingWithInsertsIncludesEveryCommittedPurchase | — |
| 05 | PurchaseConcurrencyIT.concurrentRequestsNeverAdmitPurchase1001 | — |
| 06 | Closed previous-day fixture against fixed next-day clock; RunPublicationIT.registrationRequiresClosedPastDateAndRealMongoRejectsBadTypes rejects open/current/future | — |
| 07 | Quoted CRLF/LF complete-flow cases; invalid header/date/currency/amount/UTF-8 feature cases require whole-run failure/no report | — |
| 08 | Header-only complete-flow cases; no-header failure fixture | — |
| 09 | CsvBoundaryIT: 2,000 rows/2 MiB publish; row 2,001 fails without report; 2 MiB+1 registration fails. SettlementCsvParserTest.settlementLimitsAreInclusive proves raw +1 parser rejection | — |
| 10 | Canonical complete-flow feature and ReconciliationFlowIT compare committed independent expected JSON | — |
| 11 | Duplicate-unknown/unequal-duplicate complete-flow fixtures include all row evidence | — |
| 12 | RunPublicationIT.simultaneousBindingsFixTheFirstVersionAndRegistrationHasUniqueLogicalIdentity; upload/replay scenarios | — |
| 13 | upload-registration.feature returned URL/checksum/bounded expiry/bound versions; S3StubIT | Real AWS signature/expiry enforcement pending Task 14 |
| 14 | api-access.feature; ApiBoundaryIT.publicOpenApiAndSwaggerRuntimeIntegration; pending/filter/pagination in RunPublicationIT | — |
| 15 | api-access.feature; ApiBoundaryIT missing/unknown/wrong-role public/internal tokens | — |
| 16 | RunPublicationIT.simultaneousIdenticalAndConflictingPublicationsHaveOneAtomicWinner; malformed full reports | — |
| 17 | Canonical replay; publicationReplayCannotDowngradeCompletedRun; completed recovery/lost completion response | — |
| 18 | WorkerRetryIT exact-version outage retry; RecoveryIT callback outage/stale/manual recovery | Real AWS asynchronous scheduling pending Task 14 |
| 19 | Invalid-settlement deterministic failure/attemptId/no report; ProcessingServiceTest correlated diagnostics | — |
| 20 | Parser/generator units and CsvBoundaryIT exercise supported bounds | Cold/warm cloud runtime measurements pending Task 14 |
| 21 | Local direct real handler evidence only | Real S3 notification/private networking/roles pending Task 14 |
| 22 | PurchaseConcurrencyIT.mongoSchemaRejectsFloatingPointAmountsAndInvalidGuardStatesAndDateIndexIsUsed; real run schema/index races | — |
| 23 | Local tests do not establish account eligibility or teardown | Free-plan/Atlas M0/usage visibility/teardown pending Tasks 13–14 |
| 24 | generator.feature executes packaged CLI over public API/upload against independent expected JSON | Cloud demonstration pending Task 14 |
| 25 | Fresh mode builds inspect three artifacts; generator runtime POM contains Jackson only and no application/database dependency; real CLI acceptance | — |
| 26 | reconciliation.feature; committed CSV/purchases/expected fixtures; real Spring/Mongo/handler | — |
| 27 | LocalEnvironmentIT/ScenarioFailureIT/WorkerInvocationLifecycleIT success/startup/scenario/invocation cleanup; Docker-client probe | TLS-client probe does not simulate every physical daemon-absence path |
| 28 | Prepared run-publication.feature/RunPublicationIT; real parsing/comparison/retry features and WorkerRetryIT | — |
| 29 | Per-class zero-miss reports in three modules; removed sole-covering test fails selected coverage modes | — |
| 30 | Three modules' PIT status/survivor audit; viable-survivor high-floor negative probe | — |
| 31 | Complete mode script inspects fresh selected/forbidden suites/data/artifacts | — |
| 32 | Gate script: Cucumber default/all/acceptance; coverage default/all/unit/mutation; mutation default/all/mutation fail | — |
| 33 | Standard skips package/verify; six profile conflicts; zero-unit/zero-scenario/no-coverage probes | — |

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
