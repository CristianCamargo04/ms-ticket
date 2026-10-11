output "api_base_url" {
  description = "Public ALB endpoint (create a Route 53 alias matching the certificate name in front of it)."
  value       = "https://${module.alb_waf.alb_dns_name}"
}

output "alb_dns_name" {
  description = "ALB DNS name."
  value       = module.alb_waf.alb_dns_name
}

output "alb_zone_id" {
  description = "ALB hosted zone ID (Route 53 alias target)."
  value       = module.alb_waf.alb_zone_id
}

output "ecr_repository_url" {
  description = "Push images here: docker push <url>:<git sha>."
  value       = module.ecr.repository_url
}

output "deployed_image" {
  description = "Image reference deployed to both services."
  value       = local.image
}

output "ecs_cluster_name" {
  description = "ECS cluster name."
  value       = module.ecs_cluster.cluster_name
}

output "ecs_service_names" {
  description = "ECS service names."
  value       = { api = module.api.service_name, worker = module.worker.service_name }
}

output "cognito_issuer_uri" {
  description = "JWT issuer to configure in the API."
  value       = module.auth.issuer_uri
}

output "cognito_jwks_uri" {
  description = "JWK Set URI to configure in the API."
  value       = module.auth.jwks_uri
}

output "cognito_web_client_id" {
  description = "Public web client ID (PKCE)."
  value       = module.auth.web_client_id
}

output "cognito_hosted_domain" {
  description = "Cognito hosted login / OAuth2 base URL."
  value       = module.auth.hosted_domain
}

output "dynamodb_table_names" {
  description = "Physical DynamoDB table names."
  value       = module.dynamodb.table_names
}

output "sqs_queue_urls" {
  description = "Work queue URLs."
  value       = module.sqs.queue_urls
}

output "sqs_dlq_names" {
  description = "Dead-letter queue names."
  value       = module.sqs.dlq_names
}

output "payment_gateway_secret_arn" {
  description = "Secret to fill out-of-band with the payment provider API key."
  value       = aws_secretsmanager_secret.payment_gateway.arn
}

output "alarm_topic_arn" {
  description = "SNS topic receiving all alarms."
  value       = module.observability.alarm_topic_arn
}

output "dashboard_url" {
  description = "CloudWatch dashboard."
  value       = "https://${var.aws_region}.console.aws.amazon.com/cloudwatch/home?region=${var.aws_region}#dashboards:name=${module.observability.dashboard_name}"
}

output "nat_gateway_public_ips" {
  description = "Egress IPs to allow-list at the payment provider."
  value       = module.network.nat_gateway_public_ips
}
