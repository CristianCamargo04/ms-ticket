# dev: small, cheap, disposable - but same topology and controls as prod.
# Placeholders (account id, certificate, e-mails, domains) must be replaced.

project             = "ms-ticket"
environment         = "dev"
aws_region          = "us-east-1"
allowed_account_ids = ["111111111111"] # dev account
owner               = "ticketing-platform-team"
cost_center         = "CC-TICKETING-NONPROD"
repository          = "github.com/example-org/ms-ticket"

# Network: 2 AZs, one shared NAT gateway, interface endpoints in a single AZ.
# Flow logs: only rejected flows, to CloudWatch Logs (small volume, quick to grep).
vpc_cidr                    = "10.20.0.0/16"
availability_zones          = ["us-east-1a", "us-east-1b"]
single_nat_gateway          = true
interface_endpoint_az_count = 1
flow_logs_destination       = "cloud-watch-logs"
flow_logs_traffic_type      = "REJECT"

# Edge
certificate_arn              = "arn:aws:acm:us-east-1:111111111111:certificate/00000000-0000-0000-0000-000000000000"
waf_rate_limit_per_ip        = 2000
waf_orders_rate_limit_per_ip = 100

# Auth: Essentials tier (no threat protection), MFA optional. E-mails from the
# Cognito default sender (daily cap, fine for a team); set an SES identity to lift it.
auth_callback_urls          = ["http://localhost:3000/callback", "https://app.dev.example.com/callback"]
auth_logout_urls            = ["http://localhost:3000/", "https://app.dev.example.com/"]
auth_mfa_configuration      = "OPTIONAL"
auth_user_pool_tier         = "ESSENTIALS"
auth_advanced_security_mode = "OFF"
auth_ses_identity_arn       = null

# Workloads: one task each, all on Fargate Spot (interruptions are acceptable in dev).
image_tag                   = "0.1.0" # CI overrides: -var image_tag=<git sha>
cpu_architecture            = "X86_64"
spring_profiles_active      = "prod"
payment_gateway_base_url    = "https://payments.sandbox.example.com"
enable_execute_command      = true
enhanced_container_insights = false

api = {
  cpu                        = 512
  memory                     = 1024
  desired_count              = 1
  min_capacity               = 1
  max_capacity               = 2
  cpu_target                 = 70
  requests_per_target        = 1000
  capacity_provider_strategy = [{ capacity_provider = "FARGATE_SPOT", weight = 1 }]
}

worker = {
  cpu                        = 512
  memory                     = 1024
  desired_count              = 1
  min_capacity               = 1
  max_capacity               = 2
  cpu_target                 = 70
  backlog_per_task           = 100
  capacity_provider_strategy = [{ capacity_provider = "FARGATE_SPOT", weight = 1 }]
}

# Data: easy teardown, short recovery windows, on-demand ceiling as a cost breaker.
deletion_protection               = false
allow_destroy_with_data           = true
kms_deletion_window_days          = 7
point_in_time_recovery_days       = 7
secret_recovery_window_days       = 7
contributor_insights_mode         = "THROTTLED_KEYS"
dynamodb_max_on_demand_throughput = { read_request_units = 1000, write_request_units = 1000 }

# Observability: short retention.
log_retention_days         = 14
flow_logs_retention_days   = 7
waf_log_retention_days     = 30
access_logs_retention_days = 30
alarm_email_addresses      = []

# Governance
monthly_budget_usd         = 250
budget_notification_emails = ["ticketing-platform-team@example.com"]
