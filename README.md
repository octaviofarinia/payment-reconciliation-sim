# Payment Reconciliation Simulator

A Java 21 / Spring Boot 4.1.1 application that compares immutable ARS purchases with a
daily settlement CSV. The API stores purchases and reports in MongoDB. A separately
packaged Java Lambda validates the CSV and classifies each reference as MATCHED,
MISSING_IN_SETTLEMENT, MISSING_INTERNALLY, AMOUNT_MISMATCH or DUPLICATE.

The AWS deployment runs the API on EC2 with MongoDB Atlas, stores settlement files in a
versioned S3 bucket and invokes the worker through S3 notifications. The scenario
generator exercises this flow through the public API and checks the complete report
against independent expected results. The deployment and a generator simulation have
been verified on AWS.

The project uses synthetic purchases and settlement data. It does not process card data
or move money.

## Learn the project interactively

[Reconciliation Lab](learning-workspace/README.md) is a browser-first course with
eight modules, a shared Data/System/Code Explorer and eight interactive missions.
It builds from pure reconciliation rules to the Java backend, cloud boundaries
and recovery. Optional local coding labs connect each module to this repository.

[Open the course online](https://octaviofarinia.github.io/payment-reconciliation-sim/)
to learn without installing or running anything. Progress stays in your browser.

After cloning the repository, install Node.js 22.12 or newer (with npm), then run
from the repository root:

```sh
cd learning-workspace
npm ci
npm run build
npm run preview
```

Open the URL printed by Vite, normally http://localhost:4173/. The core course
needs no Java, Docker, backend server or cloud account. Source and the dependency
lockfile are committed; `dist/` is generated locally. See the
[site README](learning-workspace/README.md#run-the-course-from-a-fresh-clone) for
development, testing, Windows/WSL and progress backup instructions.

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
commands, both verify skips, reports, gates and [requirement coverage](docs/testing.md#requirement-traceability).
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
it imports no API/worker implementation and accesses no database.

## Generator usage

The executable needs a running API and a working upload-to-worker path. For the
AWS deployment, follow the [deployment guide](docs/deployment-guide.md) and establish
the SSH tunnel first. Local acceptance runs the actual generator entry point
within its managed environment.

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

The [deployment guide](docs/deployment-guide.md) covers account and Atlas setup,
Terraform, runtime configuration, the SSH tunnel and teardown.
[Deployment reference](docs/deployment.md) describes networking, permissions and
script behavior. [Cloud validation](docs/demo-evidence.md) covers upload checks,
outage recovery, runtime benchmarks and cleanup verification.

## Architecture and tradeoffs

The [architecture reference](docs/architecture.md) describes the data contracts,
comparison rules and component boundaries. The closed-date dataset and original
S3 version make retries reproducible.
An atomic embedded report is bounded at 8 MiB; purchases/CSV are capped at
1,000/2,000 rows and 2 MiB. Lambda has a five-minute timeout and concurrency one;
logs retain seven days.

The Lambda/EC2 split demonstrates event-driven batch processing but couples worker
availability to the API. Private worker HTTP and bearer tokens are synthetic-demo
compromises. There is no queue, NAT gateway, load balancer or transaction snapshot.
The [benchmark procedure](docs/demo-evidence.md#runtime-benchmark) records cold and
warm duration, memory and upload-to-completion time. Deployment costs depend on
the selected resources, usage and account benefits.
