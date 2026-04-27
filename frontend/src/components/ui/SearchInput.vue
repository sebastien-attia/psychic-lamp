<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { MagnifyingGlassIcon, XMarkIcon } from '@heroicons/vue/24/outline'

/**
 * Accessible search box: leading magnifier icon, two-way `v-model`
 * binding, and an inline clear button that appears only when the field
 * is non-empty.
 *
 * Debouncing is *not* the input's concern — the page binds its own
 * `searchInput` ref (immediate) and a separate `useDebouncedRef`-backed
 * value to drive fetches and URL writes. That separation lets the
 * input stay reusable for non-debounced contexts and keeps cursor
 * behaviour snappy.
 *
 * Emits `clear` when the user clicks the X so the parent can flush its
 * debounce synchronously (otherwise the URL would lag the visible
 * input clear by ~300 ms).
 *
 * Pressing `Esc` while the input has focus also fires `clear` and
 * resets the value, so the keyboard story matches the visual clear
 * affordance without needing a global handler.
 *
 * Exposes a `focus()` method through `defineExpose` so parents (and
 * the global `/` shortcut) can move focus into the input via a
 * template ref.
 *
 * The browser's native `<input type="search">` clear control is
 * suppressed so the custom button is the single, predictable affordance.
 */
const props = defineProps<{
  /** Two-way bound search value. */
  modelValue: string
  /** Localized placeholder text. */
  placeholder: string
  /** Localized `<label>` text — rendered visually hidden for a11y. */
  label: string
  /** Localized `aria-label` for the clear button. */
  clearLabel: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  /** Fires when the user clicks the inline clear button. */
  clear: []
}>()

const id = useId()

/**
 * Template ref to the underlying `<input>`, used by `focus()` and
 * therefore by the global `/` shortcut wired through the shortcuts
 * store.
 */
const inputEl = ref<HTMLInputElement | null>(null)

const value = computed({
  get: () => props.modelValue,
  set: (v: string) => emit('update:modelValue', v),
})

/**
 * Reset the model to an empty string and notify the parent so it can
 * flush any debounced consumer in the same tick.
 */
function onClear(): void {
  emit('update:modelValue', '')
  emit('clear')
}

/**
 * Handle `Esc` while the input has focus: clear the field if it's
 * non-empty (matching the visible clear button), otherwise no-op so
 * the user can still close other contextual UI without surprise.
 */
function onKeyDown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return
  if (!props.modelValue) return
  event.preventDefault()
  onClear()
}

/**
 * Move keyboard focus into the underlying `<input>` so the user can
 * start typing immediately. Invoked by the global `/` shortcut via
 * the `MainLayout` → `useShortcutsStore` → `BoatListPage` chain.
 */
function focus(): void {
  inputEl.value?.focus()
}

defineExpose({ focus })
</script>

<template>
  <div class="relative">
    <label :for="id" class="sr-only">{{ label }}</label>
    <div class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3">
      <MagnifyingGlassIcon
        class="h-5 w-5 text-slate-400"
        aria-hidden="true"
      />
    </div>
    <input
      :id="id"
      ref="inputEl"
      v-model="value"
      type="search"
      :placeholder="placeholder"
      class="block min-h-[44px] w-full rounded-md border-slate-300 bg-white pl-10 pr-10 py-2 text-sm text-slate-900 shadow-sm focus:border-bleu-500 focus:ring-bleu-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 [&::-webkit-search-cancel-button]:appearance-none"
      @keydown="onKeyDown"
    />
    <button
      v-if="value"
      type="button"
      :aria-label="clearLabel"
      class="absolute inset-y-0 right-0 flex items-center pr-3 text-slate-400 hover:text-slate-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-bleu-500 dark:hover:text-slate-200"
      @click="onClear"
    >
      <XMarkIcon class="h-5 w-5" aria-hidden="true" />
    </button>
  </div>
</template>
