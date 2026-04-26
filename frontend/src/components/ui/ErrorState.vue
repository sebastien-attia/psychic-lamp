<script setup lang="ts">
import { ExclamationTriangleIcon } from '@heroicons/vue/24/outline'

/**
 * Non-blocking error panel for failed list/data fetches: warning icon,
 * short explanation, and a retry button. The caller owns the retry
 * behaviour (re-fetch, reset filters, etc.) — this component only
 * surfaces the UX.
 *
 * Auth failures are *not* shown here: the axios interceptor in
 * `services/http.ts` handles 401 with a session-expired toast plus a
 * Keycloak redirect, so the boats store deliberately does not assign
 * 401 errors to the page-level error ref.
 */
defineProps<{
  /** Bold one-line title, already localized by the caller. */
  title: string
  /** Supporting body copy, already localized by the caller. */
  message: string
  /** Retry button label, already localized by the caller. */
  retryLabel: string
}>()

defineEmits<{
  /** Fires when the user activates the retry button. */
  retry: []
}>()
</script>

<template>
  <div
    role="alert"
    class="flex flex-col items-center justify-center rounded-lg border border-brique-200 bg-brique-50 px-6 py-12 text-center dark:border-brique-800 dark:bg-brique-900/30"
  >
    <ExclamationTriangleIcon
      class="h-10 w-10 text-brique-500 dark:text-brique-300"
      aria-hidden="true"
    />
    <h2 class="mt-3 text-base font-semibold text-brique-800 dark:text-brique-100">
      {{ title }}
    </h2>
    <p class="mt-1 max-w-md text-sm text-brique-700 dark:text-brique-200">
      {{ message }}
    </p>
    <button
      type="button"
      class="mt-5 inline-flex min-h-[44px] items-center rounded-md border border-brique-300 bg-white px-4 py-2 text-sm font-medium text-brique-700 shadow-sm hover:bg-brique-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brique-500 dark:border-brique-700 dark:bg-brique-900/40 dark:text-brique-100 dark:hover:bg-brique-900/60"
      @click="$emit('retry')"
    >
      {{ retryLabel }}
    </button>
  </div>
</template>
