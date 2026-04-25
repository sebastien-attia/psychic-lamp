<script setup lang="ts">
import {
  Dialog,
  DialogPanel,
  DialogTitle,
  TransitionRoot,
  TransitionChild,
} from '@headlessui/vue'
import { useI18n } from 'vue-i18n'

/**
 * Generic accessible confirm dialog built on Headless UI `Dialog`.
 * Used by destructive actions (e.g. boat deletion) to require an
 * explicit user acknowledgment.
 */
defineProps<{
  /** Whether the dialog is visible. */
  open: boolean
  /** Title shown at the top of the panel. */
  title: string
  /** Body copy shown below the title. */
  message: string
}>()

const emit = defineEmits<{
  /** User dismissed the dialog (Escape, backdrop, or Cancel button). */
  (e: 'cancel'): void
  /** User confirmed the destructive action. */
  (e: 'confirm'): void
}>()

const { t } = useI18n()
</script>

<template>
  <TransitionRoot as="template" :show="open">
    <Dialog as="div" class="relative z-20" @close="emit('cancel')">
      <TransitionChild
        as="template"
        enter="ease-out duration-200"
        enter-from="opacity-0"
        enter-to="opacity-100"
        leave="ease-in duration-150"
        leave-from="opacity-100"
        leave-to="opacity-0"
      >
        <div class="fixed inset-0 bg-slate-900/50 transition-opacity" />
      </TransitionChild>

      <div class="fixed inset-0 z-20 flex items-center justify-center p-4">
        <TransitionChild
          as="template"
          enter="ease-out duration-200"
          enter-from="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
          enter-to="opacity-100 translate-y-0 sm:scale-100"
          leave="ease-in duration-150"
          leave-from="opacity-100 translate-y-0 sm:scale-100"
          leave-to="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
        >
          <DialogPanel
            class="w-full max-w-md rounded-lg bg-white p-6 shadow-xl dark:bg-slate-800"
          >
            <DialogTitle
              class="text-lg font-semibold text-slate-900 dark:text-slate-100"
            >
              {{ title }}
            </DialogTitle>
            <p class="mt-2 text-sm text-slate-600 dark:text-slate-300">
              {{ message }}
            </p>
            <div class="mt-5 flex justify-end gap-2">
              <button
                type="button"
                class="rounded-md border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-700"
                @click="emit('cancel')"
              >
                {{ t('actions.cancel') }}
              </button>
              <button
                type="button"
                class="rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-red-700"
                @click="emit('confirm')"
              >
                {{ t('actions.confirm') }}
              </button>
            </div>
          </DialogPanel>
        </TransitionChild>
      </div>
    </Dialog>
  </TransitionRoot>
</template>
