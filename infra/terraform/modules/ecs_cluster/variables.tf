variable "name_prefix" {
  description = "Cluster name, e.g. \"ms-ticket-dev\"."
  type        = string
}

variable "enhanced_container_insights" {
  description = "true = Container Insights with enhanced observability (task/container level, prod); false = standard Container Insights (service level, cheaper). It is never disabled: the worker autoscaling uses its RunningTaskCount metric."
  type        = bool
  default     = true
}

variable "exec_log_group_name" {
  description = "KMS-encrypted CloudWatch log group that records ECS Exec sessions (audit trail)."
  type        = string
}

variable "tags" {
  description = "Extra tags (provider default_tags already apply)."
  type        = map(string)
  default     = {}
}
