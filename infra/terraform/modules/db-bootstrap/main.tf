# db-bootstrap module — creates per-database PostgreSQL roles + grants.
#
# Runs psql via `local-exec` on the Terraform runner. The runner host (CI
# or operator workstation) MUST be on the Flexible Server firewall allowlist
# for the duration of the apply — modules/database is responsible for that.
#
# Why a script file (psql -f) rather than inline psql -c?
# ───────────────────────────────────────────────────────
# psql performs `:'name'` / `:"name"` variable substitution only when
# parsing input as a script (file, stdin, \i). It does NOT substitute
# inside the value of `-c`/`--command=` — that string is sent verbatim to
# the server as one simple-query. Three consecutive staging deploys broke
# on the same `syntax error at or near ":"` because of this; the inline
# heredoc + nested quoting layers (Terraform → bash → psql) made every
# tweak a coin flip. Moving the SQL into sql/bootstrap-role.sql collapses
# this to a single layer of quoting and lets psql do its job.
#
# Why local-exec rather than the cyrilgdn/postgresql provider?
# ────────────────────────────────────────────────────────────
# The provider would force every per-grant resource into Terraform state,
# producing dozens of resources for a one-shot bootstrap that mirrors
# what the apps need on first contact. A single null_resource per role
# is simpler, leaves a smaller state surface, and matches what the
# (now-deleted) bootstrap-db-roles ACA Job did. Revisit if drift
# detection becomes a real need.

locals {
  # Role → target application database. The DBs themselves are created by
  # modules/database; this module only creates roles and grants on them.
  roles = {
    bff = {
      db       = "bff_session"
      password = var.bff_db_password
    }
    business_service = {
      db       = "boatapp"
      password = var.business_db_password
    }
    keycloak = {
      db       = "keycloak"
      password = var.keycloak_db_password
    }
  }
}

resource "null_resource" "bootstrap" {
  for_each = local.roles

  # Re-run triggers:
  #  - var.trigger_dependencies: password rotation, server rebuild (caller).
  #  - _role: per-instance stable identity (also avoids collision if a
  #    caller's trigger map ever contained a key named `role`).
  #  - _script_hash: re-run when the SQL itself changes — without this,
  #    edits to bootstrap-role.sql would silently never apply, since
  #    Terraform sees no diff in the resource.
  triggers = merge(var.trigger_dependencies, {
    _role        = each.key
    _script_hash = filesha256("${path.module}/sql/bootstrap-role.sql")
  })

  provisioner "local-exec" {
    # bash, not /bin/sh — Ubuntu's /bin/sh is dash, which rejects
    # `set -o pipefail`. NB: deliberately NO `set -x` — argv tracing would
    # leak any future password-on-argv mistakes into the GH Actions log.
    interpreter = ["/bin/bash", "-c"]
    environment = {
      # PGSSLMODE is the libpq env var that enforces TLS. (--set sets a
      # psql variable, not a connection option, so passing sslmode there
      # would have no effect on the connection.)
      PGSSLMODE  = "require"
      PGPASSWORD = var.admin_password
      # Password reaches psql via the env: bootstrap-role.sql does
      # `\getenv pw ROLE_PW` so the secret never appears on argv.
      ROLE_PW = each.value.password
    }
    command = <<-EOT
      set -euo pipefail
      psql --host=${var.postgres_fqdn} \
           --port=5432 \
           --username=${var.admin_username} \
           --dbname=postgres \
           --no-psqlrc \
           --set=ON_ERROR_STOP=on \
           --variable=role=${each.key} \
           --variable=db=${each.value.db} \
           --file=${abspath("${path.module}/sql/bootstrap-role.sql")}
    EOT
  }
}
