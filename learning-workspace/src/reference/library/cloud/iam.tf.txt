resource "aws_iam_role" "api" {
  name_prefix        = "${var.name}-api-"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{
      Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}
resource "aws_iam_instance_profile" "api" {
  name_prefix = "${var.name}-"
  role        = aws_iam_role.api.name
}
resource "aws_iam_role_policy" "api" {
  role   = aws_iam_role.api.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow", Action = ["s3:PutObject", "s3:GetObject", "s3:GetObjectVersion"]
        Resource = "${aws_s3_bucket.settlements.arn}/settlements/*"
      },
      {
        Effect    = "Allow", Action = ["s3:ListBucket", "s3:ListBucketVersions"]
        Resource  = aws_s3_bucket.settlements.arn
        Condition = { StringLike = { "s3:prefix" = ["settlements/*"] } }
      },
      {
        Effect   = "Allow", Action = "lambda:InvokeFunction"
        Resource = "arn:${data.aws_partition.current.partition}:lambda:${var.region}:${var.account_id}:function:${var.name}-worker"
      }
    ]
  })
}
resource "aws_iam_role" "worker" {
  name_prefix        = "${var.name}-worker-"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{
      Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}
resource "aws_iam_role_policy" "worker" {
  role   = aws_iam_role.worker.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow", Action = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = "${aws_s3_bucket.settlements.arn}/settlements/*"
      },
      {
        Effect   = "Allow", Action = "s3:GetObject"
        Resource = "${aws_s3_bucket.settlements.arn}/runtime-config/worker.json"
      },
      {
        Effect   = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.worker.arn}:*"
      },
      {
        # Lambda control plane needs these account-wide ENI actions.
        Effect = "Allow"
        Action = ["ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets",
          "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses"]
        Resource = "*"
      },
      {
        # Prevent function code from using the control-plane ENI permissions.
        Effect = "Deny"
        Action = ["ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets",
          "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses"]
        Resource  = "*"
        Condition = { ArnEquals = {
          "lambda:SourceFunctionArn" = "arn:${data.aws_partition.current.partition}:lambda:${var.region}:${var.account_id}:function:${var.name}-worker"
        } }
      }
    ]
  })
}
