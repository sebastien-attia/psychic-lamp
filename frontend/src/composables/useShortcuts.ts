import { onBeforeUnmount, onMounted } from 'vue'

/**
 * One keyboard binding: a key (case-insensitive) and the action to
 * invoke when it fires outside an input/textarea/contenteditable.
 *
 * Set `allowInInput: true` for shortcuts that *should* fire while
 * typing (e.g. `Esc` to clear). The default skips inputs so users
 * typing a boat name don't accidentally navigate.
 */
export interface ShortcutBinding {
  /** Single character (e.g. `'/'`, `'n'`) or named key (`'Escape'`). */
  key: string
  /** Handler invoked when the key is pressed. */
  handler: (event: KeyboardEvent) => void
  /**
   * When `true`, the binding fires even if focus is inside an input.
   * Defaults to `false`.
   */
  allowInInput?: boolean
}

/**
 * Single-letter ASCII keys (`a`–`z`) used as bare shortcuts. Pressed
 * with `Shift` they become uppercase letters that real users type all
 * the time (e.g. starting a sentence with capital N), so the listener
 * filters Shift for these specifically while still allowing `?` and
 * `/` — both of which are ALWAYS produced with Shift on US/UK
 * keyboards and would otherwise become unreachable.
 */
const LETTER_KEY_RE = /^[a-z]$/

/**
 * Whether the supplied target element should be treated as a typing
 * context. The shortcut listener skips events whose target is a
 * typable element so a global `n` does not interrupt the user mid-name.
 */
function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  if (target.isContentEditable) return true
  return false
}

/**
 * Register a list of {@link ShortcutBinding}s on `window` for the
 * lifetime of the calling component.
 *
 * Bindings ignore events whose target is a typing context (unless
 * `allowInInput` is set) and skip when `Cmd`, `Ctrl`, or `Alt` is
 * held — we don't want `Cmd+/` (Chrome's "find on page") to fire the
 * focus-search shortcut, for instance. `Shift` is also filtered for
 * single-letter ASCII bindings so a casual capital `N` doesn't
 * navigate; non-letter keys like `?` (which IS produced with Shift)
 * still match.
 *
 * **Keyboard-layout note:** matching is on `event.key`, which is the
 * physical-key-after-modifier-and-locale character. On US/UK QWERTY
 * the bindings shipped today (`/`, `n`, `?`) are reachable as bare
 * keys (or, for `?`, `Shift+/`). On AZERTY/QWERTZ layouts where `/`
 * and `?` require multiple modifier presses, the shortcuts are still
 * accessible but may feel awkward — the cheatsheet copy notes the
 * Latin-keyboard assumption.
 *
 * @param bindings the shortcuts to register. The list is consulted on
 *                 every keystroke; callers should pass a stable array.
 */
export function useShortcuts(bindings: ShortcutBinding[]): void {
  function onKeyDown(event: KeyboardEvent): void {
    if (event.metaKey || event.ctrlKey || event.altKey) return
    const lower = event.key.toLowerCase()
    if (event.shiftKey && LETTER_KEY_RE.test(lower)) return
    const typing = isTypingTarget(event.target)
    for (const b of bindings) {
      if (lower !== b.key.toLowerCase()) continue
      if (typing && !b.allowInInput) continue
      event.preventDefault()
      b.handler(event)
      return
    }
  }

  onMounted(() => window.addEventListener('keydown', onKeyDown))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeyDown))
}
