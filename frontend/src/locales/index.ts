import { createI18n } from 'vue-i18n'
import en from './en.json'
import fr from './fr.json'
import { getCookie } from '../utils/cookies'

/**
 * Supported UI locales. Listed in the order shown to the user in the
 * language switcher.
 */
export const SUPPORTED_LOCALES = ['en', 'fr'] as const

/** Discriminated type for the supported locales. */
export type AppLocale = (typeof SUPPORTED_LOCALES)[number]

/** Cookie name used to persist the user's chosen locale across reloads. */
export const LOCALE_COOKIE = 'boatapp.locale'

/**
 * Resolve the initial locale, in priority order:
 * 1. The persisted cookie if it names a supported locale.
 * 2. The browser's `navigator.language` prefix (e.g. `fr-CH` → `fr`)
 *    if it is supported.
 * 3. `'en'` as the safe fallback.
 *
 * Runs at module load — `<html lang>` is updated immediately so that
 * assistive tech sees the correct language attribute even before Vue
 * mounts.
 */
function resolveInitialLocale(): AppLocale {
  const stored = getCookie(LOCALE_COOKIE)
  if (isSupported(stored)) return stored
  const browser = navigator.language?.split('-')[0]?.toLowerCase()
  if (isSupported(browser)) return browser
  return 'en'
}

/**
 * Type guard — narrows `string | null | undefined` to `AppLocale`.
 *
 * @param value the candidate locale string.
 * @returns whether the value is one of {@link SUPPORTED_LOCALES}.
 */
export function isSupported(value: unknown): value is AppLocale {
  return (
    typeof value === 'string' &&
    (SUPPORTED_LOCALES as readonly string[]).includes(value)
  )
}

const initialLocale = resolveInitialLocale()
if (typeof document !== 'undefined') {
  document.documentElement.lang = initialLocale
}

/**
 * vue-i18n instance for The Boat App SPA.
 *
 * - `legacy: false` so `useI18n()` works in `<script setup>`.
 * - Both `en` and `fr` ship as fully-translated locales; the verifier
 *   and the human checks both require complete coverage so no key
 *   fallback should ever fire in normal use.
 * - Initial locale is read from the `boatapp.locale` cookie (with a
 *   `navigator.language` fallback) so the SPA opens in the user's last
 *   pick without waiting for a Vue render.
 */
export const i18n = createI18n({
  legacy: false,
  locale: initialLocale,
  fallbackLocale: 'en',
  messages: { en, fr },
})
