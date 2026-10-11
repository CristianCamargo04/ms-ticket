################################################################################
# VPC flow logs, to one of two destinations (cost-driven, per environment):
#   - "s3"               Parquet, Hive-compatible hourly partitions (Athena-ready),
#                        SSE-KMS, lifecycle to Intelligent-Tiering then expiry.
#                        S3 ingestion is a fraction of CloudWatch Logs ingestion
#                        (~$0.50/GB): the prod choice, even with ALL traffic.
#   - "cloud-watch-logs" KMS-encrypted log group; with traffic_type = REJECT
#                        (dev) only denied flows are kept, i.e. a small volume.
################################################################################

locals {
  flow_logs_to_s3 = var.flow_logs_destination == "s3"
}

resource "aws_flow_log" "this" {
  vpc_id                   = aws_vpc.this.id
  traffic_type             = var.flow_logs_traffic_type
  log_destination_type     = var.flow_logs_destination
  log_destination          = local.flow_logs_to_s3 ? aws_s3_bucket.flow_logs[0].arn : aws_cloudwatch_log_group.flow_logs[0].arn
  iam_role_arn             = local.flow_logs_to_s3 ? null : aws_iam_role.flow_logs[0].arn
  max_aggregation_interval = var.flow_logs_max_aggregation_interval

  dynamic "destination_options" {
    for_each = local.flow_logs_to_s3 ? [1] : []

    content {
      file_format                = "parquet"
      hive_compatible_partitions = true
      per_hour_partition         = true
    }
  }

  tags = merge(var.tags, { Name = var.name_prefix })

  # Log delivery validates its access to the bucket and the key when the flow log is created.
  depends_on = [aws_s3_bucket_policy.flow_logs]
}

# ------------------------------------------------------- CloudWatch Logs ----

resource "aws_cloudwatch_log_group" "flow_logs" {
  count = local.flow_logs_to_s3 ? 0 : 1

  #checkov:skip=CKV_AWS_338:Retention is set per environment (cost control); prod stores flow logs in S3.
  name              = "/aws/vpc/flow-logs/${var.name_prefix}"
  retention_in_days = var.flow_logs_retention_days
  kms_key_id        = var.flow_logs_kms_key_arn

  tags = var.tags
}

data "aws_iam_policy_document" "flow_logs_assume" {
  count = local.flow_logs_to_s3 ? 0 : 1

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
  count = local.flow_logs_to_s3 ? 0 : 1

  name               = "${var.name_prefix}-vpc-flow-logs"
  description        = "Lets VPC Flow Logs deliver records to its CloudWatch log group"
  assume_role_policy = data.aws_iam_policy_document.flow_logs_assume[0].json

  tags = var.tags
}

data "aws_iam_policy_document" "flow_logs" {
  count = local.flow_logs_to_s3 ? 0 : 1

  statement {
    sid       = "WriteToFlowLogGroup"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:DescribeLogStreams", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.flow_logs[0].arn}:*"]
  }

  statement {
    sid       = "DescribeLogGroups"
    effect    = "Allow"
    actions   = ["logs:DescribeLogGroups"]
    resources = ["arn:${local.partition}:logs:${local.region}:${local.account_id}:log-group:*"]
  }
}

resource "aws_iam_role_policy" "flow_logs" {
  count = local.flow_logs_to_s3 ? 0 : 1

  name   = "write-flow-logs"
  role   = aws_iam_role.flow_logs[0].id
  policy = data.aws_iam_policy_document.flow_logs[0].json
}

# -------------------------------------------------------------------- S3 ----
# Private, versioned, TLS-only, SSE-KMS with the logs key (its key policy lets
# delivery.logs.amazonaws.com use it for this account only).

resource "aws_s3_bucket" "flow_logs" {
  count = local.flow_logs_to_s3 ? 1 : 0

  #checkov:skip=CKV_AWS_18:This IS a log bucket; access-logging it would only produce logs about logs.
  #checkov:skip=CKV_AWS_144:Network forensics data with a bounded retention; cross-region replication is not worth the cost.
  #checkov:skip=CKV2_AWS_62:No consumer for object-created events on flow logs (queried with Athena).
  bucket        = "${var.name_prefix}-flow-logs-${local.account_id}-${local.region}"
  force_destroy = var.flow_logs_force_destroy

  tags = merge(var.tags, { Name = "${var.name_prefix}-flow-logs" })
}

resource "aws_s3_bucket_public_access_block" "flow_logs" {
  count = local.flow_logs_to_s3 ? 1 : 0

  bucket = aws_s3_bucket.flow_logs[0].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "flow_logs" {
  count = local.flow_logs_to_s3 ? 1 : 0

  bucket = aws_s3_bucket.flow_logs[0].id

  rule {
    object_ownership = "BucketOwnerEnforced" # ACLs disabled
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "flow_logs" {
  count = local.flow_logs_to_s3 ? 1 : 0

  bucket = aws_s3_bucket.flow_logs[0].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = var.flow_logs_kms_key_arn
    }
    bucket_key_enabled = true # one KMS call per bucket key, not per object
  }
}

resource "aws_s3_bucket_versioning" "flow_logs" {
  count = local.flow_logs_to_s3 ? 1 : 0

  bucket = aws_s3_bucket.flow_logs[0].id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "flow_logs" {
  count = local.flow_logs_to_s3 ? 1 : 0

  bucket = aws_s3_bucket.flow_logs[0].id

  rule {
    id     = "tier-then-expire-flow-logs"
    status = "Enabled"

    filter {}

    # No retrieval fee and no minimum object size penalty (objects < 128 KB are
    # simply not transitioned): recent data stays fast for incident queries.
    transition {
      days          = var.flow_logs_s3_tiering_days
      storage_class = "INTELLIGENT_TIERING"
    }

    expiration {
      days = var.flow_logs_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 7
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.flow_logs]
}

data "aws_iam_policy_document" "flow_logs_bucket" {
  count = local.flow_logs_to_s3 ? 1 : 0

  statement {
    sid       = "AWSLogDeliveryWrite"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.flow_logs[0].arn}/AWSLogs/*"]

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
      test     = "StringEquals"
      variable = "s3:x-amz-acl"
      values   = ["bucket-owner-full-control"]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:logs:${local.region}:${local.account_id}:*"]
    }
  }

  statement {
    sid       = "AWSLogDeliveryAclCheck"
    effect    = "Allow"
    actions   = ["s3:GetBucketAcl", "s3:ListBucket"]
    resources = [aws_s3_bucket.flow_logs[0].arn]

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

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.flow_logs[0].arn, "${aws_s3_bucket.flow_logs[0].arn}/*"]

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

resource "aws_s3_bucket_policy" "flow_logs" {
  count = local.flow_logs_to_s3 ? 1 : 0

  bucket = aws_s3_bucket.flow_logs[0].id
  policy = data.aws_iam_policy_document.flow_logs_bucket[0].json

  depends_on = [aws_s3_bucket_public_access_block.flow_logs]
}
