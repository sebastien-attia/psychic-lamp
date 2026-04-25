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

  <role>You are a senior frontend engineer implementing the authentication UX for a session-based app.</role>

  <context>
    <project>The Boat App — frontend authentication UX</project>
    <existing-code>Vue scaffold exists from Step 2B.1. Read authStore, router, Axios config.</existing-code>
    <auth-model>
      The backend handles ALL OAuth2 logic. The frontend just:
      1. Checks if the user is authenticated by calling GET /api/me
      2. If not: redirect to /oauth2/authorization/keycloak (backend's OAuth2 login)
      3. After Keycloak login, backend redirects to / with a valid session
      4. Logout: POST /api/logout → backend destroys session + Keycloak SSO logout
      NO tokens in the browser. The browser sends the SESSION cookie automatically.
    </auth-model>
    <scope>only modify files under frontend/src/</scope>
  </context>

  <instructions>
    <step order="1">
      Implement authStore fully (src/stores/authStore.ts):
      ```typescript
      // Setup store syntax
      export const useAuthStore = defineStore('auth', () => {
        const user = ref<UserInfoResponse | null>(null)
        const isAuthenticated = computed(() => user.value !== null)
        const loading = ref(true)

        async function fetchUser() {
          try {
            loading.value = true
            const response = await http.get('/api/me')
            user.value = response.data
          } catch (e) {
            user.value = null
          } finally {
            loading.value = false
          }
        }

        function login() {
          // Redirect to Spring Security's OAuth2 login endpoint
          window.location.href = '/oauth2/authorization/keycloak'
        }

        async function logout() {
          try {
            await http.post('/api/logout')
          } finally {
            user.value = null
            window.location.href = '/'
          }
        }

        return { user, isAuthenticated, loading, fetchUser, login, logout }
      })
      ```
    </step>
    <step order="2">
      Implement App.vue initialization:
      - On app mount, call authStore.fetchUser()
      - While loading, show a full-screen loading spinner/skeleton
      - If not authenticated after load, the router guard handles redirect
    </step>
    <step order="3">
      Implement Vue Router navigation guard:
      ```typescript
      router.beforeEach(async (to, from) => {
        const authStore = useAuthStore()

        // Wait for initial auth check if not done yet
        if (authStore.loading) {
          await authStore.fetchUser()
        }

        // If not authenticated, redirect to Keycloak login
        if (!authStore.isAuthenticated) {
          authStore.login()
          return false // prevent navigation until redirect completes
        }
      })
      ```
      All routes require auth (no public pages in the SPA itself).
    </step>
    <step order="4">
      Implement logout in MainLayout.vue:
      - Logout button in the user dropdown
      - Calls authStore.logout()
      - Shows brief "Logging out..." state
    </step>
    <step order="5">
      Handle session expiry gracefully:
      - Axios 401 interceptor: if GET/POST/PUT/DELETE returns 401,
        show a toast "Your session has expired" and redirect to login
      - Don't redirect on the initial /api/me check (that's expected to fail)
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it runs type-check + build, confirms
    authStore/fetchUser/login/logout wiring, /api/me and 401 handling, and
    that tokens are NOT persisted in localStorage/sessionStorage:
    ```bash
    ai-scripts/checks/02b2/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02b2/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "feat(frontend): session-based auth UX (no OAuth in frontend)

    - authStore: fetchUser via /api/me, login redirect to backend, logout via /api/logout
    - Router guard: redirect to Keycloak if not authenticated
    - App init: check auth on mount, loading state
    - Session expiry: 401 interceptor with toast + redirect
    - Zero OAuth libraries, zero token management"
    ```
  </commit>
</task>
