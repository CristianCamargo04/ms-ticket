output "repository_name" {
  description = "ECR repository name."
  value       = aws_ecr_repository.this.name
}

output "repository_arn" {
  description = "ECR repository ARN (scope of the ECS execution role pull permissions)."
  value       = aws_ecr_repository.this.arn
}

output "repository_url" {
  description = "Repository URL to tag/push images and reference them from task definitions."
  value       = aws_ecr_repository.this.repository_url
}
