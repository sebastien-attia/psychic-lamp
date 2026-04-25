# AI Usage — A Candid Retrospective

This project was built with heavy AI assistance. Pretending otherwise would
waste a reviewer's time. What follows is an honest account of *which* AI was
used, *for what*, *what was kept human*, and where the AI's first draft
needed surgery.

## AI tools used

- **Claude Code** (Anthropic, model id `claude-opus-4-7[1m]` — Claude Opus 4.7
  with 1M context) — primary day-to-day driver. Every new phase opens with
  `--permission-mode plan`; only after the proposed plan was reviewed did
  execution start.
- **Claude.ai** (web app) — used early for whiteboard-style architecture
  discussion before any prompt was committed: hexagonal layout, BFF /
  resource-server boundary, `private_key_jwt` vs client-secret trade-off,
  token-forwarding strategy.

No other AI tools (Copilot, Cursor, ChatGPT, etc.) were used.

## What AI was used for

- Project bootstrap and per-module hexagonal directory trees.
- Spring Boot scaffolding (extending Initializr-generated `pom.xml`s, not
  rewriting them).
- Domain models, ports, use-case implementations, JPA mappers — Claude wrote
  the code; ArchUnit kept it inside the lines.
- Liquibase YAML changelogs (BFF `SPRING_SESSION`, Business Service
  `APP_USER` / `BOATS` / `BOAT_AUDIT`).
- Security wiring: BFF OAuth2 session, Business Service JWT resource server,
  Keycloak `keycloak-config-cli` realm definitions.
- Vue 3 components, Pinia stores, Axios interceptor, router guards.
- OpenAPI codegen wiring on both sides (Spring HTTP Interface + TypeScript
  Axios), plus all `Dockerfile`s and the two Compose stacks.
- Terraform modules (network, database, ACR, Container Apps, Key Vault) and
  Ansible playbooks (deploy, rollback, configure-keycloak, run-migrations).
- Playwright E2E tests, axe-core accessibility checks, GitHub Actions
  workflows.
- This document.

## Representative prompts

Three excerpts pulled byte-for-byte from `ai-scripts/`. The full prompts
(plus surrounding rules and step-by-step instructions) live in
[`ai-scripts/`](ai-scripts/) — what's quoted here is the most load-bearing
slice of each.

### 1 · `ai-scripts/01-openapi-contract.md` — the OpenAPI contract

```
<role>You are a senior API architect designing a RESTful contract for a boat management application.</role>
<approach>API-first: this OpenAPI spec is the single source of truth. Backend stubs and frontend TypeScript types are generated from it.</approach>

<optimistic-locking>
  - GET responses include an ETag header containing the version number
  - PUT requests MUST include an If-Match header with the current version
  - 409 Conflict if version mismatch (concurrent modification)
  - version also included in BoatResponse body for convenience
  - POST 201 responses include a Location header pointing to the created
    boat. Its schema is `type: string, format: uri-reference` (NOT `uri`) —
    the example is a relative path like `/api/v1/boats/{uuid}`, which
    OpenAPI/JSON-Schema tooling rejects as a strict `format: uri`.
</optimistic-locking>
```

**What Claude produced.** A 790-line `contracts/openapi.yaml` covering
boat CRUDL + user info, RFC 9457 ProblemDetail with a `messages[]` extension
for validation, and a stable problem-type URI registry. Bean Validation
constraints on DTOs (`maxLength`, `format: uuid`) survived round-tripping
through the OpenAPI generator thanks to `useBeanValidation=true`.

**What I changed.** The first draft used `about:blank` as the default
`type` URI for several errors. I overrode that — `about:blank` is the
single biggest RFC 9457 anti-pattern — and added a documented URI registry
in [`.claude/rules/validation-and-errors.md`](.claude/rules/validation-and-errors.md).
That rule then propagated into every subsequent error-handling prompt.

### 2 · `ai-scripts/02a4-backend-auth.md` — split authentication

```
<role>You are a senior security engineer implementing two distinct security configurations: BFF (OAuth2 session) and Business Service (JWT resource server).</role>

<security-model>
  BFF (port 8080):
  - Manages user-facing OAuth2 flow with Keycloak (confidential client)
  - Stores session in PostgreSQL via Spring Session JDBC
  - Extracts access_token from OAuth2AuthorizedClient and forwards it as Bearer to Business Service
  - DefaultOAuth2AuthorizedClientManager handles token refresh (using stored refresh_token)
  - Handles CSRF (SPA cookie-based)
  - Serves Vue SPA static files

  Business Service (port 8081):
  - Stateless JWT Bearer token validation (spring-oauth2-resource-server)
  - Validates tokens against Keycloak JWKS endpoint
  - Syncs AppUser from JWT claims (sub, preferred_username, email, given_name, family_name) on each request
  - No session, no CSRF, no OAuth2 client
</security-model>
```

The matching Keycloak realm wiring (in
[`ai-scripts/02c1-docker.md`](ai-scripts/02c1-docker.md)) pins the
client-authentication mode:

```yaml
clientAuthenticatorType: client-jwt
attributes:
  use.jwks.url: "true"
  jwks.url: "${BFF_JWKS_URL}"
  token.endpoint.auth.signing.alg: RS256
```

**What Claude produced.** Two `SecurityFilterChain` configs, a
`BffSecurityHelper` that pulls the access token off the
`OAuth2AuthorizedClient` and rides it into a `RestClient` interceptor, a
`SecurityHelper` on the resource-server side that syncs `AppUser` from JWT
claims on every request, plus a Keycloak realm YAML that wires
`client-jwt` authentication and points at the BFF's JWKS endpoint.

**What I changed.** Two corrections worth calling out:

- The first draft used `clientAuthenticatorType: client-secret` — which
  *works* but is the lazy answer. I rewrote the prompt to insist on
  `private_key_jwt` (OIDC Core §9 / RFC 7523), generated an RSA key with
  [`ai-scripts/00b-generate-bff-key.sh`](ai-scripts/00b-generate-bff-key.sh),
  and exposed a `JwksController` so Keycloak fetches the public half on
  demand. There is no client secret anywhere in this project.
- An early version of `DevSecurityConfig` left Spring Boot's OAuth2 client
  autoconfig active in `dev` profile, which fails fast on the missing
  `client-id` registration. The fix (call out in
  [`bff/src/main/resources/application-dev.yml`](bff/src/main/resources/application-dev.yml))
  was to either exclude the autoconfig or provide a stub registration —
  classic AI blind spot, caught by `make dev` blowing up on first run.

### 3 · `ai-scripts/02c1-docker.md` — the four-stage BFF image

```
**Why 4 stages, not 3.** `@openapitools/openapi-generator-cli` (used by
`frontend/package.json` → `generate:api`) is a thin Node wrapper that shells
out to `java -jar`. `node:22-alpine` has no JRE, so calling `npm run build`
(which chains `generate:api`) from the Node stage fails with
`/bin/sh: java: not found`. Splitting codegen into its own JDK-bearing stage
keeps the Node stage Java-free and matches the layout the verification script
now enforces (see ai-scripts/checks/02c1/run.sh).
```

**What Claude produced.** Initially, a *three*-stage Dockerfile that ran
`npm run build` inside a Node image — and `npm run build` calls
`openapi-generator-cli`, which is a Java tool. The build failed on Docker
Hub with `Cannot run program "java"`. Commit
[`598fb9b`](https://github.com/sebastien-attia/psychic-lamp/commit/598fb9b)
fixed it by splitting TS codegen into a dedicated stage that has a JRE on
the path.

**What I changed.** Even after the fix landed, the underlying frontend
`prebuild` script *also* ran the codegen (belt-and-braces in dev mode), so
a careless edit could quietly bring back the JRE-in-Node failure. Commit
[`5a466dc`](https://github.com/sebastien-attia/psychic-lamp/commit/5a466dc)
added a guard: the Docker `frontend` stage runs `npm run build:no-codegen`
instead of `npm run build`, and the `prebuild` hook short-circuits when
the generated `frontend/src/services/api-client/` already exists. This is
a pattern I want to repeat: **once the AI flinches on a class of failure,
add a build-level gate so the failure can't return**.

## How AI output was validated

Every phase ran through the same gauntlet:

1. **Plan first**: Claude Code ran in `--permission-mode plan` until I'd read
   and approved the proposed file changes. No executions on first contact.
2. **Path-scoped rules** in [`.claude/rules/`](.claude/rules/) pinned
   conventions per area (BFF Java, Business Service Java, frontend Vue,
   infrastructure, OpenAPI contract, validation/errors, testing, git).
3. **PostToolUse hooks** in [`.claude/settings.json`](.claude/settings.json)
   auto-format on every edit (`spotless:apply` for Java, `prettier --write`
   for TS/Vue) — drift-free with zero ceremony.
4. **`@code-reviewer` subagent** invoked after every code-touching turn,
   per [`CLAUDE.md`](CLAUDE.md) policy. Findings are graded **must-fix**,
   **should-fix**, **consider** — must-fix get applied in the same turn.
5. **ArchUnit** runs on every `mvn verify` and fails the build the moment
   `domain.*` imports anything from Spring or Jakarta. This caught more
   AI-introduced layering mistakes than any human review would have.
6. **Testcontainers** spins up a real PostgreSQL for integration tests; BFF
   tests also run a real Keycloak. No mocks for the external systems that
   matter.
7. **Playwright** drives the full local-intg stack end-to-end through the
   real BFF login flow.
8. **Per-phase verification scripts** in
   [`ai-scripts/checks/`](ai-scripts/checks/) (one `run.sh` + one
   `human.md` per phase) gated each commit with both automated checks and
   a human-eyes-on checklist.

## What was NOT delegated to AI

- **OpenAPI contract design.** The endpoint shape, ETag/If-Match strategy,
  and error envelope are domain decisions. AI helped *transcribe* them
  into YAML, but the design came from sketches in Claude.ai and judgment.
- **Security architecture choices.** `private_key_jwt` over client secret;
  Spring Session JDBC over Redis; stateless JWT validation on the
  resource server with no session; CSRF cookie strategy. Each is a
  human-made trade-off, defended in
  [`.claude/rules/`](.claude/rules/).
- **Azure subscription bootstrap.** OIDC federation, the
  `00d-bootstrap-azure.sh` one-off, GitHub Environment protection rules,
  `DTRACK_*` secrets — all done by hand, never written to the AI prompts.
- **Production releases.** `deploy-production.yml` requires manual
  approval on a protected GitHub Environment. AI never has the keys.
- **Git commit messages.** Conventional-commits style (see
  [`.claude/rules/git-conventions.md`](.claude/rules/git-conventions.md)),
  hand-written, with the *why* in the body. Browse the log — every commit
  is atomic, scoped, and tells a story.
- **This document.** AI_USAGE.md must reflect lived experience. Anything
  else would defeat its purpose.

## Development workflow

The whole project is driven by [`ai-scripts/run-phase.sh`](ai-scripts/run-phase.sh):

```
./ai-scripts/run-phase.sh <phase>      # e.g. 02a4, 02c1, 04
```

For each phase:

1. The orchestrator prints a coloured summary of what's about to be produced.
2. `claude --permission-mode plan "$(cat ai-scripts/<phase>.md)"` proposes a
   plan; you read and accept it.
3. Execution happens with the relevant rules auto-loaded (path-scoped) and
   formatting hooks active.
4. `ai-scripts/checks/<phase>/run.sh` runs the automated gate; it must be
   green before the commit.
5. `ai-scripts/checks/<phase>/human.md` is a manual checklist (visual
   inspection, UX, accessibility, anything a script can't see).
6. Commit, with a hand-written conventional message.

The repo's commit log is the cleanest possible answer to "what did AI
actually do?" — every commit is small enough to review, and the messages
say *why* in the body. Look at e.g.
[`598fb9b`](https://github.com/sebastien-attia/psychic-lamp/commit/598fb9b)
or [`5a466dc`](https://github.com/sebastien-attia/psychic-lamp/commit/5a466dc):
those are the corrections, written down so the next AI iteration learns
from them.

## Closing note

Building this app with Claude was faster than building it without. It was
*not* push-button. The biggest leverage came from (a) writing tight,
opinionated prompts that pinned down trade-offs the AI would otherwise
fumble, (b) wiring ArchUnit / Testcontainers / Playwright as merciless
external graders, and (c) reading every diff before committing. The AI is
a junior engineer who types fast and never gets tired; senior judgment is
still required, and that's where the human stays in the loop.
