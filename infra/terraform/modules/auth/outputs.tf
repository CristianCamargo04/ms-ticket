output "user_pool_id" {
  description = "Cognito user pool ID."
  value       = aws_cognito_user_pool.this.id
}

output "user_pool_arn" {
  description = "Cognito user pool ARN."
  value       = aws_cognito_user_pool.this.arn
}

output "issuer_uri" {
  description = "OIDC issuer the API must validate (\"iss\" claim)."
  value       = "https://${aws_cognito_user_pool.this.endpoint}"
}

output "jwks_uri" {
  description = "JWK Set URI with the token signing keys (spring.security.oauth2.resourceserver.jwt.jwk-set-uri)."
  value       = "https://${aws_cognito_user_pool.this.endpoint}/.well-known/jwks.json"
}

output "web_client_id" {
  description = "App client ID of the web client (\"client_id\" claim of access tokens, \"aud\" of ID tokens)."
  value       = aws_cognito_user_pool_client.web.id
}

output "resource_server_identifier" {
  description = "Resource server identifier (scope prefix)."
  value       = aws_cognito_resource_server.api.identifier
}

output "scope_identifiers" {
  description = "Fully qualified custom scopes, e.g. \"ms-ticket/orders.write\"."
  value       = aws_cognito_resource_server.api.scope_identifiers
}

output "group_names" {
  description = "User groups mapped to application roles."
  value       = [for group in aws_cognito_user_group.this : group.name]
}

output "hosted_domain" {
  description = "Base URL of the Cognito hosted login / OAuth2 endpoints."
  value       = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${data.aws_region.current.region}.amazoncognito.com"
}
