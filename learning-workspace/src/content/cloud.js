import network from '../reference/library/cloud/network.tf.txt?raw'
import iam from '../reference/library/cloud/iam.tf.txt?raw'
import compute from '../reference/library/cloud/compute.tf.txt?raw'
import tokenFilter from '../reference/library/cloud/DemoTokenFilter.java.txt?raw'
import recovery from '../reference/library/cloud/RecoveryService.java.txt?raw'
import repository from '../reference/library/cloud/RunRepository.java.txt?raw'
import processing from '../reference/library/cloud/ProcessingService.java.txt?raw'
import generator from '../reference/library/cloud/GenerationWorkflow.java.txt?raw'
import testing from '../reference/library/cloud/testing.md.txt?raw'
import deployment from '../reference/library/cloud/deployment.md.txt?raw'

export const cloudSources = [
  { id: 'cloud-network', title: 'Routes and security groups', path: 'infra/network.tf', group: 'Infrastructure', note: 'The worker has a private API path and an S3 Gateway endpoint. Its subnet has no default internet route.', focus: [40, 90], content: network },
  { id: 'cloud-iam', title: 'Two AWS roles, different permissions', path: 'infra/iam.tf', group: 'Infrastructure', note: 'The API may upload settlements and invoke the worker. The worker may read settlement versions and its exact config object.', focus: [44, 78], content: iam },
  { id: 'cloud-compute', title: 'Bounded compute and asynchronous retries', path: 'infra/compute.tf', group: 'Infrastructure', note: 'Java 21 Lambda uses 512 MiB, a 300-second timeout, concurrency 1, and a one-hour asynchronous event age.', focus: [62, 90], content: compute },
  { id: 'cloud-token-filter', title: 'Public and worker bearer roles', path: 'reconciliation-api/src/main/java/org/octavio/paymentreconciliationsim/security/DemoTokenFilter.java', group: 'API', note: 'Unknown credentials return 401; a valid token belonging to the wrong role returns 403. Configured tokens must be distinct.', focus: [41, 61], content: tokenFilter },
  { id: 'cloud-recovery', title: 'Recover the retained original', path: 'reconciliation-api/src/main/java/org/octavio/paymentreconciliationsim/run/RecoveryService.java', group: 'API', note: 'Verification and atomic binding precede asynchronous invocation. Recovery cannot replace a missing bound original.', focus: [29, 52], content: recovery },
  { id: 'cloud-publication', title: 'Atomic publication and completion protection', path: 'reconciliation-api/src/main/java/org/octavio/paymentreconciliationsim/run/RunRepository.java', group: 'API', note: 'Mongo findAndModify predicates prevent conflicting identity binding and protect a completed report from later failures.', focus: [29, 42], content: repository },
  { id: 'cloud-processing', title: 'The worker failure boundary', path: 'reconciliation-worker/src/main/java/org/octavio/paymentreconciliationsim/worker/ProcessingService.java', group: 'Worker', note: 'The worker validates the full input before publishing. Transport failures remain failed invocations even if their diagnostic callback also fails.', focus: [47, 95], content: processing },
  { id: 'cloud-generator', title: 'A client verifies the whole result', path: 'scenario-generator/src/main/java/org/octavio/paymentreconciliationsim/generator/GenerationWorkflow.java', group: 'Generator', note: 'The generator uses the public client, waits for completion, retrieves the report and compares it with an expected report.', focus: [8, 35], content: generator },
  { id: 'cloud-testing', title: 'Verification modes and evidence boundaries', path: 'docs/testing.md', group: 'Evidence', note: 'Local Java, HTTP and Mongo checks establish different claims from actual AWS delivery. The retained checkpoint is historical evidence, not a fresh course run.', focus: [3, 56], content: testing },
  { id: 'cloud-deployment', title: 'Deployment rehearsal and pending cloud evidence', path: 'docs/deployment.md', group: 'Evidence', note: 'The deployment definition is source-only. Account checks, Terraform validation, actual cloud delivery, Atlas startup and teardown remain pending in the project documentation.', focus: [3, 35], content: deployment },
]

export const cloudModules = [
  {
    id: 'security', number: '06', title: 'Connect the system safely',
    summary: 'Trace the allowed paths, separate AWS permissions from API roles, and put each secret with its owner.',
    outcome: 'Explain why a request needs a network path, the right service permission and the correct application credential.',
    duration: '25 min', lens: 'system',
    sourceIds: ['cloud-network', 'cloud-iam', 'cloud-token-filter', 'cloud-deployment'],
    lessons: [
      {
        id: 'security-network', title: 'Give every connection a reason', kicker: '06.1 · Network boundaries',
        lead: 'A cloud diagram becomes useful when every arrow explains who can reach whom, over which path.',
        paragraphs: [
          'The API runs on EC2 in the public subnet. The Lambda worker runs in a separate private subnet. A subnet is a range of addresses; its route table decides where traffic can go. The worker subnet has no default route to the internet. It reads settlement objects through the S3 Gateway endpoint and calls the API using the instance’s private address.',
          'Security groups narrow those routes further. The API accepts port 8080 only from the worker group. The worker can send to that API port and to the regional S3 service on 443. The developer reaches the API through a loopback-bound SSH tunnel. Only the API connects to MongoDB Atlas; there is no worker-to-database edge.',
          'In Explorer, follow the selected reference from its S3 input through the worker to the API. That path crosses several boundaries but keeps the same evidence. The source uses private HTTP for worker-to-API traffic; private addressing alone does not mean encryption. This diagram describes the Terraform design. The browser does not test an actual AWS network.',
        ],
        takeaway: { title: 'An arrow needs a route and a rule', text: 'A reachable service still needs permission. Network access is one layer, not a complete authorization decision.' },
        sourceIds: ['cloud-network', 'cloud-compute'], concepts: ['cloud-vpc', 'cloud-security-group', 'cloud-gateway-endpoint'],
      },
      {
        id: 'security-permissions', title: 'Separate network, IAM and API roles', kicker: '06.2 · Authorization layers',
        lead: 'Three different checks can stop the same request. Learn to diagnose the layer that refused it.',
        paragraphs: [
          'IAM governs AWS service actions. The API role may upload and read settlement objects, list their versions, and invoke this worker. The worker role may read settlement versions and its exact runtime-config object. Giving the worker a route to S3 does not grant permission to write a file. Likewise, a broad network path should not become a broad IAM policy.',
          'The Java API uses a separate application boundary. Public /api/v1 routes require the demo bearer token. Internal /internal/v1 routes require the worker token, including reads. A missing or unknown token returns 401. A recognized token used for the other role returns 403. The tokens must differ, and the filter compares their bytes using MessageDigest.isEqual.',
          'Try the connection tester with a working route but the wrong token, then with the right token but a blocked route. These are different failures with different remedies. The generator has the public demo role; it cannot publish reports through the worker endpoints. IAM credentials do not replace these application tokens.',
        ],
        takeaway: { title: 'Ask which boundary rejected the request', text: 'Routing and security groups control reachability; IAM controls AWS actions; bearer roles control the Java API.' },
        sourceIds: ['cloud-iam', 'cloud-token-filter', 'cloud-network'], concepts: ['cloud-iam-role', 'cloud-bearer-role', 'cloud-least-privilege'],
      },
      {
        id: 'security-runtime', title: 'Deliver configuration without spreading secrets', kicker: '06.3 · Runtime ownership',
        lead: 'A worker that only compares files should not inherit the API’s database credentials.',
        paragraphs: [
          'The deployment flow prepares a protected runtime file outside source control. The API receives its Mongo URI and the two distinct tokens through a root-owned 0600 environment file. The worker receives only its own token, stored in the private S3 runtime-config/worker.json object. Its Lambda environment contains the API location and config object location, not the secret value.',
          'Terraform defines infrastructure but does not carry runtime secrets in variables, plans, state, user data or object resources. The deployment script transfers configuration separately and checks the API artifact’s SHA-256. Requesting a systemd restart is still not proof that Atlas connectivity and schema initialization succeeded; startup must be observed.',
          'A warm worker loads its token when the handler is constructed. Updating the S3 config object alone does not refresh every existing handler. The documented rotation sequence pauses producers, installs matching API and worker config, replaces the worker through a reviewed plan, and then resumes. This is a coordinated change, not a transactional rollback across EC2 and S3.',
        ],
        takeaway: { title: 'Give components only the secrets they use', text: 'The generator owns a demo token; the worker owns a worker token; only the API owns Mongo access. Secret rotation also has a lifecycle.' },
        sourceIds: ['cloud-deployment', 'cloud-iam', 'cloud-token-filter'], concepts: ['cloud-runtime-secret', 'cloud-least-privilege'],
      },
    ],
    mission: {
      title: 'Open only the necessary path',
      brief: 'Diagnose requests across the worker, S3, API and database. Change network reachability, AWS permission and bearer role independently.',
      goal: 'Make the worker’s required reads and API calls succeed while keeping worker uploads, wrong-role API calls and worker-to-Atlas access blocked.',
    },
    lab: {
      title: 'Trace the authorization boundary locally',
      prerequisites: ['Java 21 and the repository Maven wrapper.', 'Run commands from the repository root. This lab uses local tests and requires no AWS account or real tokens.'],
      commands: ['./mvnw clean verify -Punit-tests'],
      steps: [
        'Read DemoTokenFilterTest and identify one 401 case and one 403 case. Follow both into DemoTokenFilter.',
        'Add a focused wrong-role case for a public or internal route using test-only token strings. Run the unit verification command.',
        'Trace a worker S3 read through network.tf and iam.tf. Write down the required route, security-group rule and IAM action separately.',
        'Compare the runtime ownership table in docs/deployment.md with your route map. Keep real secrets out of source and test fixtures.',
      ],
      expected: 'The unit suite and coverage gates pass, and your explanation distinguishes a route failure, an AWS permission failure, an unknown token and a valid token in the wrong role.',
    },
  },
  {
    id: 'recovery', number: '07', title: 'Design for failure',
    summary: 'Break a connection, recover the original input and replay a completed event without changing its report.',
    outcome: 'Diagnose a failed attempt and recover safely using immutable input identity and atomic publication.',
    duration: '25 min', lens: 'system',
    sourceIds: ['cloud-processing', 'cloud-recovery', 'cloud-publication', 'cloud-compute'],
    lessons: [
      {
        id: 'recovery-attempts', title: 'Separate bad input from a failed attempt', kicker: '07.1 · Failure semantics',
        lead: 'A malformed settlement and an unreachable API both interrupt progress, but retrying them means different things.',
        paragraphs: [
          'The worker first verifies the registered input, reads the exact object version, obtains the closed purchase set, parses the entire CSV, and only then compares and publishes. A deterministic validation failure records a nonretryable failure. No partial report is published, even if many valid rows appeared before the invalid row.',
          'A temporary API, S3 or transport failure is different. The worker attempts to record a retryable failure and throws so the invocation remains failed. If the failure callback is also unavailable, it preserves the original failure rather than pretending success. That leaves room for a later retry or an operator recovery after connectivity returns.',
          'The Terraform configuration limits function-error retries to two and asynchronous event age to one hour. A browser replay illustrates the behavior; it is not an AWS timing guarantee. A noncompleted run with no update for more than 20 minutes is marked stale by the API. Stale is an observation, not cancellation, proof of worker death or an automatic recovery schedule.',
        ],
        takeaway: { title: 'Retry the attempt, preserve the evidence', text: 'Temporary transport failure may recover with the same input. Deterministically invalid input must not become a partial successful report.' },
        sourceIds: ['cloud-processing', 'cloud-compute', 'cloud-deployment'], concepts: ['cloud-retryable-failure', 'cloud-stale-run', 'cloud-attempt'],
      },
      {
        id: 'recovery-original', title: 'Recover the original, not the newest file', kicker: '07.2 · Immutable input',
        lead: 'A run identifies one input. Recovery must not quietly reinterpret that run against a replacement.',
        paragraphs: [
          'Recovery starts from the run’s registered checksum, byte length and object key. If an object version is already bound, recovery verifies that exact version. If nothing has been bound yet, the storage implementation searches retained versions and deterministically chooses the oldest matching bytes. The checksum matters because a filename alone does not identify the contents.',
          'RecoveryService binds the verified object atomically before invoking Lambda. This order prevents concurrent recoveries from choosing different originals. The worker receives the run ID, bucket, key and version ID. A completed run is a no-op; a failed or stale run can return to processing after the original has been verified.',
          'If no matching upload exists for an unbound run, the API reports UPLOAD_NEEDED. If a previously bound original is unavailable, it reports ORIGINAL_UNAVAILABLE and forbids replacement. In the mission, restoring connectivity is helpful; selecting the newest different version is not. Repeating computation is safe only when you know which evidence it repeats.',
        ],
        takeaway: { title: 'Identity survives failure', text: 'Keep the original object version and registered checksum attached to the run. Recovery reuses that identity rather than selecting a convenient replacement.' },
        sourceIds: ['cloud-recovery', 'cloud-processing', 'cloud-publication'], concepts: ['cloud-object-identity', 'cloud-idempotency'],
      },
      {
        id: 'recovery-publication', title: 'Replay without rewriting completion', kicker: '07.3 · Idempotent effects',
        lead: 'The worker can do the same work again. The system must still have one stable completed report.',
        paragraphs: [
          'Imagine the API commits the report, but its response never reaches the worker. The worker cannot infer whether publication succeeded from that lost response. A later delivery may read and compare the same input again. The service canonicalizes the report so an identical completed publication is a no-op, while a conflicting report is rejected.',
          'The repository enforces publication with one Mongo findAndModify operation. Its predicate requires the matching object identity and a status other than COMPLETED. Failure updates also exclude completed runs. These atomic conditions protect the result when attempts overlap or a delayed failure arrives after success. Reserved Lambda concurrency one is a capacity limit, not the mechanism that makes this correct.',
          'Do not confuse delivery duplicates with settlement duplicates. DUP-001 has two rows in the business input and produces the DUPLICATE classification. Replaying an S3 event is an infrastructure event; it must not add another result or change the five-result report. The contract is repeatable work with protected effects, not a promise that execution happens exactly once.',
        ],
        takeaway: { title: 'Completed means stable', text: 'An identical replay preserves the report; a conflicting publication fails; a late attempt failure cannot downgrade completion.' },
        sourceIds: ['cloud-publication', 'cloud-processing', 'cloud-recovery'], concepts: ['cloud-idempotency', 'cloud-atomic-publication', 'cloud-attempt'],
      },
    ],
    mission: {
      title: 'Recover a run without changing its meaning',
      brief: 'Interrupt the worker’s API connection, inspect the failure, restore access and select the retained original. Then replay the completed delivery.',
      goal: 'Reach COMPLETED with the original input, preserve all five canonical results, and prove that a second delivery does not alter the report.',
    },
    lab: {
      title: 'Reproduce recovery with the local harness',
      prerequisites: ['Java 21, the repository Maven wrapper and Docker accessible from the same environment.', 'The harness starts MongoDB 8.0.14 as a replica set, real Spring HTTP and a version-aware S3 stub. No AWS credentials are needed.'],
      commands: ['./mvnw clean verify -Pacceptance-tests'],
      steps: [
        'Read WorkerRetryIT and RecoveryIT. Identify where each test creates an outage, retains the object version and restores access.',
        'Run acceptance verification. Inspect the Failsafe and Cucumber reports rather than assuming packaging proves the scenarios ran.',
        'Follow publicationReplayCannotDowngradeCompletedRun in RunPublicationIT to the repository status predicate.',
        'Add an assertion to an appropriate recovery case that the original object identity and complete report remain unchanged after replay. Rerun acceptance verification.',
      ],
      expected: 'Local acceptance verifies retry and recovery against real API/Mongo behavior and the S3 stub. It does not establish real AWS asynchronous scheduling or deployed network permissions.',
    },
  },
  {
    id: 'capstone', number: '08', title: 'Own the whole system',
    summary: 'Run the complete scenario, distinguish evidence from assumptions, rehearse deployment and design a justified extension.',
    outcome: 'Explain, verify and extend the project while preserving its data and recovery contracts.',
    duration: '30 min', lens: 'system',
    sourceIds: ['cloud-generator', 'cloud-testing', 'cloud-deployment', 'cloud-publication'],
    lessons: [
      {
        id: 'capstone-evidence', title: 'Verify from outside the implementation', kicker: '08.1 · Independent evidence',
        lead: 'A green worker log is useful, but it is not a complete correctness check.',
        paragraphs: [
          'The scenario generator behaves like an external client. It creates purchases, closes the business date, registers the settlement checksum and length, uploads through the returned instructions, and polls the public API. Once the run is completed, it retrieves the report and compares it with expected results. A completed identity replay skips the upload because no new object version is necessary.',
          'The canonical expected report is committed independently of the worker’s comparator. Verification checks all results and their evidence, not just a total of five or a COMPLETED status. That matters for the selected reference: an amount mismatch with the wrong amounts, or a duplicate missing one settlement row, could still produce a plausible summary.',
          'Use different evidence for different claims. Unit tests exercise logic; acceptance tests use real Java HTTP, Mongo and the worker with an S3 stub; this course models behavior in a browser. The repository documents a retained local verification checkpoint. Actual AWS signatures, delivery, private routing, cold/warm timings, account eligibility and teardown remain pending cloud observations.',
        ],
        takeaway: { title: 'A claim needs matching evidence', text: 'Compare the complete report against independent expectations, and never relabel a browser model or a local stub as an observed cloud deployment.' },
        sourceIds: ['cloud-generator', 'cloud-testing', 'cloud-deployment'], concepts: ['cloud-independent-oracle', 'cloud-evidence-boundary'],
      },
      {
        id: 'capstone-deployment', title: 'Rehearse the complete lifecycle', kicker: '08.2 · Deployment and operations',
        lead: 'A deployment is a sequence of verified handoffs, including the handoff back to an empty environment.',
        paragraphs: [
          'Begin with local verified artifacts and the deployment prerequisites. The documented cloud design requires account-specific eligibility, current credits and service access, an appropriate concurrency quota, and an Atlas M0 database with scoped roles. Source code cannot establish that a real account qualifies or that a deployment costs nothing. Review an actual Terraform plan only after those checks exist.',
          'Terraform provisions the network, roles, versioned bucket, EC2 and Lambda. Bootstrap installs Java and the service definition, but it contains neither application secrets nor the API JAR. Artifact and protected runtime configuration are delivered separately. The operator then verifies Atlas startup, the SSH tunnel, real upload enforcement, notification delivery, a completed canonical run, and failure recovery.',
          'Teardown belongs to the same lifecycle. The documented flow stops new work, accounts for asynchronous delivery, removes retained S3 object versions and runtime config, destroys the managed resources, checks independently for leftovers, and removes the dedicated Atlas access. The course rehearsal performs none of these cloud operations. Its purpose is to teach the order and the evidence required at each boundary.',
        ],
        takeaway: { title: 'Provisioned is not verified', text: 'An accepted plan, a running process and a completed local test each prove something different. Deployment and cleanup need their own observations.' },
        sourceIds: ['cloud-deployment', 'cloud-compute', 'cloud-testing'], concepts: ['cloud-terraform-plan', 'cloud-evidence-boundary'],
      },
      {
        id: 'capstone-extension', title: 'Change one boundary deliberately', kicker: '08.3 · Extend the architecture',
        lead: 'You now know enough to ask what a new requirement changes—and what must remain true.',
        paragraphs: [
          'Finish the capstone by stabilizing the purchase day, delivering the original settlement, recovering from an interrupted attempt, publishing, and asserting the full report. Keep one reference selected as you move between Course, Explorer and Missions. Its local evidence lets you connect a small business rule to object storage, transport, API validation and database publication.',
          'Choose an extension because of a concrete pressure. Adding a queue could provide a new buffering and redrive boundary between S3 and the worker. Larger reports would require revisiting the embedded Mongo document, atomic publication and result pagination. A new business classification would affect the comparator, report contract, API validator, canonical fixtures and independent generator expectations.',
          'These are design proposals, not features already present. For your chosen extension, name the changed components, one invariant to preserve and a test that would expose a regression. A queue must preserve the original object identity through redelivery. Larger report storage needs an explicit completion protocol. A new outcome needs evidence that both producer and consumer agree on its meaning.',
        ],
        takeaway: { title: 'Own the contract before adding the technology', text: 'A useful extension names the pressure it addresses, the boundaries it changes, the invariant it preserves and the evidence that verifies it.' },
        sourceIds: ['cloud-generator', 'cloud-publication', 'cloud-processing', 'cloud-testing'], concepts: ['cloud-system-invariant', 'cloud-independent-oracle', 'cloud-atomic-publication'],
      },
    ],
    mission: {
      title: 'Ship a trustworthy reconciliation',
      brief: 'Coordinate a stable business day, original upload, interrupted worker attempt, recovery and immutable publication. Finish with an independent report check and a justified extension.',
      goal: 'Produce the exact canonical report, replay it safely, and identify the affected boundaries and preserved invariant for one proposed change.',
    },
    lab: {
      title: 'Verify the project, then propose an extension',
      prerequisites: ['Java 21, the repository Maven wrapper and Docker accessible from the invoking environment.', 'Run Maven verification sequentially from the repository root. The full default mode includes units, acceptance, coverage and mutation testing.'],
      commands: ['./mvnw clean verify'],
      steps: [
        'Run the full local verification command and inspect the unit, Failsafe/Cucumber, JaCoCo and PIT outputs documented in docs/testing.md.',
        'Trace the packaged generator scenario in generator.feature and GenerationWorkflow. Explain why its expected report must remain independent of the worker comparator.',
        'Choose one extension: a new outcome, an asynchronous queue boundary, or reports that outgrow the embedded document. Draw the affected components and write down the invariant to preserve.',
        'For a code extension, add an independent expected example and a failing behavioral test before implementation. Keep cloud provisioning outside this local exercise.',
        'Use docs/deployment.md to rehearse the cloud sequence on paper. Mark actual account, Terraform and cloud observations as pending until they have been performed in an authorized environment.',
      ],
      expected: 'Fresh local reports support your local correctness claim, and the extension proposal names affected contracts and a meaningful verification strategy. Browser completion does not claim cloud deployment or new Java verification.',
    },
  },
]

export const cloudGlossary = [
  { id: 'cloud-vpc', term: 'VPC', definition: 'A logically isolated AWS network. This project defines separate EC2 and worker subnets within one VPC; route tables determine where their traffic can go.', moduleId: 'security' },
  { id: 'cloud-security-group', term: 'Security group', definition: 'Rules controlling allowed network traffic for a resource. The API group accepts port 8080 from the worker group, not from the public internet.', moduleId: 'security' },
  { id: 'cloud-gateway-endpoint', term: 'S3 Gateway endpoint', definition: 'A route-table connection to S3 that lets the private worker subnet reach allowed S3 objects without a NAT gateway or default internet route.', moduleId: 'security' },
  { id: 'cloud-iam-role', term: 'IAM role', definition: 'An AWS identity assumed by a service. Its policy names permitted AWS actions and resources; it does not grant Java API bearer-token access.', moduleId: 'security' },
  { id: 'cloud-bearer-role', term: 'Bearer role', definition: 'An application access role proven by possession of the configured token. Demo and worker tokens authorize different API route families.', moduleId: 'security' },
  { id: 'cloud-least-privilege', term: 'Least privilege', definition: 'Give a component only the access its work requires. Here the worker reads settlement versions and owns no database credential or settlement-write permission.', moduleId: 'security' },
  { id: 'cloud-runtime-secret', term: 'Runtime secret', definition: 'A credential supplied to a running component outside source and Terraform state. Rotating it must account for processes that have already loaded the old value.', moduleId: 'security' },
  { id: 'cloud-retryable-failure', term: 'Retryable failure', definition: 'A failed attempt whose cause may disappear, such as a temporary connection outage. A retry reuses the original input; it does not repair deterministically malformed CSV.', moduleId: 'recovery' },
  { id: 'cloud-stale-run', term: 'Stale run', definition: 'A noncompleted run with no update for more than 20 minutes. It is a diagnostic signal, not proof that a worker stopped and not an automatic recovery action.', moduleId: 'recovery' },
  { id: 'cloud-attempt', term: 'Attempt', definition: 'One worker invocation identified separately from the reconciliation run. Several attempts can process the same run and original object version.', moduleId: 'recovery' },
  { id: 'cloud-object-identity', term: 'Object identity', definition: 'The bucket, key, version ID and SHA-256 that bind a run to exact settlement input. Recovery must preserve an already bound identity.', moduleId: 'recovery' },
  { id: 'cloud-idempotency', term: 'Idempotency', definition: 'Repeating an operation has the same intended effect as doing it once. Identical completed report publication is a no-op; repeated execution itself is still possible.', moduleId: 'recovery' },
  { id: 'cloud-atomic-publication', term: 'Atomic publication', definition: 'A single database update that checks the original identity and current status while storing the complete report. Competing attempts cannot partially overwrite completion.', moduleId: 'recovery' },
  { id: 'cloud-independent-oracle', term: 'Independent expected result', definition: 'A known correct report maintained separately from the code being tested. The canonical fixture can expose comparator mistakes that recomputing expectations with that comparator would hide.', moduleId: 'capstone' },
  { id: 'cloud-evidence-boundary', term: 'Evidence boundary', definition: 'The limit of what an observation proves. A local S3 stub can exercise request behavior; it cannot prove actual AWS authorization, delivery or cost.', moduleId: 'capstone' },
  { id: 'cloud-terraform-plan', term: 'Terraform plan', definition: 'A reviewable description of proposed infrastructure changes for a specific configuration and state. It is neither deployment success nor an account eligibility or cost guarantee.', moduleId: 'capstone' },
  { id: 'cloud-system-invariant', term: 'System invariant', definition: 'A property that must remain true across operations and extensions, such as preserving exact input identity or preventing a completed report from being downgraded.', moduleId: 'capstone' },
]
