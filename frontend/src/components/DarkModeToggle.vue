<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Switch } from '@headlessui/vue'
import { SunIcon, MoonIcon } from '@heroicons/vue/24/outline'
import { useDarkMode } from '../composables/useDarkMode'

/**
 * Light/dark theme toggle, rendered as a Headless UI `Switch` so screen
 * readers announce a switch role with `aria-checked` state. Reads and
 * writes through the `useDarkMode` composable so the choice persists
 * in the `boatapp.theme` cookie and survives reloads.
 */
const { isDark, toggle } = useDarkMode()
const { t } = useI18n()
</script>

<template>
  <Switch
    :model-value="isDark"
    :aria-label="t('nav.toggleDarkMode')"
    class="relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-nautical-500 focus-visible:ring-offset-2 dark:focus-visible:ring-offset-slate-900"
    :class="isDark ? 'bg-nautical-600' : 'bg-slate-300'"
    @update:model-value="toggle"
  >
    <span
      class="inline-flex h-6 w-6 transform items-center justify-center rounded-full bg-white shadow ring-0 transition-transform"
      :class="isDark ? 'translate-x-5' : 'translate-x-0.5'"
    >
      <MoonIcon
        v-if="isDark"
        class="h-4 w-4 text-nautical-700"
        aria-hidden="true"
      />
      <SunIcon
        v-else
        class="h-4 w-4 text-amber-500"
        aria-hidden="true"
      />
    </span>
  </Switch>
</template>
