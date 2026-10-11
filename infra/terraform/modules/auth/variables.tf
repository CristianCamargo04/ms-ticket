variable "name_prefix" {
  description = "Prefix for every resource name, e.g. \"ms-ticket-dev\"."
  type        = string
}

variable "user_pool_tier" {
  description = "Cognito feature plan: ESSENTIALS or PLUS (PLUS is required for threat protection / advanced security)."
  type        = string
  default     = "ESSENTIALS"

  validation {
    condition     = contains(["ESSENTIALS", "PLUS"], var.user_pool_tier)
    error_message = "user_pool_tier must be ESSENTIALS or PLUS."
  }
}

variable "advanced_security_mode" {
  description = "Threat protection (compromised credentials, adaptive auth): OFF, AUDIT or ENFORCED. AUDIT/ENFORCED require user_pool_tier = PLUS."
  type        = string
  default     = "OFF"

  validation {
    condition     = contains(["OFF", "AUDIT", "ENFORCED"], var.advanced_security_mode)
    error_message = "advanced_security_mode must be OFF, AUDIT or ENFORCED."
  }
}

variable "mfa_configuration" {
  description = "OPTIONAL (users may enrol TOTP) or ON (TOTP required for every user)."
  type        = string
  default     = "OPTIONAL"

  validation {
    condition     = contains(["OPTIONAL", "ON"], var.mfa_configuration)
    error_message = "mfa_configuration must be OPTIONAL or ON."
  }
}

variable "password_minimum_length" {
  description = "Minimum password length (all character classes are always required)."
  type        = number
  default     = 12

  validation {
    condition     = var.password_minimum_length >= 12 && var.password_minimum_length <= 99
    error_message = "password_minimum_length must be between 12 and 99."
  }
}

variable "deletion_protection" {
  description = "Protect the user pool (and every identity in it) and the trigger log group from deletion."
  type        = bool
  default     = true
}

variable "resource_server_identifier" {
  description = "Resource server identifier (OAuth2 scope prefix). Matches the API audience \"ms-ticket\"."
  type        = string
  default     = "ms-ticket"
}

variable "callback_urls" {
  description = "Allowed OAuth2 redirect URIs of the web client (HTTPS except localhost)."
  type        = list(string)

  validation {
    condition     = length(var.callback_urls) > 0 && alltrue([for url in var.callback_urls : can(regex("^(https://|http://localhost)", url))])
    error_message = "callback_urls must not be empty and must use HTTPS (http is only allowed for localhost)."
  }
}

variable "logout_urls" {
  description = "Allowed sign-out redirect URIs of the web client."
  type        = list(string)
  default     = []
}

variable "domain_prefix" {
  description = "Prefix of the Cognito hosted domain (<prefix>.auth.<region>.amazoncognito.com). null = \"<name_prefix>-<account id>\"."
  type        = string
  default     = null
}

variable "access_token_validity_minutes" {
  description = "Lifetime of access and ID tokens (short: tokens cannot be revoked once issued to a resource server)."
  type        = number
  default     = 15

  validation {
    condition     = var.access_token_validity_minutes >= 5 && var.access_token_validity_minutes <= 1440
    error_message = "access_token_validity_minutes must be between 5 and 1440."
  }
}

variable "refresh_token_validity_days" {
  description = "Lifetime of refresh tokens."
  type        = number
  default     = 7

  validation {
    condition     = var.refresh_token_validity_days >= 1 && var.refresh_token_validity_days <= 3650
    error_message = "refresh_token_validity_days must be between 1 and 3650."
  }
}

# ------------------------------------------------------------------ e-mail --

variable "ses_identity_arn" {
  description = "Verified SES identity (domain or address, out of the SES sandbox) Cognito sends e-mails from (email_sending_account = DEVELOPER). null = Cognito default sender, capped at a few dozen e-mails per day (dev only)."
  type        = string
  default     = null

  validation {
    condition     = var.ses_identity_arn == null || can(regex("^arn:aws[a-zA-Z-]*:ses:[a-z0-9-]+:[0-9]{12}:identity/.+$", var.ses_identity_arn))
    error_message = "ses_identity_arn must be null or an SES identity ARN (arn:aws:ses:<region>:<account>:identity/<domain or address>)."
  }
}

variable "email_from_address" {
  description = "Sender of Cognito e-mails, e.g. \"Tickets <no-reply@example.com>\"; must belong to ses_identity_arn. Required with ses_identity_arn."
  type        = string
  default     = null

  validation {
    condition     = var.ses_identity_arn == null || var.email_from_address != null
    error_message = "email_from_address is required when ses_identity_arn is set."
  }

  validation {
    condition     = var.email_from_address == null || can(regex("[^@<>\\s]+@[^@<>\\s]+\\.[^@<>\\s]+", var.email_from_address))
    error_message = "email_from_address must contain an e-mail address (optionally with a display name)."
  }
}

variable "email_reply_to_address" {
  description = "Optional Reply-To of Cognito e-mails (SES sending only)."
  type        = string
  default     = null
}

variable "ses_configuration_set" {
  description = "Optional SES configuration set (bounce/complaint/delivery events) for Cognito e-mails (SES sending only)."
  type        = string
  default     = null
}

# ---------------------------------------------------------- trigger Lambda --

variable "logs_kms_key_arn" {
  description = "KMS key ARN that encrypts the log group of the Cognito trigger Lambda."
  type        = string
}

variable "log_retention_days" {
  description = "Retention of the Cognito trigger Lambda logs."
  type        = number
  default     = 30

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be a value supported by CloudWatch Logs."
  }
}

variable "trigger_xray_tracing" {
  description = "Active X-Ray tracing for the Cognito trigger Lambda (false = PassThrough: no traces, no X-Ray permissions)."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Extra tags (provider default_tags already apply)."
  type        = map(string)
  default     = {}
}
