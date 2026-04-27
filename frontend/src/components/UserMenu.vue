<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Menu,
  MenuButton,
  MenuItems,
  MenuItem,
} from '@headlessui/vue'
import { UserCircleIcon } from '@heroicons/vue/24/outline'
import { useAuthStore } from '../stores/auth'

/**
 * Headless UI dropdown showing the current user's profile and a sign-out
 * action. The button collapses to an icon on small screens.
 */
const auth = useAuthStore()
const { t } = useI18n()

/**
 * Display name composed from the user's first + last name. Falls back to
 * an empty string before the auth store has populated `user`.
 */
const fullName = computed(() => {
  const u = auth.user
  if (!u) return ''
  return `${u.firstName} ${u.lastName}`.trim()
})
</script>

<template>
  <Menu as="div" class="relative inline-block text-left">
    <MenuButton
      class="inline-flex min-h-[44px] items-center gap-x-2 rounded-md px-2 py-1.5 text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800 focus:outline-none focus:ring-2 focus:ring-bleu-500"
      :aria-label="t('nav.menu')"
    >
      <UserCircleIcon class="h-6 w-6" />
      <span class="hidden sm:inline">{{ fullName || auth.user?.username }}</span>
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
        class="absolute right-0 z-10 mt-2 w-56 origin-top-right rounded-md bg-white shadow-lg ring-1 ring-black/5 focus:outline-none dark:bg-slate-800 dark:ring-white/10"
      >
        <div class="px-4 py-3 text-sm">
          <p class="font-medium text-slate-900 dark:text-slate-100">
            {{ fullName }}
          </p>
          <p class="truncate text-slate-500 dark:text-slate-400">
            {{ auth.user?.email }}
          </p>
        </div>
        <div class="py-1">
          <MenuItem v-slot="{ active }" :disabled="auth.loggingOut">
            <button
              type="button"
              :disabled="auth.loggingOut"
              :class="[
                active ? 'bg-slate-100 dark:bg-slate-700' : '',
                auth.loggingOut ? 'cursor-not-allowed opacity-60' : '',
                'block w-full px-4 py-2 text-left text-sm text-slate-700 dark:text-slate-200',
              ]"
              @click="auth.logout()"
            >
              {{ auth.loggingOut ? t('nav.signingOut') : t('nav.signOut') }}
            </button>
          </MenuItem>
        </div>
      </MenuItems>
    </transition>
  </Menu>
</template>
