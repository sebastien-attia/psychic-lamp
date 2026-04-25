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
    class="flex flex-col items-center justify-center rounded-lg border border-red-200 bg-red-50 px-6 py-12 text-center dark:border-red-800 dark:bg-red-900/30"
  >
    <ExclamationTriangleIcon
      class="h-10 w-10 text-red-500 dark:text-red-300"
      aria-hidden="true"
    />
    <h2 class="mt-3 text-base font-semibold text-red-800 dark:text-red-100">
      {{ title }}
    </h2>
    <p class="mt-1 max-w-md text-sm text-red-700 dark:text-red-200">
      {{ message }}
    </p>
    <button
      type="button"
      class="mt-5 inline-flex items-center rounded-md border border-red-300 bg-white px-4 py-2 text-sm font-medium text-red-700 shadow-sm hover:bg-red-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-500 dark:border-red-700 dark:bg-red-900/40 dark:text-red-100 dark:hover:bg-red-900/60"
      @click="$emit('retry')"
    >
      {{ retryLabel }}
    </button>
  </div>
</template>
