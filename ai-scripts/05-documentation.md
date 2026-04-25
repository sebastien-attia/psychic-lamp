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

  <role>You are a senior engineer writing project documentation for a technical challenge submission.</role>

  <context>
    <project>The Boat App — final documentation</project>
    <audience>Technical reviewers at OWT (Swisscom Digital Technology SA) evaluating code quality, architecture, AI usage, and professionalism.</audience>
    <evaluation-criteria>Code Quality, Architecture, Authentication, Docker Setup, AI Methodology, UX/UI, Testing, Git Hygiene</evaluation-criteria>
    <existing-code>The full application is implemented and tested. Read the codebase before writing docs.</existing-code>
  </context>

  <instructions>
    <step order="1a">
      Create README.md at project root — DEVELOPER audience:
      This file is for engineers setting up, developing, and deploying the application.
      Structure:
      - Title + one-line description + badge (CI status)
      - Architecture diagram (Mermaid or ASCII) showing hexagonal layers
      - Tech stack table (with version numbers: Java 25, Spring Boot 4, Vue 3, PostgreSQL 17, Keycloak 26, etc.)
      - Prerequisites: Docker, Docker Compose, Git (with minimum versions)
      - Local development setup:
          * Full stack (local-intg profile): `docker compose up` → app at http://localhost:8080
          * Dev mode (no Keycloak): `docker compose -f docker-compose.dev.yml up` + `cd frontend && npm run dev`
      - Running tests:
          * Backend: `cd backend && ./mvnw verify` (ArchUnit + integration tests with Testcontainers)
          * E2E: `cd frontend && npx playwright test`
      - API Documentation: http://localhost:8080/swagger-ui.html
      - Deployment: brief overview (Terraform → Azure infra, Ansible → deploy, GitHub Actions → CI/CD)
      - Key design decisions: hexagonal architecture, session-based auth, ServiceResponse validation pattern
      - Link to USER_GUIDE.md for end-user instructions
      Keep it CONCISE — max 150 lines. Don't over-explain. No app usage instructions here.
    </step>
    <step order="1b">
      Create USER_GUIDE.md at project root — END USER audience:
      This file is for people who use the Boat App — not developers.
      Structure:
      - What is the Boat App? (one short paragraph)
      - Getting started:
          * Start the app: `docker compose up`
          * Open browser: http://localhost:8080
      - Default login credentials (Keycloak demo user: demo / demo123)
      - How to use the app:
          * Log in: click Login, enter credentials, redirect to boat list
          * View your boats: paginated list, search bar filters by name or description
          * Add a boat: click "New Boat", fill name (required, max 64 chars), description (optional, max 256 chars)
          * View boat details: click a boat card to see full details
          * Edit a boat: click Edit on detail page, update fields, save
          * Delete a boat: click Delete, confirm in dialog — action is irreversible
          * Log out: click your name in the top-right menu → Logout
      - Concurrent edit handling: if another user edits the same boat while you're editing it,
        you will see a conflict message with a Refresh button
      - Troubleshooting:
          * App not loading → check `docker compose ps` — all services must be healthy
          * Login fails → check Keycloak is running at http://localhost:8180
      Keep it SHORT and non-technical — max 80 lines. No architecture or code references.
    </step>
    <step order="2">
      Create AI_USAGE.md at project root.
      This document is evaluated as seriously as the code. Structure:

      ## AI Tools Used
      - Claude Code (Anthropic) — primary development tool
      - Claude.ai — architecture planning, prompt design

      ## What AI Was Used For
      - Architecture: initial project structure, module decomposition
      - Code generation: Spring Boot scaffolding, Vue components, Terraform modules
      - Testing: integration test structure, E2E test scenarios
      - Infrastructure: Dockerfiles, CI/CD pipelines, Terraform modules
      - Documentation: README structure, this document

      ## Representative Prompts (3-5 verbatim)
      [Include the actual prompts from the ai-scripts/ directory that were most impactful]
      For each prompt, explain:
      - What you asked for
      - What Claude produced
      - What you changed, fixed, or rejected

      ## How AI Output Was Validated
      - All generated code was compiled and tested before committing
      - Integration tests with Testcontainers validate backend behavior
      - E2E tests with Playwright validate full user journey
      - OpenAPI contract tests ensure backend/frontend stay in sync
      - Manual review of every generated file for security issues
      - Specific fixes applied: [list actual corrections made]

      ## What Was NOT Delegated to AI — And Why
      - OpenAPI contract design: requires domain understanding and API design expertise
      - Security configuration review: OWASP compliance requires human judgment
      - Architecture decisions: hexagonal vs layered, technology choices
      - Git history curation: meaningful commit messages require context
      - This document: AI_USAGE.md must reflect genuine experience

      ## Development Workflow
      - API-first: OpenAPI contract defined first, then sequential per-track development
      - Three sequential tracks from the project root: backend (02a*), frontend (02b*), infra (02c*)
      - Claude Code rules: .claude/rules/ for path-scoped conventions
      - Hooks: auto-formatting on every file edit
      - Human checkpoints: every sub-step reviewed before proceeding
    </step>
    <step order="3">
      Verify springdoc-openapi produces Swagger UI:
      - Accessible at http://localhost:8080/swagger-ui.html
      - All endpoints documented
      - Try-it-out works with JWT authentication
    </step>
    <step order="4">
      Final housekeeping:
      - Verify .gitignore covers everything (no secrets, no generated code, no IDE files)
      - Verify .env.example is complete and documented
      - Verify all TODO comments are resolved
      - Verify no console.log or System.out.println left in production code
      - Verify all tests pass: backend, frontend, E2E
      - Run `docker compose up` from scratch and verify everything works
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it checks README.md / AI_USAGE.md
    presence and required sections, greps for leftover TODO/FIXME and
    console.log, confirms OpenAPI reference in docs, and looks for broken
    relative links:
    ```bash
    ai-scripts/checks/5/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/5/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "docs: README (developer), USER_GUIDE (end user), AI_USAGE.md, and final polish

    - README.md: developer guide — architecture, setup, tests, deployment, design decisions
    - USER_GUIDE.md: end-user guide — login, CRUD walkthrough, troubleshooting
    - AI_USAGE.md: tools, prompts, validation methodology
    - Swagger UI verified and accessible
    - Final cleanup: no TODOs, no debug output, no secrets"
    ```
  </commit>
</task>
