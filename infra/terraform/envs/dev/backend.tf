# Remote state in S3 (one state per environment = isolated blast radius).
#
# Partial configuration: the bucket and region are supplied at init time so the
# same code works with one state bucket per AWS account:
#
#   cp backend.hcl.example backend.hcl   # fill in the bucket of the target account
#   terraform init -backend-config=backend.hcl
#
# encrypt      -> state objects are encrypted at rest (SSE-S3, or SSE-KMS with kms_key_id)
# use_lockfile -> native S3 locking (conditional writes); no DynamoDB lock table needed
terraform {
  backend "s3" {
    key          = "ms-ticket/dev/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}
