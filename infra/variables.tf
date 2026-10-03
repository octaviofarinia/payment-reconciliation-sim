variable "region" {
  type    = string
  default = "us-east-1"
}
variable "account_id" {
  type        = string
  description = "Verified FREE/ACTIVE account ID; never credentials."
  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "Use the verified 12-digit AWS account ID."
  }
}
variable "prerequisites_confirmed" {
  type        = bool
  default     = false
  description = "Set true only after documented account/resource/quota/Atlas preflight. Not a substitute for evidence."
  validation {
    condition     = var.prerequisites_confirmed
    error_message = "Run cloud-smoke.py preflight and verify resource eligibility and Atlas M0 before planning/applying."
  }
}
variable "name" {
  type    = string
  default = "reconciliation-demo"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,29}$", var.name))
    error_message = "Name must be 3-30 lowercase letters, digits or hyphens."
  }
}
variable "instance_type" {
  type        = string
  description = "User-verified eligible x86_64 EC2 type; no paid default."
}
variable "ssh_public_key" {
  type        = string
  description = "OpenSSH public key only. Never supply the private key."
  validation {
    condition     = can(regex("^(ssh-ed25519|ssh-rsa) [A-Za-z0-9+/=]+", var.ssh_public_key))
    error_message = "Supply an OpenSSH public key."
  }
}
variable "developer_cidr" {
  type        = string
  description = "Developer's current public IPv4 /32 for SSH only."
  validation {
    condition     = can(cidrnetmask(var.developer_cidr)) && can(regex("/32$", var.developer_cidr))
    error_message = "SSH requires a single IPv4 /32."
  }
}
variable "worker_artifact_path" {
  type        = string
  description = "Built worker -lambda.jar path, resolved relative to infra."
  default     = "../reconciliation-worker/target/reconciliation-worker-0.0.1-SNAPSHOT-lambda.jar"
  validation {
    condition     = fileexists(var.worker_artifact_path)
    error_message = "Build and verify the Lambda JAR before planning."
  }
}
