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
  description = "Protect the user pool (and every identity in it) from deletion."
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

variable "tags" {
  description = "Extra tags (provider default_tags already apply)."
  type        = map(string)
  default     = {}
}
