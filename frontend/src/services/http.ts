import axios, { AxiosError, type AxiosInstance } from 'axios'
import { ApiProblemError } from './problem-detail'
import { isProblemDetail } from '../types/problem-detail'

/**
 * URL the frontend redirects to when the BFF returns 401. Spring Security on
 * the BFF then drives the OAuth2 Authorization-Code flow against Keycloak and
 * returns the user to the SPA root with a session cookie set.
 *
 * In `dev` mode (no BFF, no Keycloak) the Business Service has `permitAll`
 * so 401s do not occur; this constant is therefore unreachable in dev.
 */
const LOGIN_REDIRECT_URL = '/oauth2/authorization/keycloak'

/**
 * Shared axios instance for every API call from the SPA.
 *
 * - `baseURL: ''` — same-origin requests only; the Vite dev proxy (or, in
 *   production, the BFF static handler) routes `/api/**` to the backend.
 * - `withCredentials: true` — required to send the `JSESSIONID` session
 *   cookie set by the BFF after OAuth2 login.
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
 * Synthetic `ProblemDetail` raised on 401 so callers (notably the router
 * `beforeEach` guard) get a typed rejection they can `instanceof`-check
 * against `ApiProblemError`. The browser navigation to Keycloak is already
 * in flight when this error is thrown — settling the promise prevents
 * upstream `Promise.all` / `await` chains from hanging if the redirect is
 * delayed (slow network, popup blocker).
 */
function authRequiredProblem(instance: string) {
  return new ApiProblemError({
    type: 'https://boatapp.owt.ch/problems/auth-required',
    title: 'auth-required',
    status: 401,
    instance,
    messages: [],
  })
}

/**
 * Response interceptor that:
 *
 * 1. On HTTP 401 — triggers a browser redirect to the BFF OAuth2 login
 *    endpoint AND rejects with a synthetic auth-required `ApiProblemError`
 *    so `await` chains settle deterministically.
 * 2. On any non-2xx with an `application/problem+json` body — rejects with
 *    a typed {@link ApiProblemError} so callers can read `messages[]`.
 * 3. Otherwise — forwards the axios error unchanged.
 */
http.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const status = error.response?.status
    if (status === 401) {
      window.location.assign(LOGIN_REDIRECT_URL)
      return Promise.reject(authRequiredProblem(error.config?.url ?? ''))
    }

    const body = error.response?.data
    if (isProblemDetail(body)) {
      return Promise.reject(new ApiProblemError(body))
    }
    return Promise.reject(error)
  },
)
