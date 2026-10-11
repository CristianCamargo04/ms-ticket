output "service_name" {
  description = "ECS service name (CloudWatch ServiceName dimension)."
  value       = aws_ecs_service.this.name
}

output "service_arn" {
  description = "ECS service ARN."
  value       = aws_ecs_service.this.id
}

output "task_definition_arn" {
  description = "Task definition ARN (with revision) currently deployed."
  value       = aws_ecs_task_definition.this.arn
}

output "task_role_arn" {
  description = "Application (task) role ARN."
  value       = aws_iam_role.task.arn
}

output "task_role_name" {
  description = "Application (task) role name."
  value       = aws_iam_role.task.name
}

output "execution_role_arn" {
  description = "Execution role ARN (ECS agent)."
  value       = aws_iam_role.execution.arn
}

output "security_group_id" {
  description = "Security group of the tasks."
  value       = aws_security_group.this.id
}

output "container_name" {
  description = "Container name inside the task definition."
  value       = local.container_name
}
