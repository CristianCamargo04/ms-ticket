variable "name_prefix" {
  description = "Prefix used in key aliases and descriptions, e.g. \"ms-ticket-dev\"."
  type        = string
}

variable "deletion_window_in_days" {
  description = "Waiting period before a scheduled key deletion completes. Short in dev, maximum in prod."
  type        = number
  default     = 30

  validation {
    condition     = var.deletion_window_in_days >= 7 && var.deletion_window_in_days <= 30
    error_message = "deletion_window_in_days must be between 7 and 30."
  }
}

variable "rotation_period_in_days" {
  description = "Automatic key material rotation period."
  type        = number
  default     = 365

  validation {
    condition     = var.rotation_period_in_days >= 90 && var.rotation_period_in_days <= 2560
    error_message = "rotation_period_in_days must be between 90 and 2560."
  }
}

variable "key_administrator_arns" {
  description = "IAM principals allowed to administer (not use) the keys. Empty = the account root, i.e. administration is delegated to IAM policies. When set, it MUST include the principal that runs Terraform (KMS lockout safety check)."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Extra tags for the keys (provider default_tags already apply)."
  type        = map(string)
  default     = {}
}
