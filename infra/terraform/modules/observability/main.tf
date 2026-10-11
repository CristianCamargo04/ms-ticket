################################################################################
# Observability: log groups, alarm topic, alarms, dashboard and cost budget.
################################################################################

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region

  # Business metrics extracted from logs live in their own namespace.
  metrics_namespace = "${var.name_prefix}/application"
}

# ------------------------------------------------------------ log groups ----

resource "aws_cloudwatch_log_group" "service" {
  for_each = toset(var.services)

  #checkov:skip=CKV_AWS_338:Retention is set per environment (cost control); prod keeps application logs for a year.
  name                        = "/ecs/${var.name_prefix}/${each.key}"
  retention_in_days           = var.log_retention_days
  kms_key_id                  = var.logs_kms_key_arn
  deletion_protection_enabled = var.log_group_deletion_protection

  tags = merge(var.tags, { Service = each.key })
}

resource "aws_cloudwatch_log_group" "ecs_exec" {
  #checkov:skip=CKV_AWS_338:Retention is set per environment (cost control).
  name                        = "/ecs/${var.name_prefix}/exec-sessions"
  retention_in_days           = var.log_retention_days
  kms_key_id                  = var.logs_kms_key_arn
  deletion_protection_enabled = var.log_group_deletion_protection

  tags = var.tags
}

# A captured payment for an order whose tickets were already released needs a
# manual refund (implementation-spec section 5, ProcessOrderPayment): page on it.
resource "aws_cloudwatch_log_metric_filter" "payment_captured_for_released_order" {
  count = contains(var.services, "worker") ? 1 : 0

  name           = "${var.name_prefix}-payment-captured-for-released-order"
  log_group_name = aws_cloudwatch_log_group.service["worker"].name
  pattern        = "\"payment captured for released order\""

  metric_transformation {
    name          = "PaymentCapturedForReleasedOrder"
    namespace     = local.metrics_namespace
    value         = "1"
    default_value = "0"
  }
}

# ------------------------------------------------------------ alarm topic ----

resource "aws_sns_topic" "alarms" {
  name              = "${var.name_prefix}-alarms"
  display_name      = "${var.name_prefix} alarms"
  kms_master_key_id = var.sns_kms_key_arn

  tags = var.tags
}

data "aws_iam_policy_document" "alarms_topic" {
  statement {
    sid       = "AllowCloudWatchAlarmsOfThisAccount"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarms.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:cloudwatch:${local.region}:${local.account_id}:alarm:*"]
    }
  }

  statement {
    sid    = "AllowAccountPrincipalsThroughIam"
    effect = "Allow"
    actions = [
      "sns:GetTopicAttributes",
      "sns:ListSubscriptionsByTopic",
      "sns:Publish",
      "sns:SetTopicAttributes",
      "sns:Subscribe",
    ]
    resources = [aws_sns_topic.alarms.arn]

    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${local.account_id}:root"]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sns:Publish", "sns:Subscribe"]
    resources = [aws_sns_topic.alarms.arn]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_sns_topic_policy" "alarms" {
  arn    = aws_sns_topic.alarms.arn
  policy = data.aws_iam_policy_document.alarms_topic.json
}

resource "aws_sns_topic_subscription" "email" {
  for_each = toset(var.alarm_email_addresses)

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = each.value
}
