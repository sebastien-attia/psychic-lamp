/**
 * Tiny `document.cookie` helpers for the SPA's user-preference cookies
 * (`boatapp.theme`, `boatapp.locale`).
 *
 * These cookies are non-HttpOnly, set from JavaScript, and never sent to
 * the backend — they exist only so the inline `<head>` boot script and
 * the Vue runtime can persist a UI choice across reloads. The CSRF
 * cookie (`XSRF-TOKEN`) and session cookie (`SESSION`) are managed by
 * the BFF and are unaffected by these helpers.
 */

/**
 * One year in seconds — the default `max-age` for a preference cookie.
 * Long enough that the user does not have to re-pick after every
 * browser cache eviction; short enough that an abandoned device
 * eventually drops the value.
 */
export const ONE_YEAR_SECONDS = 60 * 60 * 24 * 365

/**
 * Allowed shape for cookie names. Restricted to the safe ASCII set
 * (RFC 6265 token characters) so callers cannot smuggle `=` or `;`
 * into `document.cookie` and silently corrupt the cookie string.
 */
const VALID_NAME = /^[a-zA-Z0-9._-]+$/

/**
 * Throw a descriptive `TypeError` when a cookie name fails
 * {@link VALID_NAME}. Centralises the validation so {@link getCookie}
 * and {@link setCookie} stay symmetrical.
 */
function assertValidName(name: string): void {
  if (!VALID_NAME.test(name)) {
    throw new TypeError(
      `Invalid cookie name "${name}" — must match /^[a-zA-Z0-9._-]+$/`,
    )
  }
}

/**
 * Read a cookie's value, or `null` if not present.
 *
 * @param name cookie name (case-sensitive). Must match
 *             `/^[a-zA-Z0-9._-]+$/` — throws otherwise.
 * @returns the decoded cookie value, or `null` if missing.
 */
export function getCookie(name: string): string | null {
  assertValidName(name)
  const match = document.cookie.match(
    new RegExp('(?:^|; )' + escapeRegex(name) + '=([^;]*)'),
  )
  return match ? decodeURIComponent(match[1]) : null
}

/**
 * Write a cookie with sensible defaults for SPA preferences:
 * `path=/`, `SameSite=Lax`, and a one-year `max-age`. The `Secure`
 * attribute is set automatically when the page is served over HTTPS so
 * the cookie is not stripped on staging/production but local `http://`
 * dev still works.
 *
 * @param name  cookie name.
 * @param value cookie value (will be URL-encoded).
 * @param maxAgeSeconds optional `max-age` override; defaults to one year.
 */
export function setCookie(
  name: string,
  value: string,
  maxAgeSeconds: number = ONE_YEAR_SECONDS,
): void {
  assertValidName(name)
  const secure =
    typeof window !== 'undefined' && window.location.protocol === 'https:'
      ? '; Secure'
      : ''
  document.cookie =
    `${name}=${encodeURIComponent(value)}` +
    `; path=/; max-age=${maxAgeSeconds}; SameSite=Lax${secure}`
}

/**
 * Escape a string for safe interpolation into a `RegExp` source.
 * Only used internally by {@link getCookie}.
 */
function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
