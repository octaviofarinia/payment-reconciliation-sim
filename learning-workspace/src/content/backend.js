import purchaseController from '../reference/library/backend/purchase-controller.txt?raw';
import purchaseDecoder from '../reference/library/backend/purchase-decoder.txt?raw';
import purchaseService from '../reference/library/backend/purchase-service.txt?raw';
import businessDate from '../reference/library/backend/business-date.txt?raw';
import runService from '../reference/library/backend/run-service.txt?raw';
import uploadStorage from '../reference/library/backend/upload-storage.txt?raw';
import tokenFilter from '../reference/library/backend/token-filter.txt?raw';
import workerHandler from '../reference/library/backend/worker-handler.txt?raw';
import processingService from '../reference/library/backend/processing-service.txt?raw';
import csvParser from '../reference/library/backend/csv-parser.txt?raw';
import comparator from '../reference/library/backend/comparator.txt?raw';
import reportValidator from '../reference/library/backend/report-validator.txt?raw';
import runRepository from '../reference/library/backend/run-repository.txt?raw';
import generatorWorkflow from '../reference/library/backend/generator-workflow.txt?raw';

const apiRoot = 'reconciliation-api/src/main/java/org/octavio/paymentreconciliationsim/';
const workerRoot = 'reconciliation-worker/src/main/java/org/octavio/paymentreconciliationsim/worker/';
const generatorRoot = 'scenario-generator/src/main/java/org/octavio/paymentreconciliationsim/generator/';

export const backendSources = [
  { id: 'backend-purchase-controller', title: 'PurchaseController.java', path: `${apiRoot}controller/PurchaseController.java`, group: 'API', note: 'The public HTTP entry point translates a creation result into 201 or an unchanged replay into 200.', focus: [21, 25], content: purchaseController },
  { id: 'backend-purchase-decoder', title: 'PurchaseRequestDecoder.java', path: `${apiRoot}controller/PurchaseRequestDecoder.java`, group: 'API', note: 'A strict JSON boundary: exact fields, typed strings, real dates, and an integral Java long amount.', focus: [14, 21], content: purchaseDecoder },
  { id: 'backend-purchase-service', title: 'PurchaseService.java', path: `${apiRoot}service/PurchaseService.java`, group: 'API', note: 'Existing-reference replay is resolved before a new purchase reserves capacity inside a transaction.', focus: [32, 46], content: purchaseService },
  { id: 'backend-business-date', title: 'BusinessDateService.java', path: `${apiRoot}businessdate/BusinessDateService.java`, group: 'API', note: 'Closing a day and reserving a purchase write the same guard document. Closed inputs become stable.', focus: [46, 77], content: businessDate },
  { id: 'backend-run-service', title: 'RunService.java', path: `${apiRoot}run/RunService.java`, group: 'API', note: 'Registration fixes logical input identity; publication checks identity and preserves a completed report.', focus: [28, 57], content: runService },
  { id: 'backend-upload-storage', title: 'S3SettlementStorage.java', path: `${apiRoot}storage/S3SettlementStorage.java`, group: 'API', note: 'Creates a checksum-bound, length-bound upload URL and recovers the retained original object version.', focus: [31, 43], content: uploadStorage },
  { id: 'backend-token-filter', title: 'DemoTokenFilter.java', path: `${apiRoot}security/DemoTokenFilter.java`, group: 'API', note: 'Separates public and worker roles, generates correlation IDs, and bounds report request bytes.', focus: [44, 67], content: tokenFilter },
  { id: 'backend-worker-handler', title: 'ReconciliationHandler.java', path: `${workerRoot}ReconciliationHandler.java`, group: 'Worker', note: 'The Lambda entry point processes every invocation record and preserves a retry signal for transient failures.', focus: [12, 25], content: workerHandler },
  { id: 'backend-processing-service', title: 'ProcessingService.java', path: `${workerRoot}ProcessingService.java`, group: 'Worker', note: 'Orders metadata, byte verification, identity binding, input fetch, CSV validation, comparison, and publication.', focus: [31, 58], content: processingService },
  { id: 'backend-csv-parser', title: 'SettlementCsvParser.java', path: `${workerRoot}csv/SettlementCsvParser.java`, group: 'Worker', note: 'Rejects malformed or oversized external input before any successful value escapes the parser.', focus: [30, 63], content: csvParser },
  { id: 'backend-comparator', title: 'ReconciliationComparator.java', path: `${workerRoot}domain/ReconciliationComparator.java`, group: 'Worker', note: 'A pure function over validated purchases and settlement evidence. Duplicate precedence comes first.', focus: [47, 60], content: comparator },
  { id: 'backend-report-validator', title: 'ReportValidator.java', path: `${apiRoot}run/ReportValidator.java`, group: 'API', note: 'Validates complete report structure, evidence coverage, internal values, and summary counts without recomputing classifications.', focus: [20, 44], content: reportValidator },
  { id: 'backend-run-repository', title: 'RunRepository.java', path: `${apiRoot}run/RunRepository.java`, group: 'API', note: 'Conditional MongoDB updates bind an object identity and publish the report with COMPLETED atomically.', focus: [29, 47], content: runRepository },
  { id: 'backend-generator-workflow', title: 'GenerationWorkflow.java', path: `${generatorRoot}GenerationWorkflow.java`, group: 'Generator', note: 'An independent public client creates data, closes it, uploads, polls, and verifies the complete output.', focus: [8, 28], content: generatorWorkflow },
];

export const backendModules = [
  {
    id: 'api', number: '02', title: 'Put an API around it',
    summary: 'Define HTTP contracts and separate the responsibilities of the API, worker and client.',
    outcome: 'Trace a request through Spring and choose the correct public or worker contract.',
    duration: '25 min', lens: 'code',
    sourceIds: ['backend-purchase-controller', 'backend-purchase-decoder', 'backend-token-filter', 'backend-generator-workflow'],
    lessons: [
      {
        id: 'api-contracts', title: 'Validate a purchase request', kicker: 'From Java values to HTTP',
        lead: 'The first boundary turns untrusted JSON into a purchase the application can reason about.',
        paragraphs: [
          'The comparator receives validated Java values. A caller starts with JSON. POST /api/v1/transactions therefore passes through PurchaseRequestDecoder before PurchaseService can write anything. The decoder requires exactly transactionReference, merchantId, businessDate, amountCentavos, and currency. Unknown or missing fields fail instead of being silently ignored.',
          'For AMOUNT-001, amountCentavos is the integer 40000, displayed as ARS 400.00. The request decoder requires an integral JSON number that fits a Java long; the purchase model then requires a positive amount and the literal currency ARS. A string such as "40000", a decimal, or an invalid calendar date is a contract error, not a reconciliation mismatch.',
          'The controller exposes the result: 201 means a new purchase was created, while 200 means an identical purchase already existed. 400 means the request itself was invalid. Keeping those meanings separate lets a client decide whether to fix its payload, continue, or investigate a conflict.',
        ],
        takeaway: { title: 'Request validation and business rules', text: 'HTTP decoding checks representation. The service applies business rules. The comparator never needs to know how JSON arrived.' },
        sourceIds: ['backend-purchase-controller', 'backend-purchase-decoder'],
        concepts: ['backend-contract', 'backend-controller', 'backend-centavos'],
      },
      {
        id: 'api-roles', title: 'Two callers, two roles', kicker: 'Authentication and authorization',
        lead: 'The generator may create purchases. Only the worker may publish a reconciliation report.',
        paragraphs: [
          'The public /api/v1 routes require the demo bearer token. The /internal/v1 routes require a different worker bearer token, including reads. DemoTokenFilter checks both configured values and then checks whether the recognized role is allowed on the route. Calling something internal is only a name; the filter supplies the access check.',
          'A missing or unknown credential gets 401. A valid demo token on a worker route gets 403: the caller was recognized, but its role is wrong. The browser request console uses named simulated roles so you can test this distinction without entering a real credential.',
          'Each application response gets a fresh X-Correlation-Id generated by the server. Error JSON includes that identifier with a stable code and safe message. A developer can investigate a failed request without exposing a request body, token, or stack trace to the caller.',
        ],
        takeaway: { title: 'Recognized does not mean permitted', text: 'Authentication identifies an accepted credential; authorization checks whether its role can perform this operation.' },
        sourceIds: ['backend-token-filter'],
        concepts: ['backend-bearer', 'backend-authorization', 'backend-correlation'],
      },
      {
        id: 'api-boundaries', title: 'Give each module a job', kicker: 'Read the repository as a map',
        lead: 'The Maven modules form useful boundaries before a single cloud service is involved.',
        paragraphs: [
          'reconciliation-api is the Spring Boot application. It owns MongoDB, purchase and business-date rules, run registration, and report persistence. reconciliation-worker is a separately packaged plain Java Lambda. It reads settlement bytes, validates CSV, runs the comparator, and talks to the API over HTTP; it does not reach directly into MongoDB.',
          'scenario-generator is an independent public client. GenerationWorkflow creates the date and purchases, closes the date, registers the CSV, uploads it, polls metadata, and verifies every result page against expected output. It owns its HTTP representations and imports neither the API nor the worker implementation. That makes it useful evidence that the public contract is sufficient.',
          'acceptance-tests is the fourth module. Its local harness runs the real API, MongoDB, worker handler, and generator with simulated AWS. This course adds browser models of those boundaries; it does not execute the Java runtime. Use the source links to move from an explanation to the actual owner of each behavior.',
        ],
        takeaway: { title: 'Follow responsibility, then deployment', text: 'The API stores and protects business state; the worker computes; the generator independently exercises the public system.' },
        sourceIds: ['backend-generator-workflow', 'backend-processing-service', 'backend-purchase-controller'],
        concepts: ['backend-maven', 'backend-adapter', 'backend-spring'],
      },
    ],
    mission: { title: 'Choose the right contract', brief: 'Use the request console to inspect successful calls, invalid bodies, and wrong-role requests.', goal: 'Explain which boundary owns each rejection and make a valid public request with the demo role.' },
    lab: {
      title: 'Trace a real Spring request',
      prerequisites: ['Java 21 and the repository Maven wrapper', 'Run from the repository root; use mvnw.cmd on Windows', 'Run Maven builds sequentially in a checkout'],
      commands: ['./mvnw clean verify -Punit-tests'],
      steps: ['Open PurchaseControllerTest and PurchaseRequestDecoderTest in reconciliation-api/src/test/java. Find the creation, unchanged replay, invalid-field, and numeric-type cases.', 'Follow the call from PurchaseController through PurchaseRequestDecoder into PurchaseService. Identify where HTTP representation stops and business logic starts.', 'Run the unit verification command. Inspect reconciliation-api/target/surefire-reports and target/site/jacoco before changing a contract.'],
      expected: 'Unit tests and coverage checks exercise the HTTP and business boundaries without Docker or AWS. This is an optional repository lab, not a command for starting an interactive backend.',
    },
  },
  {
    id: 'stability', number: '03', title: 'Make the input stable',
    summary: 'Make repeated requests and concurrent writes preserve one reproducible business day.',
    outcome: 'Explain immutable purchases, idempotent replay, and why closing a date makes retries safe.',
    duration: '30 min', lens: 'data',
    sourceIds: ['backend-purchase-service', 'backend-business-date', 'backend-run-service'],
    lessons: [
      {
        id: 'stability-identity', title: 'Replay is not a second purchase', kicker: 'Identity before insertion',
        lead: 'A lost HTTP response must not force the client to guess whether it created a purchase.',
        paragraphs: [
          'Imagine the client creates MATCH-001 for 10000 centavos and loses the response. It sends exactly the same request again. PurchaseService first looks up the globally unique transaction reference. If every business field matches, it returns the existing purchase with created=false, producing HTTP 200. The ledger still contains one purchase.',
          'Now replay MATCH-001 with 10001 centavos, a different merchant, or another date. The identity already belongs to different business fields, so the service returns 409. A currency other than ARS instead fails request validation with 400 before the service runs. A create never becomes an update. This immutability lets a future worker trust a closed ledger.',
          'The distinction also explains a subtle case: an identical replay after the day is CLOSED still returns the existing purchase. The service resolves that replay before asking to reserve a new slot. A genuinely new reference after closure takes the reservation path and fails.',
        ],
        takeaway: { title: 'Identical replay or conflict', text: 'An identical create is a safe replay. A changed create with the same identity is a conflict, not an edit.' },
        sourceIds: ['backend-purchase-service'],
        concepts: ['backend-idempotency', 'backend-immutable', 'backend-transaction'],
      },
      {
        id: 'stability-close', title: 'Close the race window', kicker: 'A state check needs a write',
        lead: 'Checking that a day is open is insufficient if another request can close it immediately afterward.',
        paragraphs: [
          'A new purchase is created inside a MongoDB transaction. Before insertion, BusinessDateService.reserve conditionally increments purchaseCount and revision on the day document, requiring state=OPEN and purchaseCount below 1000. Reserving capacity and inserting the purchase succeed together or roll back together.',
          'Closing the date conditionally changes that same document from OPEN to CLOSED and increments its revision. Because close and reserve write the same guard, a concurrent transaction cannot slip a late purchase through a stale read. The retry policy can retry a transient conflict and evaluate the now-current day state.',
          'Closing an already closed date returns the existing date. Reading reconciliation input requires CLOSED. The worker then reads immutable purchases for that date in reference order. The project does not copy a transaction snapshot into each run; stability comes from closure plus immutable records.',
        ],
        takeaway: { title: 'A shared guard for closure and purchase writes', text: 'The shared guard document forces close and new-purchase reservation to coordinate instead of trusting a separate check.' },
        sourceIds: ['backend-business-date', 'backend-purchase-service'],
        concepts: ['backend-transaction', 'backend-guard', 'backend-mongodb'],
      },
      {
        id: 'stability-reproducible', title: 'Name the input you will retry', kicker: 'From a day to a run',
        lead: 'A reliable retry means running the same rules against the same internal and external input.',
        paragraphs: [
          'The closed business date identifies stable internal purchases. The SHA-256 digest identifies the exact settlement bytes. The rules version identifies the comparison semantics. Together with source=SIMULATED, these form the logical run identity. Re-registering that identity finds the existing run instead of creating another independent report.',
          'The registration also records byteLength. Reusing a logical identity with a different declared length conflicts. Once processing binds a versioned S3 object, object identity adds bucket, key, and versionId to the checksum. A replacement upload cannot quietly become the input of the already-bound run.',
          'For the canonical day, this means AMOUNT-001 remains an internal 40000-centavo purchase while its selected settlement evidence remains 45000. A retry is allowed to recover from transport trouble. It is not allowed to reinterpret which purchase, file version, or rules were used.',
        ],
        takeaway: { title: 'Stable input for every retry', text: 'Closed immutable purchases, exact settlement bytes, and a rules version give a retry a stable meaning.' },
        sourceIds: ['backend-run-service', 'backend-run-repository'],
        concepts: ['backend-sha256', 'backend-idempotency', 'backend-object-version'],
      },
    ],
    mission: { title: 'Close the day safely', brief: 'Create, repeat, conflict, close, and attempt a late write in the state simulator.', goal: 'Keep one immutable purchase, close the day, and distinguish a safe replay from a forbidden new write.' },
    lab: {
      title: 'Prove the closure boundary',
      prerequisites: ['Java 21 and the Maven wrapper', 'Docker accessible from the same environment for the optional acceptance command', 'Run commands sequentially from the repository root'],
      commands: ['./mvnw clean verify -Punit-tests', './mvnw clean verify -Pacceptance-tests'],
      steps: ['Read PurchaseServiceTest and BusinessDateServiceTest. Find identical replay, conflicting replay, closed-day, and capacity cases.', 'Read acceptance-tests/src/test/java/org/octavio/paymentreconciliationsim/acceptance/PurchaseConcurrencyIT.java to see how real concurrent requests exercise the guard.', 'Run unit verification first. If Docker is available, run acceptance verification; the harness supplies its own MongoDB replica set and simulated AWS.'],
      expected: 'Identical replay leaves one immutable purchase. New writes cannot cross closure or the purchase limit. Acceptance tests check the real transaction boundary; the browser simulator illustrates the invariant.',
    },
  },
  {
    id: 'upload', number: '04', title: 'Cross the cloud boundary',
    summary: 'Register intent, upload exact bytes, and bind the version that a worker may process.',
    outcome: 'Explain why metadata, file contents, and object version are separate parts of the upload contract.',
    duration: '30 min', lens: 'system',
    sourceIds: ['backend-run-service', 'backend-upload-storage', 'backend-processing-service'],
    lessons: [
      {
        id: 'upload-register', title: 'Register before sending bytes', kicker: 'The API records intent',
        lead: 'A reconciliation run starts with a small metadata request, not a CSV sent through Spring.',
        paragraphs: [
          'The client computes the file SHA-256 and byte length, then registers a reconciliation run. RunService accepts a lowercase 64-character hexadecimal digest and 1 through 2097152 bytes. The business date must already be CLOSED and strictly before today in America/Buenos_Aires. These checks make the intended input explicit before storage is involved.',
          'A new registration creates AWAITING_UPLOAD with source=SIMULATED, rulesVersion=v1, and a key shaped like settlements/YYYY-MM-DD/runId.csv. A repeated logical identity returns the same run. HTTP 201 versus 200 describes creation versus replay; neither means reconciliation is finished.',
          'An incomplete registration includes upload instructions. A completed replay returns no new upload instructions because the report already exists. A checksum identifies bytes, including whitespace and line endings: editing an apparently cosmetic newline creates different bytes and therefore a different digest.',
        ],
        takeaway: { title: 'Registered input metadata', text: 'Registration records what should be processed. Upload, processing, and completed publication are later events.' },
        sourceIds: ['backend-run-service'],
        concepts: ['backend-sha256', 'backend-object-storage', 'backend-idempotency'],
      },
      {
        id: 'upload-direct', title: 'Upload through a narrow capability', kicker: 'The file goes straight to S3',
        lead: 'The API grants a temporary, constrained upload instead of forwarding every file byte.',
        paragraphs: [
          'S3SettlementStorage signs a PUT request for the registered object key. It binds the expected content length and SHA-256 checksum, and the signature lasts 600 seconds. The client receives the URL, expiration, and required headers. The x-amz-checksum-sha256 value uses Base64 bytes; the registration digest uses hexadecimal text.',
          'The client uploads the exact CSV directly to S3. It must preserve the required content-length and checksum headers. The generator does this through its HTTP client. In this learning site, the upload workbench models that contract locally; it does not make a signed request or store a real object in AWS.',
          'This separates the control path from the byte-transfer path: the API registers the intended file, while object storage receives it. It also introduces independent failures. Registration can succeed and an upload can fail or never happen. A successful upload still does not prove that the worker published a report.',
        ],
        takeaway: { title: 'Follow control and data separately', text: 'The API coordinates the run. The client sends bytes to S3. The worker later computes and publishes the result.' },
        sourceIds: ['backend-upload-storage', 'backend-generator-workflow'],
        concepts: ['backend-presigned', 'backend-sha256', 'backend-object-storage'],
      },
      {
        id: 'upload-version', title: 'Bind one original version', kicker: 'Names are not immutable content',
        lead: 'A bucket and key name an object location. A version ID identifies a particular retained upload.',
        paragraphs: [
          'An S3 ObjectCreated event supplies bucket, key, and versionId. The worker fetches run metadata, verifies that the object belongs to the registered run, reads the expected version, and verifies the actual byte length and checksum. Only then does it mark processing with that object identity.',
          'Once a run has bound an original version, later attempts must use that identity. A new upload at the same key is another version, not a replacement permission. This matters even if a retry arrives after a user overwrites the object location: the outcome must still explain the original evidence.',
          'Recovery checks the retained bound version when one exists. Before any version is bound, it scans retained versions and deterministically chooses the oldest matching input. If the bound original is unavailable, a later upload cannot substitute for it. The recovery module will let you explore that failure explicitly.',
        ],
        takeaway: { title: 'A retry needs an original', text: 'Version binding preserves the relationship between a report and the exact external evidence that produced it.' },
        sourceIds: ['backend-upload-storage', 'backend-processing-service', 'backend-run-repository'],
        concepts: ['backend-object-version', 'backend-event', 'backend-sha256'],
      },
    ],
    mission: { title: 'Deliver the registered input', brief: 'Register the sample, try a changed payload, upload valid bytes, and inspect version binding.', goal: 'Explain why a matching key is insufficient and why a later version cannot replace the bound original.' },
    lab: {
      title: 'Inspect the upload contract offline',
      prerequisites: ['Java 21 and the Maven wrapper', 'Repository source and its unit-test dependencies', 'No AWS account or real bearer token is needed for these unit tests'],
      commands: ['./mvnw clean verify -Punit-tests'],
      steps: ['Read S3SettlementStorageTest for signed upload headers, byte bounds, retained versions, and original-version recovery.', 'Read RunServiceTest for registration replay, closed dates, prior-day validation, and changed length. Compare each assertion with the browser model.', 'Run unit verification and inspect the storage test reports. For real authorization, expiry, and delivery evidence, follow docs/demo-evidence.md separately; offline tests do not establish cloud behavior.'],
      expected: 'Offline tests validate request construction and identity decisions. Actual S3 authorization and event-delivery evidence remain a separate cloud exercise in this repository.',
    },
  },
  {
    id: 'worker', number: '05', title: 'Build the worker',
    summary: 'Combine strict ingestion, pure rules, and one complete report behind a Lambda handler.',
    outcome: 'Trace the worker pipeline and explain why invalid input cannot publish a partial report.',
    duration: '35 min', lens: 'code',
    sourceIds: ['backend-worker-handler', 'backend-processing-service', 'backend-csv-parser', 'backend-comparator', 'backend-report-validator', 'backend-run-repository'],
    lessons: [
      {
        id: 'worker-ingestion', title: 'Validate the entire CSV', kicker: 'Validate the whole file',
        lead: 'A malformed final row invalidates the input even if every earlier row looked usable.',
        paragraphs: [
          'SettlementCsvParser bounds actual bytes at 2097152 and data rows at 2000. It decodes UTF-8 strictly, then uses RFC 4180 CSV parsing with no trimming or ignored empty lines. The header must be exactly business_date,transaction_reference,amount_centavos,currency, in that order. Row numbers count data records, beginning at one after the header.',
          'Each row must contain four fields, the registered date exactly, a reference matching [A-Za-z0-9_-]{1,64}, a positive integer amount that fits a long, and ARS. A space-padded reference, zero amount, different date, or invalid currency fails at ingestion. Duplicate references are allowed through this boundary because they are valid evidence for the DUPLICATE business outcome.',
          'The parser builds rows privately and returns an immutable list only after the entire file passes. ProcessingService calls compare only after parse returns. This prevents a report that silently includes the first four rows and drops an invalid fifth row.',
        ],
        takeaway: { title: 'Invalid input is not an outcome', text: 'CSV validity decides whether comparison may run. Duplicate and mismatch are classifications of valid evidence.' },
        sourceIds: ['backend-csv-parser', 'backend-processing-service'],
        concepts: ['backend-csv', 'backend-boundary', 'backend-centavos'],
      },
      {
        id: 'worker-orchestration', title: 'Separate comparison from I/O', kicker: 'Adapters around a pure core',
        lead: 'The Lambda handler coordinates delivery. The comparator only decides what the evidence means.',
        paragraphs: [
          'ReconciliationHandler is a plain Java Lambda entry point. It parses each invocation record, attaches an attempt ID, and hands valid references to ProcessingService. The service fetches metadata, verifies versioned bytes, binds processing identity, fetches the closed internal input, checks identity again, parses the complete CSV, compares, and publishes.',
          'In the middle, ReconciliationComparator has no HTTP, S3, MongoDB, or Spring calls. It groups settlement rows by reference, takes the union with internal references, and sorts results and evidence deterministically. For DUP-001, two rows mean DUPLICATE before amounts are compared. For AMOUNT-001, one external 45000-centavo row versus internal 40000 means AMOUNT_MISMATCH.',
          'The worker never writes MongoDB directly. It submits a complete report to the internal API using the worker role. That keeps storage and business-state ownership in the API while allowing the comparison core to stay small enough for direct unit tests.',
        ],
        takeaway: { title: 'Separate decisions from transport', text: 'Adapters obtain trusted inputs and publish results. A pure comparator makes the business decision reproducible and testable.' },
        sourceIds: ['backend-worker-handler', 'backend-processing-service', 'backend-comparator'],
        concepts: ['backend-lambda', 'backend-pure', 'backend-adapter'],
      },
      {
        id: 'worker-publication', title: 'Publish all of the evidence', kicker: 'One report, one visible transition',
        lead: 'A caller should observe either an incomplete run or a complete report, never half a report.',
        paragraphs: [
          'The API validates the submission against the run identity and closed purchases. ReportValidator checks unique references, correct internal fields, compatible evidence cardinality, unique contiguous row numbers, and summary counts. It validates the boundary without recalculating MATCHED versus AMOUNT_MISMATCH; the worker owns that classification.',
          'RunRepository stores the bounded report inside the run document and sets status=COMPLETED in the same conditional update. Both the received report body and the serialized run document have independent 8 MiB limits. Results become queryable only after completion, with reference ordering, optional outcome filtering, and pages capped at 100 results.',
          'An identical completed report may be replayed safely. A conflicting report cannot overwrite it, and a late failure callback cannot downgrade a COMPLETED run. Invalid CSV is reported as a non-retriable input failure; transport trouble preserves a retry signal. The next module explores how those choices shape recovery.',
        ],
        takeaway: { title: 'Completion is a durable boundary', text: 'Publishing the entire report and COMPLETED together makes the visible result atomic and protects it from later attempts.' },
        sourceIds: ['backend-report-validator', 'backend-run-repository', 'backend-run-service', 'backend-processing-service'],
        concepts: ['backend-atomic', 'backend-pagination', 'backend-retry'],
      },
    ],
    mission: { title: 'Protect the publication boundary', brief: 'Inject a CSV defect, run validation, repair the input, and follow comparison through publication.', goal: 'Demonstrate that one invalid row blocks the whole report, while valid duplicate rows produce a DUPLICATE result.' },
    lab: {
      title: 'Test the pure core and its boundary',
      prerequisites: ['Java 21 and the Maven wrapper', 'Run from the repository root', 'No Docker or cloud account is needed for unit verification'],
      commands: ['./mvnw clean verify -Punit-tests'],
      steps: ['Inspect SettlementCsvParserTest for malformed UTF-8, wrong header, overflow, and late-row rejection. Find the cases that distinguish invalid input from duplicate evidence.', 'Open ReconciliationComparatorTest and trace the duplicate-precedence case through the real comparator. Then inspect ProcessingServiceTest to see where publication is blocked.', 'Run unit verification. As an extension, add a parser or comparator boundary case in a local branch and confirm it fails for the intended reason before changing production code.'],
      expected: 'The parser returns no partial success. The comparator classifies validated evidence. The orchestration tests prove that only a complete valid report crosses the API boundary.',
    },
  },
];

export const backendGlossary = [
  { id: 'backend-contract', term: 'HTTP contract', definition: 'The accepted route, method, headers, body, response, and error meanings a caller can rely on. Here JSON shape is validated explicitly at the API boundary.', moduleId: 'api' },
  { id: 'backend-controller', term: 'Spring controller', definition: 'A Spring component that maps an HTTP request to application behavior. PurchaseController decodes the request, delegates to PurchaseService, and chooses 201 or 200.', moduleId: 'api' },
  { id: 'backend-centavos', term: 'Integer centavos', definition: 'The smallest ARS unit stored as a positive Java long. 40000 centavos is displayed as ARS 400.00; comparison uses integers, not floating-point currency.', moduleId: 'api' },
  { id: 'backend-bearer', term: 'Bearer token', definition: 'A credential sent in the Authorization header. Possession grants the configured role; this demo separates public and worker tokens and uses simulated role labels in the browser.', moduleId: 'api' },
  { id: 'backend-authorization', term: 'Authorization', definition: 'The decision that an authenticated role may perform an operation. A known token for the wrong route family receives 403 in this API.', moduleId: 'api' },
  { id: 'backend-correlation', term: 'Correlation ID', definition: 'A server-generated identifier connecting an HTTP response to diagnostic evidence. This API returns a fresh UUID in X-Correlation-Id and error JSON.', moduleId: 'api' },
  { id: 'backend-maven', term: 'Maven reactor', definition: 'The root build coordinates the API, worker, generator, and acceptance-test modules. Each production module has a distinct responsibility and packaged artifact.', moduleId: 'api' },
  { id: 'backend-spring', term: 'Spring Boot', definition: 'The framework that assembles and runs the API with HTTP routing, configured services, and MongoDB integration. The worker and generator are separately packaged plain Java programs.', moduleId: 'api' },
  { id: 'backend-adapter', term: 'Adapter', definition: 'Code that translates an external system into the values an application needs. The worker uses S3 and HTTP adapters around a comparator with no network dependencies.', moduleId: 'worker' },
  { id: 'backend-idempotency', term: 'Idempotency', definition: 'Repeating the same intention preserves the same business effect. Identical purchase creation returns the existing purchase, and a logical run replay reuses its identity.', moduleId: 'stability' },
  { id: 'backend-immutable', term: 'Immutable purchase', definition: 'A stored business fact that cannot be changed by replaying a create request. Reusing its reference with different business fields is a conflict.', moduleId: 'stability' },
  { id: 'backend-transaction', term: 'Database transaction', definition: 'A boundary that commits related writes together or rolls them back. New-purchase reservation and purchase insertion share a MongoDB transaction in this project.', moduleId: 'stability' },
  { id: 'backend-guard', term: 'Guard document', definition: 'A shared database record whose conditional update protects an invariant. Purchase reservation and date closure contend on the business-day document.', moduleId: 'stability' },
  { id: 'backend-mongodb', term: 'MongoDB', definition: 'The document database owned by the API. It stores immutable purchases, business-day state, and bounded run documents with embedded reports; the worker accesses them through HTTP.', moduleId: 'stability' },
  { id: 'backend-sha256', term: 'SHA-256', definition: 'A digest computed from exact bytes. The run stores 64 lowercase hexadecimal characters; the upload checksum header carries the same digest bytes encoded in Base64. It is not encryption.', moduleId: 'upload' },
  { id: 'backend-object-storage', term: 'Object storage', definition: 'Storage addressed by a bucket and key rather than a database query. S3 holds settlement CSV bytes while the API retains business metadata and reports.', moduleId: 'upload' },
  { id: 'backend-presigned', term: 'Presigned URL', definition: 'A temporary signed capability for a particular storage request. This project signs a PUT for 600 seconds and binds its object key, checksum, and content length.', moduleId: 'upload' },
  { id: 'backend-object-version', term: 'Object version', definition: 'A retained identity for one upload at a storage key. A run binds the original S3 version so an overwrite cannot change its reconciliation evidence.', moduleId: 'upload' },
  { id: 'backend-event', term: 'Event', definition: 'A message that something happened. Here an S3 ObjectCreated notification identifies uploaded bucket, key, and version; it is a trigger, not proof that processing completed.', moduleId: 'upload' },
  { id: 'backend-csv', term: 'CSV ingestion', definition: 'Turning external CSV bytes into validated domain rows. The worker applies strict UTF-8, RFC 4180 syntax, exact fields, bounded size, and business-value checks before comparison.', moduleId: 'worker' },
  { id: 'backend-boundary', term: 'Trust boundary', definition: 'A point where data from another component must be checked before it is trusted. JSON decoding, versioned byte verification, CSV parsing, and report validation are separate boundaries.', moduleId: 'worker' },
  { id: 'backend-lambda', term: 'AWS Lambda', definition: 'An event-invoked compute service. The worker exposes a Java RequestHandler entry point, processes the supplied object references, and returns or throws to signal invocation success or failure.', moduleId: 'worker' },
  { id: 'backend-pure', term: 'Pure comparison', definition: 'A calculation that produces results from its input values without network or database work. ReconciliationComparator groups evidence and applies deterministic classification rules.', moduleId: 'worker' },
  { id: 'backend-atomic', term: 'Atomic publication', definition: 'Making the complete report and COMPLETED status visible in one database update. Readers cannot observe a partly inserted report through the results endpoint.', moduleId: 'worker' },
  { id: 'backend-pagination', term: 'Pagination', definition: 'Retrieving a bounded slice of a result set. The API uses zero-based pages, optional outcome filtering, reference ordering, and a maximum page size of 100.', moduleId: 'worker' },
  { id: 'backend-retry', term: 'Retry', definition: 'Another attempt to complete the same intended operation after failure. Stable input identity and idempotent publication make retries safe; invalid input still needs correction.', moduleId: 'worker' },
];
