################################################################################
# SQS Standard queues (at-least-once, ADR-003) + one DLQ per queue.
# Consumers are idempotent, so duplicates are harmless; poison messages are
# moved to the DLQ after max_receive_count deliveries and raise an alarm
# (modules/observability).
################################################################################

resource "aws_sqs_queue" "dlq" {
  for_each = toset(var.queues)

  name                              = "${var.name_prefix}-${each.key}-dlq"
  message_retention_seconds         = var.dlq_message_retention_seconds
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds

  tags = merge(var.tags, { Name = "${var.name_prefix}-${each.key}-dlq", QueueRole = "dead-letter" })
}

resource "aws_sqs_queue" "this" {
  for_each = toset(var.queues)

  name                              = "${var.name_prefix}-${each.key}"
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  receive_wait_time_seconds         = var.receive_wait_time_seconds
  message_retention_seconds         = var.message_retention_seconds
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = var.max_receive_count
  })

  tags = merge(var.tags, { Name = "${var.name_prefix}-${each.key}", QueueRole = "work" })
}

# Only the matching source queue may use each DLQ (separate resource to avoid a
# dependency cycle between the queue and its DLQ).
resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  for_each = toset(var.queues)

  queue_url = aws_sqs_queue.dlq[each.key].id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.this[each.key].arn]
  })
}

locals {
  all_queues = merge(
    { for name, queue in aws_sqs_queue.this : name => queue },
    { for name, queue in aws_sqs_queue.dlq : "${name}-dlq" => queue },
  )
}

data "aws_iam_policy_document" "tls_only" {
  for_each = local.all_queues

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [each.value.arn]

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

resource "aws_sqs_queue_policy" "tls_only" {
  for_each = local.all_queues

  queue_url = each.value.id
  policy    = data.aws_iam_policy_document.tls_only[each.key].json
}
