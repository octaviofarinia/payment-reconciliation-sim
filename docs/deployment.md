# AWS deployment

This is a source-only deployment definition. Start with the current
[handoff and prerequisites](../README.md), [verified application checkpoint](testing.md#retained-verified-application-checkpoint)
and [full requirement ledger](testing.md#requirement-traceability). Terraform and AWS CLI were absent in
the implementation WSL environment. Terraform fmt/init/validate/plan, account
preflight, EC2 bootstrap, Atlas startup, actual notification delivery, cloud smoke
and teardown are **pending**, not established by the offline tests. Nothing here
upgrades an AWS account plan or creates an Atlas resource.

## Topology and fixed bounds

One VPC contains a public EC2 subnet and a private Lambda subnet. EC2 uses an
Amazon Linux 2023 x86_64 AMI, Java 21, an encrypted 8 GiB gp3 root volume and a
dynamic public IPv4 address. No EIP is allocated. SSH port 22 admits only the
developer's current IPv4 /32. API port 8080 admits only the worker security group;
the developer uses an SSH tunnel bound to loopback.

Lambda uses Java 21/x86_64, the shaded worker JAR, 512 MiB, 300 seconds and reserved
concurrency 1. Async events expire after 3,600 seconds with at most two retries for
function errors. Throttling/service-error retry scheduling is separately governed
by Lambda and the same event age. CloudWatch retains logs seven days. There is
no queue, NAT gateway, load balancer, interface endpoint or provisioned concurrency.

Worker egress permits the private API on 8080 and the regional S3 prefix list on
443. Its subnet has no default internet route. A single S3 Gateway endpoint has
a policy limited to settlement reads and the exact worker config object. EC2 uses
its public route for HTTPS (AWS API/AL2023 packages) and Atlas TLS on 27017. VPC
DNS resolution is enabled. The worker does not connect to Atlas.

The private versioned bucket has SSE-S3 AES256, all four public access blocks,
bucket-owner-enforced ownership and a deny-insecure-transport policy. It has no
gateway-only bucket restriction: an external generator must be able to use a
presigned upload. Only ObjectCreated events matching settlements/ and .csv invoke
Lambda. Terraform installs invoke permission and versioning before notification.

## Prerequisites before any apply

The user prepares Terraform >=1.9,<2, AWS CLI v2 with freetier get-account-plan-state,
Python 3, OpenSSH, Java 21/build tools and configured AWS identity in the chosen
environment. The scripts neither install tools nor read or display credential
files. Use an AWS profile outside the repository; do not paste credentials into
chat, Terraform variables or shell arguments.

Confirm in the actual account that its plan is FREE/ACTIVE, USD credits remain,
and the plan has not expired. Verify each intended service/resource is available
under that plan and that the selected EC2 type, public IPv4, 8 GiB gp3, S3
storage/requests, Lambda/VPC and CloudWatch consumption fit current benefits and
credits. A positive credit balance is not proof of resource eligibility or a
zero-cost estimate. Record the account/region-specific evidence and review usage
in Billing/Free Tier before and after the session. Do not upgrade the account to
make a blocked service available.

Create an Atlas **M0** cluster through Atlas, with an application database (for
example reconciliation). Through Atlas UI/API, provision a database user with
readWrite plus dbAdmin scoped only to that database, or verified equivalent
narrower permissions. Startup installs validators using createCollection,
createIndex and collMod; readWrite alone is insufficient for collMod. Do not use
atlasAdmin or unsupported createUser/createRole database commands. Successful
cloud startup and the smoke transaction remain the actual compatibility check.

Create an ignored .runtime/eligibility.json containing current evidence, not
credentials. The following shape documents required fields; replace every
placeholder with actual evidence before using it:

~~~json
{
  "accountId": "123456789012",
  "region": "us-east-1",
  "instanceType": "YOUR_VERIFIED_X86_TYPE",
  "confirmedAt": "CURRENT_UTC_ISO_TIMESTAMP",
  "atlasTier": "M0",
  "atlasDatabaseRolesVerified": true,
  "resources": {
    "ec2": "Account benefit/service eligibility evidence and selected type",
    "publicIpv4": "Account benefit/credit evidence for one public IPv4",
    "ebsGp3": "Account benefit/credit evidence for encrypted 8 GiB gp3",
    "s3": "Account service access and storage/request credit evidence",
    "lambda": "Account service access and invocation/compute credit evidence",
    "cloudWatch": "Account service access and seven-day log credit evidence",
    "vpc": "Account access for VPC, IGW, routes and S3 gateway endpoint"
  }
}
~~~

From the repository root, after user preparation and authorization for read-only
account checks:

~~~sh
umask 077
mkdir -p .runtime
python3 scripts/cloud-smoke.py preflight \
  --account-id YOUR_ACCOUNT_ID --region us-east-1 \
  --instance-type YOUR_VERIFIED_X86_TYPE \
  --eligibility .runtime/eligibility.json > .runtime/preflight.json
~~~

The command checks STS identity, actual Free Tier plan fields, x86_64 support and
Lambda account settings. At least **101 currently unreserved concurrency units**
are required to reserve one while retaining 100. A quota of 10, 50 or 100 blocks
this design. Resolve only through an approved change compatible with the Free
plan; otherwise keep deployment pending. Never remove the cap or upgrade a plan.
Missing/denied/unsupported metadata also blocks provisioning. The evidence file
must be refreshed within 24 hours; retain the returned plan/credits/expiry/quota
and evidence hash. Resource eligibility and Atlas fields are explicit operator
attestations, not values this script can independently obtain from AWS.

## Terraform validation and deployment order

Build verified artifacts with the established commands in [testing.md](testing.md).
Use the current reviewed artifacts if already verified; no runtime rebuild is
needed merely to edit infrastructure. The default worker path is resolved from
infra. Copy infra/terraform.tfvars.example to ignored infra/terraform.tfvars,
choose the verified region/account/instance type and current /32, and supply only
the SSH **public** key. Set prerequisites_confirmed=true only after the preflight
and account-specific resource/Atlas checks above. This explicit false-by-default
variable prevents an accidental plan/apply with unconfirmed prerequisites.

~~~sh
terraform -chdir=infra fmt -check
terraform -chdir=infra init -backend=false
terraform -chdir=infra validate
terraform -chdir=infra plan -out=reviewed.tfplan
terraform -chdir=infra show -json reviewed.tfplan > infra/plan.json
python3 scripts/cloud-smoke.py plan infra/plan.json
~~~

Provider hashicorp/aws is pinned to 6.16.0. Initial init must generate and verify
.terraform.lock.hcl; review and commit that lockfile when tooling is prepared.
No lockfile/checksums were fabricated. Keep local state/plan files private and
ignored. No remote backend or extra service is provisioned.

The named terraformPlanHasNoUnapprovedChargeableNetworkComponents guard reads
actual Terraform JSON, rejects unapproved resource types, interface endpoints and
incorrect Lambda/retry/log settings. Its unit-test fixtures are not plan evidence.
It complements manual review: confirm exactly the intended VPC/subnets/routes,
one eligible EC2/volume/public IPv4, private/versioned bucket, one Lambda,
gateway endpoint, scoped roles and seven-day log group. Review IAM, route and SG
diffs and resource eligibility; the guard is not a cost oracle. No saved plan
exists from this source-only implementation. If formatting requires corrections,
run terraform fmt and re-run the sequence before approval.

Only after prerequisites, successful checks, current credit/quota recheck and
explicit provisioning authorization, apply the reviewed plan:

~~~sh
terraform -chdir=infra apply reviewed.tfplan
terraform -chdir=infra output -json > .runtime/outputs.json
~~~

Refresh outputs after any change. EC2 bootstrap installs Corretto 21 on that
future EC2 host and prepares systemd directories; it does not carry an artifact
or secrets, and the service cannot start until both are supplied. Verify the
EC2 SSH host key through a trusted channel and add it to known_hosts yourself.
Deployment requires StrictHostKeyChecking=yes; it never disables host checking.

Add exactly EC2's current public IPv4 /32 to Atlas's network access list. Use a
TLS-verified mongodb+srv URI naming the application database, or an explicit
mongodb URI with tls=true. Never allow 0.0.0.0/0 in Atlas. A stop/start or instance
replacement can change the public IPv4: remove the old /32, add the new /32,
refresh SSH host verification/output and retest connectivity.

## Runtime configuration and artifact upload

Privately prepare .runtime/runtime.json with **mode 0600** and exactly three
fields: demoToken, workerToken and mongoUri. Tokens must be distinct, 1–1024
printable ASCII characters with no whitespace. This is a local secret file;
do not commit it, print it, source it as shell code or pass its content on a
command line. The URI must name a database and must retain TLS verification.

~~~sh
scripts/deploy-api.sh \
  reconciliation-api/target/reconciliation-api-0.0.1-SNAPSHOT-exec.jar \
  .runtime/runtime.json /path/to/private-ssh-key
~~~

The script validates inputs, verifies the AWS account against Terraform outputs,
waits for cloud-init, uploads only workerToken as encrypted
s3://BUCKET/runtime-config/worker.json, sends the API environment file over SSH
stdin into root-owned /etc/reconciliation/runtime.env (0600), installs the API
JAR, checks its SHA-256, then requests a systemd restart. It does not print secrets
or request bodies. Temporary local files are 0600 inside a private temporary
directory and removed on exit. No token/Mongo value reaches Terraform variables,
data sources, object resources, state, plans, user-data or Lambda environment.

| Consumer | Runtime setting | Bound property |
| --- | --- | --- |
| API secret file | SPRING_MONGODB_URI | spring.mongodb.uri (Boot 4) |
| API secret file | RECONCILIATION_SECURITY_DEMOTOKEN | reconciliation.security.demo-token |
| API secret file | RECONCILIATION_SECURITY_WORKERTOKEN | reconciliation.security.worker-token |
| API service | RECONCILIATION_AWS_REGION | reconciliation.aws.region |
| API service | RECONCILIATION_S3_BUCKET | reconciliation.s3.bucket |
| API service | RECONCILIATION_LAMBDA_FUNCTIONNAME | reconciliation.lambda.function-name |
| Generator | RECONCILIATION_DEMO_TOKEN | Separate public client token |
| Lambda | RECONCILIATION_API_URI | Private EC2 URL |
| Lambda | RECONCILIATION_CONFIG_BUCKET | Same private settlements bucket |
| Lambda | RECONCILIATION_CONFIG_KEY | runtime-config/worker.json |
| Lambda runtime | AWS_REGION | Runtime-provided; never configured by Terraform |

The service runs as reconciliation; systemd reads the root-owned environment file
before changing user. Literal EnvironmentFile encoding preserves quotes, slashes,
dollar signs, backticks and equals signs; it is never shell-evaluated.
Worker IAM permits GetObject only for that exact config key, plus versioned reads
under settlements/. It cannot upload/delete settlements, read Mongo credentials
or invoke other functions. API IAM permits settlement uploads/reads/version
listing and invocation of only this worker. ENI permissions require a wildcard
resource for Lambda's control plane; an explicit SourceFunctionArn deny prevents
function code from using those permissions.

The worker loads its token once when a handler is constructed. For token rotation,
stop producers and quiesce processing, install the new matching API/worker config,
then review/apply a Terraform plan with -replace=aws_lambda_function.worker before
resuming. An S3 overwrite alone does not refresh existing warm handlers. If any
deployment step fails, leave producers stopped, correct the failure and rerun;
the script is not a transactional rollback across S3 and EC2.

## Tunnel, smoke and recovery

In a foreground terminal (replace PUBLIC_IP and key; retain host verification):

~~~sh
ssh -i /path/to/private-ssh-key -o StrictHostKeyChecking=yes \
  -o ExitOnForwardFailure=yes -N -L 127.0.0.1:8080:127.0.0.1:8080 ec2-user@PUBLIC_IP
~~~

Open http://127.0.0.1:8080/swagger-ui/index.html and authorize with the demo token.
Read [api-access.md](api-access.md) for HTTP errors and public/worker role rules.
For readiness, loading Swagger and a valid authenticated public request establish
the application started; a failed process needs EC2 service diagnostics inspected
locally with secret-aware handling. Merely requesting a systemd restart is not
proof of Atlas schema initialization.

With explicit cloud smoke authorization, prepare a fresh disposable demo database:
the canonical generator uses fixed globally unique references. Choose two unused
business dates strictly before today in America/Buenos_Aires. The benchmark date
defaults to the canonical date minus one day; --benchmark-date can choose another
distinct past date. The script checks ISO/distinct dates, while the API owns the
authoritative timezone rule. No host timezone package is installed by the script.

Before any other worker invocation, run from the repository root with the verified
generator executable present and Java 21 on PATH:

~~~sh
python3 scripts/cloud-smoke.py smoke --outputs .runtime/outputs.json \
  --runtime .runtime/runtime.json --business-date YYYY-MM-DD \
  --benchmark --benchmark-date EARLIER-YYYY-MM-DD \
  --output .runtime/demo-evidence.json
~~~

The --output path must be new and its parent must exist. The script reserves it
with mode 0600 before cloud mutation and retains sanitized partial evidence on
failure. It never overwrites an earlier report. Keep generated reports ignored
until reviewed for sanitization. No measured cloud result exists from this source
preparation; [demo-evidence.md](demo-evidence.md) records every pending criterion.

The benchmark executes first: 1,000 purchases, 2,000 distinct settlement references
disjoint from purchases, exactly 2,097,152 CSV bytes and 3,000 expected results.
Padding after a quoted currency field follows the already verified local CSV
boundary fixture. All pages and complete row evidence are checked against
independent expected outcomes. The report document's 8 MiB bound remains enforced
by the API; this workload does not attempt to fill that separate storage cap.

After direct S3 completion, the script synchronously invokes the original-version
event. Its returned Tail must contain both a successful diagnostic and REPORT for
the same request ID; a delayed initial duplicate cannot satisfy this replay check.
CloudWatch supplies the same request's duration, init, billed duration, configured
and used memory, and stream. Cold requires Init Duration. Warm requires the later
successful replay in that exact stream without Init Duration. Missing or ambiguous
proof fails the benchmark, leaving the observations for investigation. Prepare a
fresh worker through the reviewed replacement procedure only with deployment
authorization if needed; invocation order alone never establishes cold/warm.

The target is strictly less than 120 seconds for Lambda Duration plus Init Duration
when present. The configured limit is strictly less than 300 seconds for Lambda
Duration, which is separate from initialization. The report also records wall time
from immediately before upload to observed completion, including network/polling
latency. It captures observed correlated attempts; it does not infer retry scheduling
or prove the controlled outage scenario from their count. A miss exits nonzero.

The canonical phase invokes the unchanged executable
scenario-generator/target/scenario-generator-0.0.1-SNAPSHOT-exec.jar with public
--base-url, --business-date, --seed 0 and --scenario canonical arguments. Only
RECONCILIATION_DEMO_TOKEN is passed as a secret; it receives no worker/Atlas/AWS
credentials. Captured generator output is never echoed. If Java exits nonzero after registration,
the wrapper retains only one unambiguous UUID from the CLI's complete recovery
marker in the sanitized error and partial canonical evidence. Missing/ambiguous
markers, launch failures or wrapper timeouts may leave no known run ID; inspect
public runs for the requested date and do not infer that registration never occurred. The wrapper verifies
COMPLETED, totalResultCount=5, all five outcomes and complete independent expected
results, original checksum/version, direct delivery and its own correlated replay.
The named realS3UploadPublishesReportWithinRuntimeBudget check passes only when
canonical and the requested maximum cold/warm benchmark both pass.

Deployed topology/worker IAM (including the SourceFunctionArn ENI deny), log
retention, private networking, public/worker HTTP restrictions, Swagger availability
and anonymous S3 denial are checked. Run without --benchmark for canonical smoke
only; maximum-workload and the named combined check stay PENDING. Output status
AUTOMATED_CHECKS_PASSED is limited to these automated checks: account eligibility,
real signature/expiry abuse, controlled outage/manual recovery, usage visibility
and teardown still require the evidence procedure below.

If a run stops progressing, query its public metadata. After strictly more than
20 minutes incomplete metadata can flag recoveryNeeded. POST
/api/v1/reconciliation-runs/{runId}/reprocess uses the retained original version;
incomplete success is 202, completed replay is 200. UPLOAD_NEEDED and
ORIGINAL_UNAVAILABLE remain 409; replacement of a bound input is forbidden.
After async event-age exhaustion, manual recovery remains necessary. There is no
automatic recovery loop or queue added here.

## Teardown

First stop generators and API registration/uploads, close the tunnel, and wait
for all issued upload URLs to expire (at most ten minutes). Verify no external
uploader remains. The destructive command requires the exact bucket from this
Terraform state; it verifies the account, disables notification, caps new worker
invocations at zero, removes **all object versions and delete markers**, including
every runtime-config version, and then presents Terraform's destroy confirmation:

~~~sh
scripts/teardown.sh --confirm-bucket EXACT_BUCKET_FROM_TERRAFORM_OUTPUT
~~~

In-flight work may fail while storage disappears; only run this after deciding
to discard the demo. S3 deletion errors abort before destroy. The bucket has
force_destroy=false, so a concurrent new upload prevents silent destruction.
For an interrupted/partial teardown, retain state and inspect remaining resources;
retry version cleanup and terraform destroy as needed rather than deleting state.
The standard script requires live bucket/function outputs and is not a universal
repair tool for already-manually-deleted resources.

Confirm Terraform state has no remaining managed resources and check AWS for
remaining EC2/EBS/public addresses, Lambda/ENIs, bucket versions and log group;
AWS ENI deletion can take time. Remove the Atlas /32 and dedicated database user
through Atlas, and delete the disposable demo database/cluster only if intended.
Remove local runtime files and securely manage any retained state/evidence.
Review Billing/Free Tier/credits after resource deletion because usage reporting
can lag. Retain evidence of teardown; this task has not executed it.

## Sources checked during implementation

- [AWS account plans](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier.html)
  and [GetAccountPlanState CLI](https://docs.aws.amazon.com/cli/latest/reference/freetier/get-account-plan-state.html).
- [Lambda reserved concurrency](https://docs.aws.amazon.com/lambda/latest/dg/configuration-concurrency.html),
  [VPC permissions](https://docs.aws.amazon.com/lambda/latest/dg/configuration-vpc.html),
  [reserved environment variables](https://docs.aws.amazon.com/lambda/latest/dg/configuration-envvars.html).
- [S3 gateway endpoints](https://docs.aws.amazon.com/vpc/latest/privatelink/vpc-endpoints-s3.html)
  and [notification filters](https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-how-to-filtering.html).
- [Provider 6.16.0 release](https://github.com/hashicorp/terraform-provider-aws/releases/tag/v6.16.0)
  and [tagged Lambda implementation](https://raw.githubusercontent.com/hashicorp/terraform-provider-aws/v6.16.0/internal/service/lambda/function.go).
  Provider refresh writes environment variables into state; ignore_changes would
  not make a runtime secret safe there.
- [Provider notification dependency](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_notification)
  and [async invocation bounds](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function_event_invoke_config.html).
- [AL2023 Java](https://docs.aws.amazon.com/linux/al2023/ug/java.html),
  [systemd EnvironmentFile parser](https://raw.githubusercontent.com/systemd/systemd/main/src/basic/env-file.c).
- [Atlas M0 limits](https://www.mongodb.com/docs/atlas/reference/free-shared-limitations/),
  [database users](https://www.mongodb.com/docs/atlas/security-add-mongodb-users/),
  [collMod privileges](https://www.mongodb.com/docs/manual/reference/command/collMod/),
  [Atlas unsupported commands](https://www.mongodb.com/docs/atlas/unsupported-commands/).
