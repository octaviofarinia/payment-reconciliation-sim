provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]
  default_tags {
    tags = {
      Project = var.name
      Purpose = "payment-reconciliation-demo"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_availability_zones" "available" {
  state = "available"
}
