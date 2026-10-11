# prod: multi-AZ everything, deletion protection, long retention, no shell access.
# Placeholders (account id, certificate, e-mails, domains) must be replaced.

project             = "ms-ticket"
environment         = "prod"
aws_region          = "us-east-1"
allowed_account_ids = ["222222222222"] # prod account (separate from dev)
owner               = "ticketing-platform-team"
cost_center         = "CC-TICKETING-PROD"
repository          = "github.com/example-org/ms-ticket"

# Network: 3 AZs, one NAT gateway per AZ, interface endpoints in every AZ.
# Flow logs: all traffic, Parquet in S3 (Athena), Intelligent-Tiering then expiry.
vpc_cidr                    = "10.30.0.0/16"
availability_zones          = ["us-east-1a", "us-east-1b", "us-east-1c"]
single_nat_gateway          = false
interface_endpoint_az_count = null
flow_logs_destination       = "s3"
flow_logs_traffic_type      = "ALL"

# Edge
certificate_arn              = "arn:aws:acm:us-east-1:222222222222:certificate/00000000-0000-0000-0000-000000000000"
waf_rate_limit_per_ip        = 1000
waf_orders_rate_limit_per_ip = 50

# Auth: Plus tier with threat protection enforced (adaptive MFA on risky sign-ins).
auth_callback_urls          = ["https://tickets.example.com/callback"]
auth_logout_urls            = ["https://tickets.example.com/"]
auth_mfa_configuration      = "OPTIONAL"
auth_user_pool_tier         = "PLUS"
auth_advanced_security_mode = "ENFORCED"
# E-mails through SES (verified domain, production access granted): required in prod.
auth_ses_identity_arn   = "arn:aws:ses:us-east-1:222222222222:identity/example.com"
auth_email_from_address = "ms-ticket <no-reply@example.com>"

# Workloads
image_tag                   = "0.1.0" # CI overrides: -var image_tag=<git sha> (promoted from dev)
cpu_architecture            = "X86_64"
spring_profiles_active      = "prod"
payment_gateway_base_url    = "https://payments.example.com"
enable_execute_command      = false
enhanced_container_insights = true

# API: on-demand Fargate only, one task per AZ minimum.
api = {
  cpu                        = 1024
  memory                     = 2048
  desired_count              = 3
  min_capacity               = 3
  max_capacity               = 12
  cpu_target                 = 60
  requests_per_target        = 600
  capacity_provider_strategy = [{ capacity_provider = "FARGATE", weight = 1, base = 3 }]
}

# Worker: a guaranteed on-demand base, burst capacity mostly on Spot
# (interruptions are safe: unacknowledged messages are redelivered and consumers are idempotent).
worker = {
  cpu              = 1024
  memory           = 2048
  desired_count    = 2
  min_capacity     = 2
  max_capacity     = 10
  cpu_target       = 60
  backlog_per_task = 50
  capacity_provider_strategy = [
    { capacity_provider = "FARGATE", weight = 1, base = 2 },
    { capacity_provider = "FARGATE_SPOT", weight = 3 },
  ]
}

# Data: protected, maximum recovery windows, no throughput ceiling.
deletion_protection               = true
allow_destroy_with_data           = false
kms_deletion_window_days          = 30
point_in_time_recovery_days       = 35
secret_recovery_window_days       = 30
contributor_insights_mode         = "THROTTLED_KEYS" # ACCESSED_AND_THROTTLED_KEYS only while investigating
dynamodb_max_on_demand_throughput = null

# Observability: one year of logs (audit / incident forensics).
log_retention_days         = 365
flow_logs_retention_days   = 365
waf_log_retention_days     = 365
access_logs_retention_days = 365
alarm_email_addresses      = ["ticketing-oncall@example.com"]

# Governance
monthly_budget_usd         = 3000
budget_notification_emails = ["ticketing-platform-team@example.com", "finops@example.com"]
