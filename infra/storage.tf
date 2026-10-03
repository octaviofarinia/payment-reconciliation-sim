resource "aws_s3_bucket" "settlements" {
  bucket_prefix = "${var.name}-"
  force_destroy = false
}
resource "aws_s3_bucket_public_access_block" "settlements" {
  bucket                  = aws_s3_bucket.settlements.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_ownership_controls" "settlements" {
  bucket = aws_s3_bucket.settlements.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}
resource "aws_s3_bucket_versioning" "settlements" {
  bucket = aws_s3_bucket.settlements.id
  versioning_configuration {
    status = "Enabled"
  }
}
resource "aws_s3_bucket_server_side_encryption_configuration" "settlements" {
  bucket = aws_s3_bucket.settlements.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}
resource "aws_s3_bucket_policy" "tls" {
  bucket = aws_s3_bucket.settlements.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.settlements.arn, "${aws_s3_bucket.settlements.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}
resource "aws_lambda_permission" "settlements" {
  statement_id   = "AllowSettlementS3"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.worker.function_name
  principal      = "s3.amazonaws.com"
  source_arn     = aws_s3_bucket.settlements.arn
  source_account = data.aws_caller_identity.current.account_id
}
resource "aws_s3_bucket_notification" "settlements" {
  bucket = aws_s3_bucket.settlements.id
  lambda_function {
    lambda_function_arn = aws_lambda_function.worker.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = "settlements/"
    filter_suffix       = ".csv"
  }
  depends_on = [
    aws_lambda_permission.settlements,
    aws_s3_bucket_versioning.settlements,
    aws_lambda_function_event_invoke_config.worker
  ]
}
