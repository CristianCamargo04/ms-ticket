variable "name_prefix" {
  description = "Physical queue names are \"<name_prefix>-<logical name>\" and \"<name_prefix>-<logical name>-dlq\"."
  type        = string
}

variable "queues" {
  description = "Logical names of the work queues; each one gets its own dead-letter queue."
  type        = list(string)
  default     = ["order-reservation", "order-payment"]
}

variable "kms_key_arn" {
  description = "Customer-managed KMS key for SSE of queues and DLQs."
  type        = string
}

variable "kms_data_key_reuse_period_seconds" {
  description = "How long SQS reuses a data key before calling KMS again (60-86400). Higher = fewer KMS requests (cost) at a slightly larger crypto period."
  type        = number
  default     = 300

  validation {
    condition     = var.kms_data_key_reuse_period_seconds >= 60 && var.kms_data_key_reuse_period_seconds <= 86400
    error_message = "kms_data_key_reuse_period_seconds must be between 60 and 86400."
  }
}

variable "visibility_timeout_seconds" {
  description = "Must exceed the worst-case processing time of one message (ADR-003: 60 s)."
  type        = number
  default     = 60
}

variable "receive_wait_time_seconds" {
  description = "Long polling wait time (20 s = fewest empty receives = lowest cost)."
  type        = number
  default     = 20

  validation {
    condition     = var.receive_wait_time_seconds >= 0 && var.receive_wait_time_seconds <= 20
    error_message = "receive_wait_time_seconds must be between 0 and 20."
  }
}

variable "message_retention_seconds" {
  description = "Retention of the work queues (default 4 days)."
  type        = number
  default     = 345600
}

variable "dlq_message_retention_seconds" {
  description = "Retention of the dead-letter queues (default and maximum 14 days). Must be longer than the source retention because the original enqueue timestamp is kept."
  type        = number
  default     = 1209600

  validation {
    condition     = var.dlq_message_retention_seconds >= 60 && var.dlq_message_retention_seconds <= 1209600
    error_message = "dlq_message_retention_seconds must be between 60 and 1209600."
  }
}

variable "max_receive_count" {
  description = "Deliveries before a message is moved to its DLQ (poison message protection)."
  type        = number
  default     = 5

  validation {
    condition     = var.max_receive_count >= 1 && var.max_receive_count <= 1000
    error_message = "max_receive_count must be between 1 and 1000."
  }
}

variable "tags" {
  description = "Extra tags for the queues (provider default_tags already apply)."
  type        = map(string)
  default     = {}
}
