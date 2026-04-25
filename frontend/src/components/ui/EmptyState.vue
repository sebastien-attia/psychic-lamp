<script setup lang="ts">
import type { Component } from 'vue'

/**
 * Generic empty-state panel: large icon, short title, supporting copy,
 * and an optional CTA button.
 *
 * The list page renders this in two flavours — "no boats yet" (with a
 * "Create your first boat" CTA emitting `cta`) and "no matches" (no
 * CTA, just a hint to clear the search). Keeping a single component
 * with prop-driven content avoids two near-duplicate templates.
 */
defineProps<{
  /** Heroicon component (e.g. `InboxIcon`) rendered above the title. */
  icon: Component
  /** Bold one-line title, already localized by the caller. */
  title: string
  /** Supporting body copy, already localized by the caller. */
  message: string
  /** Optional CTA button label. When omitted, no button is rendered. */
  ctaLabel?: string
}>()

defineEmits<{
  /** Fires when the user activates the optional CTA button. */
  cta: []
}>()
</script>

<template>
  <div class="flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-300 bg-white px-6 py-16 text-center dark:border-slate-700 dark:bg-slate-800/50">
    <component
      :is="icon"
      class="h-12 w-12 text-nautical-400 dark:text-nautical-300"
      aria-hidden="true"
    />
    <h2 class="mt-4 text-lg font-semibold text-slate-900 dark:text-slate-100">
      {{ title }}
    </h2>
    <p class="mt-2 max-w-md text-sm text-slate-600 dark:text-slate-400">
      {{ message }}
    </p>
    <button
      v-if="ctaLabel"
      type="button"
      class="mt-6 inline-flex items-center rounded-md bg-amber-500 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-amber-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500"
      @click="$emit('cta')"
    >
      {{ ctaLabel }}
    </button>
  </div>
</template>
