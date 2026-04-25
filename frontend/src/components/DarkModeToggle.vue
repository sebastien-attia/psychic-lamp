<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { SunIcon, MoonIcon } from '@heroicons/vue/24/outline'
import { useDarkMode } from '../composables/useDarkMode'

/**
 * Light/dark theme toggle button. Reads + writes through the
 * `useDarkMode` composable so the choice persists in `localStorage`.
 */
const { isDark, toggle } = useDarkMode()
const { t } = useI18n()
</script>

<template>
  <button
    type="button"
    class="rounded-md p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-700 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-200 focus:outline-none focus:ring-2 focus:ring-nautical-500"
    :aria-label="t('nav.toggleDarkMode')"
    @click="toggle"
  >
    <SunIcon v-if="isDark" class="h-5 w-5" />
    <MoonIcon v-else class="h-5 w-5" />
  </button>
</template>
