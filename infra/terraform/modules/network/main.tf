################################################################################
# VPC: public subnets (ALB + NAT only) and private subnets (ECS tasks, VPC
# endpoint ENIs) across N Availability Zones.
################################################################################

# Allow-list of zones (zone-name filter): also fails fast if a pinned AZ does
# not exist in the region or is not available.
data "aws_availability_zones" "selected" {
  state = "available"

  filter {
    name   = "zone-name"
    values = var.availability_zones
  }

  lifecycle {
    postcondition {
      condition     = length(self.names) == length(var.availability_zones)
      error_message = "Every entry of availability_zones must be an available AZ of the region."
    }
  }
}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region

  azs      = data.aws_availability_zones.selected.names
  az_count = length(var.availability_zones)

  # /16 example: private 10.x.64.0/18, 10.x.128.0/18, 10.x.192.0/18 (16k IPs each:
  # Fargate tasks consume one IP per task); public 10.x.0.0/24, 10.x.1.0/24 ...
  public_block    = cidrsubnet(var.vpc_cidr, 2, 0)
  public_subnets  = [for index in range(local.az_count) : cidrsubnet(local.public_block, 6, index)]
  private_subnets = [for index in range(local.az_count) : cidrsubnet(var.vpc_cidr, 2, index + 1)]

  nat_gateway_count = var.single_nat_gateway ? 1 : local.az_count
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true # required by interface endpoints with private DNS

  tags = merge(var.tags, { Name = var.name_prefix })
}

# Lock down the default security group: nothing may accidentally use it.
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, { Name = "${var.name_prefix}-default-deny-all" })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, { Name = var.name_prefix })
}

resource "aws_subnet" "public" {
  count = local.az_count

  vpc_id                  = aws_vpc.this.id
  cidr_block              = local.public_subnets[count.index]
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = false # only the ALB and NAT gateways live here

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-public-${local.azs[count.index]}"
    Tier = "public"
  })
}

resource "aws_subnet" "private" {
  count = local.az_count

  vpc_id            = aws_vpc.this.id
  cidr_block        = local.private_subnets[count.index]
  availability_zone = local.azs[count.index]

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-private-${local.azs[count.index]}"
    Tier = "private"
  })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, { Name = "${var.name_prefix}-public" })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count = local.az_count

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  count = local.nat_gateway_count

  #checkov:skip=CKV2_AWS_19:Elastic IPs are attached to NAT gateways, not to EC2 instances.
  domain = "vpc"

  tags = merge(var.tags, { Name = "${var.name_prefix}-nat-${local.azs[count.index]}" })

  depends_on = [aws_internet_gateway.this]
}

resource "aws_nat_gateway" "this" {
  count = local.nat_gateway_count

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = merge(var.tags, { Name = "${var.name_prefix}-${local.azs[count.index]}" })

  depends_on = [aws_internet_gateway.this]
}

# One private route table per AZ so that, with one NAT per AZ, an AZ outage
# never breaks egress of the surviving AZs.
resource "aws_route_table" "private" {
  count = local.az_count

  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, { Name = "${var.name_prefix}-private-${local.azs[count.index]}" })
}

resource "aws_route" "private_nat" {
  count = local.az_count

  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[min(count.index, local.nat_gateway_count - 1)].id
}

resource "aws_route_table_association" "private" {
  count = local.az_count

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}
