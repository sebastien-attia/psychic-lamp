<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useBoatsStore } from '../stores/boats'
import { ApiProblemError } from '../services/problem-detail'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import type { BoatResponse } from '../services/api-client/generated/models'

/**
 * Read-only detail view for a single boat. Provides edit + delete
 * actions; deletion goes through `ConfirmDialog`. A 404 from the API
 * (deleted, never existed, or no access) renders an empty state instead
 * of a blank screen.
 */
const props = defineProps<{
  /** UUID injected by Vue Router from `/boats/:id`. */
  id: string
}>()

const router = useRouter()
const store = useBoatsStore()
const { t } = useI18n()

const boat = ref<BoatResponse | null>(null)
const notFound = ref(false)
const dialogOpen = ref(false)

onMounted(async () => {
  try {
    boat.value = await store.getBoat(props.id)
  } catch (e) {
    if (e instanceof ApiProblemError && e.status === 404) {
      notFound.value = true
      return
    }
    throw e
  }
})

/**
 * Handle the `ConfirmDialog` `@confirm` event: delete the boat and
 * navigate back to the list view.
 */
async function confirmDelete(): Promise<void> {
  dialogOpen.value = false
  await store.deleteBoat(props.id)
  void router.push({ name: 'boats.list' })
}
</script>

<template>
  <section v-if="notFound" class="mx-auto max-w-2xl text-center">
    <h1 class="text-2xl font-bold text-slate-900 dark:text-slate-100">
      {{ t('boats.detail.notFound') }}
    </h1>
    <RouterLink
      to="/boats"
      class="mt-4 inline-block text-nautical-600 hover:underline dark:text-nautical-300"
    >
      {{ t('nav.boats') }}
    </RouterLink>
  </section>

  <section v-else-if="boat" class="mx-auto max-w-2xl">
    <header class="flex items-start justify-between">
      <div>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-slate-100">
          {{ boat.name }}
        </h1>
        <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">
          {{ new Date(boat.createdAt).toLocaleString() }}
        </p>
      </div>
      <div class="flex gap-2">
        <RouterLink
          :to="{ name: 'boats.edit', params: { id: boat.id } }"
          class="rounded-md bg-nautical-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-nautical-700"
        >
          {{ t('boats.detail.edit') }}
        </RouterLink>
        <button
          type="button"
          class="rounded-md border border-red-300 px-3 py-1.5 text-sm font-medium text-red-700 hover:bg-red-50 dark:border-red-700 dark:text-red-300 dark:hover:bg-red-900/30"
          @click="dialogOpen = true"
        >
          {{ t('boats.detail.delete') }}
        </button>
      </div>
    </header>

    <p
      v-if="boat.description"
      class="mt-6 whitespace-pre-line text-slate-700 dark:text-slate-300"
    >
      {{ boat.description }}
    </p>

    <ConfirmDialog
      :open="dialogOpen"
      :title="t('boats.detail.delete')"
      :message="boat.name"
      @cancel="dialogOpen = false"
      @confirm="confirmDelete"
    />
  </section>
</template>
