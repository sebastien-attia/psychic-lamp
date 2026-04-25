import { computed, type ComputedRef } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  i18n,
  isSupported,
  LOCALE_COOKIE,
  SUPPORTED_LOCALES,
  type AppLocale,
} from '../locales'
import { setCookie } from '../utils/cookies'

/**
 * Composable returning the current locale plus a `setLocale` action.
 *
 * On change, three side effects run together:
 *
 * 1. `i18n.global.locale.value` switches so every `t()` call re-renders.
 * 2. `document.documentElement.lang` is updated so assistive tech and
 *    `lang`-targeted CSS pick up the new language.
 * 3. The `boatapp.locale` cookie is persisted so the next page load
 *    starts in the same locale.
 *
 * @returns a typed `current` (read-only) plus `setLocale(next)`,
 *          and the available locales for switcher rendering.
 */
export function useLocale(): {
  current: ComputedRef<AppLocale>
  available: typeof SUPPORTED_LOCALES
  setLocale: (next: AppLocale) => void
} {
  const { locale } = useI18n()

  const current = computed<AppLocale>(() =>
    isSupported(locale.value) ? locale.value : 'en',
  )

  /**
   * Switch the active UI locale and persist the choice. No-ops if the
   * `next` value is unsupported (defensive; the switcher should never
   * pass an unknown locale).
   */
  function setLocale(next: AppLocale): void {
    if (!isSupported(next)) return
    if (locale.value === next) return
    // Mutate via the global instance so both the in-template `useI18n`
    // ref and any outside-component callers (e.g. http.ts) see the
    // change.
    i18n.global.locale.value = next
    if (typeof document !== 'undefined') {
      document.documentElement.lang = next
    }
    setCookie(LOCALE_COOKIE, next)
  }

  return { current, available: SUPPORTED_LOCALES, setLocale }
}
