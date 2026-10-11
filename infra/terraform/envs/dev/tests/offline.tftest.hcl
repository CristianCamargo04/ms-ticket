# Offline end-to-end test of this environment (no AWS credentials needed):
# the AWS provider is mocked, so `terraform test` evaluates every module,
# variable validation, pre/postcondition and output with the real
# terraform.tfvars, and checks the contracts shared with the application.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111111111111"
      arn        = "arn:aws:iam::111111111111:role/ci"
      user_id    = "AROATESTTESTTESTTEST"
    }
  }

  mock_data "aws_region" {
    defaults = {
      region = "us-east-1"
      name   = "us-east-1"
    }
  }

  mock_data "aws_partition" {
    defaults = {
      partition          = "aws"
      dns_suffix         = "amazonaws.com"
      reverse_dns_prefix = "com.amazonaws"
    }
  }

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }

  mock_data "aws_elb_service_account" {
    defaults = {
      arn = "arn:aws:iam::127311923021:root"
    }
  }

  # Realistic identifiers: the provider validates ARN / ID formats even when mocked.
  mock_resource "aws_kms_key" {
    defaults = {
      arn    = "arn:aws:kms:us-east-1:111111111111:key/00000000-0000-0000-0000-000000000000"
      key_id = "00000000-0000-0000-0000-000000000000"
    }
  }

  mock_resource "aws_vpc" {
    defaults = {
      id  = "vpc-0123456789abcdef0"
      arn = "arn:aws:ec2:us-east-1:111111111111:vpc/vpc-0123456789abcdef0"
    }
  }

  mock_resource "aws_subnet" {
    defaults = {
      id  = "subnet-0123456789abcdef0"
      arn = "arn:aws:ec2:us-east-1:111111111111:subnet/subnet-0123456789abcdef0"
    }
  }

  mock_resource "aws_internet_gateway" {
    defaults = {
      id = "igw-0123456789abcdef0"
    }
  }

  mock_resource "aws_route_table" {
    defaults = {
      id = "rtb-0123456789abcdef0"
    }
  }

  mock_resource "aws_eip" {
    defaults = {
      id        = "eipalloc-0123456789abcdef0"
      public_ip = "203.0.113.10"
    }
  }

  mock_resource "aws_nat_gateway" {
    defaults = {
      id = "nat-0123456789abcdef0"
    }
  }

  mock_resource "aws_security_group" {
    defaults = {
      id  = "sg-0123456789abcdef0"
      arn = "arn:aws:ec2:us-east-1:111111111111:security-group/sg-0123456789abcdef0"
    }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:us-east-1:111111111111:log-group:mock"
    }
  }

  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::111111111111:role/mock"
    }
  }

  mock_resource "aws_dynamodb_table" {
    defaults = {
      arn = "arn:aws:dynamodb:us-east-1:111111111111:table/mock"
    }
  }

  mock_resource "aws_sqs_queue" {
    defaults = {
      arn = "arn:aws:sqs:us-east-1:111111111111:mock"
      id  = "https://sqs.us-east-1.amazonaws.com/111111111111/mock"
      url = "https://sqs.us-east-1.amazonaws.com/111111111111/mock"
    }
  }

  mock_resource "aws_ecr_repository" {
    defaults = {
      arn            = "arn:aws:ecr:us-east-1:111111111111:repository/mock"
      repository_url = "111111111111.dkr.ecr.us-east-1.amazonaws.com/mock"
    }
  }

  mock_resource "aws_s3_bucket" {
    defaults = {
      arn = "arn:aws:s3:::mock-bucket"
    }
  }

  mock_resource "aws_lb" {
    defaults = {
      arn        = "arn:aws:elasticloadbalancing:us-east-1:111111111111:loadbalancer/app/mock/0123456789abcdef"
      arn_suffix = "app/mock/0123456789abcdef"
      dns_name   = "mock-123456789.us-east-1.elb.amazonaws.com"
      zone_id    = "Z35SXDOTRQ7X7K"
    }
  }

  mock_resource "aws_lb_target_group" {
    defaults = {
      arn        = "arn:aws:elasticloadbalancing:us-east-1:111111111111:targetgroup/mock/0123456789abcdef"
      arn_suffix = "targetgroup/mock/0123456789abcdef"
    }
  }

  mock_resource "aws_lb_listener" {
    defaults = {
      arn = "arn:aws:elasticloadbalancing:us-east-1:111111111111:listener/app/mock/0123456789abcdef/0123456789abcdef"
    }
  }

  mock_resource "aws_wafv2_web_acl" {
    defaults = {
      arn = "arn:aws:wafv2:us-east-1:111111111111:regional/webacl/mock/00000000-0000-0000-0000-000000000000"
    }
  }

  mock_resource "aws_sns_topic" {
    defaults = {
      arn = "arn:aws:sns:us-east-1:111111111111:mock"
    }
  }

  mock_resource "aws_secretsmanager_secret" {
    defaults = {
      arn = "arn:aws:secretsmanager:us-east-1:111111111111:secret:mock-AbCdEf"
      id  = "arn:aws:secretsmanager:us-east-1:111111111111:secret:mock-AbCdEf"
    }
  }

  mock_resource "aws_cognito_user_pool" {
    defaults = {
      id       = "us-east-1_AbCdEfGhI"
      arn      = "arn:aws:cognito-idp:us-east-1:111111111111:userpool/us-east-1_AbCdEfGhI"
      endpoint = "cognito-idp.us-east-1.amazonaws.com/us-east-1_AbCdEfGhI"
    }
  }

  mock_resource "aws_ecs_cluster" {
    defaults = {
      arn = "arn:aws:ecs:us-east-1:111111111111:cluster/mock"
    }
  }

  mock_resource "aws_ecs_task_definition" {
    defaults = {
      arn = "arn:aws:ecs:us-east-1:111111111111:task-definition/mock:1"
    }
  }
}

override_data {
  target = module.network.data.aws_availability_zones.selected
  values = {
    names = ["us-east-1a", "us-east-1b"]
  }
}

run "environment_matches_application_contracts" {
  command = apply # safe: every resource comes from the mocked provider

  assert {
    condition = module.dynamodb.table_names == {
      events               = "ms-ticket-dev-events"
      tickets              = "ms-ticket-dev-tickets"
      orders               = "ms-ticket-dev-orders"
      "ticket-transitions" = "ms-ticket-dev-ticket-transitions"
      "distributed-locks"  = "ms-ticket-dev-distributed-locks"
    }
    error_message = "DynamoDB tables must be the 5 tables of implementation-spec 6.1 (schema-migrations is local/CI only) prefixed with <project>-<env>-."
  }

  assert {
    condition = alltrue([
      contains(keys(module.dynamodb.index_arns), "events/status-eventDate-index"),
      contains(keys(module.dynamodb.index_arns), "tickets/eventId-statusKey-index"),
      contains(keys(module.dynamodb.index_arns), "orders/deadline-index"),
      length(module.dynamodb.index_arns) == 3,
    ])
    error_message = "GSIs must match implementation-spec 6.1."
  }

  assert {
    condition = (
      module.sqs.queue_names["order-reservation"] == "ms-ticket-dev-order-reservation" &&
      module.sqs.queue_names["order-payment"] == "ms-ticket-dev-order-payment" &&
      module.sqs.dlq_names["order-reservation"] == "ms-ticket-dev-order-reservation-dlq" &&
      module.sqs.dlq_names["order-payment"] == "ms-ticket-dev-order-payment-dlq"
    )
    error_message = "Each work queue needs its own DLQ with the expected names."
  }

  assert {
    condition     = length(module.network.private_subnet_ids) == 2 && length(module.network.public_subnet_ids) == 2 && length(module.network.nat_gateway_public_ips) == 1
    error_message = "dev runs in 2 AZs with a single NAT gateway."
  }

  assert {
    condition     = output.ecs_service_names == { api = "ms-ticket-dev-api", worker = "ms-ticket-dev-worker" }
    error_message = "Service names are used as CloudWatch dimensions by the alarms; they must match."
  }

  assert {
    condition     = module.ecs_cluster.cluster_name == "ms-ticket-dev"
    error_message = "The cluster name is used as a CloudWatch dimension by the alarms; it must match."
  }

  assert {
    condition     = local.common_environment["AWS_DYNAMODB_TABLES_TICKETTRANSITIONS"] == "ms-ticket-dev-ticket-transitions" && local.common_environment["AWS_SQS_ORDERPAYMENTQUEUE"] == "ms-ticket-dev-order-payment"
    error_message = "Table/queue names must be injected with the Spring relaxed-binding names of implementation-spec section 8."
  }

  assert {
    condition     = length(module.observability.api_deployment_alarm_names) == 2
    error_message = "API deployments must be guarded by the 5xx-rate and p99 alarms."
  }
}

run "rejects_mutable_latest_tag" {
  command = plan

  variables {
    image_tag = "latest"
  }

  expect_failures = [var.image_tag]
}

run "rejects_ecs_exec_in_prod" {
  command = plan

  variables {
    environment             = "prod"
    allow_destroy_with_data = false
    enable_execute_command  = true
  }

  expect_failures = [var.enable_execute_command]
}

run "rejects_advanced_security_without_plus_tier" {
  command = plan

  variables {
    auth_user_pool_tier         = "ESSENTIALS"
    auth_advanced_security_mode = "ENFORCED"
  }

  expect_failures = [var.auth_advanced_security_mode]
}
