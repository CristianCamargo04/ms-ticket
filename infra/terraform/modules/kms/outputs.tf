output "key_arns" {
  description = "Map of key purpose (dynamodb, sqs, logs, platform) to key ARN."
  value       = { for purpose, key in aws_kms_key.this : purpose => key.arn }
}

output "alias_names" {
  description = "Map of key purpose to alias name."
  value       = { for purpose, alias in aws_kms_alias.this : purpose => alias.name }
}

output "dynamodb_key_arn" {
  description = "ARN of the key that encrypts DynamoDB tables."
  value       = aws_kms_key.this["dynamodb"].arn
}

output "sqs_key_arn" {
  description = "ARN of the key that encrypts SQS queues."
  value       = aws_kms_key.this["sqs"].arn
}

output "logs_key_arn" {
  description = "ARN of the key that encrypts CloudWatch log groups."
  value       = aws_kms_key.this["logs"].arn
}

output "platform_key_arn" {
  description = "ARN of the key that encrypts ECR, Secrets Manager and SNS."
  value       = aws_kms_key.this["platform"].arn
}
