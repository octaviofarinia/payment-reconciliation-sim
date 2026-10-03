# Payment Reconciliation Simulator

A Java 21 / Spring Boot 4.1.1 simulator for comparing immutable ARS purchases with a daily settlement CSV. The API stores purchases and reports in MongoDB. The separately packaged Java Lambda parses the CSV and classifies MATCHED, MISSING_IN_SETTLEMENT, MISSING_INTERNALLY, AMOUNT_MISMATCH and DUPLICATE results.

## Current handoff status

**Local application verification is established; required cloud evidence is PENDING.**
The reviewed application checkpoint is commit `0ea3358`: 277 unit tests,
89 acceptance/integration cases including 40 Cucumber scenarios, zero missed
included unit lines/branches and all three 80% PIT gates. The nine actual build
modes and isolated negative gate proofs are described in [testing.md](docs/testing.md).
Java, POMs and acceptance sources have not changed since that checkpoint.

Terraform source, deployment scripts and cloud evidence tooling are prepared.
Actual Terraform validation/provider lockfile, AWS Free-account eligibility,
Atlas M0 startup, deployment, real S3 authorization/delivery, cold/warm timings,
outage recovery, usage and teardown remain **REQUIRED PENDING**.
[The evidence ledger](docs/demo-evidence.md) records those gaps. This is a source
handoff, not overall project completion or a measured zero-cost claim.
Use generated data only; this project processes no cards or money movement.

## Local prerequisites and verification

Use Java 21, the committed Maven wrapper (Maven 3.9.16), Python 3 for verification
scripts, and Docker accessible from the same environment for local acceptance.
The harness starts the pinned MongoDB 8.0.14 replica set, a real Spring HTTP API,
a versioned/checksum-aware S3 stub and the real Lambda handler automatically.
It uses dummy AWS credentials and requires no AWS/Atlas account.

From the repository root on Unix/WSL:

~~~sh
./mvnw clean verify
~~~

On Windows use `mvnw.cmd` with the same arguments. Maven/PIT builds must run
sequentially in a worktree. [testing.md](docs/testing.md) lists the seven supported
commands, both verify skips, reports, gates and [all REQ/AC evidence](docs/testing.md#requirement-traceability).
The acceptance harness is the executable local complete-flow demonstration;
there is no standalone interactive local complete-flow launcher.

## Modules and artifacts

| Module | Responsibility | Packaged artifact under target |
| --- | --- | --- |
| reconciliation-api | Purchase/date/upload APIs, MongoDB, atomic reports, Swagger/security/recovery | reconciliation-api-0.0.1-SNAPSHOT-exec.jar |
| reconciliation-worker | Java Lambda handler, CSV validation, comparison, S3/API adapters | reconciliation-worker-0.0.1-SNAPSHOT-lambda.jar |
| scenario-generator | Public HTTP client, seeded purchases/CSV and independent result verification | scenario-generator-0.0.1-SNAPSHOT-exec.jar |
| acceptance-tests | Real API/Mongo/handler/generator verification with simulated AWS | Test-only module; no deployment artifact |

The generator has its own HTTP representations and Jackson runtime dependencies;
it imports no API/worker implementation and accesses no database. Dependency and
boundary checks were part of the retained local verification.

## Generator usage

The executable needs a running API and a working upload-to-worker path. For the
cloud demo, establish the verified SSH tunnel and deployment prerequisites in
[deployment.md](docs/deployment.md#tunnel-smoke-and-recovery) first. Local acceptance
runs the actual generator entry point within its managed environment.

Privately set `RECONCILIATION_DEMO_TOKEN` in the invoking environment; it must be
1–1024 printable ASCII characters without spaces. Use a fresh disposable dataset
for canonical references and an unused date strictly before today in
America/Buenos_Aires. Replace the date below before execution:

~~~sh
java -jar scenario-generator/target/scenario-generator-0.0.1-SNAPSHOT-exec.jar \
  --base-url http://127.0.0.1:8080 --business-date YYYY-MM-DD \
  --seed 0 --scenario canonical
~~~

Required arguments are `--base-url http[s]://HOST[:PORT]`, `--business-date YYYY-MM-DD`,
`--seed SIGNED_LONG` and `--scenario canonical|all-matched`. The base URI permits only
an empty/root path and no userinfo, query or fragment. Optional `--expected FILE`
supplies generator-owned JSON `{summary,results}`; canonical uses the independent
committed [expected fixture](acceptance-tests/src/test/resources/fixtures/canonical-expected.json)
by default. All-matched generates 100 references of the form
`S<unsignedseedhex>_D<ISOdate>_<001..100>`, with expected results from known purchases.

The client creates the date and purchases, closes it, registers and directly
uploads the exact CSV, polls metadata and checks all result pages. Canonical has
211 CSV bytes, four purchases, five settlement rows, four distinct settlement
references and five results, one per outcome. Exit 0 prints
`Verified runId=<id> results=<count>`; exit 2 means invalid arguments/token; exit 1
means workflow/upload/expected mismatch/failure/deadline/interruption. Failures
after registration identify the run and public reprocess route. Recovery is
explicit; the generator never automatically reprocesses. No secret belongs in
CLI arguments, committed output or screenshots.

## Swagger, deployment and recovery

Open [Swagger through the established tunnel](http://127.0.0.1:8080/swagger-ui/index.html).
The public document is `/v3/api-docs`; use Swagger **Authorize** with the demo token.
Public `/api/v1` routes require the demo bearer; every `/internal/v1` route requires
the distinct worker bearer. [api-access.md](docs/api-access.md) defines role checks,
error codes, correlation IDs and request/result limits.

Public operations create purchases/dates, close a date, register a reconciliation
run, list/query its status and retrieve filtered paginated results. Recovery uses
`POST /api/v1/reconciliation-runs/{runId}/reprocess` against retained original input.
An incomplete run may need recovery after more than twenty minutes of inactivity;
202 accepts an incomplete invocation, 200 is completed replay, and
UPLOAD_NEEDED/ORIGINAL_UNAVAILABLE remain 409. A replacement version cannot replace
a bound original.

Follow [deployment.md](docs/deployment.md) for the conditional account/Atlas
preflight, Terraform validation/plan, artifact and secret deployment, host-verified
SSH tunnel, smoke and teardown. [demo-evidence.md](docs/demo-evidence.md) contains
the required manual signature/expiry, outage, benchmark and cleanup procedures.
No tools are installed or cloud resources created by local verification.

## Architecture and tradeoffs

The [reviewed specification](docs/superpowers/specs/2026-10-01-payment-reconciliation-design.md)
is the requirements baseline; the current implementation status is above.
The closed-date dataset and original S3 version make retries reproducible.
An atomic embedded report is bounded at 8 MiB; purchases/CSV are capped at
1,000/2,000 rows and 2 MiB. Lambda has a five-minute timeout and concurrency one;
logs retain seven days.

The Lambda/EC2 split demonstrates event-driven batch processing but couples worker
availability to the API. Private worker HTTP and bearer tokens are synthetic-demo
compromises. There is no queue, NAT gateway, load balancer or transaction snapshot.
These are source-level design tradeoffs. Cold/warm duration, memory, wall time
and account costs have **not** been measured; [the pending evidence](docs/demo-evidence.md#architecture-observations)
must establish them before any runtime or zero-spend conclusion.
