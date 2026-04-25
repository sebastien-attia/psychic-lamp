import axios, { AxiosError, type AxiosInstance } from 'axios'
import { ApiProblemError } from './problem-detail'
import { isProblemDetail } from '../types/problem-detail'
import { pushToast } from '../composables/useToast'
import { i18n } from '../locales'

/**
 * URL the frontend redirects to when a non-bootstrap call returns 401.
 * Spring Security on the BFF then drives the OAuth2 Authorization-Code
 * flow against Keycloak and returns the user to the SPA root with a
 * session cookie set.
 *
 * In `dev` mode (no BFF, no Keycloak) the Business Service has
 * `permitAll` so 401s do not occur; this constant is therefore
 * unreachable in dev.
 */
const LOGIN_REDIRECT_URL = '/oauth2/authorization/keycloak'

/**
 * Endpoints whose 401 must NOT trigger an immediate redirect-and-toast.
 *
 * - `/api/me`: the auth store calls it once at app start; an anon 401
 *   there is the *expected* way it learns the user is signed out, so
 *   the router guard — not the interceptor — owns the redirect.
 * - `/api/logout`: a 401 on logout means the session is already gone
 *   server-side; pushing a "session expired" toast at that moment is
 *   confusing (the user just clicked Sign out).
 *
 * Compared as path suffixes (after stripping any query string) to
 * stay robust against axios setting `config.url` to either a path or
 * a fully-qualified URL depending on the calling code.
 */
const SILENT_401_PATHS = ['/api/me', '/api/logout'] as const

/**
 * Delay (ms) between pushing the "session expired" toast and
 * navigating to Keycloak, so the user actually sees the message
 * before the page unloads.
 */
const REDIRECT_DELAY_MS = 1_500

/**
 * Module-scoped guard preventing several concurrent 401 responses
 * from each scheduling their own redirect (and toasting on top of
 * each other).
 */
let redirecting = false

/**
 * Shared axios instance for every API call from the SPA.
 *
 * - `baseURL: ''` — same-origin requests only; the Vite dev proxy (or, in
 *   production, the BFF static handler) routes `/api/**` to the backend.
 * - `withCredentials: true` — required to send the `SESSION` cookie
 *   set by the BFF after OAuth2 login (Spring Session JDBC names the
 *   cookie `SESSION`, not `JSESSIONID`).
 * - `xsrfCookieName` / `xsrfHeaderName` — make axios echo the BFF's CSRF
 *   cookie back as a header on mutating requests, matching Spring Security's
 *   `CookieCsrfTokenRepository` defaults.
 */
export const http: AxiosInstance = axios.create({
  baseURL: '',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: {
    'Accept': 'application/json, application/problem+json',
  },
})

/**
 * Build a synthetic `ApiProblemError` for a 401 response. Lets callers
 * (notably `fetchUser` and the router guard) `instanceof`-check
 * against `ApiProblemError` instead of inspecting raw axios errors.
 *
 * @param instance the request URL that produced the 401.
 */
function authRequiredProblem(instance: string): ApiProblemError {
  return new ApiProblemError({
    type: 'https://boatapp.owt.ch/problems/auth-required',
    title: 'auth-required',
    status: 401,
    instance,
    messages: [],
  })
}

/**
 * Response interceptor that normalises auth and contract errors.
 *
 * 1. **HTTP 401 on a {@link SILENT_401_PATHS} endpoint** — pass
 *    through as a typed `ApiProblemError`. The auth store swallows
 *    `/api/me` 401s (router guard then calls `auth.login()`);
 *    `/api/logout` 401s mean the session is already gone and need
 *    no UX.
 * 2. **HTTP 401 on any other URL** — session has expired mid-session.
 *    Push a "Your session has expired" toast, then redirect to
 *    Keycloak. The promise still rejects so `await` chains settle.
 * 3. **Non-2xx with `application/problem+json` body** — reject with a
 *    typed {@link ApiProblemError} so callers can read `messages[]`.
 * 4. **Anything else** — forward the axios error unchanged.
 */
http.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const status = error.response?.status
    const rawUrl = error.config?.url ?? ''
    const path = rawUrl.split('?')[0]

    if (status === 401) {
      if (SILENT_401_PATHS.some((p) => path.endsWith(p))) {
        return Promise.reject(authRequiredProblem(rawUrl))
      }
      if (!redirecting) {
        redirecting = true
        pushToast({
          kind: 'error',
          message: i18n.global.t('auth.sessionExpired'),
        })
        setTimeout(
          () => window.location.assign(LOGIN_REDIRECT_URL),
          REDIRECT_DELAY_MS,
        )
      }
      return Promise.reject(authRequiredProblem(rawUrl))
    }

    const body = error.response?.data
    if (isProblemDetail(body)) {
      return Promise.reject(new ApiProblemError(body))
    }
    return Promise.reject(error)
  },
)
