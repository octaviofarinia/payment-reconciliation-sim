# Deploying the backend to AWS

This tutorial deploys the reconciliation API, the Lambda worker and their AWS
infrastructure from a fresh clone. It assumes a MongoDB Atlas cluster already
exists. [Deployment reference](deployment.md) describes the networking, runtime
configuration and operational scripts.

Commands run from the repository root in a Linux, macOS or WSL shell. Examples
use `sa-east-1`, the repository's default region.

## What gets deployed

One `terraform apply` creates 36 AWS resources. Three manual steps finish the
deployment: allow the EC2 instance in Atlas, trust the instance's SSH host key,
and run `scripts/deploy-api.sh` to install the API and its secrets.

EC2 accepts SSH from a single operator IPv4 address. The API is used through an
SSH tunnel on `127.0.0.1:8080`; port 8080 accepts only the worker security group.
The private S3 bucket accepts authenticated presigned uploads from the generator.

```mermaid
flowchart LR
    dev["Operator workstation<br/>Swagger, scripts"]
    subgraph vpc["VPC 10.42.0.0/16"]
        api["EC2: reconciliation API<br/>public subnet<br/>port 8080 open to the worker only"]
        worker["Lambda worker<br/>private subnet<br/>no internet route"]
    end
    atlas["MongoDB Atlas<br/>EC2 IP /32 only"]
    s3["S3 bucket<br/>private, versioned"]
    dev -- "SSH :22 tunnel" --> api
    dev -- "presigned HTTPS upload" --> s3
    api -- "TLS :27017" --> atlas
    s3 -- "ObjectCreated event" --> worker
    worker -- "reads CSV via S3 gateway endpoint" --> s3
    worker -- "HTTP :8080, worker token" --> api
```

| Piece | Created by | Notes |
| --- | --- | --- |
| VPC, two subnets, S3 gateway endpoint, security groups | Terraform | No NAT gateway, no load balancer |
| EC2 instance (API host) | Terraform | Boot script installs Java 21 and a systemd unit; no application or secrets |
| S3 bucket (private, versioned) | Terraform | Settlement CSVs and the worker configuration |
| Lambda worker (Java 21, 512 MB, 300 s) | Terraform | Uploaded from `reconciliation-worker/target/` |
| API jar and secrets on the instance | `scripts/deploy-api.sh` | Sent over SSH |
| Worker token in S3 | `scripts/deploy-api.sh` | `runtime-config/worker.json` |
| Atlas network access entry | Operator, in Atlas | The instance's public IP as a `/32` |
| SSH host key trust | Operator | One `known_hosts` entry |

The deployment uses four local files. All of them are gitignored; keep them
until the deployment is torn down.

| File | Written by | Contains secrets |
| --- | --- | --- |
| `infra/terraform.tfvars` | Operator | No |
| `.runtime/runtime.json` | Operator | Yes: Mongo URI and two tokens |
| `.runtime/outputs.json` | `terraform output -json` | No |
| `infra/terraform.tfstate` | Terraform | No, but Terraform needs it to change or destroy anything later |

## Prerequisites

| Tool | Version | Used for |
| --- | --- | --- |
| Terraform | >= 1.9, < 2 | Infrastructure |
| AWS CLI v2 | 2.32.0 or later, for `aws login` | Authentication and deployment scripts |
| Java JDK | 21 | Building the jars, optional end-to-end run |
| Docker | Any recent version | Acceptance tests during the build |
| Python 3 | Any 3.x | Deployment and teardown helpers |
| OpenSSH, curl | Any | Deployment, tunnel, public IP lookup |

You also need an AWS account and a MongoDB Atlas cluster with a database user.
The repository targets an Atlas M0 cluster.

## Step 1: Set up AWS access

### Use a non-root IAM user

Deploy as an IAM user rather than the account's root user. Root sessions can't
be restricted, and a leaked root session controls the whole account.

1. Sign in to the AWS console. Go to IAM → Users → Create user. Name the user
   `deployer`, enable console access, and attach the `AdministratorAccess`
   policy.
2. Sign in as `deployer` and assign an MFA device under Security credentials.
3. Create a CLI profile for the user:

~~~sh
aws login --profile deployer
aws configure set region sa-east-1 --profile deployer
~~~

### Select the profile

`infra/versions.tf` pins the AWS provider to 6.69.1. Provider 6.23.0 and later
read `aws login` sessions directly, so Terraform, the deployment scripts and the
AWS CLI all use the same profile.

Run the following in every terminal used for deployment:

~~~sh
export AWS_PROFILE=deployer
aws sts get-caller-identity --query Arn --output text
~~~

The ARN should end in `user/deployer`, not `:root`. `AWS_PROFILE` applies to
every AWS tool in that terminal that isn't given `--profile`. When the login
session expires, run `aws login --profile deployer` again.

Other authentication methods, such as IAM Identity Center (`aws sso login`),
work the same way. Set `AWS_PROFILE` to that profile's name instead.

## Step 2: Check account limits and cost

### Lambda concurrency

The worker reserves one concurrent Lambda execution. AWS requires at least 100
executions to stay unreserved, so the account needs **at least 101 unreserved**.
Some accounts, particularly new ones, have a limit of 10. With that limit,
`terraform apply` fails on the Lambda function.

Check the current value:

~~~sh
aws lambda get-account-settings --region sa-east-1 \
  --query AccountLimit.UnreservedConcurrentExecutions
~~~

If it prints less than 101, request an increase to 1,000, the AWS default:

~~~sh
aws service-quotas request-service-quota-increase --region sa-east-1 \
  --service-code lambda --quota-code L-B99A9384 --desired-value 1000
~~~

Track the request, and don't apply until the first command prints 101 or more:

~~~sh
aws service-quotas list-requested-service-quota-change-history --region sa-east-1 \
  --service-code lambda --query 'RequestedQuotas[].{value:DesiredValue,status:Status}'
~~~

Wait for sufficient account capacity before deploying. Keep
`reserved_concurrent_executions = 1`; the reservation is part of the configured
worker bounds.

### Instance type

The API host must be an x86_64 instance type. Terraform places it in the
region's first availability zone, so the type must be offered there:

~~~sh
aws ec2 describe-instance-type-offerings --region sa-east-1 \
  --location-type availability-zone \
  --filters Name=location,Values=sa-east-1a Name=instance-type,Values=t3.small \
  --query 'InstanceTypeOfferings[].InstanceType' --output text
~~~

The command prints the type name if it's available.

### Cost

On-demand prices in `sa-east-1`, from the AWS Price List API on 2026-10-16:

| Item | Price | Per month (730 h) |
| --- | --- | --- |
| EC2 t3.small | $0.0336/h | $24.53 |
| EC2 t3.micro | $0.0168/h | $12.26 |
| Public IPv4 address | $0.005/h | $3.65 |
| 8 GiB gp3 volume | $0.152 per GB-month | $1.22 |

These reference rates cover EC2, IPv4 and EBS only. The total also includes
Lambda, S3 and CloudWatch usage, with applicable account credits and benefits.
Check current regional rates in the [AWS Pricing Calculator](https://calculator.aws/)
before estimating a new deployment.

Before deploying, create a cost budget with an email alert: Billing and Cost
Management → Budgets → Create budget. Tear the deployment down when it's no
longer needed.

### Free-plan preflight (optional)

`scripts/cloud-smoke.py preflight` checks a Free-plan account's state, credits
and Lambda quota against evidence recorded in `.runtime/eligibility.json`. It
requires the account plan to be `FREE` with remaining credits, so it refuses
Paid-plan accounts. [deployment.md](deployment.md) describes it. The rest of
this guide doesn't depend on it.

## Step 3: Prepare MongoDB Atlas

The database user needs `readWrite` and `dbAdmin` on the application database.
The connection string must name that database.

### Database user roles

In Atlas, open Database Access, edit the application user, and choose Specific
Privileges. Add two entries:

- `readWrite` on database `reconciliation`
- `dbAdmin` on database `reconciliation`

The API manages its own schema. On startup it creates missing `purchases`,
`business_days` and `reconciliation_runs` collections with validators and
indexes. For existing collections it updates validators with `collMod`, which
requires privileges beyond `readWrite`. The database-scoped `dbAdmin` role
supplies those privileges.

### Network access

Leave the access list alone for now. Step 7 adds the EC2 instance's public IP as
a single `/32`. Never allow `0.0.0.0/0`.

### Connection string

In Atlas, open Connect → Drivers and copy the `mongodb+srv://` string. Then:

1. Replace the password placeholder with the user's password.
2. Insert the database name between the `/` and the `?`.

~~~text
mongodb+srv://appuser:PASSWORD@cluster0.xxxxx.mongodb.net/reconciliation?retryWrites=true&w=majority
~~~

The deployment script rejects a URI without a database name or with TLS
verification disabled. Percent-encode any `@ : / ? # [ ] %` characters in the
password, or use an Atlas-generated password, which avoids them. Keep the string
private. Step 8 reads it from a hidden prompt.

## Step 4: Build the jars

Build from the commit you intend to deploy:

~~~sh
java -version                       # must report 21
docker info > /dev/null && echo docker ok
./mvnw clean verify
~~~

The build runs the unit and acceptance tests; the acceptance tests start MongoDB
in Docker. Two of the resulting jars are deployed:

| Jar | Deployed by |
| --- | --- |
| `reconciliation-worker/target/reconciliation-worker-0.0.1-SNAPSHOT-lambda.jar` | Terraform, as the Lambda function |
| `reconciliation-api/target/reconciliation-api-0.0.1-SNAPSHOT-exec.jar` | `deploy-api.sh`, to the EC2 instance |

Terraform reads the worker jar on every plan, apply **and destroy**. If the jar
is missing, even `terraform destroy` stops with `Build and verify the Lambda JAR
before planning.` Keep a built jar in place for as long as the deployment
exists. Each rebuild changes the jar's hash, so the next plan shows an in-place
Lambda update.

## Step 5: Configure Terraform variables

Create an SSH key for the instance if you don't have one:

~~~sh
ssh-keygen -t ed25519 -f ~/.ssh/reconciliation-prod
~~~

`deploy-api.sh` runs SSH non-interactively. If you set a passphrase, load the
key with `ssh-add ~/.ssh/reconciliation-prod` before deploying.

Find your public IPv4 address. Run this from the network you'll SSH from:

~~~sh
curl -4 -s https://checkip.amazonaws.com
~~~

Copy the example file and fill it in:

~~~sh
cp infra/terraform.tfvars.example infra/terraform.tfvars
~~~

~~~hcl
region                  = "sa-east-1"
account_id              = "123456789012"
prerequisites_confirmed = true
name                    = "reconciliation-demo"
instance_type           = "t3.small"
developer_cidr          = "203.0.113.10/32"
ssh_public_key          = "ssh-ed25519 AAAA... reconciliation-deploy"
worker_artifact_path    = "../reconciliation-worker/target/reconciliation-worker-0.0.1-SNAPSHOT-lambda.jar"
~~~

| Variable | Meaning |
| --- | --- |
| `region` | AWS region for every resource |
| `account_id` | 12-digit account ID. The provider refuses to act on any other account. |
| `prerequisites_confirmed` | Must be `true` to plan. Set it after completing Steps 2 and 3. Terraform validates the flag; account and Atlas checks are performed separately. |
| `name` | Prefix for resource names: 3–30 lowercase letters, digits or hyphens |
| `instance_type` | x86_64 type for the API host. t3.small has 2 GiB of memory; t3.micro has 1 GiB for the OS and JVM, whose heap is capped at 384 MiB. |
| `developer_cidr` | The only address allowed to SSH in: the public IPv4 from `curl` above, followed by `/32` |
| `ssh_public_key` | Contents of `~/.ssh/reconciliation-prod.pub`. Never the private key. |
| `worker_artifact_path` | Lambda jar path, relative to `infra/` |

The IPv4 flag in `curl -4` matters: the SSH rule accepts IPv4 only. A VPN or
mobile hotspot gives a different address. If your address changes later, update
`developer_cidr` and apply again (see Operations).

## Step 6: Plan and apply

Apply only after Step 2's concurrency check prints 101 or more.

~~~sh
export AWS_PROFILE=deployer
terraform -chdir=infra fmt -check
terraform -chdir=infra init
terraform -chdir=infra validate
terraform -chdir=infra plan -out=reviewed.tfplan
~~~

- **`fmt -check`:** If it lists files, run `terraform -chdir=infra fmt` and
  commit the result.
- **`init`:** Downloads the pinned provider and verifies it against the committed
  `infra/.terraform.lock.hcl`. Review and commit lockfile changes when updating
  provider versions or platform checksums.

A first deployment plans `36 to add, 0 to change, 0 to destroy`. Before
applying, check:

- `aws_instance.api` uses the intended `instance_type`.
- `aws_vpc_security_group_ingress_rule.ssh` contains your `/32`.
- `aws_lambda_function.worker` has `reserved_concurrent_executions = 1`,
  `memory_size = 512` and `timeout = 300`.
- No NAT gateway or load balancer appears.

`scripts/cloud-smoke.py plan` cannot validate plans where Terraform represents
Lambda environment variables as an unknown map. It returns
`Cannot verify unknown Lambda environment keys` for that representation. Review
`infra/compute.tf` and the actual plan manually: the Lambda environment must
contain only `RECONCILIATION_API_URI`, `RECONCILIATION_CONFIG_BUCKET` and
`RECONCILIATION_CONFIG_KEY`, with no runtime secrets.

Apply the reviewed plan and save the outputs:

~~~sh
terraform -chdir=infra apply reviewed.tfplan
umask 077; mkdir -p .runtime
terraform -chdir=infra output -json > .runtime/outputs.json
~~~

If anything changes after planning, plan again; Terraform refuses to apply a
stale plan. If an apply stops partway, fix the cause, then plan and apply again.
Terraform creates only what's missing.

## Step 7: Connect the instance to Atlas and trust its host key

The API can't reach Atlas, and `deploy-api.sh` can't connect, until both parts of
this step are done. The script uses `StrictHostKeyChecking=yes`, so it stops at
the first SSH call if `known_hosts` has no entry for the instance.

~~~sh
IP=$(terraform -chdir=infra output -raw ssh_host); echo "$IP"
~~~

### Atlas access

In Atlas, open Network Access → Add IP Address and enter the address above
followed by `/32`.

### SSH host key

Compare the key the instance presents over the network with the fingerprint AWS
recorded in its boot log:

~~~sh
ID=$(aws ec2 describe-instances --region sa-east-1 --filters Name=ip-address,Values="$IP" \
  --query 'Reservations[].Instances[].InstanceId' --output text)
aws ec2 get-console-output --region sa-east-1 --instance-id "$ID" --latest --output text \
  | sed -n '/BEGIN SSH HOST KEY FINGERPRINTS/,/END SSH HOST KEY FINGERPRINTS/p'
ssh-keyscan -t ed25519 "$IP" 2>/dev/null > /tmp/hostkey
ssh-keygen -lf /tmp/hostkey
~~~

If the `SHA256:` fingerprint on the ED25519 line matches in both outputs, trust
the key and test the connection:

~~~sh
cat /tmp/hostkey >> ~/.ssh/known_hosts
ssh -i ~/.ssh/reconciliation-prod -o StrictHostKeyChecking=yes ec2-user@"$IP" \
  'cloud-init status --wait'
~~~

The last command prints `status: done`. The boot log can take a few minutes to
appear, and its format varies by AMI. If it has no fingerprint block, the
fallback is to connect once with `-o StrictHostKeyChecking=accept-new`. That
trusts whatever answers at the address the first time.

## Step 8: Create the runtime secrets and deploy the API

`.runtime/runtime.json` is the only secrets file. `deploy-api.sh` turns it into
an environment file on the instance and a token file in S3.

### Create the secrets file

The file holds exactly three fields: two different random tokens and the Atlas
URI. The commands below prompt for the URI without echoing it or saving it in
shell history, and generate both tokens:

~~~sh
umask 077; mkdir -p .runtime
read -rsp 'Atlas URI: ' MONGO_URI; echo; export MONGO_URI
python3 -c 'import json,os,secrets; print(json.dumps({"demoToken": secrets.token_urlsafe(32), "workerToken": secrets.token_urlsafe(32), "mongoUri": os.environ["MONGO_URI"]}, indent=2))' > .runtime/runtime.json
unset MONGO_URI; chmod 600 .runtime/runtime.json
~~~

The result has this shape:

~~~json
{
  "demoToken": "random value",
  "workerToken": "different random value",
  "mongoUri": "mongodb+srv://appuser:PASSWORD@cluster0.xxxxx.mongodb.net/reconciliation?retryWrites=true&w=majority"
}
~~~

The tokens are shared secrets the API checks. AWS and Atlas don't issue them.
`demoToken` is the one used in Swagger.

### Deploy

~~~sh
export AWS_PROFILE=deployer
scripts/deploy-api.sh \
  reconciliation-api/target/reconciliation-api-0.0.1-SNAPSHOT-exec.jar \
  .runtime/runtime.json \
  ~/.ssh/reconciliation-prod
~~~

The script, in order:

1. Validates `runtime.json`: exactly three fields, mode 0600, distinct tokens
   without whitespace, and a TLS URI that names a database. A failure here stops
   it before anything changes.
2. Reads the Terraform outputs and confirms the AWS account matches.
3. Waits for the instance's first-boot setup to finish.
4. Uploads `{"workerToken": …}` to `s3://BUCKET/runtime-config/worker.json`.
5. Writes `/etc/reconciliation/runtime.env` on the instance (root-owned, mode
   0600).
6. Copies the jar to `/opt/reconciliation/api.jar` and verifies its SHA-256.
7. Restarts `reconciliation-api.service`.

It ends with `Deployment files installed and service restart requested.` That
confirms the restart was requested, not that the API started; Step 9 checks
that.

| Secret | Stored in | Read by |
| --- | --- | --- |
| Mongo URI | `/etc/reconciliation/runtime.env` as `SPRING_MONGODB_URI` | API |
| `demoToken` | Same file, as `RECONCILIATION_SECURITY_DEMOTOKEN` | API |
| `workerToken` | Same file, as `RECONCILIATION_SECURITY_WORKERTOKEN`, and S3 `worker.json` | API and Lambda |

None of these values reach Terraform state, the instance's user data or git. The
Lambda never receives the Mongo URI.

## Step 9: Verify the deployment

The API is ready when three things are true:

- The service log shows a clean start.
- The three collections exist in Atlas.
- An authorized call through Swagger returns `200`.

1. **Service log.** Look for Spring Boot's `Started …` line and no repeating
   errors. A Mongo timeout usually means the Atlas `/32` is missing or the URI
   is wrong.

    ~~~sh
    ssh -i ~/.ssh/reconciliation-prod ec2-user@"$IP" \
      'systemctl is-active reconciliation-api; sudo journalctl -u reconciliation-api -n 100 --no-pager'
    ~~~

2. **Atlas.** Browse Collections for the `reconciliation` database should list
   `purchases`, `business_days` and `reconciliation_runs`.
3. **Swagger through the tunnel.** Start the tunnel in a separate terminal and
   leave it running:

    ~~~sh
    ssh -i ~/.ssh/reconciliation-prod -o StrictHostKeyChecking=yes -o ExitOnForwardFailure=yes \
      -N -L 127.0.0.1:8080:127.0.0.1:8080 ec2-user@"$(terraform -chdir=infra output -raw ssh_host)"
    ~~~

    Open `http://127.0.0.1:8080/swagger-ui/index.html`, choose Authorize, and
    enter `demoToken` from `.runtime/runtime.json`. Run
    `GET /api/v1/reconciliation-runs` with any `businessDate`. A `200` response
    confirms the token works and the API can read from Atlas; an unused date
    returns `[]`.

### Optional: end-to-end run

The scenario generator exercises the full path: API, presigned S3 upload,
Lambda worker, and the worker's callback to the API. With the tunnel open:

~~~sh
export RECONCILIATION_DEMO_TOKEN=$(python3 -c 'import json; print(json.load(open(".runtime/runtime.json"))["demoToken"])')
java -jar scenario-generator/target/scenario-generator-0.0.1-SNAPSHOT-exec.jar \
  --base-url http://127.0.0.1:8080 --business-date YYYY-MM-DD --seed 0 --scenario canonical
unset RECONCILIATION_DEMO_TOKEN
~~~

Success prints `Verified runId=<id> results=5`.

- **Date:** Use a date before today in `America/Buenos_Aires` that hasn't been
  used yet.
- **Repeating it:** The canonical scenario uses globally unique fixed references.
  Use a fresh disposable database for a new canonical dataset, or rerun the same
  date to exercise replay. Recreating a disposable database requires restarting
  the API to install its collections, validators and indexes.
- **If it fails:** It prints the run ID and the `reprocess` route to retry with.

## Operations

| Situation | Action |
| --- | --- |
| SSH times out after your public IP changed | Update `developer_cidr`, then plan and apply. Only the SSH rule changes. |
| Pausing to save cost | `aws ec2 stop-instances --region sa-east-1 --instance-ids "$ID"`. Instance and IPv4 charges stop. The volume and its files remain. |
| Resuming after a pause | Start the instance; it gets a new public IP. Run `terraform -chdir=infra apply -refresh-only`, re-export `outputs.json`, replace the Atlas `/32`, and add the new address to `known_hosts`. The host key is unchanged, so the fingerprint should match the earlier one. |
| API code changed | Rebuild and run `deploy-api.sh` again. Keep the previous jar for rollback. |
| Worker code changed | Rebuild, then plan and apply. The plan shows an in-place Lambda update. |
| Boot script or service template changed | Terraform replaces the instance. The new instance has new IPs, a new host key and an empty disk: repeat Steps 7–9. Terraform updates the Lambda's API address itself. |
| Rotating tokens | Stop all uploads. Edit `runtime.json` and run `deploy-api.sh`. Then run `terraform -chdir=infra plan -replace=aws_lambda_function.worker -out=reviewed.tfplan` and apply it, because warm Lambda instances keep the old token in memory. |
| A run is incomplete after more than 20 minutes | Call `POST /api/v1/reconciliation-runs/{runId}/reprocess`. It reprocesses the original uploaded file. |
| Worker logs | `aws logs tail /aws/lambda/reconciliation-demo-worker --since 30m --region sa-east-1`. Logs are kept for 7 days. |
| API logs | The `journalctl` command from Step 9. |
| `ExpiredToken` errors | `aws login --profile deployer`. |

## Teardown

`scripts/teardown.sh` empties the bucket and runs `terraform destroy`. Atlas
cleanup is manual.

1. Close the tunnel and stop anything that uploads. Wait 10 minutes so every
   issued upload URL has expired.
2. Confirm the worker jar and `infra/terraform.tfvars` still exist. Destroy
   fails without them.
3. Run the teardown script:

    ~~~sh
    export AWS_PROFILE=deployer
    scripts/teardown.sh --confirm-bucket "$(terraform -chdir=infra output -raw bucket)"
    ~~~

    The script verifies the account, disables S3 notifications, sets the
    Lambda's concurrency to 0, and deletes every object version. Then it runs
    `terraform destroy`, which asks for `yes`.
4. AWS can be slow to release the Lambda's network interfaces. If destroy times
   out on a subnet or security group, run `terraform -chdir=infra destroy`
   again.
5. Confirm that `terraform -chdir=infra state list` prints nothing.
6. In Atlas, remove the instance's `/32` and, if it's no longer needed, the
   database user.
7. Check Billing a day later, because usage reporting lags.

Keep the state file until step 5 prints nothing. Deleting the state removes
nothing from AWS; it only makes Terraform lose track of what it created.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `No valid credential sources found` | No profile selected, not logged in, or an AWS provider older than 6.23.0 | `export AWS_PROFILE=deployer`; `aws login --profile deployer` |
| `ExpiredToken` in any AWS call | Login session expired | `aws login --profile deployer` |
| Plan fails with `Run cloud-smoke.py preflight and verify resource eligibility and Atlas M0 before planning/applying.` | `prerequisites_confirmed` is `false` | Complete Steps 2–3, then set it to `true` |
| Apply fails on `aws_lambda_function.worker` with a reserved or unreserved concurrency error | Lambda concurrency limit below 101 | Step 2, then plan and apply again |
| Apply fails on the Lambda because the execution role can't create network interfaces | New IAM permissions haven't propagated | Wait a minute, then plan and apply again |
| `Saved plan is stale` | State changed after planning | Plan again |
| `Build and verify the Lambda JAR before planning.` | Worker jar missing | `./mvnw clean verify` |
| `Host key verification failed.` from `deploy-api.sh` | No `known_hosts` entry for the current address | Step 7 |
| SSH or `deploy-api.sh` hangs, then times out | Public IP no longer matches `developer_cidr` | Update it, then plan and apply |
| `FAIL Runtime input must have mode 0600` | File permissions | `chmod 600 .runtime/runtime.json` |
| `FAIL Invalid runtime configuration fields` | Missing or extra keys | Use exactly `demoToken`, `workerToken`, `mongoUri` |
| `FAIL Invalid or equal runtime tokens` | Tokens are equal or contain whitespace | Regenerate them (Step 8) |
| `FAIL Mongo URI must identify an application database` | No database name in the URI | Step 3 |
| `FAIL Wrong AWS account` | Profile points at a different account | Check `AWS_PROFILE` and `account_id` |
| Service restarts repeatedly; the log shows Mongo timeouts | Atlas `/32` missing or wrong | Step 7 |
| Log shows `not authorized` for `collMod` | User lacks `dbAdmin` | Step 3 |

## References

- [deployment.md](deployment.md): topology, Free-plan preflight and runtime
  configuration
- [api-access.md](api-access.md): tokens, roles and HTTP error codes
- [demo-evidence.md](demo-evidence.md): cloud checks, benchmarks and recovery
  verification
- [Atlas unsupported and limited commands](https://www.mongodb.com/docs/atlas/unsupported-commands/)
- [Atlas Free cluster limits](https://www.mongodb.com/docs/atlas/reference/free-shared-limitations/)
