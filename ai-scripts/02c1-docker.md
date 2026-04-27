<task>
  <project_conventions>
    Before declaring this phase done, you MUST:

    1. **Code review.** Invoke the `@code-reviewer` subagent on every file you
       wrote or edited. Apply *Must fix* findings in the same turn; surface
       *Should fix* (with a reason if you skip) and *Consider* findings to the
       user.
    2. **Documentation.** Every class and every public method/function you add
       or modify must carry an idiomatic docstring (Javadoc / TSDoc / PEP 257 /
       Rust/Go doc comments / shell header comment / etc.). Missing docs are a
       must-fix finding for the reviewer.
    3. **Self-heal.** If `.claude/agents/code-reviewer.md` is missing or
       `CLAUDE.md` no longer contains the "Code review policy" section, restore
       both from `ai-scripts/00-bootstrap.sh` before proceeding.

    These are non-negotiable per CLAUDE.md › Project conventions.
  </project_conventions>

  <role>You are a senior DevOps engineer creating Docker configs for 4 deployment environments.</role>

  <context>
    <architecture>
      Two Spring Boot services:
      - BFF (bff/, port 8080): OAuth2 session, CSRF, token forwarding, serves Vue SPA static files
      - Business Service (business-service/, port 8081): JWT resource server, domain logic, JPA
      Both share the same PostgreSQL instance (not the same database). Three isolated
      databases live on that instance — bff_session (BFF), boatapp (business-service),
      keycloak (Keycloak) — each with its own role and zero cross-DB access.

      Environment profiles:
      - dev: business-service + postgres only (no BFF, no Keycloak, auth bypass)
      - local-intg: all 4 services (BFF + business-service + postgres + keycloak)
      - staging/prod: Azure (Terraform/Ansible)
    </architecture>
  </context>

  <instructions>
    <step order="0">
      Create `infra/keycloak/realm.yaml` — the single source-of-truth realm config,
      consumed by keycloak-config-cli in all three envs (local-intg via the
      `keycloak-config` compose sidecar; staging/prod via Ansible docker task —
      see 02c3-ansible.md):

      ```yaml
      # infra/keycloak/realm.yaml — keycloak-config-cli format.
      # Applied by adorsys/keycloak-config-cli:latest-26.6.1 in all envs.
      # Env-specific values come from environment variables (substitution enabled).
      realm: boat-app
      enabled: true
      sslRequired: external
      clients:
        - clientId: boat-app-confidential
          enabled: true
          publicClient: false
          standardFlowEnabled: true
          directAccessGrantsEnabled: false
          # Authenticate to the token endpoint with a signed JWT (RFC 7523).
          # NO shared client_secret — use.jwks.url=true tells Keycloak to
          # fetch the BFF's public JWKS at the URL below.
          clientAuthenticatorType: client-jwt
          attributes:
            use.jwks.url: "true"
            jwks.url: "${BFF_JWKS_URL}"
            token.endpoint.auth.signing.alg: RS256
          redirectUris:
            - "${BFF_BASE_URL}/*"
          webOrigins:
            - "${BFF_BASE_URL}"
      roles:
        realm:
          - name: user
            description: Standard boat-app user
      users:
        - username: demo
          enabled: true
          credentials:
            - type: password
              value: "${DEMO_PASSWORD}"
              temporary: false
          realmRoles: [user]
      ```

      Also create per-env variable files (committed; real passwords live in vaults,
      these files hold only URLs + placeholders):
      - `infra/keycloak/env/local-intg.env`
        ```env
        BFF_BASE_URL=http://localhost:8080
        BFF_JWKS_URL=http://bff:8080/.well-known/jwks.json
        # DEMO_PASSWORD taken from the shell env / .env (default demo123 in local-intg)
        ```
      - `infra/keycloak/env/staging.env` and `production.env` — same two vars with
        the public BFF FQDN. `DEMO_PASSWORD` is injected by Ansible from vault.

      IMPORTANT — export hygiene: `realm.yaml` is **hand-authored**, not exported
      from the admin UI. If you ever export a realm via the admin UI for debugging,
      strip credentials/hashes/salts BEFORE committing. See 02a4-backend-auth.md
      step 4.
    </step>
    <step order="1">
      Create bff/Dockerfile (4-stage build — codegen + frontend + BFF + runtime).

      **Why 4 stages, not 3.** `@openapitools/openapi-generator-cli` (used by
      `frontend/package.json` → `generate:api`) is a thin Node wrapper that shells
      out to `java -jar`. `node:22-alpine` has no JRE, so calling `npm run build`
      (which chains `generate:api`) from the Node stage fails with
      `/bin/sh: java: not found`. Splitting codegen into its own JDK-bearing stage
      keeps the Node stage Java-free and matches the layout the verification script
      now enforces (see ai-scripts/checks/02c1/run.sh).

      - **Stage 1 (ts-codegen):** `openapitools/openapi-generator-cli:v<X>` (this
        image already bundles Java + the generator JAR). COPY `contracts/` in,
        run the generator driven by `contracts/codegen-typescript-axios.json`
        (the same config consumed by `npm run generate:api` — flags live in one
        place, no drift), output to `/out`. Pin `<X>` to the version the npm
        wrapper auto-selects in `frontend/openapitools.json` — bumping requires
        bumping both. `RUN` does NOT inherit the image's `ENTRYPOINT`, so call
        `/usr/local/bin/docker-entrypoint.sh generate ...` by absolute path.
      - **Stage 2 (frontend):** `node:22-alpine` → `npm ci` →
        `COPY --from=ts-codegen /out/ ./src/services/api-client/generated/` →
        `npm run build:no-codegen` (defined in 02b1; skips `generate:api`).
        Outputs `dist/`.
      - **Stage 3 (bff-build):** `eclipse-temurin:25-jdk` → copy frontend dist/
        into `bff/src/main/resources/static/` → `cd bff && ./mvnw package -DskipTests`.
      - **Stage 4 (runtime):** `eclipse-temurin:25-jre` → non-root user,
        HEALTHCHECK on `/actuator/health`, container-aware JVM flags.

      Build context = repo root (compose passes `context: .`); `frontend/`,
      `bff/`, and `contracts/` must all be present at the context root.

      DO NOT install a JRE in the Node stage to "make `npm run build` work" — that
      fattens the stage and re-introduces the drift between npm and Docker codegen
      flags. The codegen stage is the canonical fix.
    </step>
    <step order="2">
      Create business-service/Dockerfile (2-stage build — no frontend).
      business-service is a four-module Maven reactor (domain → application →
      infrastructure → bootstrap), so the Dockerfile must:
      - Copy each submodule's pom.xml *before* `dependency:go-offline` so the
        layer cache survives source-only changes (parent + 4 child poms).
      - Run `./mvnw -pl bootstrap -am package -DskipTests` (build bootstrap
        and everything it depends on; skip unrelated reactor work).
      - Copy the runnable artifact from
        `business-service/bootstrap/target/business-service.jar` (finalName
        in bootstrap/pom.xml strips the version suffix).
      Stages:
      - Stage 1 (build): eclipse-temurin:25-jdk → reactor build as above.
      - Stage 2 (runtime): eclipse-temurin:25-jre → non-root user, HEALTHCHECK
        on /actuator/health, JVM container flags.
    </step>
    <step order="3">
      Create docker-compose.yml (default = local-intg, full stack — all 4 services):
      ```yaml
      # Usage: docker compose up
      # Starts: postgres + keycloak + business-service + bff
      # Browser: http://localhost:8080 (BFF serves Vue SPA + handles OAuth)
      # Business Service API (internal): http://localhost:8081 (not for browser use)
      services:
        postgres:
          image: postgres:17-alpine
          volumes:
            - postgres-data:/var/lib/postgresql/data
            # Runs ONLY on first boot of postgres-data (empty volume). If you change
            # 01-init-dbs.sh, run `docker compose down -v` to re-run it. See step 7.
            - ./infra/postgres/init:/docker-entrypoint-initdb.d:ro
          healthcheck:
            test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_ADMIN_USER:-postgres}"]
            interval: 5s
            timeout: 5s
            retries: 5
          environment:
            # Admin role used ONLY for provisioning (docker-entrypoint init). Apps never use it.
            POSTGRES_USER: ${POSTGRES_ADMIN_USER:-postgres}
            POSTGRES_PASSWORD: ${POSTGRES_ADMIN_PASSWORD:-changeme}
            POSTGRES_DB: postgres
            # Per-role passwords consumed by 01-init-dbs.sh to create LOGIN roles.
            BFF_DB_PASSWORD: ${BFF_DB_PASSWORD:-bff}
            BUSINESS_DB_PASSWORD: ${BUSINESS_DB_PASSWORD:-business_service}
            KEYCLOAK_DB_PASSWORD: ${KEYCLOAK_DB_PASSWORD:-keycloak}

        keycloak:
          image: quay.io/keycloak/keycloak:26.6.1
          # No --import-realm. Realm config is applied declaratively by the
          # keycloak-config service below (reads infra/keycloak/realm.yaml).
          command: start-dev
          ports: ["8180:8080"]
          depends_on: { postgres: { condition: service_healthy } }
          healthcheck:
            test: ["CMD-SHELL", "curl -sf http://localhost:8080/health/ready || exit 1"]
            interval: 10s
            timeout: 5s
            retries: 10
          environment:
            KEYCLOAK_ADMIN: ${KEYCLOAK_ADMIN:-admin}
            KEYCLOAK_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD:-admin}
            KC_HEALTH_ENABLED: "true"
            KC_METRICS_ENABLED: "true"
            KC_DB: postgres
            KC_DB_URL: jdbc:postgresql://postgres:5432/${KEYCLOAK_DB_NAME:-keycloak}
            KC_DB_USERNAME: ${KEYCLOAK_DB_USER:-keycloak}
            KC_DB_PASSWORD: ${KEYCLOAK_DB_PASSWORD:-keycloak}

        # Short-lived sidecar: applies infra/keycloak/realm.yaml against the
        # live Keycloak admin endpoint. Idempotent (create-or-update).
        # Same YAML is reused by Ansible in staging/prod (see 02c3-ansible.md).
        keycloak-config:
          image: docker.io/adorsys/keycloak-config-cli:latest-26.6.1
          depends_on: { keycloak: { condition: service_healthy } }
          restart: "no"
          volumes:
            - ./infra/keycloak:/config:ro
          environment:
            KEYCLOAK_URL: http://keycloak:8080
            KEYCLOAK_USER: ${KEYCLOAK_ADMIN:-admin}
            KEYCLOAK_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD:-admin}
            KEYCLOAK_AVAILABILITYCHECK_ENABLED: "true"
            KEYCLOAK_AVAILABILITYCHECK_TIMEOUT: 120s
            IMPORT_FILES_LOCATIONS: /config/realm.yaml
            IMPORT_VAR_SUBSTITUTION_ENABLED: "true"
            # Variables referenced by realm.yaml (resolved at import time).
            BFF_BASE_URL: http://localhost:8080
            BFF_JWKS_URL: http://bff:8080/.well-known/jwks.json
            DEMO_PASSWORD: ${DEMO_PASSWORD:-demo123}

        business-service:
          build: { context: ., dockerfile: business-service/Dockerfile }
          ports: ["8081:8081"]
          depends_on:
            postgres: { condition: service_healthy }
            keycloak: { condition: service_healthy }
          healthcheck:
            test: ["CMD-SHELL", "curl -sf http://localhost:8081/actuator/health || exit 1"]
            interval: 10s
            timeout: 5s
            retries: 10
          environment:
            SPRING_PROFILES_ACTIVE: local-intg
            DATABASE_URL: jdbc:postgresql://postgres:5432/${BUSINESS_DB_NAME:-boatapp}
            DATABASE_USERNAME: ${BUSINESS_DB_USER:-business_service}
            DATABASE_PASSWORD: ${BUSINESS_DB_PASSWORD:-business_service}
            KEYCLOAK_ISSUER_URI: http://keycloak:8080/realms/boat-app

        bff:
          build: { context: ., dockerfile: bff/Dockerfile }
          ports: ["8080:8080"]
          depends_on:
            postgres: { condition: service_healthy }
            business-service: { condition: service_healthy }
            keycloak: { condition: service_healthy }
          environment:
            SPRING_PROFILES_ACTIVE: local-intg
            # BFF's own bff_session database (owned by role `bff`). Liquibase in BFF
            # creates SPRING_SESSION on startup. No access to boatapp or keycloak DBs.
            DATABASE_URL: jdbc:postgresql://postgres:5432/${BFF_DB_NAME:-bff_session}
            DATABASE_USERNAME: ${BFF_DB_USER:-bff}
            DATABASE_PASSWORD: ${BFF_DB_PASSWORD:-bff}
            BUSINESS_SERVICE_URL: http://business-service:8081
            KEYCLOAK_ISSUER_URI: http://keycloak:8080/realms/boat-app
            KEYCLOAK_CLIENT_ID: ${KEYCLOAK_CLIENT_ID:-boat-app-confidential}
            # private_key_jwt — no shared secret. The PEM is mounted as a Docker
            # secret and the public half is served by the BFF at /.well-known/jwks.json
            # for Keycloak (use.jwks.url=true on the client) to fetch.
            BFF_SIGNING_KEY_PATH: /run/secrets/bff-signing-key.pem
            BFF_SIGNING_KEY_ID: ${BFF_SIGNING_KEY_ID:-bff-key-1}
          secrets:
            - bff-signing-key

      volumes:
        postgres-data:

      secrets:
        bff-signing-key:
          # Generated by ai-scripts/00b-generate-bff-key.sh (gitignored).
          file: ./infra/docker/keys/bff-signing-key.pem
      ```
    </step>
    <step order="4">
      Create docker-compose.dev.yml (dev mode — business-service + postgres only, no BFF, no Keycloak):
      ```yaml
      # Usage: docker compose -f docker-compose.dev.yml up
      # Starts: postgres-dev + business-service-dev (auth bypass — no JWT validation)
      # For frontend dev: cd frontend && npm run dev  (Vite proxies /api to localhost:8081)
      # No BFF, no Keycloak needed in dev mode.
      services:
        postgres-dev:
          image: postgres:17-alpine
          ports: ["5432:5432"]
          environment:
            # Dev mode reuses the same init script as local-intg — creates all 3 DBs
            # (bff_session, boatapp, keycloak) and 3 roles. Dev only uses boatapp,
            # but extras are harmless and keep the init script single-source.
            POSTGRES_USER: ${POSTGRES_ADMIN_USER:-postgres}
            POSTGRES_PASSWORD: ${POSTGRES_ADMIN_PASSWORD:-changeme}
            POSTGRES_DB: postgres
            BFF_DB_PASSWORD: ${BFF_DB_PASSWORD:-bff}
            BUSINESS_DB_PASSWORD: ${BUSINESS_DB_PASSWORD:-business_service}
            KEYCLOAK_DB_PASSWORD: ${KEYCLOAK_DB_PASSWORD:-keycloak}
          volumes:
            - postgres-dev-data:/var/lib/postgresql/data
            - ./infra/postgres/init:/docker-entrypoint-initdb.d:ro
          healthcheck:
            test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_ADMIN_USER:-postgres}"]
            interval: 5s
            timeout: 5s
            retries: 5

        business-service-dev:
          build: { context: ., dockerfile: business-service/Dockerfile }
          ports: ["8081:8081"]
          depends_on: { postgres-dev: { condition: service_healthy } }
          environment:
            SPRING_PROFILES_ACTIVE: dev
            DATABASE_URL: jdbc:postgresql://postgres-dev:5432/${BUSINESS_DB_NAME:-boatapp}
            DATABASE_USERNAME: ${BUSINESS_DB_USER:-business_service}
            DATABASE_PASSWORD: ${BUSINESS_DB_PASSWORD:-business_service}
          # No KEYCLOAK_ISSUER_URI — dev profile uses permitAll(), no JWT validation

      volumes:
        postgres-dev-data:
      ```
    </step>
    <step order="4b">
      Create `infra/postgres/init/01-init-dbs.sh` — PostgreSQL bootstrap for the
      `postgres:17-alpine` container. Mounted read-only into
      `/docker-entrypoint-initdb.d/` so it runs on first boot of the data volume.

      Creates three LOGIN roles (bff, business_service, keycloak) with passwords
      from env vars, then three databases each OWNED by its role. Finally REVOKEs
      PUBLIC CONNECT and GRANTs CONNECT only to the owner — a compromised `bff`
      role cannot reach `boatapp` or `keycloak`, and vice versa.

      ```bash
      #!/usr/bin/env bash
      # infra/postgres/init/01-init-dbs.sh
      # Runs ONLY on first boot of the postgres-data volume (empty data dir).
      # If you edit this file, run `docker compose down -v` to recreate the volume.
      # Idempotent — every CREATE is guarded.
      set -euo pipefail

      psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
        DO \$\$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='bff') THEN
            CREATE ROLE bff LOGIN PASSWORD '${BFF_DB_PASSWORD}';
          END IF;
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='business_service') THEN
            CREATE ROLE business_service LOGIN PASSWORD '${BUSINESS_DB_PASSWORD}';
          END IF;
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='keycloak') THEN
            CREATE ROLE keycloak LOGIN PASSWORD '${KEYCLOAK_DB_PASSWORD}';
          END IF;
        END \$\$;

        SELECT 'CREATE DATABASE bff_session OWNER bff'
          WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='bff_session')\gexec
        SELECT 'CREATE DATABASE boatapp OWNER business_service'
          WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='boatapp')\gexec
        SELECT 'CREATE DATABASE keycloak OWNER keycloak'
          WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='keycloak')\gexec

        REVOKE CONNECT ON DATABASE bff_session FROM PUBLIC;
        REVOKE CONNECT ON DATABASE boatapp     FROM PUBLIC;
        REVOKE CONNECT ON DATABASE keycloak    FROM PUBLIC;
        GRANT  CONNECT ON DATABASE bff_session TO bff;
        GRANT  CONNECT ON DATABASE boatapp     TO business_service;
        GRANT  CONNECT ON DATABASE keycloak    TO keycloak;
      EOSQL
      ```

      `chmod +x infra/postgres/init/01-init-dbs.sh` when creating it. The Postgres
      docker-entrypoint only executes files ending in `.sh` when they are executable.
    </step>
    <step order="5">
      Handle Keycloak issuer URI mismatch in local-intg:
      - business-service → Keycloak: http://keycloak:8080/realms/boat-app (Docker internal DNS)
      - Browser → Keycloak: http://localhost:8180/realms/boat-app (exposed port)
      - BFF → Keycloak: http://keycloak:8080/realms/boat-app (Docker internal DNS for oauth2Login)
      Configure in application-local-intg.yml for both services:
        - business-service: spring.security.oauth2.resourceserver.jwt.issuer-uri = http://keycloak:8080/realms/boat-app
        - bff: spring.security.oauth2.client.provider.keycloak.issuer-uri = http://keycloak:8080/realms/boat-app
      Add to Keycloak realm valid redirect URIs: http://localhost:8080/* (BFF port)

      Same DNS-direction concern for the JWKS URL on the boat-app-confidential
      client (private_key_jwt): Keycloak runs in the same Docker network, so the
      registered jwks.url MUST use the BFF's internal Docker DNS name —
      http://bff:8080/.well-known/jwks.json — NOT http://localhost:8080/...
      (which would resolve inside the Keycloak container, not on the host).
    </step>
    <step order="6">
      Create Makefile:
      - make dev → docker compose -f docker-compose.dev.yml up -d --build (business-service + postgres)
      - make dev-frontend → cd frontend && npm run dev (Vite HMR, proxies to business-service :8081)
      - make up → docker compose up -d --build (local-intg full stack)
      - make down → docker compose down
      - make down-dev → docker compose -f docker-compose.dev.yml down
      - make logs → docker compose logs -f
      - make clean → docker compose down -v --rmi local
      - make test-bff → cd bff && ./mvnw verify
      - make test-business → cd business-service && ./mvnw verify
      - make test → make test-bff && make test-business
      - make e2e → cd frontend && npx playwright test
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it validates both compose files,
    checks 4 services declared (bff, business-service, postgres, keycloak),
    no :latest tags, USER directive in Dockerfiles, healthchecks present,
    and no secret literals:
    ```bash
    ai-scripts/checks/02c1/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02c1/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "infra: Docker Compose for dev (business-service only) and local-intg (full stack)

    - docker-compose.yml: local-intg with bff + business-service + postgres + keycloak (4 services)
    - docker-compose.dev.yml: business-service-dev + postgres-dev (no BFF, no Keycloak, auth bypass)
    - infra/postgres/init/01-init-dbs.sh: creates 3 DBs + 3 roles on first boot, one role per DB
    - bff/Dockerfile: 4-stage (TS codegen + Node frontend build + JDK BFF build + JRE runtime)
    - business-service/Dockerfile: 2-stage (JDK build + JRE runtime)
    - BFF serves Vue SPA from resources/static/ (built in Docker)
    - Keycloak issuer URI mismatch documented (internal Docker DNS vs browser localhost)
    - Makefile for dev/up/down/test/e2e convenience commands"
    ```
  </commit>
</task>
