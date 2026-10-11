################################################################################
# ms-ticket - environment composition.
# envs/dev/*.tf and envs/prod/*.tf are intentionally identical except for the
# backend key: every difference between environments lives in terraform.tfvars.
################################################################################

locals {
  name_prefix = "${var.project}-${var.environment}"
  image       = "${module.ecr.repository_url}:${var.image_tag}"

  # Spring Boot relaxed binding: aws.dynamodb.tables.ticketTransitions <-
  # AWS_DYNAMODB_TABLES_TICKETTRANSITIONS, etc. (implementation-spec section 8).
  # OS environment variables override application-prod.yml.
  common_environment = {
    SPRING_PROFILES_ACTIVE                = var.spring_profiles_active
    AWS_REGION                            = var.aws_region
    AWS_DYNAMODB_TABLES_EVENTS            = module.dynamodb.table_names["events"]
    AWS_DYNAMODB_TABLES_TICKETS           = module.dynamodb.table_names["tickets"]
    AWS_DYNAMODB_TABLES_ORDERS            = module.dynamodb.table_names["orders"]
    AWS_DYNAMODB_TABLES_TICKETTRANSITIONS = module.dynamodb.table_names["ticket-transitions"]
    AWS_DYNAMODB_TABLES_DISTRIBUTEDLOCKS  = module.dynamodb.table_names["distributed-locks"]
    AWS_SQS_ORDERRESERVATIONQUEUE         = module.sqs.queue_names["order-reservation"]
    AWS_SQS_ORDERPAYMENTQUEUE             = module.sqs.queue_names["order-payment"]
    TICKETING_MIGRATIONS_ENABLED          = "false" # Terraform owns the schema in AWS
    TICKETING_PAYMENT_BASEURL             = var.payment_gateway_base_url
    # Cognito (ADR-007): signature via JWKS, issuer, audience = app client id
    # ("client_id" claim of access tokens), roles = Cognito groups.
    TICKETING_SECURITY_ISSUER                           = module.auth.issuer_uri
    TICKETING_SECURITY_AUDIENCE                         = module.auth.web_client_id
    TICKETING_SECURITY_ROLESCLAIM                       = "cognito:groups"
    SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI = module.auth.jwks_uri
    JAVA_TOOL_OPTIONS                                   = "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
  }

  # Both spellings: TICKETING_RUNTIME_MODE (placeholder used by docker-compose /
  # application.yml) and TICKETING_RUNTIMEMODE (canonical relaxed binding of
  # ticketing.runtime-mode).
  runtime_mode_environment = {
    api    = { TICKETING_RUNTIME_MODE = "api", TICKETING_RUNTIMEMODE = "api" }
    worker = { TICKETING_RUNTIME_MODE = "worker", TICKETING_RUNTIMEMODE = "worker" }
  }
}

# ---------------------------------------------------------------- security --

module "kms" {
  source = "../../modules/kms"

  name_prefix             = local.name_prefix
  deletion_window_in_days = var.kms_deletion_window_days
}

module "network" {
  source = "../../modules/network"

  name_prefix                 = local.name_prefix
  vpc_cidr                    = var.vpc_cidr
  availability_zones          = var.availability_zones
  single_nat_gateway          = var.single_nat_gateway
  interface_endpoint_az_count = var.interface_endpoint_az_count
  flow_logs_kms_key_arn       = module.kms.logs_key_arn
  flow_logs_retention_days    = var.flow_logs_retention_days
}

module "auth" {
  source = "../../modules/auth"

  name_prefix            = local.name_prefix
  user_pool_tier         = var.auth_user_pool_tier
  advanced_security_mode = var.auth_advanced_security_mode
  mfa_configuration      = var.auth_mfa_configuration
  deletion_protection    = var.deletion_protection
  callback_urls          = var.auth_callback_urls
  logout_urls            = var.auth_logout_urls
}

# Payment provider API key: the value is set out-of-band (never in code/state):
#   aws secretsmanager put-secret-value --secret-id <arn> --secret-string '{"apiKey":"..."}'
resource "aws_secretsmanager_secret" "payment_gateway" {
  #checkov:skip=CKV2_AWS_57:Third-party API key, rotated by the provider's own process (no Lambda rotation possible).
  name                    = "${local.name_prefix}/payment-gateway"
  description             = "Payment gateway credentials of the ${local.name_prefix} worker"
  kms_key_id              = module.kms.platform_key_arn
  recovery_window_in_days = var.secret_recovery_window_days
}

# Placeholder so that the first deployment can start; replaced out-of-band.
resource "aws_secretsmanager_secret_version" "payment_gateway_placeholder" {
  secret_id     = aws_secretsmanager_secret.payment_gateway.id
  secret_string = jsonencode({ apiKey = "SET-OUT-OF-BAND" })

  lifecycle {
    ignore_changes = [secret_string, version_stages]
  }
}

# -------------------------------------------------------------------- data --

module "dynamodb" {
  source = "../../modules/dynamodb"

  name_prefix                 = local.name_prefix
  kms_key_arn                 = module.kms.dynamodb_key_arn
  deletion_protection_enabled = var.deletion_protection
  point_in_time_recovery_days = var.point_in_time_recovery_days
  contributor_insights_mode   = var.contributor_insights_mode
  max_on_demand_throughput    = var.dynamodb_max_on_demand_throughput
}

module "sqs" {
  source = "../../modules/sqs"

  name_prefix = local.name_prefix
  kms_key_arn = module.kms.sqs_key_arn
}

# ------------------------------------------------------------------- edge --

module "alb_waf" {
  source = "../../modules/alb_waf"

  name_prefix                  = local.name_prefix
  vpc_id                       = module.network.vpc_id
  vpc_cidr_block               = module.network.vpc_cidr_block
  public_subnet_ids            = module.network.public_subnet_ids
  certificate_arn              = var.certificate_arn
  deletion_protection          = var.deletion_protection
  force_destroy_logs_bucket    = var.allow_destroy_with_data
  access_logs_retention_days   = var.access_logs_retention_days
  waf_rate_limit_per_ip        = var.waf_rate_limit_per_ip
  waf_orders_rate_limit_per_ip = var.waf_orders_rate_limit_per_ip
  waf_log_retention_days       = var.waf_log_retention_days
  logs_kms_key_arn             = module.kms.logs_key_arn
}

# ---------------------------------------------------------------- compute --

module "ecr" {
  source = "../../modules/ecr"

  repository_name = local.name_prefix
  kms_key_arn     = module.kms.platform_key_arn
  force_delete    = var.allow_destroy_with_data
}

module "ecs_cluster" {
  source = "../../modules/ecs_cluster"

  name_prefix                 = local.name_prefix
  enhanced_container_insights = var.enhanced_container_insights
  exec_log_group_name         = module.observability.ecs_exec_log_group_name
}

module "api" {
  source = "../../modules/ecs_service"

  name_prefix        = local.name_prefix
  service_name       = "api"
  cluster_arn        = module.ecs_cluster.cluster_arn
  cluster_name       = module.ecs_cluster.cluster_name
  vpc_id             = module.network.vpc_id
  subnet_ids         = module.network.private_subnet_ids
  image              = local.image
  ecr_repository_arn = module.ecr.repository_arn
  cpu                = var.api.cpu
  memory             = var.api.memory
  cpu_architecture   = var.cpu_architecture
  container_port     = 8080

  environment           = merge(local.common_environment, local.runtime_mode_environment.api)
  task_role_policy_json = data.aws_iam_policy_document.api_task.json
  log_group_name        = module.observability.service_log_group_names["api"]
  log_group_arn         = module.observability.service_log_group_arns["api"]

  # Only the ALB may reach the API tasks.
  load_balancer_target_group_arn = module.alb_waf.target_group_arn
  ingress_security_group_ids     = [module.alb_waf.alb_security_group_id]
  deployment_alarm_names         = module.observability.api_deployment_alarm_names

  desired_count              = var.api.desired_count
  min_capacity               = var.api.min_capacity
  max_capacity               = var.api.max_capacity
  capacity_provider_strategy = var.api.capacity_provider_strategy
  cpu_target_value           = var.api.cpu_target
  alb_request_count_scaling = {
    resource_label = "${module.alb_waf.alb_arn_suffix}/${module.alb_waf.target_group_arn_suffix}"
    target_value   = var.api.requests_per_target
  }

  enable_execute_command = var.enable_execute_command
  exec_log_group_arn     = module.observability.ecs_exec_log_group_arn
}

module "worker" {
  source = "../../modules/ecs_service"

  name_prefix        = local.name_prefix
  service_name       = "worker"
  cluster_arn        = module.ecs_cluster.cluster_arn
  cluster_name       = module.ecs_cluster.cluster_name
  vpc_id             = module.network.vpc_id
  subnet_ids         = module.network.private_subnet_ids
  image              = local.image
  ecr_repository_arn = module.ecr.repository_arn
  cpu                = var.worker.cpu
  memory             = var.worker.memory
  cpu_architecture   = var.cpu_architecture
  container_port     = null # no ingress at all: it only polls SQS

  environment           = merge(local.common_environment, local.runtime_mode_environment.worker)
  task_role_policy_json = data.aws_iam_policy_document.worker_task.json
  log_group_name        = module.observability.service_log_group_names["worker"]
  log_group_arn         = module.observability.service_log_group_arns["worker"]

  secrets = {
    # secret_id of the version (= secret ARN) also orders the task start after the first version exists.
    TICKETING_PAYMENT_APIKEY = { secret_arn = aws_secretsmanager_secret_version.payment_gateway_placeholder.secret_id, json_key = "apiKey" }
  }
  secrets_kms_key_arns = [module.kms.platform_key_arn]

  desired_count              = var.worker.desired_count
  min_capacity               = var.worker.min_capacity
  max_capacity               = var.worker.max_capacity
  capacity_provider_strategy = var.worker.capacity_provider_strategy
  cpu_target_value           = var.worker.cpu_target
  queue_backlog_scaling = {
    queue_names  = values(module.sqs.queue_names)
    target_value = var.worker.backlog_per_task
  }

  enable_execute_command = var.enable_execute_command
  exec_log_group_arn     = module.observability.ecs_exec_log_group_arn
}

# ---------------------------------------------------------- observability --

module "observability" {
  source = "../../modules/observability"

  name_prefix                   = local.name_prefix
  logs_kms_key_arn              = module.kms.logs_key_arn
  sns_kms_key_arn               = module.kms.platform_key_arn
  log_retention_days            = var.log_retention_days
  log_group_deletion_protection = var.deletion_protection
  alarm_email_addresses         = var.alarm_email_addresses

  alb_arn_suffix          = module.alb_waf.alb_arn_suffix
  target_group_arn_suffix = module.alb_waf.target_group_arn_suffix
  waf_web_acl_name        = module.alb_waf.web_acl_name
  ecs_cluster_name        = local.name_prefix
  ecs_service_names       = { api = "${local.name_prefix}-api", worker = "${local.name_prefix}-worker" }
  queue_names             = module.sqs.queue_names
  dlq_names               = module.sqs.dlq_names
  dynamodb_table_names    = module.dynamodb.table_names

  monthly_budget_amount      = var.monthly_budget_usd
  budget_notification_emails = var.budget_notification_emails
}
