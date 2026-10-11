################################################################################
# Internet-facing Application Load Balancer: TLS termination (TLS 1.2/1.3),
# HTTP->HTTPS redirect, security response headers, access logs.
################################################################################

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region
}

resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb"
  description = "Public HTTPS entry point of ${var.name_prefix}"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, { Name = "${var.name_prefix}-alb" })
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  for_each = toset(var.ingress_cidr_blocks)

  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from clients"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_ingress_rule" "http" {
  for_each = toset(var.ingress_cidr_blocks)

  #checkov:skip=CKV_AWS_260:Port 80 only answers a permanent redirect to HTTPS; no content is served over HTTP.
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from clients (redirected to HTTPS)"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "targets" {
  security_group_id = aws_security_group.alb.id
  description       = "To API tasks inside the VPC"
  cidr_ipv4         = var.vpc_cidr_block
  ip_protocol       = "tcp"
  from_port         = var.target_port
  to_port           = var.target_port
}

resource "aws_vpc_security_group_egress_rule" "health_checks" {
  count = var.health_check_port == null || var.health_check_port == var.target_port ? 0 : 1

  security_group_id = aws_security_group.alb.id
  description       = "Health checks to the management port of API tasks inside the VPC"
  cidr_ipv4         = var.vpc_cidr_block
  ip_protocol       = "tcp"
  from_port         = var.health_check_port
  to_port           = var.health_check_port
}

resource "aws_lb" "this" {
  #checkov:skip=CKV_AWS_150:Deletion protection is variable-driven: true in prod, false in dev so the environment stays disposable.
  name                       = "${var.name_prefix}-alb"
  internal                   = false
  load_balancer_type         = "application"
  ip_address_type            = "ipv4"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = var.public_subnet_ids
  idle_timeout               = var.idle_timeout
  enable_http2               = true
  drop_invalid_header_fields = true
  desync_mitigation_mode     = "defensive"
  enable_waf_fail_open       = false
  enable_deletion_protection = var.deletion_protection

  access_logs {
    bucket  = aws_s3_bucket.access_logs.id
    prefix  = "alb"
    enabled = true
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-alb" })

  # The ALB validates write access to the bucket when access logs are enabled.
  depends_on = [aws_s3_bucket_policy.access_logs]
}

resource "aws_lb_target_group" "api" {
  #checkov:skip=CKV_AWS_378:TLS terminates at the ALB; ALB->task traffic never leaves private subnets and is restricted by security groups.
  name                          = "${var.name_prefix}-api"
  vpc_id                        = var.vpc_id
  target_type                   = "ip" # Fargate (awsvpc)
  port                          = var.target_port
  protocol                      = "HTTP"
  protocol_version              = "HTTP1"
  deregistration_delay          = 30 # aligned with the app graceful shutdown
  load_balancing_algorithm_type = "least_outstanding_requests"

  # Health checks hit the management port (actuator) directly; the listener only
  # forwards client traffic to target_port, which does not serve /actuator at all.
  health_check {
    enabled             = true
    path                = var.health_check_path
    port                = var.health_check_port == null ? "traffic-port" : tostring(var.health_check_port)
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-api" })
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = var.ssl_policy
  certificate_arn   = var.certificate_arn

  # Defence in depth: the app (Spring Security) sets them too.
  routing_http_response_server_enabled                         = false
  routing_http_response_strict_transport_security_header_value = "max-age=31536000; includeSubDomains"
  routing_http_response_x_content_type_options_header_value    = "nosniff"
  routing_http_response_x_frame_options_header_value           = "DENY"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }

  tags = var.tags
}

resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }

  tags = var.tags
}

# Defence in depth for /actuator: the application serves actuator only on the
# management port (never forwarded by the listener), WAF blocks normalised
# variants (percent-encoding, //, /./, upper case: waf.tf) and this rule answers
# 404 for the plain paths at the edge.
resource "aws_lb_listener_rule" "block_actuator" {
  count = var.block_actuator_paths ? 1 : 0

  listener_arn = aws_lb_listener.https.arn
  priority     = 10

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      status_code  = "404"
      message_body = jsonencode({ title = "NOT_FOUND", status = 404 })
    }
  }

  condition {
    path_pattern {
      values = ["/actuator", "/actuator/*"]
    }
  }

  tags = var.tags
}
