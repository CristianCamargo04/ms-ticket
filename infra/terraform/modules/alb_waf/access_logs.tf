################################################################################
# S3 bucket for ALB access logs: private, versioned, TLS-only, auto-expiring.
# ALB log delivery only supports SSE-S3 (AES256), not SSE-KMS.
################################################################################

data "aws_elb_service_account" "this" {}

resource "aws_s3_bucket" "access_logs" {
  #checkov:skip=CKV_AWS_18:This IS the log bucket; logging it into itself would recurse.
  #checkov:skip=CKV_AWS_144:Access logs are operational data; cross-region replication is not worth the cost.
  #checkov:skip=CKV_AWS_145:ALB access log delivery only supports SSE-S3 (AES256), not SSE-KMS.
  #checkov:skip=CKV2_AWS_62:No consumer for object-created events on access logs.
  bucket        = "${var.name_prefix}-alb-logs-${local.account_id}-${local.region}"
  force_destroy = var.force_destroy_logs_bucket

  tags = merge(var.tags, { Name = "${var.name_prefix}-alb-logs" })
}

resource "aws_s3_bucket_public_access_block" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    object_ownership = "BucketOwnerEnforced" # ACLs disabled
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    id     = "expire-access-logs"
    status = "Enabled"

    filter {}

    expiration {
      days = var.access_logs_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 7
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.access_logs]
}

data "aws_iam_policy_document" "access_logs" {
  statement {
    sid       = "AllowElbLogDelivery"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.access_logs.arn}/alb/AWSLogs/${local.account_id}/*"]

    principals {
      type        = "Service"
      identifiers = ["logdelivery.elasticloadbalancing.amazonaws.com"]
    }
  }

  # Regions launched before August 2022 deliver with the regional ELB account.
  statement {
    sid       = "AllowElbLogDeliveryLegacyRegions"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.access_logs.arn}/alb/AWSLogs/${local.account_id}/*"]

    principals {
      type        = "AWS"
      identifiers = [data.aws_elb_service_account.this.arn]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.access_logs.arn, "${aws_s3_bucket.access_logs.arn}/*"]

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

resource "aws_s3_bucket_policy" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  policy = data.aws_iam_policy_document.access_logs.json

  depends_on = [aws_s3_bucket_public_access_block.access_logs]
}
