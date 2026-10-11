################################################################################
# AWS WAF (regional) in front of the ALB.
#   0. Amazon IP reputation list         (known bad actors / botnets)
#   1. Anonymous IP list                  (scalper bots run from hosting providers;
#                                          VPN/Tor users only counted by default)
#   2. Block /actuator                    (after URL decoding, path normalisation
#                                          and lower-casing: no encoding bypass)
#   3. Rate limit per client IP           (volumetric abuse)
#   4. Stricter rate limit on order POST  (bots / scalping on ticket drops)
#   5. AWS Core rule set (OWASP top 10)
#   6. Known bad inputs (Log4Shell, ...)
# Blocked rate-limited requests get a 429 in RFC 9457 shape, like the app.
# Rate-based rules aggregate on the source IP of the connection to the ALB (not
# on X-Forwarded-For), so a client cannot spoof its way around them.
# The in-app per-customer limiter (Bucket4j) still applies behind the WAF.
################################################################################

locals {
  waf_metric_prefix = replace(var.name_prefix, "-", "")
}

resource "aws_wafv2_web_acl" "this" {
  name        = "${var.name_prefix}-alb"
  description = "Edge protection for the ${var.name_prefix} public API"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  custom_response_body {
    key          = "rate-limited"
    content_type = "APPLICATION_JSON"
    content = jsonencode({
      type      = "about:blank"
      title     = "RATE_LIMIT_EXCEEDED"
      status    = 429
      errorCode = "RATE_LIMIT_EXCEEDED"
      detail    = "Too many requests, retry later."
    })
  }

  rule {
    name     = "aws-ip-reputation"
    priority = 0

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAmazonIpReputationList"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
      metric_name                = "${local.waf_metric_prefix}IpReputation"
    }
  }

  rule {
    name     = "aws-anonymous-ip"
    priority = 1

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAnonymousIpList"

        dynamic "rule_action_override" {
          for_each = toset(var.waf_anonymous_ip_rules_to_count)

          content {
            name = rule_action_override.value

            action_to_use {
              count {}
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
      metric_name                = "${local.waf_metric_prefix}AnonymousIp"
    }
  }

  dynamic "custom_response_body" {
    for_each = var.block_actuator_paths ? [1] : []

    content {
      key          = "not-found"
      content_type = "APPLICATION_JSON"
      content      = jsonencode({ type = "about:blank", title = "NOT_FOUND", status = 404 })
    }
  }

  # The application serves actuator only on its management port, which the
  # listener never forwards to; this rule is defence in depth against
  # /%61ctuator, //actuator, /api/../actuator, /ACTUATOR and similar variants.
  dynamic "rule" {
    for_each = var.block_actuator_paths ? [1] : []

    content {
      name     = "block-actuator"
      priority = 2

      action {
        block {
          custom_response {
            response_code            = 404
            custom_response_body_key = "not-found"
          }
        }
      }

      statement {
        byte_match_statement {
          search_string         = "/actuator"
          positional_constraint = "STARTS_WITH"

          field_to_match {
            uri_path {}
          }

          text_transformation {
            priority = 0
            type     = "URL_DECODE"
          }

          text_transformation {
            priority = 1
            type     = "NORMALIZE_PATH"
          }

          text_transformation {
            priority = 2
            type     = "LOWERCASE"
          }
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        sampled_requests_enabled   = true
        metric_name                = "${local.waf_metric_prefix}BlockActuator"
      }
    }
  }

  rule {
    name     = "rate-limit-per-ip"
    priority = 3

    action {
      block {
        custom_response {
          response_code            = 429
          custom_response_body_key = "rate-limited"
        }
      }
    }

    statement {
      rate_based_statement {
        limit                 = var.waf_rate_limit_per_ip
        evaluation_window_sec = var.waf_rate_limit_window_seconds
        aggregate_key_type    = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
      metric_name                = "${local.waf_metric_prefix}RateLimitPerIp"
    }
  }

  rule {
    name     = "rate-limit-order-placement"
    priority = 4

    action {
      block {
        custom_response {
          response_code            = 429
          custom_response_body_key = "rate-limited"
        }
      }
    }

    statement {
      rate_based_statement {
        limit                 = var.waf_orders_rate_limit_per_ip
        evaluation_window_sec = var.waf_rate_limit_window_seconds
        aggregate_key_type    = "IP"

        scope_down_statement {
          and_statement {
            statement {
              byte_match_statement {
                search_string         = "POST"
                positional_constraint = "EXACTLY"

                field_to_match {
                  method {}
                }

                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }

            statement {
              regex_match_statement {
                # POST /api/v1/events/{eventId}/orders
                regex_string = "^/api/v1/events/[^/]+/orders/?$"

                field_to_match {
                  uri_path {}
                }

                # Defeat trivial evasion such as //api/v1/... or URL-encoded paths.
                text_transformation {
                  priority = 0
                  type     = "URL_DECODE"
                }

                text_transformation {
                  priority = 1
                  type     = "NORMALIZE_PATH"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
      metric_name                = "${local.waf_metric_prefix}RateLimitOrders"
    }
  }

  rule {
    name     = "aws-common-rule-set"
    priority = 5

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
      metric_name                = "${local.waf_metric_prefix}CommonRuleSet"
    }
  }

  rule {
    name     = "aws-known-bad-inputs"
    priority = 6

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
      metric_name                = "${local.waf_metric_prefix}KnownBadInputs"
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    sampled_requests_enabled   = true
    metric_name                = "${local.waf_metric_prefix}WebAcl"
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-alb" })
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = aws_lb.this.arn
  web_acl_arn  = aws_wafv2_web_acl.this.arn
}

################################################################################
# WAF logging -> CloudWatch Logs (name must start with "aws-waf-logs-").
################################################################################

resource "aws_cloudwatch_log_group" "waf" {
  #checkov:skip=CKV_AWS_338:Retention is set per environment (cost control); prod uses >= 365 days.
  name              = "aws-waf-logs-${var.name_prefix}"
  retention_in_days = var.waf_log_retention_days
  kms_key_id        = var.logs_kms_key_arn

  tags = var.tags
}

data "aws_iam_policy_document" "waf_log_delivery" {
  statement {
    sid       = "AllowWafLogDelivery"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.waf.arn}:*"]

    principals {
      type        = "Service"
      identifiers = ["delivery.logs.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:logs:${local.region}:${local.account_id}:*"]
    }
  }
}

resource "aws_cloudwatch_log_resource_policy" "waf" {
  policy_name     = "${var.name_prefix}-waf-log-delivery"
  policy_document = data.aws_iam_policy_document.waf_log_delivery.json
}

resource "aws_wafv2_web_acl_logging_configuration" "this" {
  resource_arn            = aws_wafv2_web_acl.this.arn
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]

  # Never persist bearer tokens or cookies in logs.
  redacted_fields {
    single_header {
      name = "authorization"
    }
  }

  redacted_fields {
    single_header {
      name = "cookie"
    }
  }

  dynamic "logging_filter" {
    for_each = var.waf_log_only_blocked ? [1] : []

    content {
      default_behavior = "DROP"

      filter {
        behavior    = "KEEP"
        requirement = "MEETS_ANY"

        condition {
          action_condition {
            action = "BLOCK"
          }
        }

        condition {
          action_condition {
            action = "COUNT"
          }
        }
      }
    }
  }

  depends_on = [aws_cloudwatch_log_resource_policy.waf]
}
