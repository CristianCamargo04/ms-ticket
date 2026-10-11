variable "name_prefix" {
  description = "Prefix for every resource name, e.g. \"ms-ticket-dev\"."
  type        = string
}

# ------------------------------------------------------------------ logs ----

variable "logs_kms_key_arn" {
  description = "KMS key ARN that encrypts the log groups."
  type        = string
}

variable "log_retention_days" {
  description = "Retention of the application and ECS Exec log groups."
  type        = number
  default     = 30

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be a value supported by CloudWatch Logs."
  }
}

variable "log_group_deletion_protection" {
  description = "Protect the log groups against deletion (audit evidence in prod)."
  type        = bool
  default     = false
}

variable "services" {
  description = "Services that get a log group \"/ecs/<name_prefix>/<service>\"."
  type        = list(string)
  default     = ["api", "worker"]
}

# -------------------------------------------------------------- alarming ----

variable "sns_kms_key_arn" {
  description = "KMS key ARN that encrypts the alarm SNS topic (its key policy must allow cloudwatch.amazonaws.com)."
  type        = string
}

variable "alarm_email_addresses" {
  description = "E-mail endpoints subscribed to the alarm topic (each one must confirm the subscription)."
  type        = list(string)
  default     = []
}

variable "alb_arn_suffix" {
  description = "ALB ARN suffix (LoadBalancer dimension)."
  type        = string
}

variable "target_group_arn_suffix" {
  description = "API target group ARN suffix (TargetGroup dimension)."
  type        = string
}

variable "waf_web_acl_name" {
  description = "WAF web ACL name (dashboard)."
  type        = string
}

variable "ecs_cluster_name" {
  description = "ECS cluster name."
  type        = string
}

variable "ecs_service_names" {
  description = "Map of logical service (api, worker) to ECS service name."
  type        = map(string)
}

variable "queue_names" {
  description = "Map of logical queue to work queue name."
  type        = map(string)
}

variable "dlq_names" {
  description = "Map of logical queue to dead-letter queue name."
  type        = map(string)
}

variable "dynamodb_table_names" {
  description = "Map of logical table to physical DynamoDB table name."
  type        = map(string)
}

variable "lambda_function_names" {
  description = "Map of logical name to Lambda function name that get an errors/throttles alarm (e.g. Cognito triggers)."
  type        = map(string)
  default     = {}
}

variable "queue_age_threshold_seconds" {
  description = "Alarm when the oldest message of a work queue is older than this (consumers stuck or under-provisioned)."
  type        = number
  default     = 300
}

variable "alb_5xx_rate_threshold_percent" {
  description = "Alarm when (target + ELB 5xx) / requests exceeds this percentage."
  type        = number
  default     = 5
}

variable "alb_5xx_min_requests_per_minute" {
  description = "Ignore the 5xx rate below this traffic (avoids paging for 1 error out of 2 requests at night)."
  type        = number
  default     = 20
}

variable "target_p99_latency_threshold_seconds" {
  description = "Alarm when the p99 of TargetResponseTime exceeds this (the API only accepts-and-enqueues, so it must stay fast)."
  type        = number
  default     = 1
}

variable "dynamodb_throttled_requests_threshold" {
  description = "Alarm when a table records more throttled requests than this per minute (sustained)."
  type        = number
  default     = 5
}

variable "ecs_cpu_threshold_percent" {
  description = "Alarm when a service average CPU stays above this (autoscaling at max capacity or not keeping up)."
  type        = number
  default     = 85
}

variable "ecs_memory_threshold_percent" {
  description = "Alarm when a service average memory stays above this (JVM heap / native memory pressure)."
  type        = number
  default     = 85
}

# ---------------------------------------------------------------- budget ----

variable "monthly_budget_amount" {
  description = "Monthly cost budget in USD. null = no budget."
  type        = number
  default     = null
}

variable "budget_notification_emails" {
  description = "Recipients of budget notifications (actual > 80 %, forecast > 100 %)."
  type        = list(string)
  default     = []
}

variable "budget_filter_tag" {
  description = "Optional cost allocation tag filter for the budget (the tag must be activated in Billing). null = whole account (recommended with one account per environment)."
  type = object({
    key   = string
    value = string
  })
  default = null
}

variable "tags" {
  description = "Extra tags (provider default_tags already apply)."
  type        = map(string)
  default     = {}
}
