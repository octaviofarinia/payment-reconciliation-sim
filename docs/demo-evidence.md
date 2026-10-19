# Cloud validation

The AWS deployment and a scenario-generator simulation have been verified. The
procedures below support repeatable deployment checks, upload validation,
recovery testing and runtime measurement. Local automated tests are documented in
[testing.md](testing.md); setup and deployment are covered by the
[deployment guide](deployment-guide.md).

## Generator simulation

With the API deployed and the SSH tunnel open, run the generator as described in
[the deployment guide](deployment-guide.md#optional-end-to-end-run). Use a fresh
synthetic database for the canonical scenario's fixed references and a business
date strictly before today in `America/Buenos_Aires`.

The generator uses only the public API and the returned presigned upload
instructions. A successful canonical run prints `Verified runId=<id> results=5`
after checking COMPLETED status and the complete report against the independent
expected fixture. It produces four purchases, five settlement rows and one result
for each of the five outcomes.

## Deployment checks

`scripts/cloud-smoke.py smoke` checks the deployed topology, worker IAM, log
retention, private networking, application token roles, Swagger availability and
anonymous S3 denial. It runs the canonical generator and verifies report equality,
original checksum/version, direct delivery and a correlated replay.

From the repository root, with Java 21 and the packaged generator available:

~~~sh
python3 scripts/cloud-smoke.py smoke --outputs .runtime/outputs.json \
  --runtime .runtime/runtime.json --business-date YYYY-MM-DD \
  --output .runtime/demo-evidence.json
~~~

The output path must be new and its parent directory must exist. The script
creates the report with mode 0600 before cloud operations, retains known
observations on failure and exits nonzero when a check fails. Reports include UTC
times, resource inventory, run identity, counts and check statuses. They omit
runtime tokens, MongoDB URIs, presigned URLs and raw generator output. Keep them
under ignored `.runtime/` and review resource identifiers before sharing.

`AUTOMATED_CHECKS_PASSED` applies to the requested automated checks. The JSON also
tracks checks performed separately, such as signature/expiry rejection,
controlled outages, account usage and teardown. Without `--benchmark`, the maximum
workload and combined runtime check remain `PENDING` in that report.

Inspect deployed Lambda environment keys, worker IAM, routes and security groups
to confirm that the worker reads its token from the designated S3 object and has
no Atlas credentials or direct database path. Local fixtures exercise the check
logic; deployment checks inspect the configured AWS resources.

## Runtime benchmark

Run the benchmark before other worker invocations when collecting cold-start
observations. Use a fresh synthetic database and two unused past business dates:

~~~sh
python3 scripts/cloud-smoke.py smoke --outputs .runtime/outputs.json \
  --runtime .runtime/runtime.json --business-date YYYY-MM-DD \
  --benchmark --benchmark-date EARLIER-YYYY-MM-DD \
  --output .runtime/benchmark-evidence.json
~~~

The benchmark runs first, followed by the canonical generator. Its workload has
1,000 internal purchases, 2,000 disjoint settlement references and exactly
2,097,152 CSV bytes. The expected report has 1,000 MISSING_IN_SETTLEMENT and 2,000
MISSING_INTERNALLY results. All result pages and row evidence are checked.

After the S3-triggered completion, the script synchronously replays the identical
original-version event. The returned log tail must include a successful
diagnostic and Lambda REPORT for the same request ID. Cold classification requires
Init Duration; warm classification requires a later successful replay in the same
log stream without Init Duration. Missing or ambiguous correlation fails the
benchmark. Invocation order alone cannot identify a cold start.

The processing target is strictly less than 120 seconds for Lambda Duration plus
Init Duration when present. The configured timeout check compares Lambda Duration
with the 300-second limit. The report records billed duration, configured/used
memory and upload-to-completion wall time separately. Purchase insertion time is
outside the worker-processing measurement. Max Memory Used can reflect the
execution environment's peak across invocations.

The script preserves the first S3-triggered completion before replay and does not
force a cold start. The combined `realS3UploadPublishesReportWithinRuntimeBudget`
check requires both the canonical phase and the maximum-workload benchmark to
pass. Retain each report with the commit and artifact hashes used for the run.

## Upload validation

Use separate disposable past dates and registered synthetic CSVs for each check.
Keep upload URLs and authorization headers in a private client.

1. Change only the signed checksum header and attempt PUT. In another fresh
   registration, keep the header but alter payload bytes of the same length.
   Check the S3 rejection code and confirm that no successful report is published.
2. Change only `X-Amz-Signature` and check rejection. For a separate unmodified
   registration, wait beyond `expiresAt` with a clock-skew margin, PUT the exact
   bytes and headers, and record the expiry rejection with UTC observation times.
3. Upload valid input, wait for COMPLETED, and retain the original version, summary
   and all result pages. Before the URL expires, upload identical bytes again.
   Confirm that the new S3 version cannot replace the bound original or change
   completed metadata/results. A replay of the original version exercises a
   different case from a new-version upload.
4. In Swagger, inspect one MATCHED, MISSING_IN_SETTLEMENT, MISSING_INTERNALLY,
   AMOUNT_MISMATCH and DUPLICATE result from the canonical run.

Check the S3 error code as well as the HTTP status so that an unrelated failure
does not count as a successful checksum, signature or expiry check.

## Outage and recovery

Register a separate synthetic run while the API is healthy and retain its private
upload instructions. Stop the demo API over the verified SSH connection:

~~~sh
ssh -i /path/to/private-ssh-key -o StrictHostKeyChecking=yes ec2-user@PUBLIC_IP \
  'sudo systemctl stop reconciliation-api.service'
~~~

Upload while it is stopped. Inspect CloudWatch logs for the run ID, original
version, request IDs and retry diagnostics. An unavailable API can prevent failure
callbacks, leaving persisted status stale. Restore the service promptly:

~~~sh
ssh -i /path/to/private-ssh-key -o StrictHostKeyChecking=yes ec2-user@PUBLIC_IP \
  'sudo systemctl start reconciliation-api.service'
~~~

Confirm readiness and inspect public metadata. Record automatic retry completion
separately from manual recovery. To exercise manual recovery, call
`POST /api/v1/reconciliation-runs/{runId}/reprocess` while the run is incomplete.
A successful invocation request returns 202; completed replay returns 200.
Correlate the recovered attempt with the original version and compare the complete
expected report. Account for racing asynchronous retries when recording status at
the time of the request.

A run with no update for strictly more than 20 minutes can report
`recoveryNeeded`. `UPLOAD_NEEDED` and `ORIGINAL_UNAVAILABLE` return 409. Recovery
cannot replace a bound original with a newer object version. Do not edit database
run state to simulate a successful recovery.

## Teardown verification

Stop generators and new registrations/uploads, close the SSH tunnel and allow
issued upload URLs to expire, at most ten minutes. Run the teardown script with
the exact bucket from Terraform outputs:

~~~sh
scripts/teardown.sh --confirm-bucket EXACT_BUCKET_FROM_TERRAFORM_OUTPUT
terraform -chdir=infra state list
~~~

Retain resource identifiers privately before destruction. Verify removal of the
EC2 instance, root EBS volume, public IPv4, Lambda and its network interfaces,
bucket object versions/delete markers, runtime configuration, log group, network
and IAM resources. An empty state alone cannot identify resources previously
removed from Terraform management. Preserve partial failures and retry cleanup
using the remaining state.

Remove the Atlas `/32` and dedicated database user when no longer needed. Delete
the disposable database or cluster only when its data can be discarded. Review
Billing/Free Tier after cleanup, allowing for reporting lag. The local test suite
can run independently after the cloud stack is removed.

## References

- [Lambda REPORT fields](https://docs.aws.amazon.com/lambda/latest/dg/java-logging.html)
- [Synchronous Invoke and log tail](https://docs.aws.amazon.com/lambda/latest/api/API_Invoke.html)
- [S3 presigned URLs, expiry and checksums](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
- [Lambda asynchronous retries](https://docs.aws.amazon.com/lambda/latest/dg/invocation-async-error-handling.html)
