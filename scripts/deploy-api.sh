#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077
if [[ $# != 3 ]]; then
  echo "Usage: deploy-api.sh API_EXEC_JAR RUNTIME_JSON SSH_PRIVATE_KEY" >&2
  exit 2
fi
root="$(cd "$(dirname "$0")/.." && pwd)"
artifact="$(realpath "$1")"
runtime="$(realpath "$2")"
key="$(realpath "$3")"
[[ -f "$artifact" && -f "$runtime" && -f "$key" ]] || { echo "Missing deployment input" >&2; exit 2; }
for tool in terraform aws ssh python3; do command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 2; }; done
scratch="$(mktemp -d)"
trap 'rm -rf -- "$scratch"' EXIT
python3 "$root/scripts/cloud-smoke.py" config "$runtime" "$scratch"
terraform -chdir="$root/infra" output -json > "$scratch/outputs.json"
python3 "$root/scripts/cloud-smoke.py" account --outputs "$scratch/outputs.json"
value() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]]["value"])' "$scratch/outputs.json" "$1"; }
host="$(value ssh_host)"
bucket="$(value bucket)"
region="$(value region)"
python3 -c 'import ipaddress,sys; ipaddress.IPv4Address(sys.argv[1])' "$host"
ssh_opts=(-i "$key" -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10)
ssh "${ssh_opts[@]}" "ec2-user@$host" 'sudo cloud-init status --wait >/dev/null'
# AWS receives a filename, never the token as an argument/environment variable.
# Config is deliberately outside Terraform; no logs/object output is displayed.
if ! aws s3api put-object --region "$region" --bucket "$bucket" \
  --key runtime-config/worker.json --body "$scratch/worker.json" \
  --server-side-encryption AES256 --no-cli-pager >/dev/null 2>"$scratch/aws-error"; then
  echo "Worker configuration upload failed; details withheld" >&2
  exit 1
fi
# SSH stdin is data. No eval, source, interpolation or secret-bearing remote command.
ssh "${ssh_opts[@]}" "ec2-user@$host" \
  "sudo sh -c 'set -eu; umask 077; cat > /etc/reconciliation/runtime.env.next; chmod 600 /etc/reconciliation/runtime.env.next; mv /etc/reconciliation/runtime.env.next /etc/reconciliation/runtime.env'" < "$scratch/api.env"
ssh "${ssh_opts[@]}" "ec2-user@$host" \
  "sudo sh -c 'set -eu; umask 022; cat > /opt/reconciliation/api.jar.next; chmod 644 /opt/reconciliation/api.jar.next; mv /opt/reconciliation/api.jar.next /opt/reconciliation/api.jar'" < "$artifact"
expected="$(sha256sum "$artifact" | cut -d ' ' -f 1)"
actual="$(ssh "${ssh_opts[@]}" "ec2-user@$host" 'sha256sum /opt/reconciliation/api.jar' | cut -d ' ' -f 1)"
[[ "$actual" == "$expected" ]] || { echo "API artifact checksum mismatch" >&2; exit 1; }
ssh "${ssh_opts[@]}" "ec2-user@$host" 'sudo systemctl restart reconciliation-api.service'
echo "Deployment files installed and service restart requested. Verify readiness through the SSH tunnel."
