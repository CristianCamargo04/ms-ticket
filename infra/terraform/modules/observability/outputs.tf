output "service_log_group_names" {
  description = "Map of service to its CloudWatch log group name."
  value       = { for service, group in aws_cloudwatch_log_group.service : service => group.name }
}

output "service_log_group_arns" {
  description = "Map of service to its CloudWatch log group ARN."
  value       = { for service, group in aws_cloudwatch_log_group.service : service => group.arn }
}

output "ecs_exec_log_group_name" {
  description = "Log group auditing ECS Exec sessions."
  value       = aws_cloudwatch_log_group.ecs_exec.name
}

output "ecs_exec_log_group_arn" {
  description = "ARN of the ECS Exec audit log group."
  value       = aws_cloudwatch_log_group.ecs_exec.arn
}

output "alarm_topic_arn" {
  description = "SNS topic receiving every alarm (subscribe PagerDuty/Slack/e-mail here)."
  value       = aws_sns_topic.alarms.arn
}

output "api_deployment_alarm_names" {
  description = "Alarms that roll back an API deployment when they fire (5xx rate, p99 latency)."
  value       = [aws_cloudwatch_metric_alarm.alb_5xx_rate.alarm_name, aws_cloudwatch_metric_alarm.target_p99_latency.alarm_name]
}

output "dashboard_name" {
  description = "CloudWatch dashboard name."
  value       = aws_cloudwatch_dashboard.this.dashboard_name
}
