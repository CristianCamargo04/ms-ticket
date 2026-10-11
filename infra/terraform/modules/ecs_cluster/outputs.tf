output "cluster_name" {
  description = "ECS cluster name (available once the capacity providers are associated)."
  value       = aws_ecs_cluster_capacity_providers.this.cluster_name
}

output "cluster_arn" {
  description = "ECS cluster ARN (available once the capacity providers are associated)."
  value       = aws_ecs_cluster.this.arn

  # Services declare FARGATE / FARGATE_SPOT strategies: they must not be
  # created before the providers are attached to the cluster.
  depends_on = [aws_ecs_cluster_capacity_providers.this]
}
