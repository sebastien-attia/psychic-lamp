import { ref, type Ref } from 'vue'
import { getCookie, setCookie } from '../utils/cookies'

/** Cookie name persisting the user's explicit theme choice. */
export const THEME_COOKIE = 'boatapp.theme'

/**
 * Read the initial theme. The inline `<head>` boot script in
 * `index.html` has already added the `dark` class to `<html>` (or
 * not), so the single source of truth for the *current* visible theme
 * is that class — reading the cookie or `prefers-color-scheme` here
 * could disagree with what the user is actually looking at.
 */
function readInitialTheme(): boolean {
  if (typeof document === 'undefined') return false
  return document.documentElement.classList.contains('dark')
}

const isDark: Ref<boolean> = ref(readInitialTheme())

/**
 * Composable managing the SPA's light/dark theme.
 *
 * Initial state mirrors the `dark` class set by the inline boot script
 * in `index.html`, which reads the `boatapp.theme` cookie (with an OS
 * `prefers-color-scheme` fallback) before Vue mounts. That keeps the
 * very first paint in the right theme — no flash of wrong theme even
 * on slow devices, because no JS module needs to load first.
 *
 * The state is module-scoped so every consumer (the toggle in the nav,
 * the test harness) shares the same `isDark` ref.
 *
 * @returns `isDark` reactive flag and a `toggle()` action.
 */
export function useDarkMode(): {
  isDark: Ref<boolean>
  toggle: () => void
} {
  /**
   * Flip the theme, update the `<html>` class so Tailwind's
   * `darkMode: 'class'` picks it up, and persist the explicit choice
   * in the `boatapp.theme` cookie so reloads remain stable.
   */
  function toggle(): void {
    isDark.value = !isDark.value
    document.documentElement.classList.toggle('dark', isDark.value)
    setCookie(THEME_COOKIE, isDark.value ? 'dark' : 'light')
  }

  return { isDark, toggle }
}

/**
 * Test-only escape hatch. Re-syncs the module-scoped ref from the
 * current `<html>` class and clears the persisted cookie. Used by the
 * Vitest setup to give each test a clean slate without re-importing
 * the module.
 *
 * Not exported through the composable's public surface — call sites
 * should not need this.
 */
export function __resetDarkModeForTests(): void {
  setCookie(THEME_COOKIE, '', 0)
  document.documentElement.classList.remove('dark')
  isDark.value = false
  // re-read in case external code mutated the class between writes
  isDark.value = readInitialTheme()
}

/**
 * Read the cookie's current value without going through the
 * composable's reactive ref. Exposed so tests can assert that
 * `toggle()` actually wrote the cookie.
 */
export function readPersistedTheme(): 'dark' | 'light' | null {
  const v = getCookie(THEME_COOKIE)
  return v === 'dark' || v === 'light' ? v : null
}
