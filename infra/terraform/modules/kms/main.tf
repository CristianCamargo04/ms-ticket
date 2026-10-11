################################################################################
# Customer-managed KMS keys, one per data domain (blast-radius isolation).
#
# Key policy model (least privilege):
#   1. Administration (no cryptographic use) for the configured administrators.
#   2. Cryptographic use ONLY through the owning AWS service (kms:ViaService +
#      kms:CallerAccount): an IAM principal can never call kms:Decrypt directly
#      with these keys, it must go through DynamoDB / SQS / Secrets Manager ...
#      Fine-grained "who" is still decided by IAM policies (task roles).
#   3. Service principals that encrypt on their own behalf (CloudWatch Logs,
#      log delivery writing VPC flow logs to S3, CloudWatch alarms publishing
#      to an encrypted SNS topic), limited to this account.
################################################################################

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region
  root_arn   = "arn:${local.partition}:iam::${local.account_id}:root"
  key_admins = length(var.key_administrator_arns) > 0 ? var.key_administrator_arns : [local.root_arn]

  keys = {
    dynamodb = {
      description  = "${var.name_prefix} - DynamoDB tables, PITR backups"
      via_services = ["dynamodb"]
    }
    sqs = {
      description  = "${var.name_prefix} - SQS queues and dead-letter queues"
      via_services = ["sqs"]
    }
    logs = {
      description = "${var.name_prefix} - CloudWatch Logs (application, WAF, ECS Exec, Lambda) and VPC flow logs (log group or S3)"
      # Reading flow logs from S3 (Athena) decrypts through S3 only.
      via_services = ["s3"]
    }
    platform = {
      description  = "${var.name_prefix} - ECR images, Secrets Manager secrets, SNS alarm topic"
      via_services = ["ecr", "secretsmanager", "sns"]
    }
  }

  key_admin_actions = [
    "kms:CancelKeyDeletion",
    "kms:Create*",
    "kms:Delete*",
    "kms:Describe*",
    "kms:Disable*",
    "kms:Enable*",
    "kms:Get*",
    "kms:List*",
    "kms:Put*",
    "kms:Revoke*",
    "kms:RotateKeyOnDemand",
    "kms:ScheduleKeyDeletion",
    "kms:TagResource",
    "kms:UntagResource",
    "kms:Update*",
  ]
}

data "aws_iam_policy_document" "key" {
  for_each = local.keys

  #checkov:skip=CKV_AWS_109:KMS key policy - "*" resource means "this key" and administration is limited to key administrators.
  #checkov:skip=CKV_AWS_111:KMS key policy - "*" resource means "this key"; usage is constrained by kms:ViaService / encryption context.
  #checkov:skip=CKV_AWS_356:KMS key policy - "*" resource means "this key" (key policies cannot reference other resources).

  statement {
    sid       = "KeyAdministration"
    effect    = "Allow"
    actions   = local.key_admin_actions
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = local.key_admins
    }
  }

  dynamic "statement" {
    for_each = length(each.value.via_services) > 0 ? [1] : []

    content {
      sid       = "AllowUseOnlyThroughOwningServices"
      effect    = "Allow"
      actions   = ["kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey*", "kms:ReEncrypt*"]
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = [local.root_arn]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:CallerAccount"
        values   = [local.account_id]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = [for service in each.value.via_services : "${service}.${local.region}.amazonaws.com"]
      }
    }
  }

  dynamic "statement" {
    for_each = length(each.value.via_services) > 0 ? [1] : []

    content {
      sid       = "AllowGrantsOnlyForAwsResources"
      effect    = "Allow"
      actions   = ["kms:CreateGrant", "kms:ListGrants", "kms:RevokeGrant"]
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = [local.root_arn]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:CallerAccount"
        values   = [local.account_id]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = [for service in each.value.via_services : "${service}.${local.region}.amazonaws.com"]
      }

      condition {
        test     = "Bool"
        variable = "kms:GrantIsForAWSResource"
        values   = ["true"]
      }
    }
  }

  dynamic "statement" {
    for_each = each.key == "logs" ? [1] : []

    content {
      sid       = "AllowCloudWatchLogsForThisAccountOnly"
      effect    = "Allow"
      actions   = ["kms:Decrypt*", "kms:Describe*", "kms:Encrypt*", "kms:GenerateDataKey*", "kms:ReEncrypt*"]
      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["logs.${local.region}.amazonaws.com"]
      }

      condition {
        test     = "ArnLike"
        variable = "kms:EncryptionContext:aws:logs:arn"
        values   = ["arn:${local.partition}:logs:${local.region}:${local.account_id}:log-group:*"]
      }
    }
  }

  # VPC flow logs delivered to an SSE-KMS bucket (AWS documented key policy).
  dynamic "statement" {
    for_each = each.key == "logs" ? [1] : []

    content {
      sid       = "AllowLogDeliveryToEncryptedBucketsOfThisAccountOnly"
      effect    = "Allow"
      actions   = ["kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey*", "kms:ReEncrypt*"]
      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["delivery.logs.amazonaws.com"]
      }

      condition {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [local.account_id]
      }

      condition {
        test     = "ArnLike"
        variable = "aws:SourceArn"
        values   = ["arn:${local.partition}:logs:${local.region}:${local.account_id}:*"]
      }
    }
  }

  dynamic "statement" {
    for_each = each.key == "platform" ? [1] : []

    content {
      sid       = "AllowCloudWatchAlarmsToPublishToEncryptedTopics"
      effect    = "Allow"
      actions   = ["kms:Decrypt", "kms:GenerateDataKey*"]
      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["cloudwatch.amazonaws.com"]
      }
    }
  }
}

resource "aws_kms_key" "this" {
  for_each = local.keys

  description             = each.value.description
  key_usage               = "ENCRYPT_DECRYPT"
  enable_key_rotation     = true
  rotation_period_in_days = var.rotation_period_in_days
  deletion_window_in_days = var.deletion_window_in_days
  policy                  = data.aws_iam_policy_document.key[each.key].json

  tags = merge(var.tags, { Name = "${var.name_prefix}-${each.key}" })
}

resource "aws_kms_alias" "this" {
  for_each = local.keys

  name          = "alias/${var.name_prefix}-${each.key}"
  target_key_id = aws_kms_key.this[each.key].key_id
}
