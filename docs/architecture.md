# Architecture and behavior

The simulator compares immutable internal purchases with a complete settlement file for one business date. Spring Boot owns ingestion, date closure, upload registration and MongoDB persistence. The Java Lambda owns CSV validation and comparison. The scenario generator uses the public API and presigned upload contract to create and verify synthetic runs.

## Processing flow

1. The client creates purchases and closes their business date.
2. The API registers the settlement checksum and byte length, then returns a run ID and presigned S3 upload instructions.
3. The client uploads the CSV directly to S3. An ObjectCreated notification invokes Lambda.
4. The worker verifies and binds the original object version, retrieves all purchases for the closed date through the private API, and validates the entire CSV.
5. The worker compares the two datasets and submits a complete report. The API validates and publishes it atomically in MongoDB.
6. Clients query status, summaries and paginated results through the public API or Swagger.

The worker has no MongoDB credentials. S3 retains original files; MongoDB stores purchases and reports. Duplicate deliveries can repeat computation while preserving one logical run and one completed report.

The simulator supports ARS purchases and complete daily settlement files. Card processing, money movement, refunds, disputes, FX, fees, partial-file merging and automatic discrepancy resolution are outside its scope.

## Business date and transaction invariants

**REQ-01 Purchase ingestion.** A purchase has transactionReference, merchantId, businessDate, amountCentavos, currency, and server-recorded receivedAt. businessDate is an explicit ISO date in this simulation. Currency must be ARS and amountCentavos a positive signed 64-bit integer; fractional JSON numbers and floating-point storage are rejected. Duplicate JSON object keys are rejected with HTTP 400, including identical duplicate values. No raw card identifiers are accepted.

References are case-sensitive strings of 1 to 64 ASCII letters, digits, underscores, or hyphens. merchantId is a nonempty string of at most 64 Unicode codepoints, preserved without trimming or normalization (including nonempty whitespace). A reference is globally unique across business dates. An exact repeat of a purchase creation request returns the existing purchase; a repeat with different business fields returns conflict.

**REQ-02 Date closure.** A business date moves from OPEN to CLOSED exactly once; closing it again is harmless. It cannot be reopened in this version. Purchases cannot be added to a closed date and purchase modification/deletion endpoints do not exist. The 1,000-purchase limit must hold under concurrent requests.

Closing a date and inserting a purchase coordinate through the same business-date guard: if insertion wins, the closed dataset includes it; if closing wins, insertion fails. Ingestion updates the date guard and inserts the purchase in one MongoDB transaction; closing updates that same guard. Bounded retries handle transaction write conflicts and recheck the date state.

**REQ-03 Stable inputs.** All runs for a closed date use the same full internal dataset, including purchases whose references are absent from settlement. Purchases absent from the file remain part of the comparison. An empty date may be explicitly created and closed.

Date validation uses America/Buenos_Aires and a controllable application clock. Upload registration requires a CLOSED date strictly earlier than the current business date. Arrival date does not determine which purchases are reconciled. A persisted close timestamp and purchase count explain the dataset's boundary.

## Settlement file contract

**REQ-04 CSV format.** A UTF-8 CSV has exactly these columns, in this order:

```csv
business_date,transaction_reference,amount_centavos,currency
2026-09-29,MATCH-001,12345,ARS
2026-09-29,AMOUNT-001,9999,ARS
```

The CSV parser supports quoted fields, LF and CRLF. The header is mandatory. Every data row must have four fields, match the registered date, satisfy the reference and positive-integer rules, and use ARS. Field values retain their whitespace and case. A header-only file is valid and represents zero external transactions for the registered date. An empty file without a header is invalid. Trailing extra fields and malformed records are rejected.

Duplicate references are valid input evidence, not a CSV validation error. Each data row retains its 1-based row number, reference and amount. Row number 1 means the first record after the header, independent of newline characters inside quoted fields. Any invalid record fails the entire file; no partial comparison results are published.

The raw byte size must be at most 2,097,152 bytes and data-row count at most 2,000. The API validates declared size at registration; the worker checks actual object size before downloading and counts bytes and rows while parsing.

## Comparison rules and report

**REQ-05 Classification.** The comparator groups settlement rows by reference and evaluates the union of internal and external references in this precedence:

| Condition | Outcome |
| --- | --- |
| Two or more settlement rows have the reference | DUPLICATE |
| Exactly one settlement row, no internal purchase | MISSING_INTERNALLY |
| Internal purchase, no settlement row | MISSING_IN_SETTLEMENT |
| One record on each side, unequal amountCentavos | AMOUNT_MISMATCH |
| One record on each side, equal amountCentavos | MATCHED |

Duplicate precedence applies even if the reference is unknown internally or repeated rows have different amounts. One outcome is produced per distinct logical reference. API ingestion prevents duplicate internal references, so this version detects duplicates in settlement only.

A result includes reference, outcome, optional internal amount, settlement evidence with row numbers and amounts, and internal merchantId when known. Evidence retains every duplicate row. Deterministic ordering by reference makes tests and report comparisons stable.

**REQ-06 Summary.** The report includes internal purchase count, settlement row count, distinct settlement reference count, total result count and count for each outcome. Outcome counts sum to total result count, not necessarily to settlement row count. A run containing discrepancies is COMPLETED; discrepancies are business outcomes, not infrastructure failures.

## Upload registration and file identity

**REQ-07 Registered uploads.** The client computes the CSV's SHA-256, submits its business date, checksum, and byte length to Spring, and receives runId, object key, required headers, expiration, and a presigned PUT URL. Spring persists the run before returning the URL.

The settlement source is `SIMULATED`. The logical run identity is (source, businessDate, SHA-256 of exact file bytes, rulesVersion). A repeated registration of this identity returns the same run. Different bytes constitute a new run and never replace old results, allowing corrected synthetic files without partial merging or automatic supersession.

Object keys use `settlements/{businessDate}/{runId}.csv`. Upload URLs last at most ten minutes, with expiry also bounded by the signing credentials. The API signs the upload checksum header and returns it to the client. File identity uses SHA-256 of the original bytes rather than an S3 ETag.

The bucket is versioned, and a run binds to its first validated object version. Processing and replay read that exact version. Reusing a presigned URL cannot change the bound input. The worker verifies actual bytes against the registered checksum; a different payload fails regardless of its filename.

The generator performs the direct S3 PUT. Swagger documents URL registration and result queries; it does not need to act as an S3 upload UI. A completed run does not receive another upload URL. An unuploaded registration is visible as AWAITING_UPLOAD; it is not reported as reconciled.

## API contract

**REQ-08 Public demo API.** Public routes are:

| Method and route | Behavior |
| --- | --- |
| POST /api/v1/business-dates | Create an OPEN date, including an empty date |
| POST /api/v1/transactions | Create or safely repeat a simulated purchase |
| POST /api/v1/business-dates/{date}/close | Close the date idempotently |
| POST /api/v1/reconciliation-runs | Register file identity and return upload instructions |
| GET /api/v1/reconciliation-runs?businessDate={date} | List runs with last-known status |
| GET /api/v1/reconciliation-runs/{runId} | Return status, input identity, errors and summary |
| GET /api/v1/reconciliation-runs/{runId}/results?outcome={outcome}&page=0&size=50 | Return deterministic paginated results, optionally filtered |
| POST /api/v1/reconciliation-runs/{runId}/reprocess | Request recovery using the retained original file |

Input validation returns 400, unknown resources 404, closed-date or conflicting-identity operations 409, unsupported body media types 415, and oversized application payloads 413. New purchases and runs return 201; harmless repeats return 200. Reprocess returns 202 when invocation is accepted, not when processing completes. Results before completion return 409 and no partial results. Pagination caps size at 100. Errors have a stable code, message, correlationId, without credentials or stack traces.

Swagger/OpenAPI documents request and response schemas, integer units, statuses, filters, and representative errors. Internal worker operations must not be exposed in the user-facing Swagger document.

**REQ-09 Worker API.** Private authenticated operations allow Lambda to obtain registered run metadata, bind the exact object version, mark an attempt as processing, fetch all closed-date purchases, submit the complete report, and record a failure when Spring is reachable. Paths use /internal/v1/reconciliation-runs/{runId} with /input, /processing, /results, and /failure operations. Input includes date, rule version, expected checksum, and purchases. Processing receives bucket, key, versionId and verified checksum; results reference that same identity.

The worker API exposes reconciliation operations. Result submission is a full idempotent PUT. Full report submissions are limited to 8,388,608 received request bytes, independently of the serialized run-document limit; larger bodies return 413 without partial persistence. The limit applies to the actual stream, including requests without a Content-Length header or using chunked transfer encoding. Spring validates result schema, bounds, registered identity, duplicate result references, required evidence, and summary consistency before publication. The comparator remains responsible for calculating classifications.

## MongoDB model and consistency

**REQ-10 Persistence.** Collections are:

| Collection | Contents and indexes |
| --- | --- |
| purchases | Immutable purchase documents; unique transactionReference; index on businessDate and transactionReference |
| business_days | One document per businessDate, OPEN/CLOSED state, count and close timestamp; unique businessDate |
| reconciliation_runs | Registered source/date/checksum/rulesVersion, S3 version identity, timestamps, status, error, summary and embedded result array; unique logical run identity; index on businessDate and createdAt |

Bounded embedded results let Spring publish the report, summary, and COMPLETED state in one atomic document update. The API enforces an 8 MiB maximum serialized run document, including evidence, before publication. Rejected reports leave the existing document unchanged. Larger workloads would require a different result-storage strategy.

MongoDB schema validation and Java validation enforce required types, currencies, amounts and states. Money is stored as BSON integers. Aggregations retrieve and filter embedded results; report validation checks summary consistency. Integration tests inspect index use for business-date queries with explain output. Database transactions protect purchase ingestion against date closure; atomic run updates protect publication.

**REQ-11 Idempotency.** Concurrent duplicate events may calculate the same result more than once. They reuse the logical run and cannot publish extra outcomes. Atomic updates protect the bounded report without a distributed lease.

For a completed run, an identical report is accepted as an unchanged replay; a conflicting report is rejected. Equality uses canonical business output: sorted results/evidence, counts and input identity, excluding attempt timestamps. Atomic state checks prevent late failure callbacks or processing notifications from downgrading completion.

## Failure handling and recovery

**REQ-12 Status and retry semantics.** Runs persist AWAITING_UPLOAD, PROCESSING, COMPLETED, or FAILED as the last-known processing state. Failure detail contains a stable code and whether another attempt is appropriate. A FAILED run may be retried when its error is transient; deterministic validation failures do not trigger automatic retry loops.

Network failures, temporary Spring/database unavailability, and worker timeouts fail the Lambda invocation so AWS can apply bounded asynchronous retries. The worker records the attempt's failure when Spring is reachable. If Spring is unavailable, persisted status may remain stale. Incomplete runs with more than twenty minutes without activity are flagged as recovery-needed; this flag does not indicate that AWS has finished retrying.

The registered run and retained original S3 version provide a recovery path even if an event or callback is lost. Manual reprocess verifies that the input exists, invokes Lambda asynchronously with runId and the original version, and reuses the same run identity. It returns an upload-needed conflict if no original was bound and no valid uploaded version can be recovered at the registered key. A completed run is returned unchanged.

Structured logs include runId, businessDate, object version, rule version, attempt identifier, duration and error code. CloudWatch retains them for seven days. The [deployment reference](deployment.md#tunnel-smoke-and-recovery) covers run inspection and recovery. AWS retries are finite; eventual completion is not guaranteed.

**REQ-13 Bounded processing.** Lambda has a five-minute timeout, reserved concurrency of one and bounded HTTP connection/read timeouts. The maximum-workload benchmark targets less than 120 seconds, including initialization. [Cloud validation](demo-evidence.md#runtime-benchmark) describes cold and warm measurements.

## AWS networking, access and budget

**REQ-14 Infrastructure.** Terraform declares one regional VPC, an internet gateway, a public subnet for EC2, private subnet routing for Lambda, security groups, an EC2 instance/profile, encrypted storage, the versioned private S3 bucket, an S3 gateway endpoint, Lambda, IAM policies, its bucket notification/invoke permission, and log retention. No NAT gateway, load balancer, paid interface endpoint, or application queue is required.

Lambda calls EC2 using its private address, and accesses the regional S3 bucket through the gateway endpoint. Spring uses EC2's public outbound connection to Atlas over TLS. Atlas M0's access list permits only the current EC2 public IP and explicit development addresses, not 0.0.0.0/0. Updating that allowlist after an instance address changes is part of deployment instructions.

API ingress is restricted to Lambda's security group. Operators and the generator reach Swagger/API through a documented SSH tunnel; SSH ingress is restricted to the developer's IP. A demo token protects administrative operations and a separate worker token protects internal operations. Private worker HTTP is an explicit synthetic-demo compromise; production deployment would require transport encryption and stronger service identity. There is no publicly accessible unauthenticated API.

EC2 and Lambda use IAM roles for AWS access. At startup the worker reads its token from the private encrypted S3 object `runtime-config/worker.json`, uploaded separately from Terraform. Lambda environment variables contain only the config bucket/key and API URI. The API reads its Mongo URI and demo/worker tokens from a protected mode-0600 runtime file outside Terraform. Do not commit secrets or put them in Terraform variables, resources, data sources, plans, or state. Do not log tokens or presigned URLs. The bucket permits authenticated external presigned uploads as well as worker reads through the gateway endpoint.

**REQ-15 Deployment cost controls.** The deployment uses a small EC2 instance, Atlas M0, one Lambda and bounded storage/log retention. The optional Free-plan preflight checks current account benefits, credits and resource eligibility. Other accounts use the same infrastructure with their applicable charges.

The [deployment guide](deployment-guide.md) covers resource selection and teardown, including EC2 storage, S3 versions and logs. Budget notifications monitor usage; they do not cap spending. Local acceptance runs independently of AWS account benefits.

## Scenario generator and validation

**REQ-16 Repeatable scenarios.** A seeded local generator creates purchases through the API, closes a past date, registers/uploads its CSV, polls status, and compares returned results with a committed expected-output fixture. It never writes directly to MongoDB or imports simulator services, repositories, domain logic, or the worker implementation. It uses the public API and the returned presigned S3 upload contract; private worker operations are not available to it. Seeded generation, CSV/checksum creation, polling, response validation, and expected-result checks are generator logic subject to the same unit coverage and mutation gates. New scenario datasets require unused references and dates or a fresh disposable database.

The canonical scenario contains 20 numbered examples of each outcome (suffixes `001` through `020`). The original examples are:

| Reference | Internal centavos | Settlement rows in centavos | Expected |
| --- | --- | --- | --- |
| MATCH-001 | 10000 | 10000 | MATCHED |
| MISS-001 | 20000 | none | MISSING_IN_SETTLEMENT |
| EXT-001 | absent | 30000 | MISSING_INTERNALLY |
| AMOUNT-001 | 40000 | 45000 | AMOUNT_MISMATCH |
| DUP-001 | 50000 | 50000, 50000 | DUPLICATE |

Each subsequent group adds 10,000 centavos to the previous group’s amounts and preserves the same five-outcome pattern. Expected counts: 80 internal purchases, 100 settlement rows, 80 distinct settlement references, 100 results, and 20 of each outcome. Additional fixtures cover an unknown duplicated reference, different duplicate amounts, all matched, header-only settlement, and an empty internal date.

## Maven modules and component boundaries

**REQ-17 Module separation.** The root Maven aggregator/parent contains these modules:

| Module | Contents and deployable artifact |
| --- | --- |
| reconciliation-api | Spring Boot application, purchases, persistence and API contracts; executable Spring Boot JAR for EC2 |
| reconciliation-worker | Java Lambda handler, CSV parser, comparator and HTTP/S3 adapters; deployable Lambda JAR/ZIP |
| scenario-generator | Standalone local executable/CLI with its own scenario generation and API client |
| acceptance-tests | Cucumber features, step definitions, committed fixtures and local test environment; no production deployment |

The parent centralizes dependency/plugin versions and test-mode settings. API and worker are separate deployable applications. The generator has no Maven dependency on either application's implementation. It owns its client-side representations of the documented HTTP contract; no shared business library couples it to the simulator. API/worker communication remains HTTP even when both artifacts are available in the same reactor.

The acceptance module depends on API, worker and generator artifacts for test startup and real-handler invocation. The generator and acceptance steps use HTTP for business actions and result assertions. The harness initializes and resets test storage and installs validators/indexes; scenario purchases, date closure, upload registration, report submission and retrieval follow the same contracts as the demo. The generator acceptance scenario executes its actual entry point.

## Acceptance-test contract

**REQ-18 Cucumber acceptance tests.** Executable Gherkin `.feature` files use Cucumber-JVM on JUnit Platform. Features describe observable business behavior and reference REQ/AC IDs. Step definitions load committed JSON fixtures describing purchases and settlement rows, produce the actual settlement CSV, submit through the API/upload contract, and retrieve status, summaries, and paginated outcomes through HTTP for comparison with independently committed expected JSON. Raw CSV fixtures cover malformed encoding and syntax.

For example:

```gherkin
@REQ-05 @REQ-06 @AC-10
Feature: Daily payment reconciliation
  Scenario: Publish the five reconciliation outcomes
    Given a closed business date with purchases from "canonical-purchases.json"
    And a settlement file generated from "canonical-settlement.json"
    When the settlement file is uploaded and processed
    Then the reconciliation report is available through the API
    And its summary and results match "canonical-expected.json"
```

The local harness starts and tears down:

- The real Spring Boot API on an ephemeral HTTP port with test credentials and a fixed controllable business clock.
- A pinned Testcontainers MongoDB image configured as a replica set, supporting real MongoDB transactions, indexes, validation and atomic updates.
- A versioned, checksum-aware S3 HTTP stub for the operations used by the application and worker. Local configuration uses dummy AWS credentials and endpoint overrides.
- The real Java worker handler configured to call that Spring API and the local S3 substitute.

For the complete flow, the harness accepts the CSV through the returned local upload URL, makes those exact bytes/version metadata available to the worker, constructs the corresponding S3 event, and invokes the real handler. Fixtures and assertions retain checksum/version semantics. The complete flow uses real transaction retrieval and comparison. Separate API contract scenarios submit prepared JSON to test report validation, idempotency and publication directly.

Fault injection exercises transient S3/API failures, and repeated handler invocation models event retries. Fixed clocks, isolated data/ports and bounded polling make scenarios repeatable. Assertions compare canonical business output independently of runtime IDs/timestamps and validate variable fields separately. Expected JSON is maintained independently of the production comparator.

Acceptance tests require no AWS account, real AWS credentials, external MongoDB account, or paid emulator. Docker is required for MongoDB; unavailable dependencies fail acceptance-enabled builds with an actionable error. Every implemented local acceptance criterion has executable coverage; concurrency and persistence criteria use the same real replica-set database.

[Cloud validation](demo-evidence.md) covers S3 notification delivery, presigned authorization/expiry, IAM, private routing, account eligibility, runtime timings and teardown against deployed resources. Cloud smoke checks are explicitly invoked and never part of the default local Maven build.

## Unit coverage, mutation testing and build modes

**REQ-19 Logic quality gates.** JaCoCo requires zero missed lines and branches in each included handwritten production class. Coverage is collected from unit tests separately from acceptance execution. PIT enforces an 80% mutation score floor in each production module using unit tests.

Comparison, parsing, validation, state transitions, retries, application limits, configuration logic and generator workflows are included. The three exact coverage exclusions and report locations are documented in [testing.md](testing.md#reports-and-boundaries). Empty test or mutation discovery fails verification.

**REQ-20 Maven execution modes.** The supported build entry point is `verify`, which includes compilation and packaging before the final checks. Surefire runs unit tests; Failsafe runs Cucumber/integration tests during `integration-test` and checks their results at `verify`. JaCoCo and PIT checks are bound into verification when selected. Acceptance, coverage or mutation failures make the selected verification build fail with a nonzero exit code.

The supported commands, profile selection, skip behavior and report locations are documented in [testing.md](testing.md). Test profiles are mutually exclusive. Enabled suites fail if no tests, scenarios, coverage data or mutations are discovered. Unit tests run without Docker; acceptance tests start the real API and a replica-set MongoDB container.

## Acceptance criteria and traceability

The requirement and acceptance identifiers are shared with the test features. [Testing](testing.md#requirement-traceability) maps them to executable checks; [cloud validation](demo-evidence.md) covers deployed checks and benchmarks.

| ID | Requirement | Observable verification |
| --- | --- | --- |
| AC-01 | REQ-01 | Valid ARS purchase persists with exact integer amount; fractional, nonpositive, wrong-currency, malformed-reference and duplicate JSON key inputs fail with 400 (including identical duplicate values); merchantId preserves nonempty text without normalization, accepts 64 Unicode codepoints and rejects 65 |
| AC-02 | REQ-01, REQ-11 | Identical purchase replay returns one record; conflicting fields for its reference return 409 |
| AC-03 | REQ-02 | Closed dates reject additions; repeated close is harmless |
| AC-04 | REQ-02 | Concurrent create/close cannot admit a late purchase or omit a successfully committed purchase from the closed dataset |
| AC-05 | REQ-02 | The 1,001st purchase is rejected, including under concurrency |
| AC-06 | REQ-03 | A delayed day-N file uses day N, not its arrival date; open/current/future date uploads are rejected |
| AC-07 | REQ-04 | Quoted CSV, LF and CRLF parse correctly; wrong date/currency/header/amount fails the whole run without partial results |
| AC-08 | REQ-04 | Header-only file classifies all internal purchases as missing; no-header file fails |
| AC-09 | REQ-04 | Exact size/row boundaries succeed; one byte or one data row beyond the limits fails |
| AC-10 | REQ-05, REQ-06 | Canonical scenario produces the five specified outcomes and exact counts |
| AC-11 | REQ-05 | Unknown duplicated reference and unequal duplicate amounts produce DUPLICATE with all row evidence |
| AC-12 | REQ-07 | Repeated registration returns one run; different file bytes create a separate run without changing the old report |
| AC-13 | REQ-07 | Presigned upload works externally; expired authorization and wrong checksum fail; object reuploads cannot alter bound inputs |
| AC-14 | REQ-08 | Swagger documents public contracts; results support filters/pagination; pending runs expose status but no partial outcomes |
| AC-15 | REQ-09, REQ-14 | Worker inputs/results reject missing or wrong tokens; public demo credentials cannot authorize worker writes |
| AC-16 | REQ-10, REQ-11 | Concurrent duplicate completion yields one report; malformed/conflicting submissions cannot change a completed report |
| AC-17 | REQ-11 | Repeated S3 event/reprocess leaves run/result counts unchanged; a late failure cannot downgrade COMPLETED |
| AC-18 | REQ-12 | Temporary API outage can recover on retry; prolonged outage remains visibly incomplete/stale and can recover through manual reprocess |
| AC-19 | REQ-12 | Invalid CSV records a failure without publication or endless retries; logs permit correlation to the original input |
| AC-20 | REQ-13 | Maximum supported workload meets the measured runtime target; cold and warm measurements are recorded |
| AC-21 | REQ-14 | A real S3 upload invokes Lambda directly; worker reaches private EC2 and S3 without NAT or direct Atlas access |
| AC-22 | REQ-10 | MongoDB validation/indexes are installed, and business-date query evidence demonstrates the intended index |
| AC-23 | REQ-15 | Deployment checks account benefits where applicable, Atlas M0, usage visibility and resource teardown |
| AC-24 | REQ-16, REQ-17, REQ-18 | Acceptance runs the real generator through public API/upload contracts and verifies expected results; repeat the demonstration against the cloud deployment |
| AC-25 | REQ-17 | Reactor builds separate API, worker and generator artifacts; generator has no dependency on application internals or direct database access |
| AC-26 | REQ-18 | Canonical Cucumber feature loads committed fixtures, uploads CSV, executes the real local handler, and verifies exact API summary/results using real Spring and MongoDB |
| AC-27 | REQ-18 | Local acceptance starts dependencies automatically, needs no AWS account, cleans up after failure, and fails clearly when Docker is unavailable |
| AC-28 | REQ-18 | Prepared-report API scenarios reject malformed/conflicting reports; complete-flow scenarios exercise real parsing/comparison and retry/idempotency behavior |
| AC-29 | REQ-19 | Each included logic class in API, worker and generator reaches 100% unit line/branch coverage; removing a necessary unit test causes the coverage gate to fail |
| AC-30 | REQ-19 | PIT runs against included logic in all three production modules; a score below the configured threshold fails verification and exclusions/adjustments have documented reasons |
| AC-31 | REQ-20 | Every documented Maven mode selects exactly its intended suites/gates; unit-only runs need no Docker and acceptance-only runs need no unit coverage data |
| AC-32 | REQ-20 | Deliberately failing Cucumber scenario fails default/all/acceptance verification; deliberately violated coverage/mutation gates fail the applicable modes |
| AC-33 | REQ-20 | Both skip properties disable tests/gates as documented; conflicting profiles fail clearly and enabled suites cannot silently discover zero tests |

Core comparisons, CSV boundaries and generator logic use automated unit tests with the REQ-19 gates. Cucumber acceptance and supporting integration tests cover HTTP behavior, date closure, uniqueness, atomic publication and complete worker processing with a replica-set MongoDB container. Worker fault scenarios inject HTTP failures and lost responses. The default Maven build runs local checks. Deployed portions use the separate cloud validation procedures.

## Sources for platform behavior

- [S3 event notifications and Lambda invocation](https://docs.aws.amazon.com/lambda/latest/dg/with-s3.html): direct asynchronous invocation, separate invoke/read permissions, and prefix filtering.
- [S3 notification delivery](https://docs.aws.amazon.com/AmazonS3/latest/userguide/EventNotifications.html): at-least-once delivery.
- [Presigned URL uploads](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html): temporary upload authorization, checksum support, and URL reuse.
- [S3 gateway endpoints](https://docs.aws.amazon.com/vpc/latest/privatelink/vpc-endpoints-s3.html): private S3 access without NAT and no additional endpoint charge.
- [Lambda asynchronous retries](https://docs.aws.amazon.com/lambda/latest/dg/invocation-async-error-handling.html): finite retries and possible duplicate processing.
- [AWS Free account plan](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier.html): temporary benefits and account-plan conditions.
- [Atlas free clusters](https://www.mongodb.com/docs/atlas/tutorial/deploy-free-tier-cluster/): the managed free development tier.
- [Java Lambda handlers](https://docs.aws.amazon.com/lambda/latest/dg/java-handler.html) and [Java deployment packages](https://docs.aws.amazon.com/lambda/latest/dg/java-package.html): locally maintained handler source and packaged code deployed to AWS.
- [Cucumber-JVM](https://cucumber.io/docs/installation/java/): Java/JUnit Platform acceptance-test tooling.
- [Maven Failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/): integration-test execution, teardown and verification phases.
- [JaCoCo checks](https://www.jacoco.org/jacoco/trunk/doc/check-mojo.html): line/branch counters and build-failing coverage rules.
- [PIT Maven configuration](https://pitest.org/quickstart/maven/): mutation execution, target selection and configurable score thresholds.
