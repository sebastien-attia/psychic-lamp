import { ref, type Ref } from 'vue'

const STORAGE_KEY = 'boatapp.theme'

/**
 * Read the persisted theme preference, falling back to the OS
 * `prefers-color-scheme` setting on first visit.
 */
function readInitialTheme(): boolean {
  const stored = localStorage.getItem(STORAGE_KEY)
  if (stored === 'dark') return true
  if (stored === 'light') return false
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

// Apply the initial theme synchronously at module load so the first paint
// matches the user's preference (no flash of wrong theme).
const initialTheme = readInitialTheme()
document.documentElement.classList.toggle('dark', initialTheme)

const isDark: Ref<boolean> = ref(initialTheme)

/**
 * Composable managing the SPA's light/dark theme.
 *
 * Reads the persisted preference from `localStorage` (falling back to the
 * OS `prefers-color-scheme` media query on first visit) at module load
 * and toggles the `dark` class on `<html>`. Tailwind's `darkMode: 'class'`
 * setting picks this up. The state is module-scoped so every consumer
 * shares the same `isDark` ref.
 *
 * @returns `isDark` reactive flag and a `toggle()` action.
 */
export function useDarkMode() {
  /**
   * Flip the theme and persist the user's explicit choice.
   */
  function toggle(): void {
    isDark.value = !isDark.value
    document.documentElement.classList.toggle('dark', isDark.value)
    localStorage.setItem(STORAGE_KEY, isDark.value ? 'dark' : 'light')
  }

  return { isDark, toggle }
}
