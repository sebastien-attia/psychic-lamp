import { createI18n } from 'vue-i18n'
import en from './en.json'

/**
 * vue-i18n instance for The Boat App SPA.
 *
 * Uses the Composition API mode (`legacy: false`) so `useI18n()` works in
 * `<script setup>`. English is the source language and the only fully
 * populated locale. French is intentionally absent until the translations
 * are complete; shipping a half-translated FR locale produces a
 * mixed-language UI through fallback, which is worse than offering only EN.
 */
export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en },
})
