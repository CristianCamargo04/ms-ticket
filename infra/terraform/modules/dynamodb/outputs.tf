output "table_names" {
  description = "Map of logical table name (events, tickets, orders, ticket-transitions, distributed-locks) to physical table name."
  value       = { for logical, table in aws_dynamodb_table.this : logical => table.name }
}

output "table_arns" {
  description = "Map of logical table name to table ARN."
  value       = { for logical, table in aws_dynamodb_table.this : logical => table.arn }
}

output "index_arns" {
  description = "Map of \"<logical table>/<index name>\" to GSI ARN (for least-privilege IAM)."
  value = merge([
    for logical, table in local.tables : {
      for index in table.global_secondary_indexes : "${logical}/${index.name}" => "${aws_dynamodb_table.this[logical].arn}/index/${index.name}"
    }
  ]...)
}

output "index_projections" {
  description = "Map of \"<logical table>/<index name>\" to the projection the tables are created with (projection_type, sorted non_key_attributes): the contract with the application's GSI reads."
  value = merge([
    for logical, table in local.tables : {
      for index in table.global_secondary_indexes : "${logical}/${index.name}" => {
        projection_type    = index.projection_type
        non_key_attributes = index.non_key_attributes == null ? [] : sort(index.non_key_attributes)
      }
    }
  ]...)
}
