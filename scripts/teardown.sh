#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077
if [[ $# != 2 || "$1" != "--confirm-bucket" ]]; then
  echo "Usage: teardown.sh --confirm-bucket EXACT_TERRAFORM_BUCKET" >&2
  echo "Stop API/generators and let all presigned uploads expire first; see docs/deployment.md." >&2
  exit 2
fi
root="$(cd "$(dirname "$0")/.." && pwd)"
for tool in terraform aws python3; do command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 2; }; done
scratch="$(mktemp -d)"
trap 'rm -rf -- "$scratch"' EXIT
terraform -chdir="$root/infra" output -json > "$scratch/outputs.json"
python3 "$root/scripts/cloud-smoke.py" account --outputs "$scratch/outputs.json"
value() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]]["value"])' "$scratch/outputs.json" "$1"; }
bucket="$(value bucket)"
region="$(value region)"
function="$(value function_name)"
[[ "$2" == "$bucket" ]] || { echo "Bucket confirmation does not match this state" >&2; exit 2; }
# Prevent further deliveries before deleting every version, including runtime config.
aws s3api put-bucket-notification-configuration --region "$region" --bucket "$bucket" --notification-configuration '{}' --no-cli-pager
aws lambda put-function-concurrency --region "$region" --function-name "$function" --reserved-concurrent-executions 0 --no-cli-pager >/dev/null
python3 "$root/scripts/cloud-smoke.py" empty-bucket --outputs "$scratch/outputs.json" --confirm-bucket "$bucket"
# Keep Terraform's interactive destructive-plan confirmation.
terraform -chdir="$root/infra" destroy
echo "AWS destroy finished. Remove the Atlas IP entry/database user and review account resource/usage inventory."
