import type { ProblemDetail } from '../services/api-client/generated/models'

/**
 * Type guard returning `true` if the given value is shaped like an RFC 9457
 * `ProblemDetail`. Used by the axios interceptor to decide whether to wrap
 * a failed response in {@link ApiProblemError}.
 *
 * Always import the `ProblemDetail` type itself directly from
 * `services/api-client/generated/models` — this module exposes only the
 * type guard, deliberately, to avoid two import paths for one type.
 *
 * @param value arbitrary response body parsed by axios
 * @returns true if `value` has the four required `ProblemDetail` fields
 */
export function isProblemDetail(value: unknown): value is ProblemDetail {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    typeof v.type === 'string' &&
    typeof v.title === 'string' &&
    typeof v.status === 'number' &&
    typeof v.instance === 'string'
  )
}
