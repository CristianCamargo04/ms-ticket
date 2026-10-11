################################################################################
# Application Auto Scaling (target tracking). Several policies may coexist:
# ECS scales OUT when ANY policy asks for it and IN only when ALL agree.
#   api    -> CPU + ALBRequestCountPerTarget
#   worker -> CPU + SQS backlog per task (metric math)
################################################################################

locals {
  autoscaling_resource_id = "service/${var.cluster_name}/${aws_ecs_service.this.name}"

  backlog_queue_ids        = var.queue_backlog_scaling == null ? [] : [for index, _ in var.queue_backlog_scaling.queue_names : "queue${index}"]
  backlog_visible_messages = join(" + ", local.backlog_queue_ids)
}

resource "aws_appautoscaling_target" "this" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = local.autoscaling_resource_id
  min_capacity       = var.min_capacity
  max_capacity       = var.max_capacity

  tags = var.tags
}

resource "aws_appautoscaling_policy" "cpu" {
  count = var.cpu_target_value == null ? 0 : 1

  name               = "${local.full_name}-cpu-target"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  resource_id        = aws_appautoscaling_target.this.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.cpu_target_value
    scale_in_cooldown  = var.scale_in_cooldown
    scale_out_cooldown = var.scale_out_cooldown

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_policy" "alb_requests" {
  count = var.alb_request_count_scaling == null ? 0 : 1

  name               = "${local.full_name}-requests-per-target"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  resource_id        = aws_appautoscaling_target.this.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.alb_request_count_scaling.target_value
    scale_in_cooldown  = var.scale_in_cooldown
    scale_out_cooldown = var.scale_out_cooldown

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = var.alb_request_count_scaling.resource_label
    }
  }
}

# backlogPerTask = (visible messages of every queue) / running tasks.
# target_value = acceptable latency / average processing time per message.
resource "aws_appautoscaling_policy" "queue_backlog" {
  count = var.queue_backlog_scaling == null ? 0 : 1

  name               = "${local.full_name}-backlog-per-task"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  resource_id        = aws_appautoscaling_target.this.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.queue_backlog_scaling.target_value
    scale_in_cooldown  = var.scale_in_cooldown
    scale_out_cooldown = var.scale_out_cooldown

    customized_metric_specification {
      dynamic "metrics" {
        for_each = var.queue_backlog_scaling.queue_names

        content {
          id          = "queue${metrics.key}"
          label       = "Visible messages in ${metrics.value}"
          return_data = false

          metric_stat {
            stat = "Sum"

            metric {
              namespace   = "AWS/SQS"
              metric_name = "ApproximateNumberOfMessagesVisible"

              dimensions {
                name  = "QueueName"
                value = metrics.value
              }
            }
          }
        }
      }

      metrics {
        id          = "tasks"
        label       = "Running tasks of ${local.full_name}"
        return_data = false

        metric_stat {
          stat = "Average"

          metric {
            namespace   = "ECS/ContainerInsights"
            metric_name = "RunningTaskCount"

            dimensions {
              name  = "ClusterName"
              value = var.cluster_name
            }

            dimensions {
              name  = "ServiceName"
              value = aws_ecs_service.this.name
            }
          }
        }
      }

      metrics {
        id          = "backlogPerTask"
        label       = "Backlog per task"
        expression  = "IF(tasks > 0, (${local.backlog_visible_messages}) / tasks, ${local.backlog_visible_messages})"
        return_data = true
      }
    }
  }
}
