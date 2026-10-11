output "alb_arn" {
  description = "ALB ARN."
  value       = aws_lb.this.arn
}

output "alb_arn_suffix" {
  description = "ALB ARN suffix (CloudWatch LoadBalancer dimension)."
  value       = aws_lb.this.arn_suffix
}

output "alb_dns_name" {
  description = "Public DNS name of the ALB (point a Route 53 alias record at it)."
  value       = aws_lb.this.dns_name
}

output "alb_zone_id" {
  description = "Hosted zone ID of the ALB (for Route 53 alias records)."
  value       = aws_lb.this.zone_id
}

output "alb_security_group_id" {
  description = "Security group of the ALB (the only allowed source of API task traffic)."
  value       = aws_security_group.alb.id
}

output "target_group_arn" {
  description = "Target group ARN of the API service (available once a listener forwards to it)."
  value       = aws_lb_target_group.api.arn

  # ECS refuses to attach a service to a target group without a load balancer.
  depends_on = [aws_lb_listener.https]
}

output "target_group_arn_suffix" {
  description = "Target group ARN suffix (CloudWatch TargetGroup dimension)."
  value       = aws_lb_target_group.api.arn_suffix
}

output "https_listener_arn" {
  description = "HTTPS listener ARN."
  value       = aws_lb_listener.https.arn
}

output "web_acl_arn" {
  description = "WAF web ACL ARN."
  value       = aws_wafv2_web_acl.this.arn
}

output "web_acl_name" {
  description = "WAF web ACL name (CloudWatch WebACL dimension)."
  value       = aws_wafv2_web_acl.this.name
}

output "access_logs_bucket" {
  description = "S3 bucket receiving ALB access logs."
  value       = aws_s3_bucket.access_logs.id
}

output "waf_log_group_name" {
  description = "CloudWatch log group receiving WAF logs."
  value       = aws_cloudwatch_log_group.waf.name
}
