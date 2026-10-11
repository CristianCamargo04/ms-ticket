################################################################################
# DynamoDB tables - mirror of docs/design/implementation-spec.md section 6.1
# (and of src/main/resources/db/migrations, which only run locally / in CI).
# Attribute names, keys and GSIs MUST stay identical to the application code.
#
# All tables: on-demand capacity, SSE with a customer-managed key, PITR,
# deletion protection (prod). The "schema-migrations" table is local/CI only.
################################################################################

locals {
  tables = {
    events = {
      hash_key   = "eventId"
      range_key  = null
      attributes = [{ name = "eventId", type = "S" }, { name = "status", type = "S" }, { name = "eventDate", type = "N" }]
      global_secondary_indexes = [
        { name = "status-eventDate-index", hash_key = "status", range_key = "eventDate", projection_type = "ALL", non_key_attributes = null },
      ]
      ttl_attribute = null
    }
    tickets = {
      hash_key   = "eventId"
      range_key  = "ticketId"
      attributes = [{ name = "eventId", type = "S" }, { name = "ticketId", type = "S" }, { name = "statusKey", type = "S" }]
      global_secondary_indexes = [
        # INCLUDE (implementation-spec section 11.5): findAvailable builds a full
        # TicketData for AVAILABLE tickets (orderId/holdExpiresAt are absent) without
        # a second read, while index items stay small (cheaper index writes than ALL).
        {
          name               = "eventId-statusKey-index"
          hash_key           = "eventId"
          range_key          = "statusKey"
          projection_type    = "INCLUDE"
          non_key_attributes = ["status", "version", "updatedAt"]
        },
      ]
      ttl_attribute = null
    }
    orders = {
      hash_key   = "orderId"
      range_key  = null
      attributes = [{ name = "orderId", type = "S" }, { name = "deadlinePartition", type = "S" }, { name = "deadlineAt", type = "N" }]
      global_secondary_indexes = [
        # Sparse index: only orders waiting for a future action carry deadlinePartition/deadlineAt.
        { name = "deadline-index", hash_key = "deadlinePartition", range_key = "deadlineAt", projection_type = "ALL", non_key_attributes = null },
      ]
      ttl_attribute = null
    }
    ticket-transitions = {
      hash_key                 = "ticketKey"
      range_key                = "transitionKey"
      attributes               = [{ name = "ticketKey", type = "S" }, { name = "transitionKey", type = "S" }]
      global_secondary_indexes = []
      ttl_attribute            = null
    }
    distributed-locks = {
      hash_key                 = "lockName"
      range_key                = null
      attributes               = [{ name = "lockName", type = "S" }]
      global_secondary_indexes = []
      ttl_attribute            = "expiresAt" # epoch seconds, garbage-collects abandoned leases
    }
  }

  contributor_insights = merge(
    { for table in var.contributor_insights_tables : table => { table = table, index_name = null } },
    merge([
      for table in var.contributor_insights_tables : {
        for index in local.tables[table].global_secondary_indexes : "${table}/${index.name}" => { table = table, index_name = index.name }
      }
    ]...),
  )
}

resource "aws_dynamodb_table" "this" {
  for_each = local.tables

  name                        = "${var.name_prefix}-${each.key}"
  billing_mode                = "PAY_PER_REQUEST"
  table_class                 = "STANDARD"
  hash_key                    = each.value.hash_key
  range_key                   = each.value.range_key
  deletion_protection_enabled = var.deletion_protection_enabled

  dynamic "attribute" {
    for_each = each.value.attributes

    content {
      name = attribute.value.name
      type = attribute.value.type
    }
  }

  dynamic "global_secondary_index" {
    for_each = each.value.global_secondary_indexes

    content {
      name               = global_secondary_index.value.name
      projection_type    = global_secondary_index.value.projection_type
      non_key_attributes = global_secondary_index.value.non_key_attributes

      key_schema {
        attribute_name = global_secondary_index.value.hash_key
        key_type       = "HASH"
      }

      key_schema {
        attribute_name = global_secondary_index.value.range_key
        key_type       = "RANGE"
      }
    }
  }

  dynamic "ttl" {
    for_each = each.value.ttl_attribute == null ? [] : [each.value.ttl_attribute]

    content {
      attribute_name = ttl.value
      enabled        = true
    }
  }

  dynamic "on_demand_throughput" {
    for_each = var.max_on_demand_throughput == null ? [] : [var.max_on_demand_throughput]

    content {
      max_read_request_units  = on_demand_throughput.value.read_request_units
      max_write_request_units = on_demand_throughput.value.write_request_units
    }
  }

  point_in_time_recovery {
    enabled                 = true
    recovery_period_in_days = var.point_in_time_recovery_days
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-${each.key}", LogicalName = each.key })
}

resource "aws_dynamodb_contributor_insights" "this" {
  for_each = local.contributor_insights

  table_name = aws_dynamodb_table.this[each.value.table].name
  index_name = each.value.index_name
  mode       = var.contributor_insights_mode
}
