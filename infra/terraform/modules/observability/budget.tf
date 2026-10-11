################################################################################
# Monthly cost budget (governance): e-mail at 80 % actual and 100 % forecast.
################################################################################

resource "aws_budgets_budget" "monthly" {
  count = var.monthly_budget_amount == null ? 0 : 1

  name         = "${var.name_prefix}-monthly"
  budget_type  = "COST"
  limit_amount = format("%.2f", var.monthly_budget_amount)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  dynamic "cost_filter" {
    for_each = var.budget_filter_tag == null ? [] : [var.budget_filter_tag]

    content {
      name   = "TagKeyValue"
      values = [format("user:%s$%s", cost_filter.value.key, cost_filter.value.value)]
    }
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    notification_type          = "ACTUAL"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    subscriber_email_addresses = var.budget_notification_emails
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    notification_type          = "FORECASTED"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    subscriber_email_addresses = var.budget_notification_emails
  }

  tags = var.tags

  lifecycle {
    precondition {
      condition     = length(var.budget_notification_emails) > 0
      error_message = "budget_notification_emails must contain at least one address when monthly_budget_amount is set."
    }
  }
}
