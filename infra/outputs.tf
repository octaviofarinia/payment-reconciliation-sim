output "ssh_host" {
  value = aws_instance.api.public_ip
}
output "api_private_address" {
  value = "http://${aws_instance.api.private_ip}:8080"
}
output "bucket" {
  value = aws_s3_bucket.settlements.id
}
output "function_name" {
  value = aws_lambda_function.worker.function_name
}
output "function_arn" {
  value = aws_lambda_function.worker.arn
}
output "log_group" {
  value = aws_cloudwatch_log_group.worker.name
}
output "region" {
  value = var.region
}
output "account_id" {
  value = var.account_id
}
output "api_security_group_id" {
  value = aws_security_group.api.id
}
output "worker_security_group_id" {
  value = aws_security_group.worker.id
}
output "worker_subnet_id" {
  value = aws_subnet.worker.id
}
output "bucket_arn" {
  value = aws_s3_bucket.settlements.arn
}
output "log_group_arn" {
  value = aws_cloudwatch_log_group.worker.arn
}
