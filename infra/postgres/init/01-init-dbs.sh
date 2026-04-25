#!/usr/bin/env bash
# infra/postgres/init/01-init-dbs.sh
#
# PostgreSQL bootstrap for the postgres:17-alpine container. Mounted
# read-only into /docker-entrypoint-initdb.d/ by both docker-compose.yml
# and docker-compose.dev.yml.
#
# Trigger: runs ONLY on first boot of the postgres-data volume (empty data
#          dir). The Postgres docker-entrypoint executes files ending in
#          .sh in this directory exactly once, in lexical order.
#
# To re-run after editing this file: `docker compose down -v` (drops the
# volume), then `docker compose up`. Without -v, the data dir already
# exists and the entrypoint skips this script.
#
# Idempotent: every CREATE is guarded so the script is safe to re-run if
# someone manually mounts a populated volume.
#
# Security model: three databases (bff_session, boatapp, keycloak) each
# OWNED by a distinct LOGIN role (bff, business_service, keycloak). After
# creation we REVOKE PUBLIC CONNECT and GRANT CONNECT only to the owner —
# a compromised `bff` role cannot reach `boatapp` or `keycloak`, and vice
# versa. The admin role from POSTGRES_USER is used here only for
# provisioning; the application services never log in as it.
#
# Password handling: passwords are passed to psql via -v variables and
# substituted into the SQL through `quote_literal(:'var')`, which produces
# a properly-escaped SQL string literal even when the password contains
# single quotes or backslashes. Bash's :? guards reject unset OR empty
# values up-front (a missing .env would otherwise mint passwordless roles).
set -euo pipefail

: "${BFF_DB_PASSWORD:?BFF_DB_PASSWORD must be set (see .env / .env.example)}"
: "${BUSINESS_DB_PASSWORD:?BUSINESS_DB_PASSWORD must be set (see .env / .env.example)}"
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD must be set (see .env / .env.example)}"

psql -v ON_ERROR_STOP=1 \
     -v bff_pwd="$BFF_DB_PASSWORD" \
     -v business_pwd="$BUSINESS_DB_PASSWORD" \
     -v keycloak_pwd="$KEYCLOAK_DB_PASSWORD" \
     --username "$POSTGRES_USER" --dbname postgres <<-'EOSQL'
  -- Create LOGIN roles. quote_literal() guarantees the password literal
  -- is correctly escaped before being parsed by the server.
  SELECT 'CREATE ROLE bff LOGIN PASSWORD ' || quote_literal(:'bff_pwd')
    WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='bff')\gexec
  SELECT 'CREATE ROLE business_service LOGIN PASSWORD ' || quote_literal(:'business_pwd')
    WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='business_service')\gexec
  SELECT 'CREATE ROLE keycloak LOGIN PASSWORD ' || quote_literal(:'keycloak_pwd')
    WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='keycloak')\gexec

  -- Each database owned by exactly one role.
  SELECT 'CREATE DATABASE bff_session OWNER bff'
    WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='bff_session')\gexec
  SELECT 'CREATE DATABASE boatapp OWNER business_service'
    WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='boatapp')\gexec
  SELECT 'CREATE DATABASE keycloak OWNER keycloak'
    WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='keycloak')\gexec

  -- Owner-only CONNECT — a compromised role cannot reach a sibling DB.
  REVOKE CONNECT ON DATABASE bff_session FROM PUBLIC;
  REVOKE CONNECT ON DATABASE boatapp     FROM PUBLIC;
  REVOKE CONNECT ON DATABASE keycloak    FROM PUBLIC;
  GRANT  CONNECT ON DATABASE bff_session TO bff;
  GRANT  CONNECT ON DATABASE boatapp     TO business_service;
  GRANT  CONNECT ON DATABASE keycloak    TO keycloak;
EOSQL
