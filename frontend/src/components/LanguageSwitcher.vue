<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import {
  Menu,
  MenuButton,
  MenuItems,
  MenuItem,
} from '@headlessui/vue'
import { LanguageIcon, CheckIcon } from '@heroicons/vue/24/outline'
import { useLocale } from '../composables/useLocale'
import type { AppLocale } from '../locales'

/**
 * Headless UI dropdown letting the user pick a UI locale (EN / FR).
 * Mirrors the visual style of `UserMenu` so the nav stays consistent.
 *
 * The list of locales is derived from `useLocale().available`, so adding
 * a new translation file in `src/locales/` and registering it in
 * `src/locales/index.ts` is enough to surface a new option here.
 */
const { t } = useI18n()
const { current, available, setLocale } = useLocale()

/**
 * Localised label for a locale code (EN → "English", FR → "Français").
 * Lookup keys live under `locale.<code>` in every translation file so
 * the menu still reads correctly when you switch language.
 */
function labelFor(code: AppLocale): string {
  return t(`locale.${code}`)
}

/**
 * Short uppercase code shown in the menu button so the nav stays
 * compact ("EN" / "FR"). The full name is in the dropdown body.
 */
function shortFor(code: AppLocale): string {
  return code.toUpperCase()
}

/**
 * Build the per-item accessible name. The visible `CheckIcon` is
 * `aria-hidden`, so without the "(selected)" suffix screen readers
 * would announce no difference between the active locale and the
 * inactive ones. `aria-current`/`aria-checked` are deliberately not
 * used — `aria-current` is wrong inside a menuitem, and Headless UI's
 * `MenuItem` does not natively render `menuitemradio`.
 */
function ariaLabelFor(code: AppLocale): string {
  return code === current.value
    ? `${labelFor(code)} (${t('locale.selected')})`
    : labelFor(code)
}
</script>

<template>
  <Menu as="div" class="relative inline-block text-left">
    <MenuButton
      class="inline-flex min-h-[44px] items-center gap-x-1.5 rounded-md px-2 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-bleu-500"
      :aria-label="t('nav.language')"
    >
      <LanguageIcon class="h-5 w-5" aria-hidden="true" />
      <span>{{ shortFor(current) }}</span>
    </MenuButton>

    <transition
      enter-active-class="transition ease-out duration-100"
      enter-from-class="transform opacity-0 scale-95"
      enter-to-class="transform opacity-100 scale-100"
      leave-active-class="transition ease-in duration-75"
      leave-from-class="transform opacity-100 scale-100"
      leave-to-class="transform opacity-0 scale-95"
    >
      <MenuItems
        class="absolute right-0 z-10 mt-2 w-44 origin-top-right rounded-md bg-white py-1 shadow-lg ring-1 ring-black/5 focus:outline-none dark:bg-slate-800 dark:ring-white/10"
      >
        <MenuItem
          v-for="code in available"
          v-slot="{ active }"
          :key="code"
        >
          <button
            type="button"
            :class="[
              active ? 'bg-slate-100 dark:bg-slate-700' : '',
              'flex w-full items-center justify-between px-4 py-2 text-left text-sm text-slate-700 dark:text-slate-200',
            ]"
            :aria-label="ariaLabelFor(code)"
            @click="setLocale(code)"
          >
            <span>{{ labelFor(code) }}</span>
            <CheckIcon
              v-if="code === current"
              class="h-4 w-4 text-bleu-600 dark:text-bleu-300"
              aria-hidden="true"
            />
          </button>
        </MenuItem>
      </MenuItems>
    </transition>
  </Menu>
</template>
