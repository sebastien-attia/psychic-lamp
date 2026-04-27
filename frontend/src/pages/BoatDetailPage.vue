<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useBoatsStore } from '../stores/boats'
import { ApiProblemError } from '../services/problem-detail'
import { showError, showSuccess } from '../composables/useToast'
import DeleteConfirmDialog from '../components/DeleteConfirmDialog.vue'
import SkeletonLoader from '../components/ui/SkeletonLoader.vue'
import type { BoatResponse } from '../services/api-client/generated/models'

/**
 * Read-only detail view for a single boat. Provides edit + delete
 * actions; deletion goes through `DeleteConfirmDialog` which keeps the
 * dialog mounted while the `DELETE` request is in flight (loading
 * spinner on the destructive button).
 *
 * A 404 from the API renders an empty state with a back link rather
 * than a blank page. The skeleton state covers the brief window
 * between mount and the first response.
 */
const props = defineProps<{
  /** UUID injected by Vue Router from `/boats/:id`. */
  id: string
}>()

const router = useRouter()
const store = useBoatsStore()
const { t, locale } = useI18n()

const boat = ref<BoatResponse | null>(null)
const notFound = ref(false)
const dialogOpen = ref(false)
const deleting = ref(false)

/** True until the first fetch resolves (or 404s) — drives the skeleton. */
const loading = computed(() => boat.value === null && !notFound.value)

/** Locale-formatted createdAt timestamp. */
const createdAtLabel = computed(() =>
  boat.value
    ? new Date(boat.value.createdAt).toLocaleString(locale.value)
    : '',
)

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
 * Handle the delete-dialog `@confirm` event. Keeps the dialog open
 * with a spinner while the request is in flight so the user has
 * feedback before the navigation that follows. On 404 (the boat was
 * already deleted by another tab) we still treat it as success — the
 * server's view matches what the user wanted. Any other failure
 * surfaces as an error toast and closes the dialog so the user is
 * not left with a frozen spinner.
 */
async function confirmDelete(): Promise<void> {
  deleting.value = true
  try {
    await store.deleteBoat(props.id)
  } catch (e) {
    const isAlreadyGone = e instanceof ApiProblemError && e.status === 404
    if (!isAlreadyGone) {
      deleting.value = false
      dialogOpen.value = false
      showError(t('errors.generic'))
      return
    }
  }
  deleting.value = false
  dialogOpen.value = false
  showSuccess(t('boats.success.deleted'))
  void router.push({ name: 'boats.list' })
}
</script>

<template>
  <section v-if="notFound" class="mx-auto max-w-2xl px-4 text-center">
    <h1 class="text-2xl font-bold text-slate-900 dark:text-slate-100">
      {{ t('boats.detail.notFound') }}
    </h1>
    <RouterLink
      to="/boats"
      class="mt-4 inline-block text-bleu-600 hover:underline dark:text-bleu-300"
    >
      {{ t('nav.boats') }}
    </RouterLink>
  </section>

  <section v-else class="mx-auto max-w-2xl px-4">
    <nav
      :aria-label="t('nav.breadcrumb')"
      class="text-sm text-slate-500 dark:text-slate-400"
    >
      <RouterLink
        to="/boats"
        class="hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-bleu-500"
      >
        {{ t('nav.boats') }}
      </RouterLink>
      <span aria-hidden="true" class="mx-2">›</span>
      <span class="text-slate-700 dark:text-slate-200">
        {{ boat?.name ?? '…' }}
      </span>
    </nav>

    <div
      v-if="loading"
      role="status"
      :aria-label="t('boats.detail.loading')"
      aria-busy="true"
      class="mt-4 space-y-4"
    >
      <SkeletonLoader class="h-7 w-2/3" />
      <SkeletonLoader class="h-4 w-1/3" />
      <SkeletonLoader class="h-20 w-full" />
    </div>

    <template v-else-if="boat">
      <header
        class="mt-4 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between"
      >
        <h1 class="text-2xl font-bold text-slate-900 dark:text-slate-100">
          {{ boat.name }}
        </h1>
        <div class="flex flex-col gap-2 sm:flex-row">
          <RouterLink
            :to="{ name: 'boats.edit', params: { id: boat.id } }"
            class="inline-flex min-h-[44px] w-full items-center justify-center rounded-md bg-bleu-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-bleu-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-bleu-600 sm:w-auto"
          >
            {{ t('boats.detail.edit') }}
          </RouterLink>
          <button
            type="button"
            class="inline-flex min-h-[44px] w-full items-center justify-center rounded-md border border-brique-300 px-4 py-2.5 text-sm font-medium text-brique-700 hover:bg-brique-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brique-500 sm:w-auto dark:border-brique-700 dark:text-brique-300 dark:hover:bg-brique-900/30"
            @click="dialogOpen = true"
          >
            {{ t('boats.detail.delete') }}
          </button>
        </div>
      </header>

      <dl
        class="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2"
      >
        <div v-if="boat.description" class="md:col-span-2">
          <dt
            class="text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400"
          >
            {{ t('boats.fields.description') }}
          </dt>
          <dd
            class="mt-1 whitespace-pre-line text-slate-700 dark:text-slate-300"
          >
            {{ boat.description }}
          </dd>
        </div>
        <div>
          <dt
            class="text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400"
          >
            {{ t('boats.fields.createdAt') }}
          </dt>
          <dd class="mt-1 text-slate-700 dark:text-slate-300">
            {{ createdAtLabel }}
          </dd>
        </div>
        <div>
          <dt
            class="text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400"
          >
            {{ t('boats.fields.version') }}
          </dt>
          <dd class="mt-1 tabular-nums text-slate-700 dark:text-slate-300">
            {{ boat.version }}
          </dd>
        </div>
      </dl>

      <DeleteConfirmDialog
        :open="dialogOpen"
        :boat-name="boat.name"
        :loading="deleting"
        @cancel="dialogOpen = false"
        @confirm="confirmDelete"
      />
    </template>
  </section>
</template>
