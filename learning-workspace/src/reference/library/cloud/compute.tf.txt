data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }
  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}
data "aws_ec2_instance_type" "api" {
  instance_type = var.instance_type
}
resource "aws_key_pair" "developer" {
  key_name_prefix = "${var.name}-"
  public_key      = var.ssh_public_key
}
resource "aws_instance" "api" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.public.id
  vpc_security_group_ids      = [aws_security_group.api.id]
  associate_public_ip_address = true
  key_name                    = aws_key_pair.developer.key_name
  iam_instance_profile        = aws_iam_instance_profile.api.name
  user_data_replace_on_change = true
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }
  root_block_device {
    encrypted             = true
    volume_type           = "gp3"
    volume_size           = 8
    delete_on_termination = true
  }
  user_data = templatefile("${path.module}/templates/bootstrap.sh.tftpl", {
    service = templatefile("${path.module}/templates/api.service.tftpl", {
      region        = var.region
      bucket        = aws_s3_bucket.settlements.id
      function_name = "${var.name}-worker"
    })
  })
  lifecycle {
    precondition {
      condition     = contains(data.aws_ec2_instance_type.api.supported_architectures, "x86_64")
      error_message = "The API instance type must support x86_64."
    }
  }
  depends_on = [aws_route.internet, aws_iam_role_policy.api]
}
resource "aws_cloudwatch_log_group" "worker" {
  name              = "/aws/lambda/${var.name}-worker"
  retention_in_days = 7
}
resource "aws_lambda_function" "worker" {
  function_name                  = "${var.name}-worker"
  role                           = aws_iam_role.worker.arn
  runtime                        = "java21"
  architectures                  = ["x86_64"]
  handler                        = "org.octavio.paymentreconciliationsim.worker.ReconciliationHandler::handleRequest"
  filename                       = var.worker_artifact_path
  source_code_hash               = filebase64sha256(var.worker_artifact_path)
  memory_size                    = 512
  timeout                        = 300
  reserved_concurrent_executions = 1
  environment {
    variables = {
      RECONCILIATION_API_URI       = "http://${aws_instance.api.private_ip}:8080"
      RECONCILIATION_CONFIG_BUCKET = aws_s3_bucket.settlements.id
      RECONCILIATION_CONFIG_KEY    = "runtime-config/worker.json"
    }
  }
  vpc_config {
    subnet_ids         = [aws_subnet.worker.id]
    security_group_ids = [aws_security_group.worker.id]
  }
  depends_on = [aws_iam_role_policy.worker, aws_vpc_endpoint.s3]
}
resource "aws_lambda_function_event_invoke_config" "worker" {
  function_name                = aws_lambda_function.worker.function_name
  maximum_event_age_in_seconds = 3600
  maximum_retry_attempts       = 2
}
