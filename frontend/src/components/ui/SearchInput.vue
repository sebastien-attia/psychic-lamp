<script setup lang="ts">
import { computed, useId } from 'vue'
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
      v-model="value"
      type="search"
      :placeholder="placeholder"
      class="block w-full rounded-md border-slate-300 bg-white pl-10 pr-10 py-2 text-sm text-slate-900 shadow-sm focus:border-nautical-500 focus:ring-nautical-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 [&::-webkit-search-cancel-button]:appearance-none"
    />
    <button
      v-if="value"
      type="button"
      :aria-label="clearLabel"
      class="absolute inset-y-0 right-0 flex items-center pr-3 text-slate-400 hover:text-slate-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-nautical-500 dark:hover:text-slate-200"
      @click="onClear"
    >
      <XMarkIcon class="h-5 w-5" aria-hidden="true" />
    </button>
  </div>
</template>
