################################################################################
# Least-privilege application permissions, derived from the data access of
# each runtime mode (docs/design/implementation-spec.md sections 4-6).
#
# Notes
#  - TransactWriteItems is not an IAM action: DynamoDB authorizes every item of
#    a transaction with its own action (UpdateItem / PutItem / ConditionCheckItem).
#  - ticket-transitions is append-only for everybody (PutItem, never Update/Delete):
#    the audit trail cannot be rewritten by the application.
#  - No DeleteItem/Scan/DescribeTable/CreateTable anywhere; DeleteItem only on the
#    lock table (lease release).
################################################################################

locals {
  tables  = module.dynamodb.table_arns
  indexes = module.dynamodb.index_arns
  queues  = module.sqs.queue_arns
}

# --------------------------------------------------------------------- API --
# create/list/get events, availability, place/get/confirm/cancel orders,
# complimentary tickets, ticket history; publishes reservation/payment messages.

data "aws_iam_policy_document" "api_task" {
  statement {
    sid       = "Events"
    effect    = "Allow"
    actions   = ["dynamodb:ConditionCheckItem", "dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem"]
    resources = [local.tables["events"]]
  }

  statement {
    sid       = "ListEventsByStatus"
    effect    = "Allow"
    actions   = ["dynamodb:Query"]
    resources = [local.indexes["events/status-eventDate-index"]]
  }

  statement {
    # BatchWriteItem: ticket pre-generation when an event is created (CreateEventUseCase).
    sid       = "Tickets"
    effect    = "Allow"
    actions   = ["dynamodb:BatchGetItem", "dynamodb:BatchWriteItem", "dynamodb:ConditionCheckItem", "dynamodb:GetItem", "dynamodb:UpdateItem"]
    resources = [local.tables["tickets"]]
  }

  statement {
    sid       = "Orders"
    effect    = "Allow"
    actions   = ["dynamodb:ConditionCheckItem", "dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem"]
    resources = [local.tables["orders"]]
  }

  statement {
    sid       = "TicketTransitionsAppendAndRead"
    effect    = "Allow"
    actions   = ["dynamodb:PutItem", "dynamodb:Query"]
    resources = [local.tables["ticket-transitions"]]
  }

  statement {
    sid       = "PublishOrderMessages"
    effect    = "Allow"
    actions   = ["sqs:GetQueueUrl", "sqs:SendMessage"]
    resources = [local.queues["order-reservation"], local.queues["order-payment"]]
  }

  statement {
    sid       = "UseSqsKeyThroughSqsOnly"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.sqs_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["sqs.${var.aws_region}.amazonaws.com"]
    }
  }

  statement {
    sid       = "UseDynamoDbKeyThroughDynamoDbOnly"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey*", "kms:ReEncrypt*"]
    resources = [module.kms.dynamodb_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["dynamodb.${var.aws_region}.amazonaws.com"]
    }
  }
}

# ------------------------------------------------------------------ worker --
# consumes order-reservation / order-payment, reserves/finalizes/releases
# inventory, runs the expiration and dispatch sweepers (lease lock).

data "aws_iam_policy_document" "worker_task" {
  statement {
    sid       = "EventCounters"
    effect    = "Allow"
    actions   = ["dynamodb:ConditionCheckItem", "dynamodb:GetItem", "dynamodb:UpdateItem"]
    resources = [local.tables["events"]]
  }

  statement {
    sid       = "Tickets"
    effect    = "Allow"
    actions   = ["dynamodb:BatchGetItem", "dynamodb:ConditionCheckItem", "dynamodb:GetItem", "dynamodb:UpdateItem"]
    resources = [local.tables["tickets"]]
  }

  statement {
    sid       = "SelectAvailableTickets"
    effect    = "Allow"
    actions   = ["dynamodb:Query"]
    resources = [local.indexes["tickets/eventId-statusKey-index"]]
  }

  statement {
    sid       = "Orders"
    effect    = "Allow"
    actions   = ["dynamodb:ConditionCheckItem", "dynamodb:GetItem", "dynamodb:UpdateItem"]
    resources = [local.tables["orders"]]
  }

  statement {
    sid       = "FindDueOrders"
    effect    = "Allow"
    actions   = ["dynamodb:Query"]
    resources = [local.indexes["orders/deadline-index"]]
  }

  statement {
    sid       = "TicketTransitionsAppendOnly"
    effect    = "Allow"
    actions   = ["dynamodb:PutItem"]
    resources = [local.tables["ticket-transitions"]]
  }

  statement {
    sid       = "SweeperLeases"
    effect    = "Allow"
    actions   = ["dynamodb:DeleteItem", "dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem"]
    resources = [local.tables["distributed-locks"]]
  }

  statement {
    sid    = "ConsumeOrderQueues"
    effect = "Allow"
    actions = [
      "sqs:ChangeMessageVisibility",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
      "sqs:GetQueueUrl",
      "sqs:ReceiveMessage",
    ]
    resources = [local.queues["order-reservation"], local.queues["order-payment"]]
  }

  statement {
    # RepublishStaleOrdersUseCase re-sends QUEUED orders (dual-write recovery).
    sid       = "RepublishReservations"
    effect    = "Allow"
    actions   = ["sqs:SendMessage"]
    resources = [local.queues["order-reservation"]]
  }

  statement {
    sid       = "UseSqsKeyThroughSqsOnly"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.sqs_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["sqs.${var.aws_region}.amazonaws.com"]
    }
  }

  statement {
    sid       = "UseDynamoDbKeyThroughDynamoDbOnly"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey*", "kms:ReEncrypt*"]
    resources = [module.kms.dynamodb_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["dynamodb.${var.aws_region}.amazonaws.com"]
    }
  }
}
