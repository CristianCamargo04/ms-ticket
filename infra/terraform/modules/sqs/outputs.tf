output "queue_names" {
  description = "Map of logical queue name to physical work queue name."
  value       = { for name, queue in aws_sqs_queue.this : name => queue.name }
}

output "queue_arns" {
  description = "Map of logical queue name to work queue ARN."
  value       = { for name, queue in aws_sqs_queue.this : name => queue.arn }
}

output "queue_urls" {
  description = "Map of logical queue name to work queue URL."
  value       = { for name, queue in aws_sqs_queue.this : name => queue.url }
}

output "dlq_names" {
  description = "Map of logical queue name to dead-letter queue name."
  value       = { for name, queue in aws_sqs_queue.dlq : name => queue.name }
}

output "dlq_arns" {
  description = "Map of logical queue name to dead-letter queue ARN."
  value       = { for name, queue in aws_sqs_queue.dlq : name => queue.arn }
}
