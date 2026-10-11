terraform {
  # >= 1.10: S3 backend native locking (use_lockfile) and cross-variable validation.
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  # Guardrail: refuse to run against any other account (e.g. prod credentials
  # while working on dev, or the other way round).
  allowed_account_ids = length(var.allowed_account_ids) > 0 ? var.allowed_account_ids : null

  # Governance: every taggable resource carries ownership and cost allocation tags.
  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      Owner       = var.owner
      CostCenter  = var.cost_center
      ManagedBy   = "terraform"
      Repository  = var.repository
    }
  }
}
