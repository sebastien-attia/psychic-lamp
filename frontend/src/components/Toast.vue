<script setup lang="ts">
import { XMarkIcon } from '@heroicons/vue/24/outline'
import { useI18n } from 'vue-i18n'
import { useToast } from '../composables/useToast'

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
          'pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-md px-4 py-3 text-sm shadow-lg ring-1',
          toast.kind === 'error'
            ? 'bg-red-50 text-red-900 ring-red-200 dark:bg-red-900/40 dark:text-red-100 dark:ring-red-800'
            : 'bg-white text-slate-900 ring-slate-200 dark:bg-slate-800 dark:text-slate-100 dark:ring-slate-700',
        ]"
        role="status"
      >
        <p class="flex-1">{{ toast.message }}</p>
        <button
          type="button"
          class="-m-1 rounded p-1 text-slate-500 hover:text-slate-700 focus:outline-none focus:ring-2 focus:ring-nautical-500 dark:text-slate-300 dark:hover:text-slate-100"
          :aria-label="t('actions.dismiss')"
          @click="dismissToast(toast.id)"
        >
          <XMarkIcon class="h-4 w-4" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>
