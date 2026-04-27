<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { RouterLink, useRouter } from 'vue-router'
import {
  Disclosure,
  DisclosureButton,
  DisclosurePanel,
} from '@headlessui/vue'
import {
  Bars3Icon,
  QuestionMarkCircleIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import { useI18n } from 'vue-i18n'
import UserMenu from '../components/UserMenu.vue'
import DarkModeToggle from '../components/DarkModeToggle.vue'
import LanguageSwitcher from '../components/LanguageSwitcher.vue'
import SkipToContent from '../components/SkipToContent.vue'
import BoatGlyph from '../components/BoatGlyph.vue'
import ShortcutsDialog from '../components/ShortcutsDialog.vue'
import { useShortcuts } from '../composables/useShortcuts'
import { useShortcutsStore } from '../stores/shortcuts'

/**
 * Top-level chrome wrapping every page. Provides a responsive nav bar
 * (hamburger on mobile via Headless UI `Disclosure`), the dark-mode
 * toggle, the language switcher, and the user dropdown. The first
 * focusable child is a `SkipToContent` link so keyboard users can jump
 * straight to `<main id="main-content">` past the nav landmark.
 *
 * Owns the global keyboard shortcuts (`/`, `n`, `?`) and the cheatsheet
 * dialog. Shortcuts that need a per-page target (the search input)
 * dispatch through the `shortcuts` store, which pages register against
 * on mount.
 */
const { t } = useI18n()
const router = useRouter()
const shortcutsStore = useShortcutsStore()
const { cheatsheetOpen } = storeToRefs(shortcutsStore)

useShortcuts([
  { key: '/', handler: () => shortcutsStore.triggerFocusSearch() },
  { key: 'n', handler: () => void router.push({ name: 'boats.create' }) },
  { key: '?', handler: () => shortcutsStore.openCheatsheet() },
])
</script>

<template>
  <div class="min-h-screen bg-slate-50 dark:bg-slate-900">
    <SkipToContent />
    <Disclosure as="nav" class="bg-white shadow dark:bg-slate-800" v-slot="{ open }">
      <div class="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div class="flex h-16 items-center justify-between">
          <div class="flex items-center gap-4">
            <RouterLink
              to="/boats"
              class="inline-flex items-center gap-2 text-lg font-bold text-bleu-700 dark:text-bleu-300"
            >
              <BoatGlyph :size="28" />
              <span>{{ t('app.title') }}</span>
            </RouterLink>
            <div class="hidden sm:flex sm:gap-4">
              <RouterLink
                to="/boats"
                class="rounded-md px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700"
              >
                {{ t('nav.boats') }}
              </RouterLink>
              <RouterLink
                to="/boats/new"
                class="rounded-md px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700"
              >
                {{ t('nav.newBoat') }}
              </RouterLink>
            </div>
          </div>

          <div class="hidden sm:flex sm:items-center sm:gap-2">
            <button
              type="button"
              class="inline-flex h-11 w-11 items-center justify-center rounded-md text-slate-500 hover:bg-slate-100 hover:text-slate-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-bleu-500 dark:text-slate-300 dark:hover:bg-slate-700"
              :aria-label="t('shortcuts.openLabel')"
              :title="t('shortcuts.openLabel')"
              @click="shortcutsStore.openCheatsheet()"
            >
              <QuestionMarkCircleIcon class="h-5 w-5" aria-hidden="true" />
            </button>
            <LanguageSwitcher />
            <DarkModeToggle />
            <UserMenu />
          </div>

          <div class="flex items-center gap-1 sm:hidden">
            <button
              type="button"
              class="inline-flex h-11 w-11 items-center justify-center rounded-md text-slate-500 hover:bg-slate-100 hover:text-slate-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-bleu-500 dark:text-slate-300 dark:hover:bg-slate-700"
              :aria-label="t('shortcuts.openLabel')"
              @click="shortcutsStore.openCheatsheet()"
            >
              <QuestionMarkCircleIcon class="h-5 w-5" aria-hidden="true" />
            </button>
            <DisclosureButton
              class="inline-flex h-11 w-11 items-center justify-center rounded-md text-slate-500 hover:bg-slate-100 hover:text-slate-700 dark:text-slate-400 dark:hover:bg-slate-700"
              :aria-label="t('nav.menu')"
            >
              <Bars3Icon v-if="!open" class="h-6 w-6" />
              <XMarkIcon v-else class="h-6 w-6" />
            </DisclosureButton>
          </div>
        </div>
      </div>

      <DisclosurePanel class="sm:hidden">
        <div class="space-y-1 px-2 pb-3 pt-2">
          <RouterLink
            to="/boats"
            class="block rounded-md px-3 py-2 text-base font-medium text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700"
          >
            {{ t('nav.boats') }}
          </RouterLink>
          <RouterLink
            to="/boats/new"
            class="block rounded-md px-3 py-2 text-base font-medium text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700"
          >
            {{ t('nav.newBoat') }}
          </RouterLink>
        </div>
        <div class="border-t border-slate-200 px-4 py-3 dark:border-slate-700">
          <div class="flex items-center justify-between gap-2">
            <UserMenu />
            <div class="flex items-center gap-2">
              <LanguageSwitcher />
              <DarkModeToggle />
            </div>
          </div>
        </div>
      </DisclosurePanel>
    </Disclosure>

    <main
      id="main-content"
      tabindex="-1"
      class="mx-auto max-w-7xl px-4 py-8 focus:outline-none sm:px-6 lg:px-8"
    >
      <slot />
    </main>

    <ShortcutsDialog
      :open="cheatsheetOpen"
      @close="shortcutsStore.closeCheatsheet()"
    />
  </div>
</template>
