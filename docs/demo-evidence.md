# Cloud demonstration evidence

Status: **REQUIRED PENDING — cloud demonstration and overall acceptance incomplete**.

No AWS/Atlas deployment, real S3 signature/expiry check, measured cold/warm run,
controlled cloud outage, account benefit verification or teardown has been
executed for Task 14. Terraform and AWS CLI were absent; the user owns preparation
of the tools and account. No provider lockfile, plan, cloud measurement or cleanup
claim is fabricated. Local evidence remains in [testing.md](testing.md).

## Required cloud criterion ledger

[All 20 requirements and 33 criteria](testing.md#requirement-traceability) link to
executable local proof at the unchanged Task 12 checkpoint. The following
criteria additionally require real cloud/manual evidence; none is complete.

| Criterion | Local evidence | Required pending real observation |
| --- | --- | --- |
| <a id="cloud-ac-13"></a>AC-13 / REQ-07 | [Registration/stub/version proof](testing.md#ac-13) | External AWS PUT, signed checksum/signature/expiry rejection and immutable original-version reuse |
| <a id="cloud-ac-18"></a>AC-18 / REQ-12 | [Fault/retry/manual recovery proof](testing.md#ac-18) | Controlled cloud outage, real attempt diagnostics and retained-input recovery |
| <a id="cloud-ac-20"></a>AC-20 / REQ-13 | [Supported-bound parser/workload proof](testing.md#ac-20) | Maximum supported real cold/warm workload, memory and less-than-120-second processing target |
| <a id="cloud-ac-21"></a>AC-21 / REQ-14 | [Real local handler/auth proof](testing.md#ac-21) | Direct S3 notification, actual private API/S3 routing and deployed least privilege/no worker Atlas access |
| <a id="cloud-ac-23"></a>AC-23 / REQ-15 | [Evidence boundary](testing.md#ac-23) | Account benefits/resources/quota, Atlas M0 startup, usage visibility, full teardown |
| <a id="cloud-ac-24"></a>AC-24 / REQ-16–REQ-18 | [Actual local generator proof](testing.md#ac-24) | Cloud executable generator and five outcomes through tunneled Swagger/public API |

Terraform fmt/init/validate/plan/provider lockfile and post-teardown local
verification remain required pending prerequisites/final checks as well.
`finalEvidenceCoversEveryCriterion` cannot pass its all-required-evidence assertion.
Record observations with reviewed commit/artifact hashes, commands, times and
sanitized source locations before replacing any PENDING status.

## Readiness and pending observations

| Required evidence | Current status | Evidence to retain after authorized execution |
| --- | --- | --- |
| Full default local verification and packaged generator | Previously established local matrix; cloud artifact selection pending | Reviewed commit and artifact SHA-256, actual verification command/result |
| Terraform fmt/init/validate/plan and provider lockfile | PENDING | Actual commands/results, reviewed lockfile and plan guard result |
| FREE/ACTIVE plan, credits/expiry, service eligibility, concurrency quota | PENDING | Current sanitized preflight report and account/region attestations |
| Atlas M0, database-scoped permissions and EC2 /32 | PENDING | Actual tier/role/access-list evidence and successful API startup |
| Direct S3 notification, private worker API, least privilege/no worker Atlas access | PENDING | Deployed checks, original-version identity, correlated diagnostic and actual policy/network review |
| Canonical executable generator and Swagger five outcomes | PENDING | Generator run ID, COMPLETED, totalResultCount 5, one of every outcome, full expected-report equality |
| Maximum workload cold/warm target and configured limit | PENDING | Actual REPORTs/stream/request IDs, counts, bytes, init/duration/memory, end-to-end elapsed and observed attempts |
| Real S3 checksum/signature/expiry and version reuse | PENDING | Sanitized statuses/error codes, original/new versions, expiry timestamps, stable original report |
| Retriable cloud outage and manual recovery | PENDING | Failed/successful request IDs, run/date/version/rules correlation, recovery response and unchanged retained input |
| Usage/free benefits visibility | PENDING | Before/after credits/usage with observation times and reporting-lag qualification |
| Full cloud and Atlas teardown | PENDING | Empty Terraform state plus independent resource/version/log checks and removed Atlas access/user |
| Local features after teardown | PENDING | Actual local verification command/result after teardown |

The automated JSON has a validation ID, UTC start/finish, requested dates,
allowlisted resource inventory, per-run identity/counts/timing/diagnostics and
explicit check statuses. It contains no runtime tokens, Mongo URI, presigned URL,
raw generator output, raw CLI response, credential file or request headers.
The output is created privately and cannot overwrite an existing report.
A failure retains known observations and exits nonzero; PENDING checks remain
pending even when automated checks pass. Review any copied resource identifiers
before sharing. Offline fixture output is **offline test evidence**, never real
cloud proof, and must not be committed as a measurement.

## Authorized execution sequence

Follow [deployment.md](deployment.md) in order: account/Atlas preparation and
current preflight, local verified artifacts, reviewed actual Terraform plan,
explicitly authorized deployment, secret-safe API deployment and verified SSH
tunnel. Keep the AWS profile outside the repository. Do not upgrade the account,
install tools automatically or change the topology to bypass a prerequisite.

Start the benchmark before canonical or any other worker event. Use a fresh demo
database for fixed canonical references and two unused past dates. Use the smoke
--benchmark/--output command in deployment.md. The maximum workload is 1,000
internal purchases plus 2,000 disjoint external references: 1,000
MISSING_IN_SETTLEMENT and 2,000 MISSING_INTERNALLY results, exactly 2 MiB raw CSV.
Canonical then runs the existing public-only generator and validates all five
outcomes. The script changes neither its CLI nor application behavior.

Cold classification requires a correlated runtime Init Duration; warm requires
a later synchronous replay of the identical bound version in the same log stream
with no Init Duration. Warm-candidate alone is insufficient. Record both attempts'
Lambda Duration, Init Duration when present, billed duration and memory. The
120-second target includes initialization; the 300-second configured timeout
comparison uses Lambda Duration. Preserve wall-clock upload-to-completion
separately, and remember runtime Max Memory Used can reflect the environment's
peak rather than an isolated invocation. Investigate any miss or missing proof
before repeating with a newly authorized fresh environment.

The benchmark script validates the returned expected report across all pages;
it does not time 1,000 purchase inserts as worker processing, infer async retry
schedules, or force a cold start. It preserves the first S3-triggered completion
before explicit replay. It rejects a replay log tail lacking its own correlated
success, even if some other duplicate attempt completed.

## Remaining real-S3 checks

After successful benchmark/canonical evidence, use separate disposable past dates
and the public Swagger API to create/close dates and register synthetic CSVs.
Use the returned upload instructions only in a private local client; never place
URLs or tokens in committed files, screenshots or shell history.

1. With a fresh registration, alter only the signed checksum header and attempt
   PUT; capture rejection status and sanitized S3 code. With another fresh
   registration, leave the required checksum header intact but alter payload
   bytes of the same length; require rejection. Retain the expected and actual
   payload hashes and confirm no successful report was published.
2. Alter only X-Amz-Signature in a privately retained URL; require rejection.
   For an unmodified fresh URL, retain expiresAt, wait until after that timestamp
   with a clock-skew margin, then PUT the exact registered bytes/headers; require
   expiry rejection and no report. Record real UTC observation times and S3 code.
   A local stub or the URL's declared lifetime does not prove enforcement.
3. Upload a valid registered object, wait for COMPLETED, retain its exact version,
   summary and all report pages. While the original URL is still valid, upload
   identical bytes again: record the new S3 version. Observe its notification and
   confirm it cannot replace the bound original version or alter completed
   metadata/report. Replaying the original version is the supported duplicate
   test; a new-version upload is a separate rejection test.
4. Use the canonical run's Swagger metadata/results to inspect one MATCHED,
   MISSING_IN_SETTLEMENT, MISSING_INTERNALLY, AMOUNT_MISMATCH and DUPLICATE result.
   Retain sanitized outcome evidence, without authorization fields.

Never turn an unexpected 4xx/5xx into an assumed signature/expiry proof: inspect
the sanitized S3 error code and establish that the intended condition caused
rejection. Record failures as failures. Use new registrations where needed;
an overwritten URL or refreshed expiry would invalidate the test.

## Retriable outage and retained-input recovery

Prepare a separate registered synthetic run while the API is healthy. Retain its
private upload instructions and synthetic bytes. With explicit outage execution
authorization, stop only the demo API over the existing verified SSH connection:

~~~sh
ssh -i /path/to/private-ssh-key -o StrictHostKeyChecking=yes ec2-user@PUBLIC_IP \
  'sudo systemctl stop reconciliation-api.service'
~~~

Upload while it is stopped. Read the worker's actual CloudWatch logs and retain
the run ID, original version, failed request IDs and retry diagnostics. A missing
API may prevent failure callbacks; distinguish that from a successfully persisted
retriable failure. Do not claim async scheduling from mocked logs or invocation
counts. Restore promptly with the same SSH command using start instead of stop,
confirm API readiness, and inspect public metadata. If automatic retry completes,
record it separately. POST /api/v1/reconciliation-runs/{runId}/reprocess to exercise
manual recovery against retained input: incomplete accepted work returns 202,
completed replay returns 200. Record the actual response and correlated original
version; wait for COMPLETED and compare the expected report. For a successful
manual recovery of incomplete work, preserve evidence that the run was incomplete
at the request, accounting for racing async retries. Never substitute a new
version for a bound missing original or manually edit database run state.

Review Lambda environment keys, worker role, private routes/SGs, exact S3 worker
config object schema and API configuration to confirm the worker has only its
worker token and no Atlas credentials/network path. Record this from actual
deployed configuration; the source guard alone is not cloud proof.

## Teardown and final evidence

Stop all producers, close the tunnel, wait for outstanding upload URLs to expire,
then execute the reviewed teardown command with the exact Terraform bucket:

~~~sh
scripts/teardown.sh --confirm-bucket EXACT_BUCKET_FROM_TERRAFORM_OUTPUT
terraform -chdir=infra state list
~~~

Retain the automated report's resource inventory and actual Terraform/AWS
identifiers privately before destruction. Independently verify removal of the
EC2 instance/root EBS volume/public IPv4, Lambda and its eventual ENIs, bucket with
every object version and delete marker (including runtime config), log group,
VPC/subnets/route tables/gateway endpoint/security groups/IGW and IAM/key resources.
An empty state alone is insufficient if resources were removed from state.
Retain partial failures and retry cleanup using remaining state; do not erase it
to make a check appear empty. Remove the Atlas /32 and dedicated database user,
and remove the disposable database/cluster only if intended.

Observe Billing/Free Tier after deletion, record remaining credits/expiry and
visibility lag, then run the established local verification after teardown.
Only actual evidence may replace PENDING entries above. Task 14 and overall
cloud acceptance remain incomplete until every required observation exists.

## Architecture observations

The implementation has the following design consequences. They are not measured
performance or cost findings; record actual observations after authorized cloud
execution.

| Decision | Consequence to assess | Current measurement status |
| --- | --- | --- |
| Java 21 Lambda, 512 MiB, concurrency one | Initialization and memory headroom at 1,000 purchases/2,000 rows/2 MiB; throughput is serialized | REQUIRED PENDING: correlated cold/warm REPORTs and upload-to-completion wall time |
| Worker obtains immutable purchases through private EC2 HTTP | Fewer snapshots, but API outages can leave stale status and need retained-input recovery | REQUIRED PENDING: controlled outage/retry/manual recovery |
| Embedded report, maximum 8 MiB | One atomic publication; larger datasets need another storage design | Local boundary/publication proof only; cloud maximum workload pending |
| Public EC2 outbound TLS plus S3 gateway endpoint/private Lambda | No NAT/load balancer/interface endpoints; Atlas access follows changing EC2 public /32 | REQUIRED PENDING: actual routing/permissions/Atlas startup |
| Free account benefits and Atlas M0 | Eligibility and credits constrain availability; source cannot establish zero spend | REQUIRED PENDING: current account/service evidence and usage before/after teardown |

## Sources and evidence boundaries

Primary implementation contracts: the reviewed deployment/API runbooks, existing
canonical expected fixture, CsvBoundaryIT's accepted quoted-field padding,
ReconciliationHandler's request-ID attempt identity and WorkerDiagnostics' schema.
The following official references are retained for the operator; they were not
newly fetched during this offline preparation:

- [Lambda REPORT and memory/init fields](https://docs.aws.amazon.com/lambda/latest/dg/java-logging.html)
- [Synchronous Invoke and log tail](https://docs.aws.amazon.com/lambda/latest/api/API_Invoke.html)
- [S3 presigned URL expiry and checksums](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
- [Asynchronous retry behavior](https://docs.aws.amazon.com/lambda/latest/dg/invocation-async-error-handling.html)
- Account, networking, role and Atlas sources already recorded in deployment.md.
