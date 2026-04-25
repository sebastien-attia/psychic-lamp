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

  <role>You are a senior frontend engineer scaffolding a Vue.js 3 project that uses session-based authentication.</role>

  <context>
    <project>The Boat App — Vue.js 3 frontend SPA</project>
    <contract>contracts/openapi.yaml (read it first to understand the API)</contract>
    <stack>Vue 3 (Composition API), TypeScript, Vite, Headless UI, Tailwind CSS, Pinia, Vue Router 4</stack>
    <auth-model>
      The BFF handles ALL OAuth2 logic. The frontend does NOT do OAuth.
      - Login: navigate to /oauth2/authorization/keycloak (BFF/Spring Security endpoint)
      - The browser receives an HttpOnly SESSION cookie after login (set by BFF)
      - API calls: just call /api/v1/boats — same origin, cookie sent automatically
      - CSRF: BFF sets XSRF-TOKEN cookie, Axios sends X-XSRF-TOKEN header
      - Logout: POST /api/logout
      - User info: GET /api/me (served by BFF)
      NO oidc-client-ts, NO PKCE, NO token storage, NO refresh logic in frontend.
    </auth-model>
    <build>
      Frontend builds to frontend/dist/, which is copied to bff/src/main/resources/static/
      so the BFF serves the SPA. In dev mode, Vite dev server proxies /api directly to Business Service.
      In local-intg mode, Vite dev server proxies /api to the BFF.
    </build>
    <dev-vs-localintg>
      Two Vite modes:
      - dev (default, npm run dev):
          * Backend: Business Service (port 8081) — no BFF, no Keycloak
          * Auth: none (Business Service dev profile has permitAll)
          * Proxy: /api → http://localhost:8081
          * No /oauth2 or /login proxy needed (no OAuth in dev mode)
      - local-intg (npm run dev:intg):
          * Backend: BFF (port 8080) — full OAuth2 + JWT
          * Auth: session cookie from BFF
          * Proxy: /api, /oauth2, /login → http://localhost:8080
    </dev-vs-localintg>
    <scope>only modify files under frontend/</scope>
  </context>

  <instructions>
    <step order="1">Read contracts/openapi.yaml to understand the API data models.</step>
    <step order="2">
      Initialize the Vue project with Vite:
      ```bash
      cd frontend && npm create vite@latest . -- --template vue-ts
      ```
      Install dependencies:
      - @headlessui/vue, @heroicons/vue
      - tailwindcss, @tailwindcss/forms, autoprefixer, postcss
      - pinia, vue-router@4
      - axios
      - vee-validate, @vee-validate/zod, zod
      - vue-i18n@next
      - @openapitools/openapi-generator-cli (devDependency) — same generator family as backend (02a1)
      DO NOT install oidc-client-ts or any OAuth library.
      DO NOT install openapi-typescript-codegen (deprecated upstream — superseded by openapi-generator-cli).
    </step>
    <step order="3">
      Configure Tailwind CSS:
      - dark mode: 'class'
      - @tailwindcss/forms plugin
      - Nautical color palette
    </step>
    <step order="4">
      Generate TypeScript API types + axios client from the OpenAPI spec using the same
      tool family as the backend (openapi-generator-cli, typescript-axios generator).

      Output path: `src/services/api-client/generated/` (the `generated/` suffix is a
      convention mirroring the backend's `dto.generated` / `client.generated` packages —
      same visual cue, same do-not-edit contract). Add `src/services/api-client/generated/`
      to `frontend/.gitignore`.

      npm script (package.json):
      ```json
      "generate:api": "openapi-generator-cli generate -i ../contracts/openapi.yaml -g typescript-axios -o src/services/api-client/generated --additional-properties=supportsES6=true,withSeparateModelsAndApi=true,apiPackage=api,modelPackage=models,withInterfaces=true"
      ```

      Run once after scaffold: `npm run generate:api`. Re-run whenever contracts/openapi.yaml
      changes (or add it as a `predev`/`prebuild` hook so it runs automatically).

      The generator produces an axios client per tag (`BusinessServiceApi`, `UserApi`) plus a
      `models/` folder with all TypeScript interfaces, including UserInfoResponse and
      ValidationErrorResponse introduced for /api/me and 422 responses respectively.
    </step>
    <step order="5">
      Configure Vite with two modes (vite.config.ts):

      ```typescript
      import { defineConfig, loadEnv } from 'vite'
      import vue from '@vitejs/plugin-vue'

      export default defineConfig(({ mode }) => {
        const isLocalIntg = mode === 'local-intg'

        return {
          plugins: [vue()],
          server: {
            proxy: isLocalIntg
              ? {
                  // local-intg: proxy to BFF (port 8080) — full OAuth2 + session
                  '/api': 'http://localhost:8080',
                  '/oauth2': 'http://localhost:8080',
                  '/login': 'http://localhost:8080',
                  '/logout': 'http://localhost:8080',
                }
              : {
                  // dev mode: proxy directly to Business Service (port 8081) — no auth
                  '/api': 'http://localhost:8081',
                  // No /oauth2 or /login proxy: no Keycloak in dev mode
                },
          },
        }
      })
      ```

      package.json scripts:
      ```json
      {
        "scripts": {
          "dev": "npm run generate:api && vite",
          "dev:intg": "npm run generate:api && vite --mode local-intg",
          "build": "npm run generate:api && vue-tsc && vite build",
          "build:intg": "npm run generate:api && vite build --mode local-intg",
          "type-check": "vue-tsc --noEmit",
          "generate:api": "openapi-generator-cli generate -i ../contracts/openapi.yaml -g typescript-axios -o src/services/api-client/generated --additional-properties=supportsES6=true,withSeparateModelsAndApi=true,apiPackage=api,modelPackage=models,withInterfaces=true"
        }
      }
      ```

      Note: `npm run build` (used in BFF Dockerfile) builds the SPA for production.
      The built dist/ is copied into bff/src/main/resources/static/ by the BFF Dockerfile.
    </step>
    <step order="6">
      Configure Axios instance (src/services/http.ts):
      - Base URL: '' (same origin — no VITE_API_BASE_URL needed)
      - withCredentials: true (send session cookie in local-intg/prod modes)
      - xsrfCookieName: 'XSRF-TOKEN' (read CSRF cookie from BFF)
      - xsrfHeaderName: 'X-XSRF-TOKEN' (send CSRF header to BFF)
      - Response interceptor: on 401 → redirect to /oauth2/authorization/keycloak
        (this redirect only makes sense in local-intg/prod where BFF is present;
        in dev mode, 401 should not occur because Business Service has permitAll)
      NO Bearer token interceptor. The session cookie is sent automatically by the browser.
    </step>
    <step order="7">
      Set up Vue Router with these routes:
      - / → redirect to /boats
      - /boats → BoatListPage (requires auth)
      - /boats/new → BoatCreatePage (requires auth)
      - /boats/:id → BoatDetailPage (requires auth)
      - /boats/:id/edit → BoatEditPage (requires auth)
      NO /login page, NO /callback page — login is handled by the BFF redirect.
      Navigation guard: call GET /api/me — if 401, redirect to /oauth2/authorization/keycloak.
      In dev mode GET /api/me returns 200 always (Business Service dev profile, no auth).
    </step>
    <step order="8">
      Set up Pinia stores:
      - authStore: user (from /api/me), isAuthenticated, fetchUser(), logout()
        - fetchUser(): GET /api/me, store result. Called on app init.
        - logout(): POST /api/logout, then window.location = '/'
          (in dev mode, /api/logout returns 200 or 404 — handle gracefully)
      - boatStore: boats, loading, error, fetchBoats(), createBoat(), etc.
      Setup store syntax (not options).
    </step>
    <step order="9">
      Create MainLayout.vue:
      - Nav bar with app name, user info (from authStore.user), dark mode toggle, logout button
      - Responsive: hamburger menu on mobile
      - Headless UI Menu for user dropdown
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it runs `generate:api`, `type-check`,
    `build`, checks no forbidden OAuth libraries / hardcoded localhost URLs /
    missing lockfile, confirms Vite proxy + Axios withCredentials:
    ```bash
    ai-scripts/checks/02b1/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02b1/human.md`.
  </verification>

  <commit>
    ```bash
    git add frontend/
    git commit -m "feat(frontend): scaffold Vue 3 with dual Vite proxy modes (dev vs local-intg)

    - Vite + Vue 3 + TypeScript + Tailwind CSS + Headless UI
    - API types + axios client generated from OpenAPI contract via openapi-generator-cli
      (typescript-axios generator — same tool family as backend codegen; output under
       src/services/api-client/generated/, gitignored, rebuilt on predev/prebuild)
    - Axios: session cookie + CSRF (no Bearer token in frontend)
    - Login via BFF redirect to Keycloak (no OAuth library)
    - Two Vite modes:
        * npm run dev: proxy /api → Business Service :8081 (no BFF, no Keycloak)
        * npm run dev:intg: proxy /api,/oauth2,/login → BFF :8080 (full OAuth2)
    - Vue Router (no /login, no /callback — BFF handles OAuth)
    - Pinia stores: authStore (GET /api/me), boatStore"
    ```
  </commit>
</task>
