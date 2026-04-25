<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterView } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowPathIcon } from '@heroicons/vue/24/outline'
import MainLayout from './layouts/MainLayout.vue'
import Toast from './components/Toast.vue'
import { useAuthStore } from './stores/auth'

/**
 * Root component.
 *
 * Bootstraps the session by calling `auth.fetchUser()` on mount and
 * gates the rendered tree on `auth.loading`:
 *
 * - While loading, a full-screen spinner stands in for the layout so
 *   no page flashes anonymous content before the router guard has
 *   decided whether to redirect to Keycloak.
 * - Once loaded, the shared `MainLayout` wraps the matched route and
 *   the global `<Toast>` overlay is mounted so the 401 interceptor
 *   can surface session-expiry messages anywhere in the app.
 */
const auth = useAuthStore()
const { t } = useI18n()

onMounted(() => {
  // The router guard will also call this; the store dedupes concurrent
  // invocations so this is just an early kick-off.
  void auth.fetchUser()
})
</script>

<template>
  <Toast />
  <div
    v-if="auth.loading"
    class="flex min-h-screen items-center justify-center bg-slate-50 text-slate-600 dark:bg-slate-900 dark:text-slate-300"
    role="status"
    :aria-label="t('auth.loading')"
  >
    <ArrowPathIcon class="mr-3 h-6 w-6 animate-spin" aria-hidden="true" />
    <span>{{ t('auth.loading') }}</span>
  </div>
  <MainLayout v-else>
    <RouterView />
  </MainLayout>
</template>
