<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import { useBoatsStore } from '../stores/boats'
import BoatCard from '../components/BoatCard.vue'

/**
 * Lists all boats accessible to the current user. Triggers a fetch on
 * mount (idempotent — the store no-ops on subsequent calls if needed).
 */
const store = useBoatsStore()
const { list, loading } = storeToRefs(store)
const { t } = useI18n()

onMounted(() => {
  void store.fetchBoats()
})
</script>

<template>
  <section>
    <h1 class="text-2xl font-bold text-slate-900 dark:text-slate-100">
      {{ t('boats.list.title') }}
    </h1>

    <p
      v-if="loading"
      class="mt-6 text-slate-500 dark:text-slate-400"
    >
      {{ t('boats.list.loading') }}
    </p>

    <p
      v-else-if="list.length === 0"
      class="mt-6 text-slate-500 dark:text-slate-400"
    >
      {{ t('boats.list.empty') }}
    </p>

    <div
      v-else
      class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3"
    >
      <BoatCard v-for="boat in list" :key="boat.id" :boat="boat" />
    </div>
  </section>
</template>
