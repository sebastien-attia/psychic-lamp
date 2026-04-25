import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { userApi } from '../services/api'
import { http } from '../services/http'
import { ApiProblemError } from '../services/problem-detail'
import type { UserInfoResponse } from '../services/api-client/generated/models'

/**
 * Spring Security OAuth2 client endpoint that initiates the
 * Authorization-Code flow against Keycloak. The BFF handles every step;
 * the SPA only needs to set `window.location` to it.
 */
const LOGIN_REDIRECT_URL = '/oauth2/authorization/keycloak'

/**
 * Pinia store holding the currently authenticated user's profile and
 * driving the SPA's session lifecycle.
 *
 * Authentication is **session-cookie-based**: there are no tokens in
 * the browser. The store offers three operations:
 *
 * - {@link fetchUser} — bootstrap the session from `GET /api/me`.
 * - {@link login} — redirect the browser to the BFF's OAuth2 endpoint.
 * - {@link logout} — `POST /api/logout`, then return to `/`.
 *
 * `loading` starts `true` so the router guard and `App.vue` can wait
 * for the very first `fetchUser()` to settle before deciding what to
 * render. Subsequent guard fires short-circuit on `loading === false`.
 */
export const useAuthStore = defineStore('auth', () => {
  /** Authenticated user profile from `/api/me`, or `null` when anonymous. */
  const user = ref<UserInfoResponse | null>(null)

  /** `true` between app boot and the first `fetchUser()` resolution. */
  const loading = ref(true)

  /** `true` while a `POST /api/logout` request is in flight. */
  const loggingOut = ref(false)

  /**
   * `true` once {@link login} has fired the redirect to Keycloak. The
   * router guard short-circuits on this so a slow browser unload does
   * not let a second guard run kick off another `/api/me` request and
   * loop into another `auth.login()` call.
   */
  const redirecting = ref(false)

  /** Convenience: `true` iff `user` has been populated. */
  const isAuthenticated = computed(() => user.value !== null)

  /**
   * Single in-flight `fetchUser` promise, used to dedupe concurrent
   * callers (e.g. `App.vue`'s `onMounted` racing the router guard).
   */
  let inFlight: Promise<void> | null = null

  /**
   * Fetch the current user from `/api/me`.
   *
   * - On success: populate `user` and clear `loading`.
   * - On 401 (or any other error): set `user = null` so the router
   *   guard can decide to send the browser to Keycloak via
   *   {@link login}. Errors are intentionally swallowed here — the
   *   axios interceptor in `services/http.ts` skips its usual
   *   redirect for the `/api/me` URL specifically so this method is
   *   the sole authority on session presence.
   *
   * Concurrent calls share the same in-flight promise.
   */
  async function fetchUser(): Promise<void> {
    if (inFlight) return inFlight
    inFlight = (async () => {
      loading.value = true
      try {
        const { data } = await userApi.getCurrentUser()
        user.value = data
      } catch (e) {
        user.value = null
        // 401 is the documented "anonymous" signal from the BFF; any
        // other failure (network, 5xx) deserves visibility so an
        // outage does not look like a silent logout. The router guard
        // will still send the user to Keycloak, but the cause is
        // surfaced to the console for ops to spot.
        if (!(e instanceof ApiProblemError && e.status === 401)) {
          console.error('[auth] /api/me failed unexpectedly:', e)
        }
      } finally {
        loading.value = false
        inFlight = null
      }
    })()
    return inFlight
  }

  /**
   * Redirect the browser to Spring Security's OAuth2 client endpoint
   * to start the Authorization-Code flow. The BFF takes over from
   * there: after Keycloak login it returns the user to `/` with a
   * session cookie set.
   *
   * Implemented as a hard navigation (not a router push) because the
   * destination is the BFF, not a SPA route.
   */
  function login(): void {
    if (redirecting.value) return
    redirecting.value = true
    window.location.href = LOGIN_REDIRECT_URL
  }

  /**
   * Sign out the current session by POSTing `/api/logout` (handled by
   * the BFF's Spring Security `CsrfLogoutFilter`) and reloading the
   * SPA root.
   *
   * - 4xx is tolerated — `dev` mode does not run the BFF, so the
   *   endpoint doesn't exist and there is no session to clear anyway.
   * - 5xx is rethrown — silently navigating away from a failed logout
   *   would give the user a false sense of security while the session
   *   lives on.
   *
   * The local `user` ref is cleared up front so the UI reacts even if
   * the page reload is delayed. `loggingOut` is exposed so the
   * UserMenu can render a transient "Signing out…" label.
   */
  async function logout(): Promise<void> {
    loggingOut.value = true
    user.value = null
    try {
      await http.post('/api/logout')
    } catch (e) {
      if (e instanceof ApiProblemError && e.status >= 500) {
        loggingOut.value = false
        throw e
      }
      // 4xx (e.g. 404 in dev mode) is a no-op for the user.
    }
    window.location.assign('/')
  }

  return {
    user,
    loading,
    loggingOut,
    redirecting,
    isAuthenticated,
    fetchUser,
    login,
    logout,
  }
})
