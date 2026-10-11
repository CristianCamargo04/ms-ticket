################################################################################
# Least-privilege application permissions, derived from the data access of
# each runtime mode (docs/design/implementation-spec.md sections 4-6).
#
# Notes
#  - TransactWriteItems is not an IAM action: DynamoDB authorizes every item of
#    a transaction with its own action. The transactions of implementation-spec
#    6.3 only contain Update (event, tickets, order) and Put (audit) items, so
#    dynamodb:ConditionCheckItem is granted to nobody.
#  - ticket-transitions: no UpdateItem/DeleteItem for anybody, but PutItem CAN
#    overwrite an existing record. The application only inserts (condition
#    attribute_not_exists(ticketKey)); IAM has no condition key that forces a
#    ConditionExpression, so this is insert-only by code, not tamper-proof
#    against a compromised task role. Tamper evidence = PITR + scheduled
#    ExportTableToPointInTime into an S3 bucket with Object Lock (compliance
#    mode); see README 2.1.
#  - BatchWriteItem (API, ticket pre-generation) authorizes PutRequest AND
#    DeleteRequest alike: IAM cannot restrict the request type. Accepted: it is
#    scoped to the tickets table and the code only sends PutRequests.
#  - No Scan/DescribeTable/CreateTable anywhere; DeleteItem only on the lock
#    table (lease release).
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
    # PutItem: CreateEvent; UpdateItem: status changes and confirm/release transactions.
    sid       = "Events"
    effect    = "Allow"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem"]
    resources = [local.tables["events"]]
  }

  statement {
    sid       = "ListEventsByStatus"
    effect    = "Allow"
    actions   = ["dynamodb:Query"]
    resources = [local.indexes["events/status-eventDate-index"]]
  }

  statement {
    # BatchWriteItem: ticket pre-generation when an event is created (CreateEvent,
    # PutRequests only - see notes). UpdateItem: confirm/release transactions.
    # No BatchGetItem: no API use case reads tickets in batch (the worker does).
    sid       = "Tickets"
    effect    = "Allow"
    actions   = ["dynamodb:BatchWriteItem", "dynamodb:GetItem", "dynamodb:UpdateItem"]
    resources = [local.tables["tickets"]]
  }

  statement {
    sid       = "Orders"
    effect    = "Allow"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem"]
    resources = [local.tables["orders"]]
  }

  statement {
    # Insert (conditional PutItem in transactions) + ticket history; no Update/Delete.
    sid       = "TicketTransitionsInsertAndRead"
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
    actions   = ["dynamodb:GetItem", "dynamodb:UpdateItem"]
    resources = [local.tables["events"]]
  }

  statement {
    # BatchGetItem: TicketPersistence.findByIds; UpdateItem: inventory transactions.
    sid       = "Tickets"
    effect    = "Allow"
    actions   = ["dynamodb:BatchGetItem", "dynamodb:GetItem", "dynamodb:UpdateItem"]
    resources = [local.tables["tickets"]]
  }

  statement {
    # TicketPersistence.findAvailable (INCLUDE projection: no second read).
    sid       = "SelectAvailableTickets"
    effect    = "Allow"
    actions   = ["dynamodb:Query"]
    resources = [local.indexes["tickets/eventId-statusKey-index"]]
  }

  statement {
    sid       = "Orders"
    effect    = "Allow"
    actions   = ["dynamodb:GetItem", "dynamodb:UpdateItem"]
    resources = [local.tables["orders"]]
  }

  statement {
    sid       = "FindDueOrders"
    effect    = "Allow"
    actions   = ["dynamodb:Query"]
    resources = [local.indexes["orders/deadline-index"]]
  }

  statement {
    # Insert only (conditional PutItem in transactions); see notes on overwrites.
    sid       = "TicketTransitionsInsertOnly"
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
