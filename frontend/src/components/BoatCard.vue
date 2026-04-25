<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { BoatResponse } from '../services/api-client/generated/models'

/**
 * Compact card displaying a single boat in list views. Clicking the card
 * navigates to the boat's detail page.
 */
defineProps<{
  /** The boat to display. */
  boat: BoatResponse
}>()
</script>

<template>
  <RouterLink
    :to="{ name: 'boats.detail', params: { id: boat.id } }"
    class="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm hover:border-nautical-400 hover:shadow-md transition dark:border-slate-700 dark:bg-slate-800 dark:hover:border-nautical-400"
  >
    <h3 class="text-lg font-semibold text-slate-900 dark:text-slate-100">
      {{ boat.name }}
    </h3>
    <p
      v-if="boat.description"
      class="mt-1 text-sm text-slate-600 dark:text-slate-400 line-clamp-2"
    >
      {{ boat.description }}
    </p>
    <p class="mt-3 text-xs text-slate-500 dark:text-slate-500">
      {{ new Date(boat.createdAt).toLocaleDateString() }}
    </p>
  </RouterLink>
</template>
