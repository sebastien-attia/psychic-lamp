<script setup lang="ts">
import { RouterLink } from 'vue-router'
import {
  Disclosure,
  DisclosureButton,
  DisclosurePanel,
} from '@headlessui/vue'
import { Bars3Icon, XMarkIcon } from '@heroicons/vue/24/outline'
import { useI18n } from 'vue-i18n'
import UserMenu from '../components/UserMenu.vue'
import DarkModeToggle from '../components/DarkModeToggle.vue'

/**
 * Top-level chrome wrapping every page. Provides a responsive nav bar
 * (hamburger on mobile via Headless UI `Disclosure`), the dark-mode
 * toggle, and the user dropdown.
 */
const { t } = useI18n()
</script>

<template>
  <div class="min-h-screen bg-slate-50 dark:bg-slate-900">
    <Disclosure as="nav" class="bg-white shadow dark:bg-slate-800" v-slot="{ open }">
      <div class="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div class="flex h-16 items-center justify-between">
          <div class="flex items-center gap-4">
            <RouterLink
              to="/boats"
              class="text-lg font-bold text-nautical-700 dark:text-nautical-300"
            >
              {{ t('app.title') }}
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
            <DarkModeToggle />
            <UserMenu />
          </div>

          <div class="flex sm:hidden">
            <DisclosureButton
              class="inline-flex items-center justify-center rounded-md p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-700 dark:text-slate-400 dark:hover:bg-slate-700"
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
          <div class="flex items-center justify-between">
            <UserMenu />
            <DarkModeToggle />
          </div>
        </div>
      </DisclosurePanel>
    </Disclosure>

    <main class="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
      <slot />
    </main>
  </div>
</template>
