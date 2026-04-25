#!/usr/bin/env bash
set -euo pipefail

echo "╔══════════════════════════════════════════════════════════╗"
echo "║       The Boat App — Environment Bootstrap              ║"
echo "╚══════════════════════════════════════════════════════════╝"

echo "▸ Initializing git..."
git init -q
git symbolic-ref HEAD refs/heads/main 2>/dev/null || git branch -M main

# ── Two-service hexagonal structure ────────────────────────────────────────
echo "▸ Creating BFF + Business Service directory trees..."
mkdir -p \
  .claude/{rules,skills,hooks,commands,agents} \
  bff/src/main/java/ch/owt/boatapp/bff/{adapter/{in/web/dto,out/client},infrastructure/{service,security,config}} \
  bff/src/main/resources \
  bff/src/test/java/ch/owt/boatapp/bff/{architecture,integration} \
  bff/src/test/resources \
  business-service/src/main/java/ch/owt/boatapp/{domain/{model/validation,port/{in,out},service/validation,exception},adapter/{in/web/dto,out/persistence/{entity,mapper,repository}},infrastructure/{config,security,service}} \
  business-service/src/main/resources/{db/changelog/changes} \
  business-service/src/test/java/ch/owt/boatapp/{architecture,integration,adapter/{in/web,out/persistence}} \
  business-service/src/test/resources \
  frontend/src/{assets,components/{ui,boats},composables,layouts,pages,router,services,stores,types,i18n} \
  frontend/e2e \
  infra/terraform/{modules/{networking,database,container-registry,container-apps,keyvault},environments/{staging,production}} \
  infra/ansible/{playbooks,inventory,roles,group_vars} \
  infra/docker/keycloak \
  contracts .github/workflows

# ── .gitignore ──────────────────────────────────────────────────────────────
echo "▸ Writing .gitignore..."
cat > .gitignore << 'EOF'
bff/target/
business-service/target/
*.class
*.jar
frontend/node_modules/
frontend/dist/
.idea/
.vscode/
*.iml
.DS_Store
infra/terraform/**/.terraform/
infra/terraform/**/*.tfstate*
infra/terraform/**/*.tfplan
*.tfvars
!*.tfvars.example
infra/ansible/*.retry
.env
.env.local
.env.*.local
*.pem
*.key
frontend/src/services/api-client/
**/generated-sources/
EOF

# ── .editorconfig ───────────────────────────────────────────────────────────
cat > .editorconfig << 'EOF'
root = true
[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 2
[*.java]
indent_size = 4
[Makefile]
indent_style = tab
EOF

# ── .env.example ────────────────────────────────────────────────────────────
cat > .env.example << 'EOF'
# PostgreSQL — one instance, three databases, one role per database (no cross-DB access).
# Admin role exists only for provisioning; apps never use it.
POSTGRES_ADMIN_USER=postgres
POSTGRES_ADMIN_PASSWORD=changeme
# BFF — owns the bff_session database (SPRING_SESSION, SPRING_SESSION_ATTRIBUTES)
BFF_DB_NAME=bff_session
BFF_DB_USER=bff
BFF_DB_PASSWORD=changeme
# Business Service — owns the boatapp database (APP_USER, BOATS, BOAT_AUDIT)
BUSINESS_DB_NAME=boatapp
BUSINESS_DB_USER=business_service
BUSINESS_DB_PASSWORD=changeme
# Keycloak — owns its own database
KEYCLOAK_DB_NAME=keycloak
KEYCLOAK_DB_USER=keycloak
KEYCLOAK_DB_PASSWORD=changeme
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=admin
KEYCLOAK_CLIENT_ID=boat-app-confidential
KEYCLOAK_CLIENT_AUTH_METHOD=private_key_jwt
KEYCLOAK_ISSUER_URI=http://keycloak:8080/realms/boat-app
# BFF holds an asymmetric signing key. Keycloak fetches the public key
# from the BFF's JWKS endpoint (use.jwks.url=true). No shared secret.
BFF_SIGNING_KEY_PATH=/run/secrets/bff-signing-key.pem
BFF_SIGNING_KEY_ID=bff-key-1
BFF_PORT=8080
BUSINESS_SERVICE_PORT=8081
BUSINESS_SERVICE_URL=http://business-service:8081
SPRING_PROFILES_ACTIVE=local-intg
EOF

# ── CLAUDE.md ───────────────────────────────────────────────────────────────
echo "▸ Writing CLAUDE.md..."
cat > CLAUDE.md << 'CLAUDEMD'
# The Boat App

Two Spring Boot services with strict hexagonal architecture.

## Architecture

```
Browser
  │ (session cookie)
  ▼
BFF (port 8080) — OAuth2 client, session, CSRF, token forwarding
  │ Bearer <access_token>
  ▼
Business Service (port 8081) — JWT resource server, domain logic, persistence
  │
  ▼
PostgreSQL (1 instance, 3 isolated DBs: bff_session, boatapp, keycloak — one role per DB)
```

### BFF (bff/)
Thin proxy with session-based OAuth2. No domain logic, no JPA.
- `adapter.in.web` — Spring @RestController (HTTP adapter). Depends on infrastructure.service.
- `adapter.out.client` — BusinessServiceClient (Spring HTTP Interface). Calls Business Service REST API.
- `infrastructure.service` — BoatBffService (thin orchestrator). No @Transactional. Injects Bearer token.
- `infrastructure.security` — SecurityConfig (OAuth2 session, CSRF), BffSecurityHelper.

### Business Service (business-service/)
Pure hexagonal service. Validates JWT Bearer tokens.
- `domain.model` — pure Java domain objects (Boat, AppUser, BoatAudit). NO Spring annotations.
- `domain.port.in` — inbound port interfaces (use cases). Pure Java.
- `domain.port.out` — outbound port interfaces (repository contracts). Pure Java.
- `domain.service` — use case implementations. Pure Java. Only depends on domain.model + ports.
- `adapter.in.web` — Spring @RestController (REST adapter IN). JWT resource server.
- `adapter.out.persistence` — Spring Data JPA (adapter OUT). Implements domain.port.out.
- `infrastructure.config` — BeanConfig (wires pure-Java domain beans).
- `infrastructure.security` — ResourceServerSecurityConfig (JWT), DevSecurityConfig (dev bypass).
- `infrastructure.service` — BoatApplicationService (@Service @Transactional, bridge layer).

**Rule: domain.* packages must have ZERO Spring/Jakarta imports. ArchUnit enforces this.**

## Environments

| Profile | Auth | BFF | Business Service | Keycloak | Database | Use |
|---------|------|-----|-----------------|----------|----------|-----|
| `dev` | None | NOT started | Started (permitAll) | NOT required | PostgreSQL | Fast iteration |
| `local-intg` | Session + JWT | Started (:8080) | Started (:8081) | Docker | PostgreSQL Docker | Integration testing |
| `staging` | Session + JWT | Azure | Azure | Azure | Azure PostgreSQL | Pre-production |
| `prod` | Session + JWT | Azure | Azure | Azure | Azure PostgreSQL | Production |

## Dev mode (no BFF, no Keycloak)

```bash
docker compose -f docker-compose.dev.yml up   # postgres + business-service (dev profile)
cd frontend && npm run dev                     # Vite proxy → localhost:8081
```

## Local-intg (full stack)

```bash
docker compose up                              # all 4 services
cd frontend && npm run dev:intg                # Vite proxy → BFF :8080
```

## Build & verify

```bash
cd frontend && npm run build
cd bff && ./mvnw verify
cd business-service && ./mvnw verify
docker compose up
```

## Conventions

- Spring Boot 4.0.6 on Java 25, Maven jar packaging, YAML config. Both services scaffolded from Spring Initializr (`ai-scripts/00c-initializr.sh`) — extend `pom.xml` rather than rewriting.
- Business Service domain is pure Java; Spring only in adapters and infrastructure
- ArchUnit enforces hexagonal boundaries in both services on every build
- Liquibase for DB migrations (YAML changelogs) — split per service: BFF owns bff_session (SPRING_SESSION), business-service owns boatapp (APP_USER/BOATS/BOAT_AUDIT); Keycloak manages its own schema
- Boat: id (UUID), name (max 64), description (max 256), createdAt (OffsetDateTime UTC), version
- BoatAudit: INSERT-ONLY, FK to APP_USER. APP_USER synced from JWT claims on each request.
- BFF auth: session-based (HttpOnly cookie), confidential Keycloak client, token forwarding
- Business Service auth: stateless JWT Bearer token validation (spring-oauth2-resource-server)
- dev profile: no auth at all, dummy user auto-created — for fast iteration without Keycloak

# Project conventions

## Code review policy

After writing or editing any source code file (and before declaring a task done), invoke the `@code-reviewer` subagent on the modified file(s).

Treat the reviewer's output as follows:
- **Must fix** findings: address them in the same turn before responding to the user.
- **Should fix** findings: address them unless there is a clear reason not to; surface that reason to the user.
- **Consider** findings: surface to the user as suggestions, do not auto-apply.

Documentation requirements are non-negotiable: every class and every public method/function must have a docstring in the language's idiomatic format. The reviewer will flag missing documentation as a must-fix finding.
CLAUDEMD

# ── Claude Code rules ───────────────────────────────────────────────────────
echo "▸ Writing Claude Code rules..."

cat > .claude/rules/bff-java.md << 'RULE'
---
paths: ["bff/**/*.java", "bff/pom.xml"]
---
# BFF Rules — Thin Proxy with OAuth2 Session

## Architecture
- BFF is a thin HTTP proxy. It has NO domain logic and NO JPA.
- adapter.in.web → Spring @RestController, depends on infrastructure.service only
- adapter.out.client → BusinessServiceClient (Spring HTTP Interface), calls Business Service
- infrastructure.service → BoatBffService, thin orchestrator, NO @Transactional
- infrastructure.security → SecurityConfig (OAuth2 session, CSRF), BffSecurityHelper

## ArchUnit rules enforced
- No JPA imports (jakarta.persistence.*) anywhere in BFF
- @Transactional is forbidden on BoatBffService and all BFF classes
- BusinessServiceClient must be an interface
- Controllers depend on infrastructure.service only (not on adapter.out.client directly)
- GlobalExceptionHandler must declare SLF4J Logger

## Token forwarding
- BFF attaches Bearer access_token to all Business Service calls via RestClient interceptor
- DefaultOAuth2AuthorizedClientManager handles token refresh automatically (refresh_token)
- Tokens stored in OAuth2AuthorizedClientRepository backed by Spring Session JDBC

## Profiles
- dev: not used in dev mode (Business Service started directly). DevSecurityConfig: permitAll.
- local-intg/staging/prod: full oauth2Login, session cookie, CSRF, token forwarding

## Jackson
- Spring Boot 4.0.6 auto-configures Jackson 3, NOT Jackson 2. The `ObjectMapper`
  bean is `tools.jackson.databind.ObjectMapper`. Wiring `com.fasterxml.jackson.databind.ObjectMapper`
  fails at startup with `NoSuchBeanDefinitionException`.
- Use Jackson 3 imports: `tools.jackson.databind.ObjectMapper`, `tools.jackson.core.JacksonException`.
- `JacksonException` is unchecked (extends `RuntimeException`) and exposes
  `getOriginalMessage()` — use it in place of Jackson 2's `JsonProcessingException`.
RULE

cat > .claude/rules/business-service-java.md << 'RULE'
---
paths: ["business-service/**/*.java", "business-service/pom.xml"]
---
# Business Service Rules — Strict Hexagonal Architecture + JWT Resource Server

## Hexagonal boundaries (enforced by ArchUnit)
- domain.model, domain.port.in, domain.port.out, domain.service → PURE JAVA ONLY
  - NO Spring annotations (@Component, @Service, @Repository, @Transactional)
  - NO Jakarta/javax annotations (@Entity, @Column, @Table)
  - NO framework imports (org.springframework.*, jakarta.*)
  - ONLY java.* and domain.* imports allowed
- adapter.in.web → Spring @RestController, stateless, depends on infrastructure.service
- adapter.out.persistence → Spring Data JPA, implements domain.port.out
  - JPA entities here (separate from domain records), with hand-written @Component mappers — no MapStruct, no annotation processor
- infrastructure.config → Spring @Configuration beans, wiring ports to adapters
- infrastructure.security → ResourceServerSecurityConfig (JWT), DevSecurityConfig (dev bypass)
- infrastructure.service → BoatApplicationService (@Service @Transactional, bridge layer)

## Inbound port design — Command and Query objects
- Mutations → <Action><Entity>Command (e.g. CreateBoatCommand)
- Reads → <Action><Entity>Query (e.g. ListBoatsQuery)
- Command/Query records live in domain.port.in (pure Java)
- Value objects BoatId(UUID) and UserId(UUID) in domain.model

## Security
- Non-dev: spring-oauth2-resource-server validates JWT Bearer tokens
- JWT sub claim = keycloakId → used to sync/find AppUser
- Stateless: no session, no CSRF
- Dev: permitAll(), dummy AppUser auto-created on startup

## ArchUnit extra rule
- Business Service must NOT import org.springframework.security.oauth2.client.* (only resource-server allowed)

## Other rules
- Constructor injection only — never @Autowired on fields
- Liquibase for migrations (YAML) — never ddl-auto=update
- Boat: id (UUID), name (max 64), description (max 256), createdAt (OffsetDateTime UTC), version
- BoatAudit: INSERT-ONLY, FK to APP_USER
- APP_USER: synced from JWT claims (sub, preferred_username, email, given_name, family_name)
- Optimistic locking: @Version on JPA entity + ETag/If-Match in web adapter

## Jackson
- Spring Boot 4.0.6 auto-configures Jackson 3, NOT Jackson 2. The `ObjectMapper`
  bean is `tools.jackson.databind.ObjectMapper`. Wiring `com.fasterxml.jackson.databind.ObjectMapper`
  fails at startup with `NoSuchBeanDefinitionException`.
- Use Jackson 3 imports: `tools.jackson.databind.ObjectMapper`, `tools.jackson.core.JacksonException`.
- `JacksonException` is unchecked (extends `RuntimeException`) and exposes
  `getOriginalMessage()` — use it in place of Jackson 2's `JsonProcessingException`.
RULE

cat > .claude/rules/frontend-vue.md << 'RULE'
---
paths: ["frontend/src/**", "frontend/package.json", "frontend/vite.config.ts"]
---
# Frontend Rules
- Vue 3 Composition API <script setup lang="ts"> — never Options API
- Headless UI for accessible primitives, Tailwind CSS for styling
- NO OAuth library — auth handled by BFF (session cookie)
- Login: redirect to /oauth2/authorization/keycloak (BFF endpoint)
- Axios: withCredentials + XSRF-TOKEN cookie + X-XSRF-TOKEN header
- API calls: same origin /api/v1/*, no CORS
- vee-validate + zod for forms, vue-i18n for EN/FR, dark mode with Tailwind
- dev mode (npm run dev): Vite proxy /api → http://localhost:8081 (Business Service)
- local-intg (npm run dev:intg): Vite proxy /api → http://localhost:8080 (BFF)
RULE

cat > .claude/rules/infrastructure.md << 'RULE'
---
paths: ["infra/**", "docker-compose*.yml", "Dockerfile*", ".github/**"]
---
# Infrastructure Rules
- Docker: multi-stage builds, non-root, pinned versions, health checks
- 4 containers (local-intg): bff (serves Vue SPA + OAuth), business-service, postgres, keycloak
- 2 containers (dev): postgres-dev, business-service-dev (auth bypass)
- docker-compose.yml: local-intg (full stack)
- docker-compose.dev.yml: dev mode (business-service+postgres, no Keycloak, no BFF)
- BFF Dockerfile: 3-stage (Node→frontend dist, JDK→BFF jar with static/, JRE runtime)
- Business Service Dockerfile: 2-stage (JDK→jar, JRE runtime)
- Terraform: modular, Azure remote state, pinned providers
- GitHub Actions: OIDC federation, staging auto-deploy, prod on release
RULE

cat > .claude/rules/openapi-contract.md << 'RULE'
---
paths: ["contracts/**"]
---
# Contract Rules
- contracts/openapi.yaml is single source of truth
- No Bearer security scheme (session-based auth visible to browser)
- ETag/If-Match for optimistic locking
- Boat: name max 64, description max 256, createdAt OffsetDateTime
- Errors: RFC 9457 (obsoletes RFC 7807). Single shape = ProblemDetail with an
  optional `messages: [ValidationMessageResponse]` extension member. Media type
  `application/problem+json`. `type` is drawn from the problem-type URI
  registry in the ProblemDetail schema description — never `about:blank`.
  `instance` is the request path. Every error response declares the
  `Content-Language` header. Declare 400, 401, 404, 409, 422, 428, 500 on
  every operation where applicable (all referencing the single shape).
- Severity enum: `ERROR | WARNING | INFO` (no `WARN`).
- `ValidationErrorResponse` is NOT part of the contract — multi-error uses
  `ProblemDetail.messages`.
RULE

cat > .claude/rules/validation-and-errors.md << 'RULE'
---
paths: ["bff/**", "business-service/**", "contracts/**"]
---
# Validation & Errors — Unified Two-Layer Design (RFC 9457)

Both services expose REST over HTTP and both act as trust boundaries
(Business Service to its Bearer-token callers; BFF to the browser). Both
enforce the same two-layer model and emit the same wire envelope.

## Two-layer validation model

1. **Syntactic** (null/blank/size/format/range/regex) — enforced at the REST
   adapter by **Jakarta Bean Validation** via `@Valid` on request bodies and
   `@Validated` on controller classes (for @PathVariable / @RequestParam).
   Produces HTTP **400 Bad Request**.

2. **Semantic** (business rules, invariants, state-dependent rules) —
   enforced in the domain. Surfaces as `ValidationFailureException`
   (carries `List<ValidationMessage>`). Produces HTTP **422 Unprocessable
   Entity**.

The domain remains authoritative: `SyntacticValidator` / `SemanticValidator`
and value-object invariants (BoatId/UserId compact constructors) still fire
for non-REST callers (CLI, queue, test). Bean Validation at the adapter is
defense-in-depth at the HTTP trust boundary — not a substitute for domain
invariants, and not a duplicate of them from the domain's point of view.

## Single wire shape — RFC 9457 ProblemDetail

Every non-2xx response uses media type `application/problem+json` and the
single schema `ProblemDetail` (generated from contracts/openapi.yaml).
Required fields: `type`, `title`, `status`, `instance`. Optional: `detail`,
and the extension member `messages: [ValidationMessageResponse]`.

- `type`     — MUST be a stable URI from the registry below. NEVER
               `about:blank`.
- `instance` — MUST be the request path (`request.getRequestURI()` in
               Spring).
- `detail`   — human-readable; safe to localize.
- `messages` — populated for 400 (syntactic) and 422 (semantic); each entry
               is `{severity, code, field, message}`. `code` is a stable
               application-level code (e.g. `field.required`,
               `field.size.invalid`). NEVER emit Jakarta constraint names
               (`NotBlank`, `Size`, …) — translate via `JakartaCodeTranslator`.
               `message` is i18n-resolved against `messages.properties`.
- Response headers — `Content-Type: application/problem+json` and
  `Content-Language` (resolved from `Accept-Language`, default `en`).

## Problem-type URI registry

| Status | `type` URI                                                  | Trigger                                   |
|--------|-------------------------------------------------------------|-------------------------------------------|
| 400    | https://boatapp.owt.ch/problems/validation                  | Bean Validation / malformed JSON          |
| 404    | https://boatapp.owt.ch/problems/not-found                   | BoatNotFoundException                     |
| 409    | https://boatapp.owt.ch/problems/concurrency-conflict        | OptimisticLockException / ConcurrentModification |
| 422    | https://boatapp.owt.ch/problems/validation                  | ValidationFailureException (domain)       |
| 428    | https://boatapp.owt.ch/problems/precondition-required       | Missing `If-Match` header                 |
| 500    | https://boatapp.owt.ch/problems/internal                    | Fallback Exception                        |
| 502    | https://boatapp.owt.ch/problems/upstream-failure            | BFF only: 5xx from Business Service       |

Handlers reference these as constants from `adapter/in/web/ProblemTypes.java`
(one copy per service). Never hand-write the URI in handler code.

## Exception → handler mapping

Every `@RestControllerAdvice` in both services handles (at minimum):

| Exception                            | Status | `type`                  | `messages[]` populated? |
|--------------------------------------|--------|-------------------------|--------------------------|
| `MethodArgumentNotValidException`    | 400    | `.../validation`        | yes (per FieldError)     |
| `ConstraintViolationException`       | 400    | `.../validation`        | yes (per violation)      |
| `HttpMessageNotReadableException`    | 400    | `.../validation`        | yes (single entry, `request.body.malformed`) |
| `ValidationFailureException`         | 422    | `.../validation`        | yes (from domain)        |
| `BoatNotFoundException`              | 404    | `.../not-found`         | no                       |
| `OptimisticLockException` / `ConcurrentModificationException` | 409 | `.../concurrency-conflict` | no |
| `MissingRequestHeaderException`      | 428    | `.../precondition-required` | no                    |
| `Exception` (fallback)               | 500    | `.../internal`          | no (never leak stack)    |

BFF only: `RestClientResponseException` from the upstream Business Service →
pass through 4xx responses byte-identical (upstream body is already
ProblemDetail-compliant); wrap 5xx as 502 `.../upstream-failure` without
leaking upstream body.

## Anti-patterns (DO NOT)

- ❌ Skipping Bean Validation annotations because "the domain validates
  anyway". Both layers run; each has a distinct purpose.
- ❌ Leaking Jakarta constraint names (`NotBlank`, `Size`, …) as wire codes.
  Map them via `JakartaCodeTranslator`.
- ❌ Importing `jakarta.validation.*` or `org.springframework.*` inside
  `..domain..`. ArchUnit enforces this.
- ❌ Returning `ResponseEntity` or HTTP-specific types from use cases.
- ❌ Using HTTP 400 for domain/business rule failures. Domain failures = 422.
- ❌ Different response shapes for adapter-origin vs domain-origin errors —
  always a single `ProblemDetail`.
- ❌ Emitting `about:blank` as `type`. Always use the registry.
- ❌ Forgetting to update contracts/openapi.yaml when adding or changing a
  Jakarta constraint on a DTO. OpenAPI constraints and Bean Validation
  annotations are kept in sync by `useBeanValidation=true` in the codegen.

## Supporting artifacts (present in BOTH services)

- `adapter/in/web/ProblemTypes.java`            — URI constants from the registry
- `adapter/in/web/JakartaCodeTranslator.java`   — Jakarta constraint → application code
- `adapter/in/web/GlobalExceptionHandler.java`  — @RestControllerAdvice with all handlers
- `src/main/resources/messages.properties`      — application-code → localized string

## Tests

- `@WebMvcTest` slice or `@SpringBootTest` integration asserts full RFC 9457
  envelope: Content-Type, Content-Language, populated `type` (matching
  registry), `instance` (matching request path), populated `messages` for
  400/422.
- Regression guard: the body must NEVER contain `about:blank`.
- BFF-specific: validation-failure test must also assert WireMock received
  ZERO upstream requests (proving the BFF's own @Valid kicked in).
RULE

cat > .claude/rules/testing.md << 'RULE'
---
paths: ["**/*Test.java", "**/*.test.ts", "**/*.spec.ts", "frontend/e2e/**"]
---
# Testing Rules
- ArchUnit tests enforce hexagonal architecture in both services (domain has ZERO Spring imports)
- Priority: ArchUnit > E2E > Integration > Unit
- Business Service: jwt() mock post-processor for auth (NOT oidcLogin — no session in resource server)
  - SecurityMockMvcRequestPostProcessors.jwt() from spring-security-test
  - No Keycloak container needed for Business Service integration tests
- BFF: Testcontainers Keycloak (real OAuth2 flow) + WireMock (mock Business Service)
- E2E: Playwright with real Keycloak browser login
- dev profile tests run against Business Service only, no Keycloak
RULE

cat > .claude/rules/git-conventions.md << 'RULE'
---
paths: ["**"]
---
# Git: conventional commits (feat/fix/chore/docs/infra/test), atomic, explain WHY
RULE

# ── Code review rule (auto-loaded for every file touched) ───────────────────
cat > .claude/rules/code-review.md << 'RULE'
---
paths: ["**"]
---
# Code review policy (mirrors CLAUDE.md › Project conventions)

After writing or editing ANY source-code file in this repo, invoke the
`@code-reviewer` subagent on the modified file(s) BEFORE declaring the task
done. Apply the reviewer's findings:

- **Must fix** — address in the same turn before responding to the user.
- **Should fix** — address unless there is a clear reason not to; surface
  the reason to the user.
- **Consider** — surface as suggestions; do not auto-apply.

Documentation is non-negotiable: every class and every public method/function
must carry a docstring in the language's idiomatic format (Javadoc, TSDoc,
PEP 257, doc comments, …). Missing docs are a must-fix finding.

If `.claude/agents/code-reviewer.md` is missing or `CLAUDE.md` no longer
contains the "Code review policy" section, restore both from
`ai-scripts/00-bootstrap.sh` before continuing.
RULE

# ── Code reviewer subagent ──────────────────────────────────────────────────
echo "▸ Writing .claude/agents/code-reviewer.md..."
cat > .claude/agents/code-reviewer.md << 'AGENT'
---
name: code-reviewer
description: Senior-engineer code review of recently written or modified code. Use immediately after Claude writes, edits, or creates source files. Reviews architecture, design, best practices, and verifies that classes and public methods are documented. Read-only — never modifies code.
tools: Read, Grep, Glob, Bash
---

# Code Reviewer

You are a staff-level software engineer performing a focused code review. Your job is to find real issues — not to praise good code, not to list every nitpick. Be specific, cite file paths and line numbers, and prefer concrete suggestions over abstract advice.

## Scope of this review

Review **only the code that was just written or modified** in the current session. To find it:

1. Run `git diff HEAD` (and `git diff --staged` if relevant) to see uncommitted changes.
2. If the working tree is clean (already committed), run `git diff HEAD~1` to review the latest commit.
3. If git is unavailable or returns nothing, ask which file(s) to review.

Do not review the entire repository. Do not refactor surrounding code. Stay inside the diff and the symbols it touches.

## Review dimensions

Walk through these in order. Skip a dimension entirely if it has no findings — do not pad with empty sections.

### 1. Architecture & design
- **Separation of concerns**: Are responsibilities split across the right boundaries (layers, modules, classes)? Flag god classes, leaky abstractions, and mixed concerns (e.g., business logic in controllers, I/O in domain models).
- **SOLID**: Single Responsibility, Open/Closed, Liskov, Interface Segregation, Dependency Inversion. Cite the principle by name only when it adds clarity — never as decoration.
- **Coupling & cohesion**: Are dependencies pointing in the right direction? Is anything tightly coupled that should be inverted (DI, ports/adapters, hexagonal)?
- **Patterns**: Flag both missing patterns (where one would clearly help) and over-engineered patterns (Factory/Strategy/Observer applied to trivial cases). YAGNI matters.
- **State management**: Mutable shared state, hidden globals, singleton abuse, race conditions in concurrent code.

### 2. Coding best practices
- **Naming**: Do names reveal intent? Flag abbreviations, single-letter variables outside of tight loops, type-suffixed names (`userList`, `dataMap`) that leak implementation, misleading names.
- **Function shape**: Length, parameter count (>4 is a smell), nesting depth (>3 is a smell), early returns vs. arrow code.
- **Magic values**: Numbers and strings without named constants.
- **Error handling**: Swallowed exceptions, overly broad catches, errors used for control flow, missing error context, unchecked failure paths.
- **Edge cases**: Null/empty/zero/negative/boundary inputs, unicode, timezone, concurrency, partial failure.
- **Dead code**: Unreachable branches, unused parameters, commented-out blocks, leftover debug statements.
- **Idiomatic usage**: Is the code idiomatic for its language and framework? Flag non-idiomatic constructs even when correct (e.g., manual loops where comprehensions/streams are clearer, mutable default arguments in Python, `==` vs `===` in JS).

### 3. State of the art
- **Modern language features**: Flag use of legacy constructs when modern equivalents exist (e.g., callbacks instead of async/await, `var` instead of `const/let`, `Optional` vs nullable types where the language supports them, pattern matching, records/data classes).
- **Type safety**: Missing or weak types in typed languages, `any`/`Object` escapes, missing generics where they'd help.
- **Standard library & ecosystem**: Reinventing things the standard library or a well-known dependency already provides — but only call this out when the dependency is reasonable.
- **Testability**: Is the code structured so it can be unit-tested without elaborate mocking? Hard-to-test code is usually badly designed code.
- **Security**: Input validation, injection (SQL, command, template), secrets in code, unsafe deserialization, missing authn/authz checks, weak crypto, logging of sensitive data.
- **Performance**: Obvious complexity issues (N+1 queries, quadratic loops on large inputs, unnecessary allocations in hot paths). Do not micro-optimize.

### 4. Documentation (mandatory check)
This is a hard requirement, not a suggestion. For every class and every public method/function in the diff, verify:

- **Class-level**: A docstring/comment block describing what the class represents and its responsibility. One sentence is acceptable if the class is genuinely simple; a multi-line block is expected for anything non-trivial.
- **Method/function-level** (public API only — private/internal helpers are exempt unless non-obvious): A docstring/comment that states:
  - What it does (in terms of behavior, not implementation).
  - Parameters: name, type if not obvious from signature, meaning, constraints.
  - Return value: what it represents and notable cases (null, empty, etc.).
  - Errors/exceptions raised and under what conditions.
  - Side effects, if any (I/O, mutation, network calls).

Use the language's idiomatic format: JSDoc/TSDoc for JS/TS, docstrings for Python (PEP 257), Javadoc for Java, XML doc comments for C#, doc comments for Rust/Go, etc. Flag missing documentation as a **must-fix** finding, not a nit. Flag documentation that merely restates the signature (`/** Gets the user. @param userId the user id @returns the user */`) as low-value and suggest improvement.

## Output format

Produce exactly this structure. Skip empty sections.

```
## Code Review

**Files reviewed**: <list>
**Verdict**: <approve | approve with comments | request changes>
**Summary**: <2–3 sentence overview of the change quality and the most important findings>

### Must fix
<Findings that block merge: bugs, security issues, missing class/method docs, broken contracts, severe design problems>

### Should fix
<Findings that should be addressed before merge but aren't strictly blocking: design improvements, error handling gaps, testability concerns>

### Consider
<Suggestions worth thinking about: alternative approaches, refactoring opportunities, future-proofing>

### Strengths
<Brief — only call out genuinely good decisions worth reinforcing. Skip if there's nothing notable>
```

## Findings format

Each finding must include:
- **Location**: `path/to/file.ext:lineNumber` (or line range)
- **Issue**: One sentence stating what's wrong.
- **Why it matters**: One sentence on the consequence.
- **Suggestion**: A concrete fix — code snippet when useful, never longer than the original.

Example:

> **`src/users/UserService.ts:42`** — `findUser` swallows the database exception and returns `null`, making it impossible for callers to distinguish "user not found" from "database is down."
> *Why it matters*: Callers will treat outages as missing users, hiding incidents and producing wrong behavior.
> *Suggestion*: Throw a typed `RepositoryError` for infrastructure failures and reserve `null` (or better, `Option<User>`) for genuine absence.

## Rules of engagement

- **Be specific or stay silent.** "Consider improving error handling" is useless. Either point to a line and propose a fix, or drop it.
- **One finding per issue.** Do not list the same problem under multiple sections.
- **Calibrate severity honestly.** Not every smell is a must-fix. Inflated severity erodes trust in the review.
- **Respect the author's choices** when they're defensible, even if you'd have done it differently. Style preferences are not findings.
- **Never edit files.** You are read-only. If a fix needs to happen, describe it; the main session or the author applies it.
- **No praise inflation.** Mention strengths only when there is a specific, non-obvious good decision. "Code is clean and well-structured" is filler.
AGENT

# ── Hooks + Skills ──────────────────────────────────────────────────────────
cat > .claude/settings.json << 'EOF'
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "Edit|Write",
      "hooks": [{
        "command": "bash -c 'FILE=\"$CLAUDE_FILE\"; case \"$FILE\" in *.java) DIR=$(echo \"$FILE\" | grep -o \"^[^/]*/\"); cd \"$DIR\" && ./mvnw spotless:apply -q 2>/dev/null || true ;; *.ts|*.vue|*.js) cd frontend && npx prettier --write \"$FILE\" 2>/dev/null || true ;; esac'"
      }]
    }]
  }
}
EOF

mkdir -p .claude/skills
cat > .claude/skills/openapi-codegen.md << 'EOF'
---
name: openapi-codegen
description: Regenerate code from OpenAPI spec
---
BFF types: same API shape as Business Service (proxied transparently)
Frontend: `cd frontend && npx openapi-typescript-codegen --input ../contracts/openapi.yaml --output src/services/api-client --client axios`
EOF

# ── Initial commit ──────────────────────────────────────────────────────────
git add -A
git commit -m "chore: initial scaffolding with BFF + Business Service split

- Two Spring Boot services: bff/ (OAuth2 session) and business-service/ (JWT resource server)
- Business service: strict hexagonal architecture, domain (pure Java) + adapters (Spring)
- BFF: thin proxy with OAuth2 client, session cookie, token forwarding to business service
- Dev mode: business-service only (no BFF, no Keycloak)
- Local-intg: all 4 services (BFF + business-service + postgres + keycloak)
- Claude Code rules enforcing hexagonal boundaries and service separation
- Code-reviewer subagent (.claude/agents/code-reviewer.md) + auto-loaded
  rule + CLAUDE.md > Project conventions: every code edit must be followed
  by an @code-reviewer pass before declaring the task done"

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  ✓ Bootstrap complete!                                   ║"
echo "║    Next: ./ai-scripts/00c-initializr.sh                  ║"
echo "║          (scaffolds bff + business-service via           ║"
echo "║           Spring Initializr: Boot 4.0.6, Java 25)        ║"
echo "║    Then: ./ai-scripts/run-phase.sh 1                     ║"
echo "╚══════════════════════════════════════════════════════════╝"
