import { config } from '@vue/test-utils'
import { i18n } from '../locales'
import { __resetDarkModeForTests } from '../composables/useDarkMode'
import { beforeEach, vi } from 'vitest'

/**
 * Global Vitest setup.
 *
 * - Installs the real vue-i18n instance into Vue Test Utils' global
 *   plugins so every mounted component can call `useI18n()` /
 *   `t(key)` without per-test boilerplate. Tests against EN messages
 *   stay deterministic because we reset the locale before every test.
 * - Stubs `window.matchMedia` because happy-dom does not implement it
 *   and the dark-mode boot script (and the `useDarkMode` initial-read)
 *   would otherwise throw.
 * - Clears `document.cookie` and the `dark` class on `<html>` between
 *   tests so the `useDarkMode` and `useLocale` singletons start each
 *   test in a known state.
 */
config.global.plugins = [i18n]

vi.stubGlobal(
  'matchMedia',
  vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
)

beforeEach(() => {
  // Reset cookies — split on `; ` and expire each one.
  document.cookie.split(';').forEach((c) => {
    const eq = c.indexOf('=')
    const name = (eq > -1 ? c.substring(0, eq) : c).trim()
    // Skip names not matching the cookie util's allowed shape so a
    // weird leftover from another test cannot make `setCookie` throw.
    if (name && /^[a-zA-Z0-9._-]+$/.test(name)) {
      document.cookie = `${name}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT`
    }
  })
  document.documentElement.classList.remove('dark')
  document.documentElement.lang = 'en'
  i18n.global.locale.value = 'en'
  // Reset the module-scoped `isDark` ref so each test mounts the
  // dark-mode composable in a clean state, even when a previous test
  // toggled it.
  __resetDarkModeForTests()
})
