import { ref, type Ref } from 'vue'

/**
 * A single transient notification rendered by `Toast.vue`.
 *
 * The shape is deliberately minimal: callers describe *what* to show,
 * not *how* — the renderer owns positioning, theming, and the
 * auto-dismiss timer.
 */
export interface ToastEntry {
  /** Monotonically increasing id used as the `<TransitionGroup>` key. */
  id: number
  /** Severity hint that drives the colour scheme in `Toast.vue`. */
  kind: 'info' | 'error'
  /** Already-localized text shown to the user. */
  message: string
}

/**
 * Default time (ms) a toast stays on screen before auto-dismissing.
 *
 * Four seconds is long enough to read a single sentence aloud, short
 * enough not to obscure subsequent user actions. Tuned to the phase
 * 02b4 spec.
 */
const DEFAULT_TTL_MS = 4_000

let nextId = 1
const queue = ref<ToastEntry[]>([])
const timers = new Map<number, ReturnType<typeof setTimeout>>()

/**
 * Reactive accessor + dispatchers for the in-house toast queue.
 *
 * **Global, not per-component**: state lives in module scope so the
 * axios interceptor, stores, and any component see the same queue
 * without prop drilling. The `useX` naming follows Vue ecosystem
 * convention but, unlike a typical composable, this returns shared
 * state — there is no isolated reactive scope per call.
 *
 * The `Toast.vue` component is the single renderer and must be
 * mounted exactly once (in `App.vue`).
 *
 * @returns the reactive queue plus `pushToast` / `dismissToast` helpers.
 */
export function useToast(): {
  toasts: Ref<ToastEntry[]>
  pushToast: (toast: Omit<ToastEntry, 'id'>, ttlMs?: number) => number
  dismissToast: (id: number) => void
  showSuccess: (message: string, ttlMs?: number) => number
  showError: (message: string, ttlMs?: number) => number
} {
  return { toasts: queue, pushToast, dismissToast, showSuccess, showError }
}

/**
 * Convenience wrapper around {@link pushToast} for success / info
 * notifications. Uses `kind: 'info'` because the toast palette only
 * distinguishes neutral from error — a green-themed success variant
 * would require a renderer change first.
 *
 * @param message already-localized text shown to the user.
 * @param ttlMs   optional override for the auto-dismiss delay.
 * @returns the assigned toast id.
 */
export function showSuccess(message: string, ttlMs?: number): number {
  return pushToast({ kind: 'info', message }, ttlMs)
}

/**
 * Convenience wrapper around {@link pushToast} for error
 * notifications.
 *
 * @param message already-localized text shown to the user.
 * @param ttlMs   optional override for the auto-dismiss delay.
 * @returns the assigned toast id.
 */
export function showError(message: string, ttlMs?: number): number {
  return pushToast({ kind: 'error', message }, ttlMs)
}

/**
 * Append a toast to the queue and schedule its auto-dismissal.
 *
 * @param toast severity + already-localized message.
 * @param ttlMs time before auto-dismiss; defaults to {@link DEFAULT_TTL_MS}.
 * @returns the assigned id, useful when callers want to dismiss early.
 */
export function pushToast(
  toast: Omit<ToastEntry, 'id'>,
  ttlMs: number = DEFAULT_TTL_MS,
): number {
  const id = nextId++
  queue.value.push({ id, ...toast })
  if (ttlMs > 0) {
    timers.set(id, setTimeout(() => dismissToast(id), ttlMs))
  }
  return id
}

/**
 * Remove a toast from the queue and clear its auto-dismiss timer if
 * still pending. No-op if the id is unknown (auto-dismiss races with
 * a manual click).
 *
 * @param id the id returned by {@link pushToast}.
 */
export function dismissToast(id: number): void {
  const timer = timers.get(id)
  if (timer !== undefined) {
    clearTimeout(timer)
    timers.delete(id)
  }
  const idx = queue.value.findIndex((t) => t.id === id)
  if (idx >= 0) queue.value.splice(idx, 1)
}
