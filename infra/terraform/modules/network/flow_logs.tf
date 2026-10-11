################################################################################
# VPC flow logs -> CloudWatch Logs (KMS-encrypted, bounded retention).
################################################################################

resource "aws_cloudwatch_log_group" "flow_logs" {
  #checkov:skip=CKV_AWS_338:Retention is set per environment (cost control); prod uses >= 365 days.
  name              = "/aws/vpc/flow-logs/${var.name_prefix}"
  retention_in_days = var.flow_logs_retention_days
  kms_key_id        = var.flow_logs_kms_key_arn

  tags = var.tags
}

data "aws_iam_policy_document" "flow_logs_assume" {
  statement {
    sid     = "VpcFlowLogsService"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }

    # Confused-deputy protection.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:ec2:${local.region}:${local.account_id}:vpc-flow-log/*"]
    }
  }
}

resource "aws_iam_role" "flow_logs" {
  name               = "${var.name_prefix}-vpc-flow-logs"
  description        = "Lets VPC Flow Logs deliver records to its CloudWatch log group"
  assume_role_policy = data.aws_iam_policy_document.flow_logs_assume.json

  tags = var.tags
}

data "aws_iam_policy_document" "flow_logs" {
  statement {
    sid       = "WriteToFlowLogGroup"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:DescribeLogStreams", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.flow_logs.arn}:*"]
  }

  statement {
    sid       = "DescribeLogGroups"
    effect    = "Allow"
    actions   = ["logs:DescribeLogGroups"]
    resources = ["arn:${local.partition}:logs:${local.region}:${local.account_id}:log-group:*"]
  }
}

resource "aws_iam_role_policy" "flow_logs" {
  name   = "write-flow-logs"
  role   = aws_iam_role.flow_logs.id
  policy = data.aws_iam_policy_document.flow_logs.json
}

resource "aws_flow_log" "this" {
  vpc_id                   = aws_vpc.this.id
  traffic_type             = var.flow_logs_traffic_type
  log_destination_type     = "cloud-watch-logs"
  log_destination          = aws_cloudwatch_log_group.flow_logs.arn
  iam_role_arn             = aws_iam_role.flow_logs.arn
  max_aggregation_interval = var.flow_logs_max_aggregation_interval

  tags = merge(var.tags, { Name = var.name_prefix })
}
