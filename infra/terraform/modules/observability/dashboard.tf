################################################################################
# One operational dashboard: edge -> API -> queues -> workers -> DynamoDB.
################################################################################

locals {
  widget_defaults = {
    view    = "timeSeries"
    stacked = false
    region  = local.region
    period  = 60
  }

  dashboard_widgets = [
    {
      type   = "text"
      x      = 0
      y      = 0
      width  = 24
      height = 2
      properties = {
        markdown = "## ${var.name_prefix} - ticketing platform\nEdge (WAF/ALB) -> API (accept & enqueue, 202) -> SQS -> workers (reservations, payments, sweepers) -> DynamoDB (conditional transactions). Alarms: SNS topic `${aws_sns_topic.alarms.name}`."
      }
    },
    {
      type   = "metric"
      x      = 0
      y      = 2
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "API traffic and errors (count/min)"
        metrics = [
          ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", var.alb_arn_suffix, { stat = "Sum", label = "Requests" }],
          ["AWS/ApplicationELB", "HTTPCode_Target_4XX_Count", "LoadBalancer", var.alb_arn_suffix, { stat = "Sum", label = "Target 4xx" }],
          ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", var.alb_arn_suffix, { stat = "Sum", label = "Target 5xx" }],
          ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", var.alb_arn_suffix, { stat = "Sum", label = "ALB 5xx" }],
        ]
      })
    },
    {
      type   = "metric"
      x      = 8
      y      = 2
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "API latency (s)"
        metrics = [
          for percentile in ["p50", "p95", "p99"] :
          ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { stat = percentile, label = percentile }]
        ]
        annotations = {
          horizontal = [{ label = "p99 alarm", value = var.target_p99_latency_threshold_seconds }]
        }
      })
    },
    {
      type   = "metric"
      x      = 16
      y      = 2
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "WAF allowed vs blocked requests"
        metrics = [
          ["AWS/WAFV2", "AllowedRequests", "WebACL", var.waf_web_acl_name, "Region", local.region, "Rule", "ALL", { stat = "Sum", label = "Allowed" }],
          ["AWS/WAFV2", "BlockedRequests", "WebACL", var.waf_web_acl_name, "Region", local.region, "Rule", "ALL", { stat = "Sum", label = "Blocked" }],
        ]
      })
    },
    {
      type   = "metric"
      x      = 0
      y      = 8
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "Queue backlog (visible / in flight)"
        # concat(...) and not flatten(): flatten is recursive and would merge the metric arrays.
        metrics = concat([
          for logical, queue in var.queue_names : [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", queue, { stat = "Maximum", label = "${logical} visible" }],
            ["AWS/SQS", "ApproximateNumberOfMessagesNotVisible", "QueueName", queue, { stat = "Maximum", label = "${logical} in flight" }],
          ]
        ]...)
      })
    },
    {
      type   = "metric"
      x      = 8
      y      = 8
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "Age of oldest message (s)"
        metrics = [
          for logical, queue in var.queue_names :
          ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", queue, { stat = "Maximum", label = logical }]
        ]
        annotations = {
          horizontal = [{ label = "alarm", value = var.queue_age_threshold_seconds }]
        }
      })
    },
    {
      type   = "metric"
      x      = 16
      y      = 8
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "Dead-letter queues (must be 0)"
        metrics = [
          for logical, queue in var.dlq_names :
          ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", queue, { stat = "Maximum", label = "${logical} DLQ" }]
        ]
      })
    },
    {
      type   = "metric"
      x      = 0
      y      = 14
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "ECS CPU utilisation (%)"
        metrics = [
          for logical, service in var.ecs_service_names :
          ["AWS/ECS", "CPUUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", service, { stat = "Average", label = logical }]
        ]
      })
    },
    {
      type   = "metric"
      x      = 8
      y      = 14
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "ECS memory utilisation (%)"
        metrics = [
          for logical, service in var.ecs_service_names :
          ["AWS/ECS", "MemoryUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", service, { stat = "Average", label = logical }]
        ]
      })
    },
    {
      type   = "metric"
      x      = 16
      y      = 14
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "Running tasks"
        metrics = [
          for logical, service in var.ecs_service_names :
          ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", var.ecs_cluster_name, "ServiceName", service, { stat = "Average", label = logical }]
        ]
      })
    },
    {
      type   = "metric"
      x      = 0
      y      = 20
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "DynamoDB consumed WCU (transactions cost 2x)"
        metrics = [
          for logical, table in var.dynamodb_table_names :
          ["AWS/DynamoDB", "ConsumedWriteCapacityUnits", "TableName", table, { stat = "Sum", label = logical }]
        ]
      })
    },
    {
      type   = "metric"
      x      = 8
      y      = 20
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "DynamoDB consumed RCU"
        metrics = [
          for logical, table in var.dynamodb_table_names :
          ["AWS/DynamoDB", "ConsumedReadCapacityUnits", "TableName", table, { stat = "Sum", label = logical }]
        ]
      })
    },
    {
      type   = "metric"
      x      = 16
      y      = 20
      width  = 8
      height = 6
      properties = merge(local.widget_defaults, {
        title = "DynamoDB contention: transaction conflicts and throttles"
        metrics = concat([
          for logical, table in var.dynamodb_table_names : [
            ["AWS/DynamoDB", "TransactionConflict", "TableName", table, { stat = "Sum", label = "${logical} tx conflicts" }],
            ["AWS/DynamoDB", "WriteThrottleEvents", "TableName", table, { stat = "Sum", label = "${logical} write throttles" }],
          ]
        ]...)
      })
    },
  ]
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = var.name_prefix
  dashboard_body = jsonencode({ widgets = local.dashboard_widgets })
}
