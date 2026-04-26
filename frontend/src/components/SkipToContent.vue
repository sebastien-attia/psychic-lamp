<script setup lang="ts">
import { useI18n } from 'vue-i18n'

/**
 * "Skip to main content" link — visually hidden until it receives
 * keyboard focus, then absolutely positioned at the top of the
 * viewport with a visible focus ring. Pairs with the
 * `id="main-content"` `<main>` element in `MainLayout` so keyboard
 * users can jump past the nav landmark. WCAG 2.1 SC 2.4.1.
 *
 * Activation explicitly moves focus into `<main>` so the *next* Tab
 * lands inside the page content. Without the manual focus call,
 * Chrome and Firefox scroll to the anchor but leave focus on the
 * link, so the next Tab cycles back into the nav — defeating the
 * feature.
 */
const { t } = useI18n()

/**
 * Move focus into `<main id="main-content">` after the browser
 * processes the hash navigation. `preventDefault` keeps the URL hash
 * out of the browser history so `Back` does not bounce the user to
 * the previous route. We re-add the hash via `history.replaceState`
 * for deep-linking (a copied URL with `#main-content` still works).
 */
function activate(event: Event): void {
  event.preventDefault()
  const main = document.getElementById('main-content')
  if (!main) return
  main.focus({ preventScroll: false })
  history.replaceState(null, '', '#main-content')
}
</script>

<template>
  <a
    href="#main-content"
    class="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-bleu-600 focus:px-4 focus:py-2 focus:text-sm focus:font-semibold focus:text-white focus:shadow-lg focus:outline-none focus:ring-2 focus:ring-bleu-500 focus:ring-offset-2 dark:focus:ring-offset-slate-900"
    @click="activate"
  >
    {{ t('nav.skipToContent') }}
  </a>
</template>
