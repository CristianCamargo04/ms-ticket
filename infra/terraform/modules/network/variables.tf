variable "name_prefix" {
  description = "Prefix for every resource name, e.g. \"ms-ticket-dev\"."
  type        = string
}

variable "vpc_cidr" {
  description = "IPv4 CIDR of the VPC (/16 to /18). Private subnets get a quarter each, public subnets a /24 (for a /16) inside the first quarter."
  type        = string

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0)) && tonumber(split("/", var.vpc_cidr)[1]) >= 16 && tonumber(split("/", var.vpc_cidr)[1]) <= 18
    error_message = "vpc_cidr must be a valid IPv4 CIDR with a prefix length between /16 and /18."
  }
}

variable "availability_zones" {
  description = "Availability Zone names to spread subnets across (2 or 3). Pinned explicitly so the network never changes when AWS adds an AZ."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) >= 2 && length(var.availability_zones) <= 3 && length(distinct(var.availability_zones)) == length(var.availability_zones)
    error_message = "availability_zones must contain 2 or 3 distinct AZ names (the ALB needs at least two AZs)."
  }
}

variable "single_nat_gateway" {
  description = "true = one shared NAT gateway (cheap, single-AZ egress: dev). false = one NAT gateway per AZ (no cross-AZ dependency: prod)."
  type        = bool
  default     = false
}

variable "interface_endpoint_services" {
  description = "AWS services reached through interface VPC endpoints (private DNS), keeping AWS API traffic off the NAT gateway."
  type        = list(string)
  default     = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs", "sts"]
}

variable "interface_endpoint_az_count" {
  description = "How many AZs get an ENI per interface endpoint. null = every AZ (prod). 1 in dev cuts endpoint cost by ~2/3 at the price of AZ resilience."
  type        = number
  default     = null

  validation {
    condition     = var.interface_endpoint_az_count == null || try(var.interface_endpoint_az_count >= 1 && var.interface_endpoint_az_count <= 3, false)
    error_message = "interface_endpoint_az_count must be null or between 1 and 3."
  }
}

variable "flow_logs_kms_key_arn" {
  description = "KMS key ARN that encrypts the VPC flow logs log group."
  type        = string
}

variable "flow_logs_retention_days" {
  description = "Retention of the VPC flow logs log group."
  type        = number
  default     = 30
}

variable "flow_logs_traffic_type" {
  description = "Traffic captured by the VPC flow logs: ACCEPT, REJECT or ALL."
  type        = string
  default     = "ALL"

  validation {
    condition     = contains(["ACCEPT", "REJECT", "ALL"], var.flow_logs_traffic_type)
    error_message = "flow_logs_traffic_type must be ACCEPT, REJECT or ALL."
  }
}

variable "flow_logs_max_aggregation_interval" {
  description = "Flow log aggregation window in seconds (60 = near real time, 600 = cheaper)."
  type        = number
  default     = 600

  validation {
    condition     = contains([60, 600], var.flow_logs_max_aggregation_interval)
    error_message = "flow_logs_max_aggregation_interval must be 60 or 600."
  }
}

variable "tags" {
  description = "Extra tags for the network resources (provider default_tags already apply)."
  type        = map(string)
  default     = {}
}
