import { onScopeDispose, ref, type Ref } from 'vue'

/**
 * Reactive `Date.now()` that ticks every `intervalMs` milliseconds.
 *
 * Used by relative-time captions (e.g. "Created 5 minutes ago" on the
 * boat card) so they re-evaluate while the user has the page open.
 * Without a ticking source, a `computed` that calls `Date.now()` reads
 * the timestamp once at first evaluation and then never refreshes —
 * "5 seconds ago" stays "5 seconds ago" until the next reactive
 * dependency changes.
 *
 * The interval is cleared in `onScopeDispose` so the ticker stops when
 * the surrounding component unmounts. Calling this outside a component
 * setup or an explicit `effectScope` leaks the interval.
 *
 * @param intervalMs how often (in ms) to refresh the ref.
 * @returns a ref whose value is the current epoch ms, refreshed on
 *          every tick.
 */
export function useNow(intervalMs: number): Readonly<Ref<number>> {
  const now = ref(Date.now())
  const handle = setInterval(() => {
    now.value = Date.now()
  }, intervalMs)

  onScopeDispose(() => {
    clearInterval(handle)
  })

  return now
}
