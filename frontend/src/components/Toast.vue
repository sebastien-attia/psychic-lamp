<script setup lang="ts">
import { XMarkIcon } from '@heroicons/vue/24/outline'
import { useI18n } from 'vue-i18n'
import { useToast, type ToastEntry } from '../composables/useToast'

/**
 * Fixed top-right toast stack rendering every entry from the
 * shared queue in {@link ../composables/useToast}.
 *
 * Mounted once in `App.vue` so it overlays every route. Toasts
 * auto-dismiss after the TTL configured at push time and can also
 * be dismissed by clicking the close button.
 */
const { toasts, dismissToast } = useToast()
const { t } = useI18n()

/**
 * Resolve the per-kind class set: a 4 px left border in the brand
 * accent colour (Brique for errors, Olive for success, Bleu for info)
 * over neutral surface colours so the message text stays readable in
 * both light and dark mode.
 */
function classesFor(kind: ToastEntry['kind']): string {
  if (kind === 'error') {
    return 'border-l-4 border-brique-500 bg-brique-50 text-brique-900 ring-brique-200 dark:bg-brique-900/40 dark:text-brique-100 dark:ring-brique-800'
  }
  if (kind === 'success') {
    return 'border-l-4 border-olive-500 bg-white text-slate-900 ring-olive-200 dark:bg-slate-800 dark:text-slate-100 dark:ring-olive-800/60'
  }
  return 'border-l-4 border-bleu-500 bg-white text-slate-900 ring-slate-200 dark:bg-slate-800 dark:text-slate-100 dark:ring-slate-700'
}
</script>

<template>
  <div
    aria-live="polite"
    class="pointer-events-none fixed inset-x-0 top-4 z-50 flex flex-col items-end gap-2 px-4 sm:px-6"
  >
    <TransitionGroup
      enter-active-class="transition ease-out duration-150"
      enter-from-class="opacity-0 translate-y-1"
      enter-to-class="opacity-100 translate-y-0"
      leave-active-class="transition ease-in duration-100"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div
        v-for="toast in toasts"
        :key="toast.id"
        :class="[
          'pointer-events-auto flex w-[calc(100vw-2rem)] max-w-sm items-start gap-3 rounded-md px-4 py-3 text-sm shadow-lg ring-1',
          classesFor(toast.kind),
        ]"
      >
        <p class="flex-1">{{ toast.message }}</p>
        <button
          type="button"
          class="-m-1 rounded p-1 text-slate-500 hover:text-slate-700 focus:outline-none focus:ring-2 focus:ring-bleu-500 dark:text-slate-300 dark:hover:text-slate-100"
          :aria-label="t('actions.dismiss')"
          @click="dismissToast(toast.id)"
        >
          <XMarkIcon class="h-4 w-4" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>
