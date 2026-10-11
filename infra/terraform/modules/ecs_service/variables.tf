variable "name_prefix" {
  description = "Prefix for every resource name, e.g. \"ms-ticket-dev\"."
  type        = string
}

variable "service_name" {
  description = "Short service name (\"api\", \"worker\"); also the container name and log stream prefix."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.service_name))
    error_message = "service_name must be lowercase alphanumeric/hyphen, 2-21 characters."
  }
}

variable "cluster_arn" {
  description = "ARN of the ECS cluster."
  type        = string
}

variable "cluster_name" {
  description = "Name of the ECS cluster (autoscaling resource id, Container Insights dimension)."
  type        = string
}

variable "vpc_id" {
  description = "VPC of the service security group."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets for the tasks (no public IPs are ever assigned)."
  type        = list(string)
}

variable "image" {
  description = "Full container image reference: <repository_url>:<immutable tag> or @sha256:<digest>."
  type        = string

  validation {
    condition     = !endswith(var.image, ":latest")
    error_message = "Use an immutable tag (git SHA) or a digest, never :latest."
  }
}

variable "ecr_repository_arn" {
  description = "ECR repository the execution role may pull from."
  type        = string
}

variable "cpu" {
  description = "Task CPU units."
  type        = number

  validation {
    condition     = contains([256, 512, 1024, 2048, 4096, 8192, 16384], var.cpu)
    error_message = "cpu must be a valid Fargate value: 256, 512, 1024, 2048, 4096, 8192 or 16384."
  }
}

variable "memory" {
  description = "Task memory (MiB); must be a valid Fargate combination with cpu."
  type        = number

  validation {
    condition     = var.memory >= 512 && var.memory <= 122880
    error_message = "memory must be between 512 and 122880 MiB."
  }
}

variable "cpu_architecture" {
  description = "X86_64 or ARM64 (Graviton: ~20% cheaper per vCPU-hour, needs a multi-arch image)."
  type        = string
  default     = "X86_64"

  validation {
    condition     = contains(["X86_64", "ARM64"], var.cpu_architecture)
    error_message = "cpu_architecture must be X86_64 or ARM64."
  }
}

variable "container_port" {
  description = "Container port (HTTP + actuator). null = no port mapping (queue workers)."
  type        = number
  default     = null
}

variable "management_port" {
  description = "Spring Boot management (actuator) port, separate from container_port (management.server.port). When the service has a container_port, ingress_security_group_ids may reach it for load balancer health checks. null = actuator on the application port."
  type        = number
  default     = null

  validation {
    condition     = var.management_port == null || try(var.management_port >= 1024 && var.management_port <= 65535, false)
    error_message = "management_port must be null or an unprivileged port (1024-65535)."
  }
}

variable "container_health_check" {
  description = "Optional container health check (ECS replaces unhealthy tasks; the deployment circuit breaker rolls back releases whose tasks never become healthy). command uses the Docker syntax, e.g. [\"CMD\", \"/usr/local/bin/healthcheck\"]. Times in seconds; start_period <= 300 (Fargate)."
  type = object({
    command      = list(string)
    interval     = optional(number, 15)
    timeout      = optional(number, 5)
    retries      = optional(number, 3)
    start_period = optional(number, 90)
  })
  default = null

  validation {
    condition = var.container_health_check == null || try(
      contains(["CMD", "CMD-SHELL"], var.container_health_check.command[0]) &&
      var.container_health_check.interval >= 5 && var.container_health_check.interval <= 300 &&
      var.container_health_check.timeout >= 2 && var.container_health_check.timeout <= 60 &&
      var.container_health_check.timeout < var.container_health_check.interval &&
      var.container_health_check.retries >= 1 && var.container_health_check.retries <= 10 &&
      var.container_health_check.start_period >= 0 && var.container_health_check.start_period <= 300,
      false
    )
    error_message = "container_health_check: command must start with CMD or CMD-SHELL; interval 5-300, timeout 2-60 (< interval), retries 1-10, start_period 0-300."
  }
}

variable "container_user" {
  description = "UID:GID the container runs as. Enforces a non-root user even if the image regresses."
  type        = string
  default     = "10001:10001"

  validation {
    condition     = var.container_user == null || !can(regex("^(0|root)(:|$)", var.container_user))
    error_message = "The container must not run as root."
  }
}

variable "stop_timeout" {
  description = "Seconds between SIGTERM and SIGKILL (graceful shutdown: drain HTTP requests and in-flight SQS messages)."
  type        = number
  default     = 60

  validation {
    condition     = var.stop_timeout >= 2 && var.stop_timeout <= 120
    error_message = "stop_timeout must be between 2 and 120 seconds (Fargate limit)."
  }
}

variable "environment" {
  description = "Plain environment variables (non-sensitive configuration only)."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Environment variables resolved from Secrets Manager at task start: name => { secret_arn, json_key }."
  type = map(object({
    secret_arn = string
    json_key   = optional(string)
  }))
  default = {}
}

variable "secrets_kms_key_arns" {
  description = "KMS keys protecting the referenced secrets (the execution role may decrypt them through Secrets Manager only)."
  type        = list(string)
  default     = []
}

variable "task_role_policy_json" {
  description = "IAM policy (JSON) with the application permissions of this service (least privilege, built by the caller)."
  type        = string
}

variable "log_group_name" {
  description = "CloudWatch log group for the container logs (awslogs driver)."
  type        = string
}

variable "log_group_arn" {
  description = "ARN of log_group_name."
  type        = string
}

variable "desired_count" {
  description = "Initial number of tasks (afterwards owned by autoscaling)."
  type        = number
  default     = 1
}

variable "min_capacity" {
  description = "Autoscaling lower bound."
  type        = number
  default     = 1
}

variable "max_capacity" {
  description = "Autoscaling upper bound (also a cost ceiling)."
  type        = number
  default     = 4

  validation {
    condition     = var.max_capacity >= 1
    error_message = "max_capacity must be >= 1."
  }
}

variable "capacity_provider_strategy" {
  description = "Fargate capacity provider mix, e.g. FARGATE base 1 + FARGATE_SPOT weight 3."
  type = list(object({
    capacity_provider = string
    weight            = number
    base              = optional(number, 0)
  }))
  default = [{ capacity_provider = "FARGATE", weight = 1, base = 0 }]

  validation {
    condition     = alltrue([for item in var.capacity_provider_strategy : contains(["FARGATE", "FARGATE_SPOT"], item.capacity_provider)])
    error_message = "capacity_provider must be FARGATE or FARGATE_SPOT."
  }
}

variable "load_balancer_target_group_arn" {
  description = "ALB target group to register the tasks in (requires container_port). null = no load balancer."
  type        = string
  default     = null
}

variable "ingress_security_group_ids" {
  description = "Security groups allowed to reach container_port (e.g. the ALB). Empty = no ingress at all."
  type        = list(string)
  default     = []
}

variable "health_check_grace_period_seconds" {
  description = "Seconds ALB health checks are ignored after a task starts (JVM warm-up)."
  type        = number
  default     = 90
}

variable "enable_execute_command" {
  description = "Allow ECS Exec (interactive shell) into tasks. Must be false in prod."
  type        = bool
  default     = false
}

variable "exec_log_group_arn" {
  description = "Log group recording ECS Exec sessions (required when enable_execute_command = true)."
  type        = string
  default     = null
}

variable "deployment_alarm_names" {
  description = "CloudWatch alarms that, when firing during a deployment, roll it back automatically."
  type        = list(string)
  default     = []
}

variable "deployment_alarms_enabled" {
  description = "Monitor deployment_alarm_names and roll back on ALARM. Break-glass: false to ship the fix of an incident whose alarms already fire (the circuit breaker stays on)."
  type        = bool
  default     = true
}

variable "cpu_target_value" {
  description = "Target average CPU utilisation (%) for target tracking. null = no CPU policy."
  type        = number
  default     = 60
}

variable "alb_request_count_scaling" {
  description = "Target tracking on ALBRequestCountPerTarget: resource_label = \"<alb arn suffix>/<target group arn suffix>\", target_value = requests per target per minute. null = disabled."
  type = object({
    resource_label = string
    target_value   = number
  })
  default = null
}

variable "queue_backlog_scaling" {
  description = "Target tracking on backlog per task = sum(ApproximateNumberOfMessagesVisible of queue_names) / RunningTaskCount. target_value = acceptable messages per task (acceptable latency / avg processing time). null = disabled."
  type = object({
    queue_names  = list(string)
    target_value = number
  })
  default = null
}

variable "scale_in_cooldown" {
  description = "Seconds after a scale-in before another scale-in (avoid flapping)."
  type        = number
  default     = 300
}

variable "scale_out_cooldown" {
  description = "Seconds after a scale-out before another scale-out."
  type        = number
  default     = 60
}

variable "tags" {
  description = "Extra tags (provider default_tags already apply)."
  type        = map(string)
  default     = {}
}
