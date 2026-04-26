import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * Cross-component plumbing for keyboard shortcuts that need to act on
 * a target component (currently just "focus the search input on the
 * boats list page"). The `MainLayout` registers the global listener
 * but does not own the search input — `BoatListPage` does — so the
 * list page registers a callback here on mount, and the layout
 * invokes it when `/` fires.
 *
 * Why a store rather than provide/inject: the layout sits *above* the
 * route in the tree, so it cannot inject anything the route provides.
 * A module-scoped store solves the inverted dependency without
 * threading a prop through every page.
 */
export const useShortcutsStore = defineStore('shortcuts', () => {
  /**
   * Page-supplied callback that focuses the search input. `null` when
   * no page has registered one; the layout no-ops in that case so the
   * shortcut feels inert on pages without a search field.
   */
  const focusSearch = ref<(() => void) | null>(null)

  /**
   * Visibility of the shortcuts cheatsheet dialog. The layout owns the
   * dialog but the `?` shortcut may be triggered from anywhere, so the
   * flag lives in the store.
   */
  const cheatsheetOpen = ref(false)

  /**
   * Register a callback that focuses the page's search input and
   * returns the teardown function the caller MUST invoke on unmount.
   *
   * Returning the teardown (rather than exposing a separate
   * `clearFocusSearch`) keeps registration paired with cleanup in a
   * single closure: the caller can `onBeforeUnmount(off)` without
   * remembering which method to call, and a second registration from
   * a different page only clears its own callback — never the
   * incumbent one — so two pages racing each other can't strand the
   * store in an inconsistent state.
   *
   * @param fn the page-supplied focus callback.
   * @returns a teardown function that drops `fn` if (and only if) it
   *          is still the registered callback.
   */
  function setFocusSearch(fn: () => void): () => void {
    focusSearch.value = fn
    return () => {
      if (focusSearch.value === fn) {
        focusSearch.value = null
      }
    }
  }

  /**
   * Invoke the registered focus-search callback if any; no-op
   * otherwise. Safe to call from the global keydown listener even on
   * pages without a search input.
   */
  function triggerFocusSearch(): void {
    focusSearch.value?.()
  }

  /** Open the cheatsheet dialog. */
  function openCheatsheet(): void {
    cheatsheetOpen.value = true
  }

  /** Close the cheatsheet dialog. */
  function closeCheatsheet(): void {
    cheatsheetOpen.value = false
  }

  return {
    focusSearch,
    cheatsheetOpen,
    setFocusSearch,
    triggerFocusSearch,
    openCheatsheet,
    closeCheatsheet,
  }
})
