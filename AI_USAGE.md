# AI Usage — A Candid Retrospective

This project was built with heavy AI assistance. Pretending otherwise would
waste a reviewer's time. What follows is an honest account of *which* AI was
used, *for what*, *what was kept human*, and how the AI's first draft was
validated.

## AI tools used

- **Claude Code** (Anthropic CLI, model `claude-opus-4-7[1m]` — Claude Opus 4.7,
  1M context). Primary day-to-day driver. Every new phase opens with
  `--permission-mode plan`; only after the proposed plan was reviewed did
  execution start.
- **OpenAI Codex** (CLI). Occasional second opinion, usually when Claude got
  stuck on a corner case or I wanted to cross-check an architectural call
  with a different model.
- **Claude.ai** (web app). Whiteboard-style architecture discussion before
  any prompt was committed: hexagonal layout, BFF / resource-server boundary,
  `private_key_jwt` vs client-secret trade-off, token-forwarding strategy.

No other AI tools (Copilot, Cursor, etc.) were used.

## What I used AI for

- **Prompt engineering as a meta-step.** Every prompt under
  [`ai-scripts/`](ai-scripts/) was itself drafted with Claude — I described
  the phase's intent, Claude shaped it into a `<role>` / `<context>` /
  `<instructions>` / `<verification>` XML prompt, and I edited the
  trade-offs by hand. The prompts then drive code generation. So the AI
  writes *both* the spec and the code; the human pins the trade-offs.
- **Code generation.** Domain models, ports, use-case implementations, JPA
  mappers, security wiring, Vue 3 components, Pinia stores, Axios
  interceptors, OpenAPI codegen wiring, Liquibase YAML changelogs — Claude
  wrote the code; ArchUnit, Testcontainers and Playwright kept it honest.
- **Architecture and design discussions.** Hexagonal split (one Maven module
  per layer), `private_key_jwt` over client-secret, Spring Session JDBC over
  Redis, `@Version` + ETag/If-Match for optimistic locking, custom Keycloak
  image vs config-only — every call argued against AI before being committed
  to a prompt.
- **CI/CD pipelines.** GitHub Actions workflows, OIDC federation to Azure,
  cosign+SLSA image signing, Trivy / CodeQL / gitleaks gates, branch
  protection as code, Dependency-Track gating.
- **Tooling development.** `ai-scripts/run-phase.sh`, the per-phase check
  scripts under `ai-scripts/checks/`, `00b-generate-bff-key.sh`,
  `00d-bootstrap-azure.sh`, `00e-create-staging-branch.sh`,
  `00f-generate-db-secrets.sh`, the `.claude/settings.json` PostToolUse
  hooks. The harness around the AI is itself written with the AI.
- **Documentation.** [`README.md`](README.md), [`CONCEPTS.md`](CONCEPTS.md),
  [`DEPLOYMENT.md`](DEPLOYMENT.md), [`USER_GUIDE.md`](USER_GUIDE.md), the
  per-phase `human.md` checkpoints, this file's first draft.
- **Learning.** Spring Boot 4 / Java 25 idioms, Keycloak 26 client
  authentication modes, Azure Container Apps + Terraform modules, OSV-Scanner
  thresholds, SLSA L3 specifics — Claude as an interactive textbook, with
  the docs cross-checked.

## Representative prompts (verbatim)

Five excerpts pulled byte-for-byte from `ai-scripts/`. The full prompts (plus
surrounding rules and step-by-step instructions) live in
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

### 2 · `ai-scripts/02a2-backend-domain.md` — pure-Java domain layer

```
<role>You are a senior Java architect implementing a strict hexagonal domain layer where the domain is 100% framework-free.</role>

<hexagonal-rule>
  CRITICAL: domain.model, application.port.in, application.port.out, application.service
  must contain ZERO Spring/Jakarta annotations. Only java.* and domain.* imports.
  JPA annotations (@Entity, @Column, @Version, @Table) belong ONLY in
  adapter.out.persistence.entity — NOT in domain.model.
  The domain model and JPA entity are SEPARATE classes, mapped by hand-written
  @Component classes. Domain models are immutable records — no setters; updates
  rebuild a fresh record via the canonical constructor. NO MapStruct, NO
  annotation processor for mapping.
</hexagonal-rule>
```

### 3 · `ai-scripts/02a4-backend-auth.md` — split authentication

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
[`ai-scripts/02c1-docker.md`](ai-scripts/02c1-docker.md)) pins client
authentication to `private_key_jwt`:

```yaml
clientAuthenticatorType: client-jwt
attributes:
  use.jwks.url: "true"
  jwks.url: "${BFF_JWKS_URL}"
  token.endpoint.auth.signing.alg: RS256
```

### 4 · `ai-scripts/02a6-backend-security.md` — supply-chain build gates

```
<role>You are a senior application-security engineer wiring supply-chain gates (SCA, SAST, SBOM, DT upload) into two Maven modules.</role>

<tools>
  - SCA: Google OSV-Scanner CLI v2.3.5 — invoked from CI via
    google/osv-scanner-action, plus an optional exec-maven-plugin profile
    for local runs. HIGH + CRITICAL block CI; MEDIUM is reported.
  - SAST: spotbugs-maven-plugin:4.9.8.3 + findsecbugs-plugin:1.14.0
    (SECURITY-only filter, excludes **/generated/**).
  - SBOM + Governance: cyclonedx-maven-plugin:2.9.1 → bom.json on package;
    dependency-track-maven-plugin:1.11.0 uploads on deploy
    (skip=true by default; CI overrides -Ddtrack.skip=false).
</tools>
```

### 5 · `ai-scripts/04-cicd.md` — staging-vs-production deploy split

```
<role>You are a senior DevOps engineer creating a CI/CD pipeline with GitHub Actions deploying to Azure.</role>

<deployment-strategy>
  TWO environments with DIFFERENT triggers:

  1. STAGING environment:
     - Triggered AUTOMATICALLY when code is pushed/merged to the "staging" branch
     - No manual approval needed
     - Full pipeline: build → test → docker → push → deploy → e2e

  2. PRODUCTION environment:
     - Triggered MANUALLY by the user creating a GitHub Release on the "main" branch
     - Requires manual approval via GitHub Environment protection rules
     - Tags images with the release version (e.g., v1.0.0)
</deployment-strategy>
```

## How AI output was validated

The single biggest leverage point was **splitting the work into small,
checkpointed phases** instead of asking for the whole app at once. Every
phase under [`ai-scripts/`](ai-scripts/) is a self-contained slice with a
plan, an automated check script, and a human checklist; nothing moves
forward until the slice is green.

Concretely, every phase ran through the same gauntlet:

1. **Plan first.** `claude --permission-mode plan` proposed the file changes
   read-only; I read and approved before any write.
2. **Path-scoped rules** in [`.claude/rules/`](.claude/rules/) pinned
   conventions per area (BFF Java, Business Service Java, frontend Vue,
   infrastructure, OpenAPI contract, validation/errors, testing, git).
3. **PostToolUse hooks** in [`.claude/settings.json`](.claude/settings.json)
   auto-format on every edit (`spotless:apply` for Java, `prettier --write`
   for TS/Vue).
4. **`@code-reviewer` subagent** invoked after every code-touching turn,
   per [`CLAUDE.md`](CLAUDE.md) policy. Findings graded **must-fix**,
   **should-fix**, **consider**; must-fix applied in the same turn.
5. **ArchUnit + Maven module graph** fail the build the moment `domain.*`
   imports anything from Spring or Jakarta. This caught more
   AI-introduced layering mistakes than any human review would have.
6. **Testcontainers** spins up real PostgreSQL for integration tests; BFF
   tests also run a real Keycloak. No mocks for the external systems that
   matter.
7. **Playwright** drives the full local-intg stack end-to-end through the
   real BFF login flow.
8. **Per-phase verification scripts** in
   [`ai-scripts/checks/`](ai-scripts/checks/) (one `run.sh` + one
   `human.md` per phase) gated each commit with both automated checks
   (`pass`/`warn`/`fail` severity, `fail` aborts the phase unless
   `FORCE=1`) and a human-eyes-on checklist.

When the AI got something wrong, the fix was rarely "redo the prompt" —
it was usually to *add a build-level gate so the failure can't return*.
Two examples that ended up shaping the repo:

- **`private_key_jwt` over client_secret.** First draft of
  `ai-scripts/02a4-backend-auth.md` accepted `clientAuthenticatorType:
  client-secret`. I rewrote it to insist on `private_key_jwt` (OIDC Core §9
  / RFC 7523), generated an RSA key with
  [`ai-scripts/00b-generate-bff-key.sh`](ai-scripts/00b-generate-bff-key.sh),
  and exposed a `JwksController` so Keycloak fetches the public half on
  demand. There is no client secret anywhere in this project.
- **JRE-in-Node Docker stage.** The first BFF Dockerfile ran
  `npm run build` from `node:22-alpine`, which fails because
  `openapi-generator-cli` shells out to `java`. Fix: split codegen into a
  dedicated JDK stage *and* short-circuit `frontend/package.json`'s
  `prebuild` hook when the generated client already exists, so a careless
  edit can't quietly bring back the failure.

## What was NOT delegated to AI

I deliberately kept the hand on **architecture, design, and — when the
trade-off was non-obvious — implementation**. AI is excellent at filling
in shape once the shape is decided; it is not the right place to *make*
the decision.

- **Architecture & design choices.** Hexagonal split with one Maven module
  per layer; `private_key_jwt` over client-secret; Spring Session JDBC over
  Redis; stateless JWT validation on the resource server with no session;
  CSRF cookie strategy; per-DB Postgres roles with `PUBLIC CONNECT` revoked;
  custom Keycloak image with a baked-in theme vs config-only. Each is a
  human-made trade-off, defended in [`.claude/rules/`](.claude/rules/) so it
  propagates into every subsequent prompt.
- **OpenAPI contract design.** Endpoint shape, ETag/If-Match strategy,
  RFC 9457 ProblemDetail with a documented problem-type URI registry —
  AI helped *transcribe* into YAML, the design came from sketches in
  Claude.ai and human judgment.
- **Some implementation when stakes were high.** Anywhere security or data
  integrity bites — JWKS controller, BFF token-forwarding interceptor,
  Liquibase per-DB role grants, the production deploy workflow guardrails —
  I read line-by-line and rewrote what didn't sit right, rather than
  rubber-stamping the AI's first pass.
- **Azure subscription bootstrap.** OIDC federation, the
  `00d-bootstrap-azure.sh` one-off, GitHub Environment protection rules,
  `DTRACK_*` secrets — done by hand, never written into AI prompts.
- **Production releases.** `deploy-production.yml` requires manual approval
  on a protected GitHub Environment. AI never has the keys.
- **Git commit messages.** Conventional-commits style (see
  [`.claude/rules/git-conventions.md`](.claude/rules/git-conventions.md)),
  hand-written, with the *why* in the body. Atomic, scoped, and the log
  tells a story.

## Closing note

Building this app with Claude (and the occasional Codex cross-check) was
faster than building it without. It was *not* push-button. The biggest
leverage came from (a) **splitting the work into small, checkpointed
phases** so every AI output is reviewed before the next one builds on it,
(b) writing tight, opinionated prompts that pin down trade-offs the AI
would otherwise fumble, (c) wiring ArchUnit / Testcontainers / Playwright
as merciless external graders, and (d) reading every diff before
committing. The AI is a fast, tireless junior engineer; senior judgment is
still required, and that's where the human stays in the loop.
