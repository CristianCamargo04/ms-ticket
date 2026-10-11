################################################################################
# Alarms -> SNS. Every alarm also notifies on recovery (ok_actions).
################################################################################

locals {
  alarm_actions = [aws_sns_topic.alarms.arn]
}

# --------------------------------------------------------------------- SQS --

# treat_missing_data = "ignore": CloudWatch only considers a queue "active" (and
# receives its metrics) for up to six hours after it held messages or was
# accessed, so a DLQ nobody touches can stop reporting while poison messages are
# still in it. "notBreaching" would then flip the alarm back to OK; "ignore"
# keeps the last state until a new datapoint arrives.
# Also rolls back worker deployments (worker_deployment_alarm_names).
resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  for_each = var.dlq_names

  alarm_name          = "${var.name_prefix}-${each.key}-dlq-not-empty"
  alarm_description   = "Poison messages in ${each.value}: inspect, fix and redrive (StartMessageMoveTask). Also rolls back worker deployments."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = each.value }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "ignore"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "queue_age" {
  for_each = var.queue_names

  alarm_name          = "${var.name_prefix}-${each.key}-oldest-message-age"
  alarm_description   = "Oldest message in ${each.value} is older than ${var.queue_age_threshold_seconds}s: workers stuck, failing or at max capacity. Also rolls back worker deployments."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = each.value }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 3
  threshold           = var.queue_age_threshold_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  tags = var.tags
}

# --------------------------------------------------------------------- ALB --

resource "aws_cloudwatch_metric_alarm" "alb_5xx_rate" {
  alarm_name          = "${var.name_prefix}-alb-5xx-rate"
  alarm_description   = "More than ${var.alb_5xx_rate_threshold_percent}% of API requests end in 5xx (target or load balancer). Also rolls back API deployments."
  evaluation_periods  = 5
  datapoints_to_alarm = 3
  threshold           = var.alb_5xx_rate_threshold_percent
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "requests"
    return_data = false

    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
      stat        = "Sum"
      period      = 60
    }
  }

  metric_query {
    id          = "target5xx"
    return_data = false

    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
      stat        = "Sum"
      period      = 60
    }
  }

  metric_query {
    id          = "elb5xx"
    return_data = false

    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_ELB_5XX_Count"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
      stat        = "Sum"
      period      = 60
    }
  }

  metric_query {
    id          = "errorRate"
    label       = "5xx rate (%)"
    expression  = "IF(requests >= ${var.alb_5xx_min_requests_per_minute}, 100 * (FILL(target5xx, 0) + FILL(elb5xx, 0)) / requests, 0)"
    return_data = true
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "target_p99_latency" {
  alarm_name                            = "${var.name_prefix}-api-p99-latency"
  alarm_description                     = "p99 TargetResponseTime above ${var.target_p99_latency_threshold_seconds}s. Also rolls back API deployments."
  namespace                             = "AWS/ApplicationELB"
  metric_name                           = "TargetResponseTime"
  dimensions                            = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  extended_statistic                    = "p99"
  period                                = 60
  evaluation_periods                    = 5
  datapoints_to_alarm                   = 3
  threshold                             = var.target_p99_latency_threshold_seconds
  comparison_operator                   = "GreaterThanThreshold"
  treat_missing_data                    = "notBreaching"
  evaluate_low_sample_count_percentiles = "ignore"
  alarm_actions                         = local.alarm_actions
  ok_actions                            = local.alarm_actions

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "no_healthy_targets" {
  alarm_name          = "${var.name_prefix}-api-no-healthy-targets"
  alarm_description   = "The API target group has no healthy task: the public API is down."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  tags = var.tags
}

# ---------------------------------------------------------------- DynamoDB --
# Metrics Insights queries aggregate every Operation dimension of a table.

resource "aws_cloudwatch_metric_alarm" "dynamodb_throttled_requests" {
  for_each = var.dynamodb_table_names

  alarm_name          = "${var.name_prefix}-ddb-${each.key}-throttled-requests"
  alarm_description   = "Sustained throttling on ${each.value}: hot partition (e.g. event counter item) or on-demand ceiling reached."
  evaluation_periods  = 5
  datapoints_to_alarm = 3
  threshold           = var.dynamodb_throttled_requests_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "throttled"
    label       = "ThrottledRequests ${each.value}"
    expression  = "SELECT SUM(ThrottledRequests) FROM SCHEMA(\"AWS/DynamoDB\", Operation, TableName) WHERE TableName = '${each.value}'"
    period      = 60
    return_data = true
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "dynamodb_system_errors" {
  for_each = var.dynamodb_table_names

  alarm_name          = "${var.name_prefix}-ddb-${each.key}-system-errors"
  alarm_description   = "DynamoDB returned HTTP 500s for ${each.value} (service-side issue: check AWS Health)."
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "systemErrors"
    label       = "SystemErrors ${each.value}"
    expression  = "SELECT SUM(SystemErrors) FROM SCHEMA(\"AWS/DynamoDB\", Operation, TableName) WHERE TableName = '${each.value}'"
    period      = 60
    return_data = true
  }

  tags = var.tags
}

# --------------------------------------------------------------------- ECS --

resource "aws_cloudwatch_metric_alarm" "ecs_cpu_high" {
  for_each = var.ecs_service_names

  alarm_name          = "${var.name_prefix}-${each.key}-cpu-high"
  alarm_description   = "${each.value} average CPU above ${var.ecs_cpu_threshold_percent}% for 8 of 10 minutes: autoscaling at max capacity or not keeping up."
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  dimensions          = { ClusterName = var.ecs_cluster_name, ServiceName = each.value }
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 10
  datapoints_to_alarm = 8
  threshold           = var.ecs_cpu_threshold_percent
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "ecs_memory_high" {
  for_each = var.ecs_service_names

  alarm_name          = "${var.name_prefix}-${each.key}-memory-high"
  alarm_description   = "${each.value} average memory above ${var.ecs_memory_threshold_percent}% for 8 of 10 minutes: risk of OOM kills."
  namespace           = "AWS/ECS"
  metric_name         = "MemoryUtilization"
  dimensions          = { ClusterName = var.ecs_cluster_name, ServiceName = each.value }
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 10
  datapoints_to_alarm = 8
  threshold           = var.ecs_memory_threshold_percent
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  tags = var.tags
}

# ---------------------------------------------------------------- business --

resource "aws_cloudwatch_metric_alarm" "payment_captured_for_released_order" {
  count = contains(var.services, "worker") ? 1 : 0

  alarm_name          = "${var.name_prefix}-payment-captured-for-released-order"
  alarm_description   = "A payment was captured for an order whose tickets were already released: manual refund required."
  namespace           = local.metrics_namespace
  metric_name         = aws_cloudwatch_log_metric_filter.payment_captured_for_released_order[0].metric_transformation[0].name
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  tags = var.tags
}

# ------------------------------------------------------------------ Lambda --
# A failing Cognito trigger is user-visible (e.g. a self-signed-up customer
# without the CUSTOMER group gets 403 on every order endpoint).

resource "aws_cloudwatch_metric_alarm" "lambda_errors" {
  for_each = var.lambda_function_names

  alarm_name          = "${var.name_prefix}-${each.key}-lambda-errors"
  alarm_description   = "Lambda ${each.value} failed or was throttled. For the Cognito post-confirmation trigger: affected users lack their default group, fix the cause and add them with admin-add-user-to-group."
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "errors"
    return_data = false

    metric {
      namespace   = "AWS/Lambda"
      metric_name = "Errors"
      dimensions  = { FunctionName = each.value }
      stat        = "Sum"
      period      = 300
    }
  }

  metric_query {
    id          = "throttles"
    return_data = false

    metric {
      namespace   = "AWS/Lambda"
      metric_name = "Throttles"
      dimensions  = { FunctionName = each.value }
      stat        = "Sum"
      period      = 300
    }
  }

  metric_query {
    id          = "failures"
    label       = "Errors + throttles"
    expression  = "FILL(errors, 0) + FILL(throttles, 0)"
    return_data = true
  }

  tags = var.tags
}
