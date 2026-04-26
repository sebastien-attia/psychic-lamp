# Remote state config for the production environment.
#
# Partial backend config: `resource_group_name` and `storage_account_name`
# are supplied at init-time via `-backend-config=...` (CI: from the
# TF_STATE_RESOURCE_GROUP / TF_STATE_STORAGE_ACCOUNT secrets the
# bootstrap script wrote). They are intentionally NOT hardcoded because
# the storage account name carries a per-subscription hash suffix
# (see ai-scripts/00d-bootstrap-azure.sh — `STATE_SA` derivation).
#
# `container_name` is fixed to "production" because the bootstrap script
# creates one container per environment (staging, production) inside the
# same storage account — keeping per-env containers isolates blob-level
# RBAC.
#
# The state file contains the bff-signing-key PEM and password material —
# the storage account must enforce HTTPS, RBAC, at-rest encryption,
# soft-delete, and (recommended) versioning.

terraform {
  backend "azurerm" {
    # Keep in lockstep with environments/staging/backend.tf — only
    # `container_name` and `key` should differ.
    container_name = "production"
    key            = "production.tfstate"
  }
}
