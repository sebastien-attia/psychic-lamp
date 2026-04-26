#!/bin/sh
# bootstrap-db-roles.sh — entrypoint for the bootstrap-db-roles ACA Job.
#
# Mirrors the (now-superseded) Ansible bootstrap-db-roles.yml playbook:
# creates three per-app PostgreSQL LOGIN roles, transfers ownership of
# each application database to its role, revokes PUBLIC CONNECT, and
# grants CONNECT to the owner.
#
# Required environment variables
# ──────────────────────────────
#   PGHOST                — FQDN of the Postgres Flexible Server.
#   PGUSER                — server-level administrator login.
#   PGPASSWORD            — admin password (sourced from KV secret
#                           `postgres-admin-password` via the Job's MI).
#   BFF_DB_PASSWORD       — password to assign to role `bff` on creation.
#   BUSINESS_DB_PASSWORD  — password to assign to role `business_service`.
#   KEYCLOAK_DB_PASSWORD  — password to assign to role `keycloak`.
#
# Exit codes
# ──────────
#   0  — success (or no-op on subsequent runs; every statement is
#        idempotent so a re-run is a clean exit).
#   *  — any non-zero psql exit. `ON_ERROR_STOP=1` makes a single SQL
#        failure abort the whole script with that exit code.
#
# Side effects
# ────────────
#   - Creates the three LOGIN roles if absent. Existing roles are NOT
#     re-passworded; rotation is a separate flow (matches the Ansible
#     playbook's `update_password: on_create` semantic).
#   - ALTERs ownership of bff_session/boatapp/keycloak to the matching
#     role (no-op when ownership is already correct).
#   - REVOKEs CONNECT from PUBLIC and GRANTs CONNECT to the owner role
#     on each of the three application databases.
#
# Network
# ───────
# The Job runs inside the ACA Environment, which sits in the
# container-apps subnet. The Postgres Flexible Server lives in a peer
# subnet of the same VNet with private DNS bound to it, so PGHOST
# resolves and 5432 is reachable without ever traversing public IPs.

set -eu

: "${PGHOST:?PGHOST not set}"
: "${PGUSER:?PGUSER not set}"
: "${PGPASSWORD:?PGPASSWORD not set}"
: "${BFF_DB_PASSWORD:?BFF_DB_PASSWORD not set}"
: "${BUSINESS_DB_PASSWORD:?BUSINESS_DB_PASSWORD not set}"
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD not set}"

export PGPORT=5432
export PGDATABASE=postgres
export PGSSLMODE=require
export PGCONNECT_TIMEOUT=10

echo "▸ bootstrap-db-roles: connecting to ${PGHOST} as ${PGUSER}"

# psql -v binds psql client-side variables. `:'name'` interpolation in the
# SQL stream substitutes the value as a properly escaped single-quoted SQL
# literal — internal quotes get doubled — so the per-app passwords cannot
# break out of the literal even if they contain apostrophes.
psql -v ON_ERROR_STOP=1 \
     -v "bff_pwd=${BFF_DB_PASSWORD}" \
     -v "biz_pwd=${BUSINESS_DB_PASSWORD}" \
     -v "kc_pwd=${KEYCLOAK_DB_PASSWORD}" \
     <<'SQL'
\set QUIET on

-- ── Per-app LOGIN roles (idempotent) ─────────────────────────────────────
-- CREATE ROLE has no IF NOT EXISTS form, so guard each in a DO block.
-- Existing roles are left as-is — password rotation is a separate flow.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'bff') THEN
    EXECUTE format('CREATE ROLE bff WITH LOGIN PASSWORD %L', :'bff_pwd');
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'business_service') THEN
    EXECUTE format('CREATE ROLE business_service WITH LOGIN PASSWORD %L', :'biz_pwd');
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak') THEN
    EXECUTE format('CREATE ROLE keycloak WITH LOGIN PASSWORD %L', :'kc_pwd');
  END IF;
END $$;

-- ── Database ownership ───────────────────────────────────────────────────
-- ALTER OWNER is a no-op when the database already has the target owner.
ALTER DATABASE bff_session OWNER TO bff;
ALTER DATABASE boatapp     OWNER TO business_service;
ALTER DATABASE keycloak    OWNER TO keycloak;

-- ── PUBLIC isolation ────────────────────────────────────────────────────
REVOKE CONNECT ON DATABASE bff_session FROM PUBLIC;
REVOKE CONNECT ON DATABASE boatapp     FROM PUBLIC;
REVOKE CONNECT ON DATABASE keycloak    FROM PUBLIC;

GRANT  CONNECT ON DATABASE bff_session TO bff;
GRANT  CONNECT ON DATABASE boatapp     TO business_service;
GRANT  CONNECT ON DATABASE keycloak    TO keycloak;
SQL

echo "▸ bootstrap-db-roles: complete"
