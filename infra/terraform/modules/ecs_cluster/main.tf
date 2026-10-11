################################################################################
# Shared ECS cluster for the "api" and "worker" services (Fargate only).
################################################################################

resource "aws_ecs_cluster" "this" {
  #checkov:skip=CKV_AWS_224:ECS Exec sessions are TLS-encrypted and audited into a KMS-encrypted log group; Exec is disabled in prod.
  #checkov:skip=CKV_AWS_65:Container Insights is always on ("enhanced" or "enabled", it cannot be disabled); checkov does not evaluate the conditional through the module call.
  name = var.name_prefix

  setting {
    name  = "containerInsights"
    value = var.enhanced_container_insights ? "enhanced" : "enabled"
  }

  configuration {
    execute_command_configuration {
      logging = "OVERRIDE"

      log_configuration {
        cloud_watch_log_group_name     = var.exec_log_group_name
        cloud_watch_encryption_enabled = true
      }
    }
  }

  tags = merge(var.tags, { Name = var.name_prefix })
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  # Services always declare their own strategy; this is only the fallback.
  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
    base              = 0
  }
}
