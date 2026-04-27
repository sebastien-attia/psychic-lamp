# db-bootstrap module — creates per-database PostgreSQL roles + grants.
#
# Runs psql via `local-exec` on the Terraform runner. The runner host (CI
# or operator workstation) MUST be on the Flexible Server firewall allowlist
# for the duration of the apply — modules/database is responsible for that.
#
# Idempotence
# ───────────
# Per role, two psql calls in sequence:
#   1. DO $$ … $$ that CREATE ROLEs only if the role does not exist (no
#      password set here — psql does NOT expand `:'pw'` inside a
#      dollar-quoted string).
#   2. ALTER ROLE … WITH LOGIN PASSWORD :'pw' — outside any DO block, so
#      psql expands `:'pw'` correctly and quote-and-escapes the secret.
# This is unconditional so password rotation propagates without manual
# intervention. GRANTs are idempotent (re-running is safe).
#
# Why local-exec rather than terraform-postgresql provider?
# ─────────────────────────────────────────────────────────
# The provider would force every per-grant resource into Terraform state,
# producing dozens of resources for a one-shot bootstrap that mirrors what
# the apps need on first contact. A single null_resource with the SQL
# inline is simpler, leaves a smaller state surface, and matches what the
# (now-deleted) bootstrap-db-roles ACA Job did.

resource "null_resource" "bootstrap" {
  # Re-run when any input dependency changes (e.g. password rotated, server
  # rebuilt). The trigger map is hashed by Terraform; values are stringified
  # automatically.
  triggers = var.trigger_dependencies

  provisioner "local-exec" {
    # bash, not /bin/sh — Ubuntu's /bin/sh is dash, which rejects
    # `set -o pipefail`. We want pipefail so a mid-pipe psql failure
    # propagates rather than being masked by the trailing command's
    # success.
    interpreter = ["/bin/bash", "-c"]
    environment = {
      # PGSSLMODE is the libpq env var that enforces TLS. (--set sets a
      # psql variable, not a connection option, so passing sslmode there
      # would have no effect on the connection.)
      PGSSLMODE            = "require"
      PGPASSWORD           = var.admin_password
      BFF_DB_PASSWORD      = var.bff_db_password
      BUSINESS_DB_PASSWORD = var.business_db_password
      KEYCLOAK_DB_PASSWORD = var.keycloak_db_password
    }
    command = <<-EOT
      set -euxo pipefail

      PSQL="psql --host=${var.postgres_fqdn} --port=5432 \
                 --username=${var.admin_username} \
                 --set=ON_ERROR_STOP=on --no-psqlrc"

      # ── bff role + DB grants ────────────────────────────────────────────
      # Step 1: ensure the role exists (no password — :'pw' would not
      # expand inside the DO $$ … $$ block).
      $PSQL --dbname=postgres --command="
        DO \$\$
        BEGIN
          IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'bff') THEN
            CREATE ROLE bff LOGIN;
          END IF;
        END \$\$;
      "
      # Step 2: set the password unconditionally — :'pw' IS expanded
      # here because the statement is at psql's top level, not inside
      # a dollar-quoted string.
      $PSQL --dbname=postgres --variable=pw="$BFF_DB_PASSWORD" --command="
        ALTER ROLE bff WITH LOGIN PASSWORD :'pw';
      "
      $PSQL --dbname=bff_session --command="
        GRANT CONNECT ON DATABASE bff_session TO bff;
        GRANT USAGE, CREATE ON SCHEMA public TO bff;
        GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO bff;
        GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO bff;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO bff;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO bff;
      "

      # ── business_service role + DB grants ───────────────────────────────
      $PSQL --dbname=postgres --command="
        DO \$\$
        BEGIN
          IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'business_service') THEN
            CREATE ROLE business_service LOGIN;
          END IF;
        END \$\$;
      "
      $PSQL --dbname=postgres --variable=pw="$BUSINESS_DB_PASSWORD" --command="
        ALTER ROLE business_service WITH LOGIN PASSWORD :'pw';
      "
      $PSQL --dbname=boatapp --command="
        GRANT CONNECT ON DATABASE boatapp TO business_service;
        GRANT USAGE, CREATE ON SCHEMA public TO business_service;
        GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO business_service;
        GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO business_service;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO business_service;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO business_service;
      "

      # ── keycloak role + DB grants ───────────────────────────────────────
      $PSQL --dbname=postgres --command="
        DO \$\$
        BEGIN
          IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'keycloak') THEN
            CREATE ROLE keycloak LOGIN;
          END IF;
        END \$\$;
      "
      $PSQL --dbname=postgres --variable=pw="$KEYCLOAK_DB_PASSWORD" --command="
        ALTER ROLE keycloak WITH LOGIN PASSWORD :'pw';
      "
      $PSQL --dbname=keycloak --command="
        GRANT CONNECT ON DATABASE keycloak TO keycloak;
        GRANT USAGE, CREATE ON SCHEMA public TO keycloak;
        GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO keycloak;
        GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO keycloak;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO keycloak;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO keycloak;
      "
    EOT
  }
}
