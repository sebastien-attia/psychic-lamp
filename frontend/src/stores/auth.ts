import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { userApi } from '../services/api'
import { http } from '../services/http'
import { ApiProblemError } from '../services/problem-detail'
import type { UserInfoResponse } from '../services/api-client/generated/models'

/**
 * Pinia store holding the currently authenticated user's profile.
 *
 * The user is fetched once at app startup via `GET /api/me`. In `dev` mode
 * the Vite proxy short-circuits this call to a dummy payload, so the store
 * is never empty in any deployed environment except briefly during boot.
 */
export const useAuthStore = defineStore('auth', () => {
  /** Authenticated user profile from `/api/me`, or `null` before first fetch. */
  const user = ref<UserInfoResponse | null>(null)

  /** True once `fetchMe()` has populated `user`. */
  const isAuthenticated = computed(() => user.value !== null)

  /**
   * Fetch the current user from `/api/me`. Called once by the router's
   * `beforeEach` guard on first navigation. On 401 the axios interceptor
   * triggers a redirect to Keycloak — this method's promise then never
   * resolves, which is intentional.
   */
  async function fetchMe(): Promise<void> {
    const { data } = await userApi.getCurrentUser()
    user.value = data
  }

  /**
   * Sign out the current session by POSTing `/api/logout` (handled by the
   * BFF's Spring Security `CsrfLogoutFilter`) and reloading the SPA root.
   *
   * - 404 is tolerated — `dev` mode does not run the BFF, so the endpoint
   *   doesn't exist and there is no session to clear anyway.
   * - 5xx is rethrown — silently navigating away from a failed logout would
   *   give the user a false sense of security while the session lives on.
   *
   * The local `user` ref is cleared before the network call so the UI
   * reacts even if the page reload is delayed.
   */
  async function logout(): Promise<void> {
    user.value = null
    try {
      await http.post('/api/logout')
    } catch (e) {
      if (e instanceof ApiProblemError && e.status >= 500) {
        throw e
      }
      // 4xx (e.g. 404 in dev mode) is a no-op for the user.
    }
    window.location.assign('/')
  }

  return { user, isAuthenticated, fetchMe, logout }
})
