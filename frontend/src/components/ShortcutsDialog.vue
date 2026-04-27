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
 * Cheatsheet dialog listing the global keyboard shortcuts available
 * across the SPA.
 *
 * Driven by a single `open` prop and a `close` event so the
 * `MainLayout` can show / hide it from the global `?` shortcut and
 * from the toolbar button. Each row pairs a `<kbd>` glyph with a
 * short description, both translated through `vue-i18n`.
 *
 * Headless UI's `Dialog` already manages focus trap, Escape-to-close
 * and inert-background, so the only extra wiring here is the visual.
 */
defineProps<{
  /** Whether the dialog is visible. */
  open: boolean
}>()

const emit = defineEmits<{
  /** Fires when the user dismisses the dialog (Escape, backdrop, or button). */
  (e: 'close'): void
}>()

const { t } = useI18n()

/**
 * Static list of shortcut rows. Each `keys` entry renders as one or
 * more `<kbd>` glyphs separated by ` + ` so future multi-key chords
 * can ship without a template change.
 */
const rows: { keys: string[]; labelKey: string }[] = [
  { keys: ['/'], labelKey: 'shortcuts.rows.focusSearch' },
  { keys: ['n'], labelKey: 'shortcuts.rows.newBoat' },
  // Esc is a *local* shortcut on the search input only, not a global
  // binding — `useShortcuts` does not register it. The label reflects
  // that scope so users don't expect a global clear from anywhere.
  { keys: ['Esc'], labelKey: 'shortcuts.rows.clearSearch' },
  { keys: ['?'], labelKey: 'shortcuts.rows.openCheatsheet' },
]
</script>

<template>
  <TransitionRoot as="template" :show="open">
    <Dialog as="div" class="fixed inset-0 z-30" @close="emit('close')">
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

      <div class="fixed inset-0 z-30 flex items-center justify-center p-4">
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
              {{ t('shortcuts.title') }}
            </DialogTitle>
            <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">
              {{ t('shortcuts.subtitle') }}
            </p>
            <ul class="mt-5 space-y-3">
              <li
                v-for="row in rows"
                :key="row.labelKey"
                class="flex items-center justify-between gap-3"
              >
                <span class="text-sm text-slate-700 dark:text-slate-200">
                  {{ t(row.labelKey) }}
                </span>
                <span class="flex items-center gap-1 text-xs">
                  <kbd
                    v-for="(k, idx) in row.keys"
                    :key="idx"
                    class="inline-flex min-w-[1.75rem] items-center justify-center rounded border border-slate-300 bg-slate-100 px-1.5 py-0.5 font-mono text-slate-700 shadow-sm dark:border-slate-600 dark:bg-slate-700 dark:text-slate-100"
                  >
                    {{ k }}
                  </kbd>
                </span>
              </li>
            </ul>
            <div class="mt-6 flex justify-end">
              <button
                type="button"
                class="inline-flex min-h-[44px] items-center justify-center rounded-md bg-bleu-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-bleu-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-bleu-600"
                @click="emit('close')"
              >
                {{ t('actions.dismiss') }}
              </button>
            </div>
          </DialogPanel>
        </TransitionChild>
      </div>
    </Dialog>
  </TransitionRoot>
</template>
