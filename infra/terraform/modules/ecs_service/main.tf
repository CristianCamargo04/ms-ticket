################################################################################
# Reusable Fargate service: hardened task definition, private networking,
# per-service IAM roles, safe deployments and target-tracking autoscaling.
# Instantiated twice from the same image (ADR-008): "api" and "worker".
################################################################################

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region

  full_name      = "${var.name_prefix}-${var.service_name}"
  container_name = var.service_name

  environment = [for name in sort(keys(var.environment)) : { name = name, value = var.environment[name] }]

  secrets = [
    for name in sort(keys(var.secrets)) : {
      name      = name
      valueFrom = var.secrets[name].json_key == null ? var.secrets[name].secret_arn : "${var.secrets[name].secret_arn}:${var.secrets[name].json_key}::"
    }
  ]

  container_definition = merge(
    {
      name                   = local.container_name
      image                  = var.image
      essential              = true
      readonlyRootFilesystem = true
      privileged             = false
      stopTimeout            = var.stop_timeout
      environment            = local.environment
      secrets                = local.secrets
      portMappings = var.container_port == null ? [] : [{
        name          = "http"
        containerPort = var.container_port
        hostPort      = var.container_port
        protocol      = "tcp"
        appProtocol   = "http"
      }]
      # Read-only root filesystem: the only writable path is an ephemeral,
      # task-scoped volume on /tmp (Fargate has no tmpfs support).
      mountPoints = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
      linuxParameters = {
        initProcessEnabled = true # reaps zombies, forwards SIGTERM, needed by ECS Exec
        capabilities       = { add = [], drop = ["ALL"] }
      }
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = var.log_group_name
          awslogs-region        = local.region
          awslogs-stream-prefix = var.service_name
          mode                  = "non-blocking" # never block the event loop on log back-pressure
          max-buffer-size       = "25m"
        }
      }
    },
    var.container_user == null ? {} : { user = var.container_user },
  )
}

resource "aws_ecs_task_definition" "this" {
  family                   = local.full_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  container_definitions    = jsonencode([local.container_definition])

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  volume {
    name = "tmp"
  }

  tags = merge(var.tags, { Name = local.full_name, Service = var.service_name })
}

resource "aws_security_group" "this" {
  name        = local.full_name
  description = "ECS tasks of ${local.full_name}"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, { Name = local.full_name, Service = var.service_name })
}

resource "aws_vpc_security_group_ingress_rule" "app" {
  count = var.container_port == null ? 0 : length(var.ingress_security_group_ids)

  security_group_id            = aws_security_group.this.id
  description                  = "Application port from an allowed source security group"
  referenced_security_group_id = var.ingress_security_group_ids[count.index]
  ip_protocol                  = "tcp"
  from_port                    = var.container_port
  to_port                      = var.container_port
}

resource "aws_vpc_security_group_egress_rule" "https" {
  security_group_id = aws_security_group.this.id
  description       = "HTTPS to AWS APIs (VPC endpoints), the payment gateway and the identity provider JWKS"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_ecs_service" "this" {
  name                               = local.full_name
  cluster                            = var.cluster_arn
  task_definition                    = aws_ecs_task_definition.this.arn
  desired_count                      = var.desired_count
  platform_version                   = "LATEST"
  propagate_tags                     = "SERVICE"
  enable_ecs_managed_tags            = true
  enable_execute_command             = var.enable_execute_command
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  availability_zone_rebalancing      = "ENABLED"
  health_check_grace_period_seconds  = var.load_balancer_target_group_arn == null ? null : var.health_check_grace_period_seconds
  wait_for_steady_state              = false

  dynamic "capacity_provider_strategy" {
    for_each = var.capacity_provider_strategy

    content {
      capacity_provider = capacity_provider_strategy.value.capacity_provider
      weight            = capacity_provider_strategy.value.weight
      base              = capacity_provider_strategy.value.base
    }
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.this.id]
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = var.load_balancer_target_group_arn == null ? [] : [var.load_balancer_target_group_arn]

    content {
      target_group_arn = load_balancer.value
      container_name   = local.container_name
      container_port   = var.container_port
    }
  }

  # A deployment whose tasks cannot become healthy is rolled back automatically.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # ... and so is one that makes the given CloudWatch alarms fire (e.g. 5xx rate).
  dynamic "alarms" {
    for_each = length(var.deployment_alarm_names) > 0 ? [1] : []

    content {
      alarm_names = var.deployment_alarm_names
      enable      = true
      rollback    = true
    }
  }

  tags = merge(var.tags, { Name = local.full_name, Service = var.service_name })

  lifecycle {
    ignore_changes = [desired_count] # owned by Application Auto Scaling

    precondition {
      condition     = var.load_balancer_target_group_arn == null || var.container_port != null
      error_message = "container_port is required when load_balancer_target_group_arn is set."
    }

    precondition {
      condition     = !var.enable_execute_command || var.exec_log_group_arn != null
      error_message = "exec_log_group_arn is required when enable_execute_command = true."
    }
  }
}
