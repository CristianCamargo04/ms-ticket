################################################################################
# Post Confirmation trigger: every self-signed-up user joins the default group
# (CUSTOMER), otherwise the API maps no role and answers 403.
#
#   - Code packaged inline from lambda/post_confirmation.py (archive_file).
#   - Least privilege: cognito-idp:AdminAddUserToGroup on THIS user pool only,
#     logs only to its own KMS-encrypted log group, X-Ray only when enabled.
#   - Only Cognito, for THIS user pool and account, may invoke it.
# Users created by administrators (AdminCreateUser) do not fire this trigger:
# operators assign their group explicitly.
################################################################################

locals {
  post_confirmation_name = "${var.name_prefix}-cognito-post-confirmation"
}

data "archive_file" "post_confirmation" {
  type             = "zip"
  output_path      = "${path.module}/.build/${local.post_confirmation_name}.zip"
  output_file_mode = "0666" # same hash on every OS (no spurious redeploys)

  source {
    content  = file("${path.module}/lambda/post_confirmation.py")
    filename = "index.py"
  }
}

resource "aws_cloudwatch_log_group" "post_confirmation" {
  #checkov:skip=CKV_AWS_338:Retention is set per environment (cost control); prod keeps logs for a year.
  name                        = "/aws/lambda/${local.post_confirmation_name}"
  retention_in_days           = var.log_retention_days
  kms_key_id                  = var.logs_kms_key_arn
  deletion_protection_enabled = var.deletion_protection

  tags = var.tags
}

# No confused-deputy condition needed: only a principal of this account with
# iam:PassRole on this role can attach it to a function.
data "aws_iam_policy_document" "post_confirmation_assume" {
  statement {
    sid     = "LambdaService"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "post_confirmation" {
  name               = local.post_confirmation_name
  description        = "Cognito post-confirmation trigger of ${var.name_prefix}: add new users to ${local.self_signup_group}"
  assume_role_policy = data.aws_iam_policy_document.post_confirmation_assume.json

  tags = var.tags
}

data "aws_iam_policy_document" "post_confirmation" {
  statement {
    sid       = "AddUsersToDefaultGroupOfThisPoolOnly"
    effect    = "Allow"
    actions   = ["cognito-idp:AdminAddUserToGroup"]
    resources = [aws_cognito_user_pool.this.arn]
  }

  statement {
    sid       = "WriteOwnLogs"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.post_confirmation.arn}:*"]
  }

  dynamic "statement" {
    for_each = var.trigger_xray_tracing ? [1] : []

    content {
      sid       = "XRayTraces"
      effect    = "Allow"
      actions   = ["xray:PutTelemetryRecords", "xray:PutTraceSegments"]
      resources = ["*"] # X-Ray does not support resource-level permissions for these actions
    }
  }
}

# Separate from the role so that the role (and the function) do not depend on
# the user pool, which itself references the function (no dependency cycle).
# The policy lands right after the pool is created, before any user can sign up.
resource "aws_iam_role_policy" "post_confirmation" {
  name   = "post-confirmation"
  role   = aws_iam_role.post_confirmation.id
  policy = data.aws_iam_policy_document.post_confirmation.json
}

resource "aws_lambda_function" "post_confirmation" {
  #checkov:skip=CKV_AWS_116:Cognito invokes triggers synchronously; a DLQ only applies to asynchronous invocations.
  #checkov:skip=CKV_AWS_117:Only calls the public Cognito API; a VPC attachment would add NAT/endpoint cost and cold-start latency without protecting anything.
  #checkov:skip=CKV_AWS_115:No reserved concurrency on purpose: sign-up bursts during ticket drops must not be throttled (a throttled trigger fails the sign-up); failures/throttles page through the observability alarm.
  #checkov:skip=CKV_AWS_272:Code is built by Terraform from this reviewed repository (archive_file), not from an external artifact store that signing would protect.
  #checkov:skip=CKV_AWS_173:The only environment variable is the non-secret group name; it is encrypted at rest with the AWS managed Lambda key.
  function_name    = local.post_confirmation_name
  description      = "Adds self-signed-up users of ${var.name_prefix} to the ${local.self_signup_group} group"
  role             = aws_iam_role.post_confirmation.arn
  runtime          = "python3.13"
  architectures    = ["arm64"]
  handler          = "index.handler"
  filename         = data.archive_file.post_confirmation.output_path
  source_code_hash = data.archive_file.post_confirmation.output_base64sha256
  memory_size      = 128
  timeout          = 5 # Cognito waits at most 5 s for a trigger

  environment {
    variables = {
      DEFAULT_GROUP = local.self_signup_group
    }
  }

  logging_config {
    log_format            = "JSON"
    log_group             = aws_cloudwatch_log_group.post_confirmation.name
    application_log_level = "INFO"
    system_log_level      = "WARN"
  }

  tracing_config {
    mode = var.trigger_xray_tracing ? "Active" : "PassThrough"
  }

  tags = var.tags
}

resource "aws_lambda_permission" "cognito_post_confirmation" {
  statement_id   = "AllowCognitoPostConfirmation"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.post_confirmation.function_name
  principal      = "cognito-idp.amazonaws.com"
  source_arn     = aws_cognito_user_pool.this.arn
  source_account = data.aws_caller_identity.current.account_id
}
