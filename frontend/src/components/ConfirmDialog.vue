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
 * Used by destructive and non-destructive actions alike — the
 * `tone` prop swaps the confirm button colour so the same component
 * can drive a benign "OK / Cancel" prompt or a destructive
 * "Delete / Cancel" prompt.
 *
 * Mobile-first: the panel is full-width with horizontal margin on
 * small viewports and centred on tablets+, the button row stacks
 * vertically on mobile (`flex-col-reverse` keeps the destructive
 * action at the top of the stack, which is the iOS convention).
 *
 * Headless UI's `Dialog` already handles focus trap, Escape-to-close,
 * and inert-background — there is no extra a11y wiring to add here.
 */
defineProps<{
  /** Whether the dialog is visible. */
  open: boolean
  /** Title shown at the top of the panel. */
  title: string
  /** Body copy shown below the title. */
  message: string
  /**
   * Disables the confirm button and renders an inline spinner. Used
   * by the page to keep the dialog mounted while the destructive
   * mutation is in flight, so the user has feedback before the
   * navigation that follows.
   */
  loading?: boolean
  /**
   * Override the default "Confirm" label — typically the verb that
   * describes the action ("Delete", "Discard", …).
   */
  confirmLabel?: string
  /**
   * Visual tone of the confirm button. `danger` (red) is the default
   * because every current call site is destructive; switch to
   * `primary` (nautical-blue) for benign confirmations.
   */
  tone?: 'danger' | 'primary'
}>()

const emit = defineEmits<{
  /** User dismissed the dialog (Escape, backdrop, or Cancel button). */
  (e: 'cancel'): void
  /** User confirmed the destructive action. */
  (e: 'confirm'): void
}>()

const { t } = useI18n()

/**
 * Resolve the confirm-button colour classes from the `tone` prop.
 * Defaults to `danger` (red) so omitting the prop preserves the
 * existing destructive-action visuals at every legacy call site.
 */
function resolveConfirmClasses(tone: 'danger' | 'primary' | undefined): string {
  if (tone === 'primary') {
    return 'bg-nautical-600 hover:bg-nautical-700 focus-visible:outline-nautical-600'
  }
  return 'bg-red-600 hover:bg-red-700 focus-visible:outline-red-600'
}
</script>

<template>
  <TransitionRoot as="template" :show="open">
    <Dialog as="div" class="relative z-20" @close="loading ? undefined : emit('cancel')">
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
            class="mx-4 w-full max-w-md rounded-lg bg-white p-6 shadow-xl sm:mx-auto dark:bg-slate-800"
          >
            <DialogTitle
              class="text-lg font-semibold text-slate-900 dark:text-slate-100"
            >
              {{ title }}
            </DialogTitle>
            <p class="mt-2 text-sm text-slate-600 dark:text-slate-300">
              {{ message }}
            </p>
            <div
              class="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end"
            >
              <button
                type="button"
                :disabled="loading"
                class="inline-flex w-full items-center justify-center rounded-md border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50 sm:w-auto dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-700"
                @click="emit('cancel')"
              >
                {{ t('actions.cancel') }}
              </button>
              <button
                type="button"
                :disabled="loading"
                :class="[
                  'inline-flex w-full items-center justify-center gap-2 rounded-md px-4 py-2.5 text-sm font-medium text-white shadow-sm transition-transform focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 active:scale-[0.98] disabled:opacity-50 disabled:active:scale-100 sm:w-auto',
                  resolveConfirmClasses(tone),
                ]"
                @click="emit('confirm')"
              >
                <svg
                  v-if="loading"
                  class="h-4 w-4 animate-spin"
                  viewBox="0 0 24 24"
                  fill="none"
                  aria-hidden="true"
                >
                  <circle
                    class="opacity-25"
                    cx="12"
                    cy="12"
                    r="10"
                    stroke="currentColor"
                    stroke-width="4"
                  />
                  <path
                    class="opacity-75"
                    fill="currentColor"
                    d="M4 12a8 8 0 0 1 8-8v4a4 4 0 0 0-4 4H4z"
                  />
                </svg>
                {{ confirmLabel ?? t('actions.confirm') }}
              </button>
            </div>
          </DialogPanel>
        </TransitionChild>
      </div>
    </Dialog>
  </TransitionRoot>
</template>
