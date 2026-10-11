variable "repository_name" {
  description = "ECR repository name, e.g. \"ms-ticket-dev\"."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$", var.repository_name))
    error_message = "repository_name must be a valid ECR repository name (lowercase)."
  }
}

variable "kms_key_arn" {
  description = "Customer-managed KMS key that encrypts image layers."
  type        = string
}

variable "untagged_image_expiry_days" {
  description = "Untagged images (failed/overwritten builds) are deleted after this many days."
  type        = number
  default     = 7
}

variable "max_tagged_images" {
  description = "Number of most recent images kept; older ones are expired (rollback window vs storage cost)."
  type        = number
  default     = 30
}

variable "force_delete" {
  description = "Allow destroying the repository while it still contains images (dev convenience only)."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Extra tags for the repository (provider default_tags already apply)."
  type        = map(string)
  default     = {}
}
