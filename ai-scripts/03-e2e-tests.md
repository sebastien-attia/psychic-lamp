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

  <role>You are a senior QA engineer writing end-to-end tests with Playwright against a session-based fullstack application.</role>

  <context>
    <project>The Boat App — E2E tests</project>
    <stack-under-test>
      Full local-intg stack (docker compose up), browser-facing surface:
      - Vite (frontend) at http://localhost:5173 — serves the Vue SPA and proxies /api,/oauth2,/login,/logout to the BFF on :8080. THIS is the URL E2E tests open in the browser.
      - BFF at http://localhost:8080 — Spring Cloud Gateway: handles OAuth2 login (callback also lands on :5173 and is proxied through), proxies /api/* to the Business Service with TokenRelay. Does NOT serve the SPA.
      - Business Service at http://localhost:8081 — internal JWT resource server (not exercised directly by E2E)
      - Keycloak at http://localhost:8180 (realm: boat-app, test user: demo/demo123)
      - PostgreSQL at localhost:5432
      Authentication (from the browser): session-based (HttpOnly cookie set by the BFF after Keycloak login; cookie is on the BFF origin and reaches the BFF same-origin via the Vite proxy)
    </stack-under-test>
    <test-runner>Playwright (TypeScript), running against docker compose up</test-runner>
    <auth-model>
      Login: browser navigates to /oauth2/authorization/keycloak (BFF endpoint) → redirected to Keycloak
      → user enters credentials → Keycloak redirects back to the BFF with code
      → BFF exchanges code, creates session (Spring Session JDBC) → browser gets SESSION cookie → redirect to /
      All subsequent /api/* requests use the session cookie automatically. The BFF forwards
      an OAuth2 Bearer access token to the Business Service on each call (invisible to the browser).
    </auth-model>
  </context>

  <instructions>
    <step order="1">
      Set up Playwright in the frontend project:
      ```bash
      cd frontend && npm init playwright@latest -- --lang=ts
      ```
      playwright.config.ts:
      - Base URL: http://localhost:8080 (the single backend service)
      - Browsers: chromium (primary)
      - Timeout: 30s per test
      - Retries: 1 on CI, 0 locally
      - Reporter: html + list
      - Global setup: wait for all services to be healthy
    </step>
    <step order="2">
      Create test helpers (frontend/e2e/helpers/):
      - auth.helper.ts:
        - loginAsDemo(page):
          1. page.goto('/') → should redirect to Keycloak login page
          2. Wait for Keycloak login form to appear
          3. Fill username: 'demo', password: 'demo123'
          4. Click sign-in button
          5. Wait for redirect back to the app (URL contains '/boats')
          6. Verify the boats page is displayed
          This is a REAL browser login through Keycloak — the session cookie is set automatically.
        - logout(page):
          1. Click user menu → click logout
          2. Wait for redirect (Keycloak logout → back to app → redirect to Keycloak login)
        - isLoggedIn(page): check if /api/me returns 200
      - api.helper.ts:
        - For seeding test data, use Playwright's request context with a pre-authenticated session:
          1. Programmatically log in via Keycloak's direct grant (resource owner password)
             to get a session cookie
          2. Use that cookie to POST /api/v1/boats to create test data
        - createBoatViaAPI(requestContext, name, description) → boat
        - deleteAllBoatsViaAPI(requestContext) → void
      - waitForHealthy(): poll http://localhost:8080/actuator/health until UP
    </step>
    <step order="3">
      Create e2e/auth.spec.ts — Authentication flow tests:
      ```typescript
      test('UC1: unauthenticated user is redirected to Keycloak login', ...)
      test('UC1: user can login via Keycloak and reach boat list', ...)
      test('user can logout and is redirected to login', ...)
      test('after logout, accessing /boats redirects to Keycloak again', ...)
      ```
    </step>
    <step order="4">
      Create e2e/boat-list.spec.ts — Boat list tests:
      ```typescript
      test.beforeEach: loginAsDemo + seed 15 boats via API

      test('UC2: displays paginated list of boats', ...)
      test('UC2: pagination controls navigate between pages', ...)
      test('UC2: can change page size', ...)
      test('UC5: search filters boats by name', ...)
      test('UC5: search with no results shows empty state', ...)
      test('empty state shown when no boats exist', ...)

      test.afterEach: delete all boats via API
      ```
    </step>
    <step order="5">
      Create e2e/boat-crud.spec.ts — CRUD operation tests:
      ```typescript
      test('UC3: create a new boat → appears in list', ...)
      test('UC3: create form validates empty name', ...)
      test('UC3: create form validates name too long (>64 chars)', ...)
      test('UC4: view boat detail page', ...)
      test('UC3: edit a boat → changes reflected', ...)
      test('UC3+UC6: delete with confirmation dialog → removed', ...)
      test('UC6: cancel deletion → boat still exists', ...)
      ```
    </step>
    <step order="6">
      Create e2e/accessibility.spec.ts:
      ```typescript
      test('boat list passes axe accessibility checks', ...)
      test('boat form passes axe accessibility checks', ...)
      test('delete dialog passes axe accessibility checks', ...)
      test('keyboard navigation works', ...)
      test('dark mode maintains accessibility', ...)
      ```
      Use @axe-core/playwright.
    </step>
    <step order="7">
      Create e2e/responsive.spec.ts:
      ```typescript
      test('mobile viewport: cards layout', ...)
      test('mobile viewport: navigation hamburger menu', ...)
      test('tablet viewport: form usable', ...)
      ```
    </step>
    <step order="8">
      Create global setup (e2e/global-setup.ts):
      - Poll http://localhost:8080/actuator/health until UP (with backoff, max 60s)
      - Verify Keycloak is accessible at http://localhost:8180
      - Clean up leftover test data
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it checks playwright config, counts
    spec files, runs `npx playwright test`, confirms the HTML report is
    generated, and looks for axe-core accessibility assertions:
    ```bash
    ai-scripts/checks/3/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/3/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "test: E2E tests with Playwright for full user journey

    - Auth: real Keycloak browser login/logout with session cookie
    - Boat list: pagination, search, empty/error states
    - CRUD: create, read, update, delete with confirmation
    - Accessibility: axe-core automated checks
    - Responsive: mobile and tablet viewports
    - Base URL: single backend at http://localhost:8080
    - Test helpers: Keycloak login, API seeding via session"
    ```
  </commit>
</task>
