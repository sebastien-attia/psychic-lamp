import type {
  ProblemDetail,
  ValidationMessageResponse,
} from './api-client/generated/models'

/**
 * Typed error wrapping an RFC 9457 `application/problem+json` response body.
 *
 * Thrown by the axios response interceptor in {@link ./http} whenever the
 * server returns a non-2xx response with a `ProblemDetail` body. Consumers
 * (forms, stores) can `instanceof`-check this and read the structured fields
 * directly instead of hand-parsing axios errors.
 */
export class ApiProblemError extends Error {
  /** RFC 9457 `type` URI from the problem-type registry — never `about:blank`. */
  public readonly type: string

  /** Short human-readable summary, stable across occurrences of `type`. */
  public readonly title: string

  /** HTTP status code mirrored from the response. */
  public readonly status: number

  /** Request path that produced this problem, e.g. `/api/v1/boats/{id}`. */
  public readonly instance: string

  /** Optional occurrence-specific explanation (already localized server-side). */
  public readonly detail?: string

  /** Validation findings — populated for 400/422; empty otherwise. */
  public readonly messages: ValidationMessageResponse[]

  /**
   * @param problem the parsed `ProblemDetail` body returned by the API.
   */
  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title)
    this.name = 'ApiProblemError'
    this.type = problem.type
    this.title = problem.title
    this.status = problem.status
    this.instance = problem.instance
    this.detail = problem.detail ?? undefined
    this.messages = problem.messages ?? []
  }

  /**
   * Group `messages[]` by `field` so vee-validate `setErrors()` can consume
   * them. Messages without a `field` (global findings) are excluded —
   * call {@link globalErrors} for those.
   *
   * @returns a record of `field` → `message[]` entries.
   */
  public fieldErrors(): Record<string, string[]> {
    const out: Record<string, string[]> = {}
    for (const m of this.messages) {
      if (!m.field) continue
      ;(out[m.field] ??= []).push(m.message)
    }
    return out
  }

  /**
   * Findings with no `field` — surfaced as a top-of-form banner so they
   * are not invisible to the user (e.g. domain-rule violations like
   * "duplicate boat name in your fleet" returned without a field).
   *
   * @returns the messages that {@link fieldErrors} excludes.
   */
  public globalErrors(): string[] {
    return this.messages.filter((m) => !m.field).map((m) => m.message)
  }
}
