################################################################################
# VPC endpoints.
#   - Gateway endpoints (free): DynamoDB (all table traffic) and S3 (ECR image
#     layers are served from S3) -> the hot data path never crosses the NAT.
#   - Interface endpoints (private DNS): SQS, ECR API/DKR, CloudWatch Logs, STS,
#     Secrets Manager.
################################################################################

locals {
  endpoint_az_count    = var.interface_endpoint_az_count == null ? local.az_count : min(var.interface_endpoint_az_count, local.az_count)
  endpoint_subnet_ids  = slice(aws_subnet.private[*].id, 0, local.endpoint_az_count)
  private_route_tables = aws_route_table.private[*].id
}

# DynamoDB: only tables of this account, only principals of this account.
data "aws_iam_policy_document" "dynamodb_endpoint" {
  statement {
    sid       = "AllowOwnAccountTablesOnly"
    effect    = "Allow"
    actions   = ["dynamodb:*"]
    resources = ["arn:${local.partition}:dynamodb:${local.region}:${local.account_id}:table/*"]

    principals {
      type        = "AWS"
      identifiers = ["*"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:PrincipalAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${local.region}.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = local.private_route_tables
  policy            = data.aws_iam_policy_document.dynamodb_endpoint.json

  tags = merge(var.tags, { Name = "${var.name_prefix}-dynamodb" })
}

# S3: ECR layer bucket (AWS-owned) + buckets owned by this account (data perimeter).
data "aws_iam_policy_document" "s3_endpoint" {
  #checkov:skip=CKV_AWS_283:Endpoint policies require Principal "*". ECR layer downloads use URLs pre-signed by ECR (AWS-owned principal), so that statement is limited to s3:GetObject on the AWS layer bucket; everything else is limited to buckets owned by this account.
  statement {
    sid       = "AllowEcrImageLayers"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["arn:${local.partition}:s3:::prod-${local.region}-starport-layer-bucket/*"]

    principals {
      type        = "AWS"
      identifiers = ["*"]
    }
  }

  statement {
    sid       = "AllowOwnAccountBucketsOnly"
    effect    = "Allow"
    actions   = ["s3:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["*"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:ResourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${local.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = local.private_route_tables
  policy            = data.aws_iam_policy_document.s3_endpoint.json

  tags = merge(var.tags, { Name = "${var.name_prefix}-s3" })
}

resource "aws_security_group" "endpoints" {
  name        = "${var.name_prefix}-vpc-endpoints"
  description = "HTTPS from inside the VPC to interface VPC endpoints"
  vpc_id      = aws_vpc.this.id

  tags = merge(var.tags, { Name = "${var.name_prefix}-vpc-endpoints" })
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_https" {
  security_group_id = aws_security_group.endpoints.id
  description       = "HTTPS from workloads in the VPC"
  cidr_ipv4         = aws_vpc.this.cidr_block
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_endpoint" "interface" {
  for_each = toset(var.interface_endpoint_services)

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${local.region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = local.endpoint_subnet_ids
  security_group_ids  = [aws_security_group.endpoints.id]

  tags = merge(var.tags, { Name = "${var.name_prefix}-${replace(each.key, ".", "-")}" })
}
