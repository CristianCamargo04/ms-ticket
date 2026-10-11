variable "name_prefix" {
  description = "Physical table names are \"<name_prefix>-<logical name>\", e.g. \"ms-ticket-dev-orders\"."
  type        = string
}

variable "kms_key_arn" {
  description = "Customer-managed KMS key used for server-side encryption of every table (and its PITR backups)."
  type        = string
}

variable "deletion_protection_enabled" {
  description = "Block DeleteTable on every table (true in prod)."
  type        = bool
  default     = true
}

variable "point_in_time_recovery_days" {
  description = "Point-in-time recovery window in days (1-35). PITR itself is always enabled."
  type        = number
  default     = 35

  validation {
    condition     = var.point_in_time_recovery_days >= 1 && var.point_in_time_recovery_days <= 35
    error_message = "point_in_time_recovery_days must be between 1 and 35."
  }
}

variable "contributor_insights_tables" {
  description = "Logical tables (and their GSIs) with CloudWatch Contributor Insights enabled, to spot hot partition keys (e.g. a sold-out event)."
  type        = list(string)
  default     = ["events", "tickets", "orders"]

  validation {
    condition     = alltrue([for table in var.contributor_insights_tables : contains(["events", "tickets", "orders", "ticket-transitions", "distributed-locks"], table)])
    error_message = "contributor_insights_tables may only contain: events, tickets, orders, ticket-transitions, distributed-locks."
  }
}

variable "contributor_insights_mode" {
  description = "ACCESSED_AND_THROTTLED_KEYS (full visibility) or THROTTLED_KEYS (cheaper: only throttled keys are analysed)."
  type        = string
  default     = "ACCESSED_AND_THROTTLED_KEYS"

  validation {
    condition     = contains(["ACCESSED_AND_THROTTLED_KEYS", "THROTTLED_KEYS"], var.contributor_insights_mode)
    error_message = "contributor_insights_mode must be ACCESSED_AND_THROTTLED_KEYS or THROTTLED_KEYS."
  }
}

variable "max_on_demand_throughput" {
  description = "Optional per-table ceiling for on-demand request units: a cost circuit breaker (e.g. in dev). null = unlimited."
  type = object({
    read_request_units  = number
    write_request_units = number
  })
  default = null
}

variable "tags" {
  description = "Extra tags for the tables (provider default_tags already apply)."
  type        = map(string)
  default     = {}
}
