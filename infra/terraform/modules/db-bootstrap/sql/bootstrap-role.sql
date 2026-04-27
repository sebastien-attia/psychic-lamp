-- Bootstrap one PostgreSQL role + per-database grants.
--
-- Invoked by modules/db-bootstrap/main.tf as:
--   ROLE_PW=<password> psql -v role=<name> -v db=<dbname> \
--                          -f sql/bootstrap-role.sql
-- The password reaches psql via the env (see `\getenv` below), not argv.
--
-- Why a script file rather than inline `psql -c`:
-- ------------------------------------------------
-- psql performs `:'name'` / `:"name"` variable substitution only when it
-- parses input as a script (file, stdin, or \i). Substitution is NOT done
-- inside the value of a `-c`/`--command=` argument — that string is sent
-- to the server as a single simple-query, untouched. Three back-to-back
-- staging deploys hit the same `syntax error at or near ":"` because of
-- this; moving the SQL into this file is the canonical fix.
--
-- Substitution forms used here:
--   :'role'  → the value, quoted as a SQL string literal
--   :"role"  → the value, quoted as a SQL identifier
-- format(... %I ...) double-quotes the identifier safely when needed.

-- Hash-bump 2026-04-27: force re-apply of all role bootstraps so the BFF
-- role/password is re-set from TF_VAR_BFF_DB_PASSWORD (the same secret KV
-- serves to the running BFF). Prior applies refreshed state without
-- re-running because triggers were unchanged.
\set ON_ERROR_STOP on

-- Pull the password from the environment into psql variable `pw` so it
-- never appears on `psql`'s argv (and therefore never in `ps`-visible
-- listings on the runner). \getenv is psql 16+, which the Ubuntu 24.04
-- runner's postgresql-client meta-package provides.
\getenv pw ROLE_PW

-- 1. Create the role only if it does not already exist.
--
--    \gexec runs the SQL string returned by the preceding SELECT. When the
--    role exists the SELECT returns zero rows and nothing executes; when
--    it doesn't, exactly one CREATE ROLE statement runs. This avoids the
--    DO $$ … $$ block that previously hid `:'role'` from substitution.
SELECT format('CREATE ROLE %I LOGIN', :'role')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role')
\gexec

-- 2. Set/rotate the password unconditionally so a rotation propagates
--    even when the role already exists.
ALTER ROLE :"role" WITH LOGIN PASSWORD :'pw';

-- 3. Hop to the target application database to grant schema-level
--    privileges. \connect re-uses the current host/user/password and
--    keeps psql variables intact.
\connect :"db"

GRANT CONNECT ON DATABASE :"db" TO :"role";
GRANT USAGE, CREATE ON SCHEMA public TO :"role";
GRANT ALL PRIVILEGES ON ALL TABLES    IN SCHEMA public TO :"role";
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO :"role";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES    TO :"role";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO :"role";
