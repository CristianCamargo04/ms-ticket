variable "name_prefix" {
  description = "Prefix for every resource name, e.g. \"ms-ticket-dev\" (keep it <= 24 chars: ALB/target group names are limited to 32)."
  type        = string

  validation {
    condition     = length(var.name_prefix) <= 24
    error_message = "name_prefix must be at most 24 characters long."
  }
}

variable "vpc_id" {
  description = "VPC of the load balancer and its targets."
  type        = string
}

variable "vpc_cidr_block" {
  description = "VPC CIDR: the ALB may only open connections to targets inside it."
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnets (at least two AZs) for the internet-facing ALB."
  type        = list(string)

  validation {
    condition     = length(var.public_subnet_ids) >= 2
    error_message = "An ALB needs subnets in at least two Availability Zones."
  }
}

variable "certificate_arn" {
  description = "ACM certificate ARN (same region as the ALB) for the HTTPS listener."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-zA-Z-]*:acm:[a-z0-9-]+:[0-9]{12}:certificate/.+$", var.certificate_arn))
    error_message = "certificate_arn must be an ACM certificate ARN."
  }
}

variable "ssl_policy" {
  description = "TLS negotiation policy of the HTTPS listener (TLS 1.3 + 1.2 only)."
  type        = string
  default     = "ELBSecurityPolicy-TLS13-1-2-2021-06"
}

variable "ingress_cidr_blocks" {
  description = "Client CIDRs allowed to reach the ALB (internet-facing: 0.0.0.0/0; restrict for private betas)."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "target_port" {
  description = "Container port of the API targets."
  type        = number
  default     = 8080
}

variable "health_check_path" {
  description = "Target health check path (Spring Boot readiness probe)."
  type        = string
  default     = "/actuator/health/readiness"
}

variable "health_check_port" {
  description = "Port of the target health checks: the Spring Boot management port (management.server.port), separate from target_port so that /actuator is never reachable through the listener. null = traffic port."
  type        = number
  default     = 8081

  validation {
    condition     = var.health_check_port == null || try(var.health_check_port >= 1024 && var.health_check_port <= 65535, false)
    error_message = "health_check_port must be null or an unprivileged port (1024-65535)."
  }
}

variable "idle_timeout" {
  description = "ALB idle timeout in seconds. Above the default 60 s so that quiet SSE availability streams are not cut."
  type        = number
  default     = 120
}

variable "deletion_protection" {
  description = "Protect the ALB against accidental deletion (true in prod)."
  type        = bool
  default     = true
}

variable "block_actuator_paths" {
  description = "Answer 404 at the edge for /actuator/**: ALB listener rule for the plain paths plus a WAF rule on the URL-decoded, path-normalised, lower-cased path (encoding tricks)."
  type        = bool
  default     = true
}

variable "access_logs_retention_days" {
  description = "Days before ALB access log objects expire."
  type        = number
  default     = 90
}

variable "force_destroy_logs_bucket" {
  description = "Allow destroying the access logs bucket while it still has objects (dev convenience only)."
  type        = bool
  default     = false
}

variable "waf_rate_limit_per_ip" {
  description = "Global WAF rate limit: maximum requests per client IP within the evaluation window."
  type        = number
  default     = 2000

  validation {
    condition     = var.waf_rate_limit_per_ip >= 10
    error_message = "WAF rate limits must be >= 10."
  }
}

variable "waf_orders_rate_limit_per_ip" {
  description = "Stricter WAF rate limit for POST /api/v1/events/*/orders (anti-bot / anti-scalping) per client IP within the evaluation window."
  type        = number
  default     = 100

  validation {
    condition     = var.waf_orders_rate_limit_per_ip >= 10
    error_message = "WAF rate limits must be >= 10."
  }
}

variable "waf_rate_limit_window_seconds" {
  description = "Evaluation window of the rate-based rules."
  type        = number
  default     = 300

  validation {
    condition     = contains([60, 120, 300, 600], var.waf_rate_limit_window_seconds)
    error_message = "waf_rate_limit_window_seconds must be 60, 120, 300 or 600."
  }
}

variable "waf_anonymous_ip_rules_to_count" {
  description = "Rules of AWSManagedRulesAnonymousIpList only counted instead of blocked. Default: VPN/Tor/proxy users are observed, requests from hosting providers (typical bot origin) are blocked."
  type        = list(string)
  default     = ["AnonymousIPList"]

  validation {
    condition     = alltrue([for rule in var.waf_anonymous_ip_rules_to_count : contains(["AnonymousIPList", "HostingProviderIPList"], rule)])
    error_message = "Valid rule names: AnonymousIPList, HostingProviderIPList."
  }
}

variable "waf_log_retention_days" {
  description = "Retention of the WAF log group."
  type        = number
  default     = 90
}

variable "waf_log_only_blocked" {
  description = "Only log requests that were blocked or counted (cuts log ingestion cost drastically)."
  type        = bool
  default     = true
}

variable "logs_kms_key_arn" {
  description = "KMS key ARN for the WAF CloudWatch log group."
  type        = string
}

variable "tags" {
  description = "Extra tags (provider default_tags already apply)."
  type        = map(string)
  default     = {}
}
