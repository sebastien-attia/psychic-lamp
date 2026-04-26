<script setup lang="ts">
import { computed } from 'vue'

/**
 * Small rounded label. Used to surface secondary metadata next to a
 * heading (e.g. the boat count beside the page title).
 */
const props = withDefaults(
  defineProps<{
    /** Visual flavour: `neutral` for inert metadata, `accent` for the
     *  Olive accent that pairs with the page's create / success CTAs. */
    variant?: 'neutral' | 'accent'
  }>(),
  { variant: 'neutral' },
)

const classes = computed(() =>
  props.variant === 'accent'
    ? 'bg-olive-100 text-olive-800 dark:bg-olive-900/40 dark:text-olive-200'
    : 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
)
</script>

<template>
  <span
    :class="['inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium', classes]"
  >
    <slot />
  </span>
</template>
