# ------------------------------------------------------------------ identity --

variable "project" {
  description = "Project name; first part of every resource name."
  type        = string
  default     = "ms-ticket"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,15}$", var.project))
    error_message = "project must be 3-16 lowercase alphanumeric/hyphen characters starting with a letter."
  }
}

variable "environment" {
  description = "Environment name; second part of every resource name."
  type        = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging or prod."
  }
}

variable "aws_region" {
  description = "AWS region of the whole stack."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-gov)?-[a-z]+-[0-9]$", var.aws_region))
    error_message = "aws_region must look like us-east-1."
  }
}

variable "allowed_account_ids" {
  description = "AWS account IDs this configuration may be applied to (provider guardrail). Empty = no check."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for id in var.allowed_account_ids : can(regex("^[0-9]{12}$", id))])
    error_message = "allowed_account_ids must contain 12-digit AWS account IDs."
  }
}

variable "owner" {
  description = "Owning team (Owner tag)."
  type        = string

  validation {
    condition     = length(trimspace(var.owner)) > 0
    error_message = "owner must not be empty."
  }
}

variable "cost_center" {
  description = "Cost center for chargeback (CostCenter tag)."
  type        = string

  validation {
    condition     = length(trimspace(var.cost_center)) > 0
    error_message = "cost_center must not be empty."
  }
}

variable "repository" {
  description = "Source repository of this infrastructure (Repository tag)."
  type        = string
  default     = "ms-ticket"
}

# ------------------------------------------------------------------ network --

variable "vpc_cidr" {
  description = "VPC CIDR (/16-/18). Use non-overlapping ranges per environment so they can be peered later."
  type        = string

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "vpc_cidr must be a valid IPv4 CIDR."
  }
}

variable "availability_zones" {
  description = "Availability Zones (2 = cheaper, 3 = prod-grade). Pinned: the network never changes when AWS adds an AZ."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) >= 2 && length(var.availability_zones) <= 3 && alltrue([for az in var.availability_zones : startswith(az, var.aws_region)])
    error_message = "availability_zones must contain 2 or 3 AZs of aws_region."
  }
}

variable "single_nat_gateway" {
  description = "One shared NAT gateway (dev) instead of one per AZ (prod)."
  type        = bool
}

variable "interface_endpoint_az_count" {
  description = "AZs with an ENI per interface endpoint; null = all (prod)."
  type        = number
  default     = null
}

# ------------------------------------------------------------- edge / TLS ----

variable "certificate_arn" {
  description = "ACM certificate ARN for the public HTTPS listener (e.g. api.dev.example.com)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-zA-Z-]*:acm:[a-z0-9-]+:[0-9]{12}:certificate/.+$", var.certificate_arn))
    error_message = "certificate_arn must be an ACM certificate ARN."
  }
}

variable "waf_rate_limit_per_ip" {
  description = "Requests per client IP per 5 minutes before the WAF answers 429."
  type        = number
  default     = 2000

  validation {
    condition     = var.waf_rate_limit_per_ip >= 100
    error_message = "waf_rate_limit_per_ip must be >= 100."
  }
}

variable "waf_orders_rate_limit_per_ip" {
  description = "Order placements (POST /api/v1/events/*/orders) per client IP per 5 minutes."
  type        = number
  default     = 100

  validation {
    condition     = var.waf_orders_rate_limit_per_ip >= 10 && var.waf_orders_rate_limit_per_ip < var.waf_rate_limit_per_ip
    error_message = "waf_orders_rate_limit_per_ip must be >= 10 and stricter than waf_rate_limit_per_ip."
  }
}

# -------------------------------------------------------------------- auth --

variable "auth_callback_urls" {
  description = "OAuth2 redirect URIs of the web client."
  type        = list(string)
}

variable "auth_logout_urls" {
  description = "Sign-out redirect URIs of the web client."
  type        = list(string)
  default     = []
}

variable "auth_mfa_configuration" {
  description = "Cognito MFA: OPTIONAL or ON."
  type        = string
  default     = "OPTIONAL"
}

variable "auth_user_pool_tier" {
  description = "Cognito feature plan: ESSENTIALS or PLUS (threat protection)."
  type        = string
  default     = "ESSENTIALS"
}

variable "auth_advanced_security_mode" {
  description = "Cognito threat protection: OFF, AUDIT or ENFORCED (AUDIT/ENFORCED need PLUS)."
  type        = string
  default     = "OFF"

  validation {
    condition     = var.auth_advanced_security_mode == "OFF" || var.auth_user_pool_tier == "PLUS"
    error_message = "auth_advanced_security_mode AUDIT/ENFORCED requires auth_user_pool_tier = PLUS."
  }
}

# -------------------------------------------------------------- workloads ----

variable "image_tag" {
  description = "Immutable image tag deployed to both services (CI passes -var image_tag=<git sha>)."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$", var.image_tag)) && var.image_tag != "latest"
    error_message = "image_tag must be a valid, immutable Docker tag (never \"latest\")."
  }
}

variable "cpu_architecture" {
  description = "X86_64 or ARM64 (Graviton, ~20% cheaper; requires a multi-arch image)."
  type        = string
  default     = "X86_64"
}

variable "spring_profiles_active" {
  description = "Spring profile of the containers in AWS (no local endpoint overrides, no migrations runner)."
  type        = string
  default     = "prod"
}

variable "payment_gateway_base_url" {
  description = "Base URL of the payment provider (HTTPS)."
  type        = string

  validation {
    condition     = startswith(var.payment_gateway_base_url, "https://")
    error_message = "payment_gateway_base_url must use HTTPS."
  }
}

variable "enable_execute_command" {
  description = "ECS Exec (shell into containers). Never in prod."
  type        = bool
  default     = false

  validation {
    condition     = !(var.environment == "prod" && var.enable_execute_command)
    error_message = "ECS Exec must be disabled in prod."
  }
}

variable "enhanced_container_insights" {
  description = "ECS Container Insights with enhanced observability (true, prod) or standard (false, cheaper)."
  type        = bool
  default     = false
}

variable "api" {
  description = "Sizing of the API service. requests_per_target = ALB requests per task per minute before scaling out."
  type = object({
    cpu                 = number
    memory              = number
    desired_count       = number
    min_capacity        = number
    max_capacity        = number
    cpu_target          = number
    requests_per_target = number
    capacity_provider_strategy = list(object({
      capacity_provider = string
      weight            = number
      base              = optional(number, 0)
    }))
  })

  validation {
    condition     = var.api.min_capacity >= 1 && var.api.min_capacity <= var.api.max_capacity && var.api.desired_count >= var.api.min_capacity && var.api.desired_count <= var.api.max_capacity
    error_message = "api: 1 <= min_capacity <= desired_count <= max_capacity."
  }
}

variable "worker" {
  description = "Sizing of the worker service. backlog_per_task = acceptable queued messages per task (latency target / avg processing time)."
  type = object({
    cpu              = number
    memory           = number
    desired_count    = number
    min_capacity     = number
    max_capacity     = number
    cpu_target       = number
    backlog_per_task = number
    capacity_provider_strategy = list(object({
      capacity_provider = string
      weight            = number
      base              = optional(number, 0)
    }))
  })

  validation {
    # min >= 1: the reservation-expiration and dispatch sweepers run in the worker.
    condition     = var.worker.min_capacity >= 1 && var.worker.min_capacity <= var.worker.max_capacity && var.worker.desired_count >= var.worker.min_capacity && var.worker.desired_count <= var.worker.max_capacity
    error_message = "worker: 1 <= min_capacity <= desired_count <= max_capacity (the sweepers need at least one worker)."
  }
}

# ------------------------------------------------------------------- data ----

variable "deletion_protection" {
  description = "Deletion protection for DynamoDB tables, the ALB, the Cognito user pool and log groups."
  type        = bool
}

variable "allow_destroy_with_data" {
  description = "Let terraform destroy non-empty ECR repositories / log buckets (dev only)."
  type        = bool
  default     = false

  validation {
    condition     = !(var.environment == "prod" && var.allow_destroy_with_data)
    error_message = "allow_destroy_with_data must be false in prod."
  }
}

variable "kms_deletion_window_days" {
  description = "Waiting period before a deleted KMS key is gone for good."
  type        = number
  default     = 30
}

variable "point_in_time_recovery_days" {
  description = "DynamoDB PITR window (1-35 days)."
  type        = number
  default     = 35
}

variable "dynamodb_max_on_demand_throughput" {
  description = "Optional per-table on-demand request unit ceiling (cost circuit breaker). null = unlimited."
  type = object({
    read_request_units  = number
    write_request_units = number
  })
  default = null
}

variable "contributor_insights_mode" {
  description = "DynamoDB Contributor Insights mode: ACCESSED_AND_THROTTLED_KEYS or THROTTLED_KEYS (cheaper)."
  type        = string
  default     = "ACCESSED_AND_THROTTLED_KEYS"
}

variable "secret_recovery_window_days" {
  description = "Secrets Manager recovery window for deleted secrets (7-30)."
  type        = number
  default     = 30

  validation {
    condition     = var.secret_recovery_window_days >= 7 && var.secret_recovery_window_days <= 30
    error_message = "secret_recovery_window_days must be between 7 and 30."
  }
}

# ---------------------------------------------------------- observability ----

variable "log_retention_days" {
  description = "Retention of application and ECS Exec logs."
  type        = number
}

variable "flow_logs_retention_days" {
  description = "Retention of VPC flow logs."
  type        = number
}

variable "waf_log_retention_days" {
  description = "Retention of WAF logs."
  type        = number
}

variable "access_logs_retention_days" {
  description = "Retention of ALB access logs in S3."
  type        = number
}

variable "alarm_email_addresses" {
  description = "E-mail addresses subscribed to the alarm topic."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for email in var.alarm_email_addresses : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", email))])
    error_message = "alarm_email_addresses must contain valid e-mail addresses."
  }
}

variable "monthly_budget_usd" {
  description = "Monthly AWS Budget in USD (null = no budget)."
  type        = number
  default     = null

  validation {
    condition     = var.monthly_budget_usd == null || try(var.monthly_budget_usd > 0, false)
    error_message = "monthly_budget_usd must be null or > 0."
  }
}

variable "budget_notification_emails" {
  description = "Recipients of budget notifications."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for email in var.budget_notification_emails : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", email))])
    error_message = "budget_notification_emails must contain valid e-mail addresses."
  }
}
