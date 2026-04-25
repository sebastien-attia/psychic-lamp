# Root-level outputs.
#
# These outputs are consumed by:
#   - Operators inspecting the deployment (`terraform output`).
#   - The Ansible playbooks in 02c3 (bootstrap-db-roles, configure-keycloak,
#     run-liquibase-jobs) which read URLs and resource names.
#   - GitHub Actions workflows in 04 which need the ACR login server to
#     tag-and-push images and the Container App / Job names to deploy and
#     migrate.
#
# Sensitive material (admin passwords, the bff-signing-key PEM) is NEVER
# exported; it lives in Key Vault and is consumed in-cluster.

output "resource_group_name" {
  description = "Name of the resource group that contains every resource provisioned by this stack."
  value       = module.networking.resource_group_name
}

output "bff_fqdn" {
  description = "External FQDN of the BFF Container App. The Vue SPA + /api/* proxy both serve from here."
  value       = module.container_apps.bff_fqdn
}

output "business_service_internal_fqdn" {
  description = "Internal-only FQDN of the business-service Container App. Reachable from the BFF over the ACA Environment's private network."
  value       = module.container_apps.business_service_internal_fqdn
}

output "keycloak_fqdn" {
  description = "External FQDN of the Keycloak Container App. Used by browsers (auth code flow) and the BFF (issuer URI / private_key_jwt audience)."
  value       = module.container_apps.keycloak_fqdn
}

output "postgres_fqdn" {
  description = "Fully-qualified domain name of the PostgreSQL Flexible Server. Used by Ansible bootstrap-db-roles for psql admin connections."
  value       = module.database.fqdn
}

output "postgres_admin_username" {
  description = "Server-level administrator login on the Flexible Server (the password lives in Key Vault as postgres-admin-password)."
  value       = module.database.admin_username
}

output "acr_login_server" {
  description = "Login server hostname of the Azure Container Registry. CI uses this with `az acr login --name` (OIDC-federated AcrPush)."
  value       = module.container_registry.login_server
}

output "keyvault_uri" {
  description = "Vault URI used by the Container Apps' azure_key_vault_secrets references and by `az keyvault secret show` from Ansible (over the private endpoint)."
  value       = module.keyvault.vault_uri
}

output "liquibase_job_names" {
  description = "Map of logical service name → ACA Container App Job name. Ansible invokes these via `az containerapp job start --name <value>` after each deploy."
  value       = module.container_apps.liquibase_job_names
}
