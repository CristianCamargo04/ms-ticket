output "vpc_id" {
  description = "VPC ID."
  value       = aws_vpc.this.id
}

output "vpc_cidr_block" {
  description = "VPC IPv4 CIDR block."
  value       = aws_vpc.this.cidr_block
}

output "availability_zones" {
  description = "Availability Zones in use."
  value       = local.azs
}

output "public_subnet_ids" {
  description = "Public subnet IDs (ALB, NAT gateways)."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Private subnet IDs (ECS tasks, interface endpoint ENIs)."
  value       = aws_subnet.private[*].id
}

output "private_route_table_ids" {
  description = "Private route table IDs (one per AZ)."
  value       = aws_route_table.private[*].id
}

output "nat_gateway_public_ips" {
  description = "Public egress IPs of the NAT gateways (allow-list them at the payment provider)."
  value       = aws_eip.nat[*].public_ip
}

output "vpc_endpoint_security_group_id" {
  description = "Security group attached to the interface VPC endpoints."
  value       = aws_security_group.endpoints.id
}

output "flow_logs_log_group_name" {
  description = "CloudWatch log group receiving VPC flow logs (null with the S3 destination)."
  value       = one(aws_cloudwatch_log_group.flow_logs[*].name)
}

output "flow_logs_bucket" {
  description = "S3 bucket receiving VPC flow logs in Parquet (null with the CloudWatch Logs destination)."
  value       = one(aws_s3_bucket.flow_logs[*].id)
}
