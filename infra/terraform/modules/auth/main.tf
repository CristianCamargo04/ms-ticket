################################################################################
# Amazon Cognito: identity provider of the API (OAuth2 / OIDC, ADR-007).
#   - Groups ADMIN / CUSTOMER  -> "cognito:groups" claim -> ROLE_ADMIN / ROLE_CUSTOMER
#   - Resource server "ms-ticket" with custom scopes
#   - Public web client: authorization code + PKCE, no client secret
# The API validates issuer + signature (JWKS) + audience + roles; see README.
################################################################################

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  domain_prefix = coalesce(var.domain_prefix, "${var.name_prefix}-${data.aws_caller_identity.current.account_id}")

  groups = {
    ADMIN    = { precedence = 0, description = "Back-office operators: create events, issue complimentary tickets, read any order" }
    CUSTOMER = { precedence = 10, description = "Ticket buyers: place, confirm and cancel their own orders" }
  }

  scopes = {
    "orders.read"  = "Read the caller's own orders"
    "orders.write" = "Place, confirm and cancel the caller's own orders"
    "events.admin" = "Create events and issue complimentary tickets"
  }
}

resource "aws_cognito_user_pool" "this" {
  name                     = var.name_prefix
  user_pool_tier           = var.user_pool_tier
  deletion_protection      = var.deletion_protection ? "ACTIVE" : "INACTIVE"
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  mfa_configuration        = var.mfa_configuration

  username_configuration {
    case_sensitive = false
  }

  password_policy {
    minimum_length                   = var.password_minimum_length
    require_lowercase                = true
    require_uppercase                = true
    require_numbers                  = true
    require_symbols                  = true
    temporary_password_validity_days = 3
    password_history_size            = 5
  }

  software_token_mfa_configuration {
    enabled = true
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  admin_create_user_config {
    allow_admin_create_user_only = false # customers self-register
  }

  user_attribute_update_settings {
    attributes_require_verification_before_update = ["email"]
  }

  # Threat protection: compromised-credential checks and adaptive authentication.
  dynamic "user_pool_add_ons" {
    for_each = var.user_pool_tier == "PLUS" ? [var.advanced_security_mode] : []

    content {
      advanced_security_mode = user_pool_add_ons.value
    }
  }

  schema {
    name                     = "email"
    attribute_data_type      = "String"
    required                 = true
    mutable                  = true
    developer_only_attribute = false

    string_attribute_constraints {
      min_length = 5
      max_length = 254
    }
  }

  tags = merge(var.tags, { Name = var.name_prefix })

  lifecycle {
    precondition {
      condition     = var.advanced_security_mode == "OFF" || var.user_pool_tier == "PLUS"
      error_message = "advanced_security_mode AUDIT/ENFORCED requires user_pool_tier = PLUS."
    }
  }
}

resource "aws_cognito_user_group" "this" {
  for_each = local.groups

  user_pool_id = aws_cognito_user_pool.this.id
  name         = each.key
  precedence   = each.value.precedence
  description  = each.value.description
}

resource "aws_cognito_resource_server" "api" {
  user_pool_id = aws_cognito_user_pool.this.id
  identifier   = var.resource_server_identifier
  name         = "${var.name_prefix}-api"

  dynamic "scope" {
    for_each = local.scopes

    content {
      scope_name        = scope.key
      scope_description = scope.value
    }
  }
}

resource "aws_cognito_user_pool_client" "web" {
  name         = "${var.name_prefix}-web"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret                      = false # public client (SPA / mobile) -> PKCE
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = concat(["openid", "email", "profile"], tolist(aws_cognito_resource_server.api.scope_identifiers))
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = var.callback_urls
  logout_urls                          = var.logout_urls
  explicit_auth_flows                  = ["ALLOW_USER_SRP_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"]
  prevent_user_existence_errors        = "ENABLED"
  enable_token_revocation              = true
  auth_session_validity                = 3

  access_token_validity  = var.access_token_validity_minutes
  id_token_validity      = var.access_token_validity_minutes
  refresh_token_validity = var.refresh_token_validity_days

  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
}

resource "aws_cognito_user_pool_domain" "this" {
  domain       = local.domain_prefix
  user_pool_id = aws_cognito_user_pool.this.id
}
