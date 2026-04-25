import { onScopeDispose, ref, watch, type Ref } from 'vue'

/**
 * Returns a read-only ref that mirrors `source` after `delayMs` of
 * inactivity, plus a `flush` function to synchronously realign the
 * lagging ref with the source.
 *
 * Used by the boat list page to debounce free-text search input —
 * binding the immediate `<input>` model to `source` and watching the
 * returned debounced ref to drive URL/state changes.
 *
 * The pending timer is cleared in `onScopeDispose` so a debounced write
 * never lands on an unmounted component (which would otherwise mutate
 * detached state and trigger Vue warnings during navigation). For this
 * to work, the composable must be called inside a component setup or
 * an explicit `effectScope` — calling it outside any scope leaks the
 * timer.
 *
 * The debounced ref is initialised to the current `source.value` rather
 * than `undefined`; otherwise the first render would see an empty
 * debounced value and any `watch(..., { immediate: true })` consumer
 * would fire a no-op fetch before the real value lands `delayMs` later.
 *
 * `flush` cancels any pending update and immediately copies the
 * current `source.value` into the debounced ref. Necessary when an
 * external reset (URL navigation, programmatic clear) has already
 * moved the source and a queued debounced write would otherwise
 * stomp on it after `delayMs`.
 *
 * @param source   the source ref to mirror.
 * @param delayMs  delay in milliseconds between the last write to
 *                 `source` and the resulting write to the returned ref.
 * @returns a tuple `[debounced, flush]` — the read-only debounced ref
 *          and a function that synchronously realigns it with the
 *          source.
 */
export function useDebouncedRef<T>(
  source: Ref<T>,
  delayMs: number,
): [Readonly<Ref<T>>, () => void] {
  const debounced = ref(source.value) as Ref<T>
  let timer: ReturnType<typeof setTimeout> | null = null

  function clearTimer(): void {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  const stop = watch(source, (next) => {
    clearTimer()
    timer = setTimeout(() => {
      debounced.value = next
      timer = null
    }, delayMs)
  })

  onScopeDispose(() => {
    clearTimer()
    stop()
  })

  /**
   * Cancel any pending debounced write and immediately mirror the
   * source's current value into the lagging ref.
   */
  function flush(): void {
    clearTimer()
    debounced.value = source.value
  }

  return [debounced, flush]
}
