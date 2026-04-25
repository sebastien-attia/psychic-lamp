<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type LocationQuery } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { storeToRefs } from 'pinia'
import { InboxIcon, MagnifyingGlassIcon, PlusIcon } from '@heroicons/vue/24/outline'

import { useBoatsStore } from '../stores/boats'
import { useDebouncedRef } from '../composables/useDebouncedRef'
import { showError, showSuccess } from '../composables/useToast'
import BoatCard from '../components/boats/BoatCard.vue'
import DeleteConfirmDialog from '../components/DeleteConfirmDialog.vue'
import Badge from '../components/ui/Badge.vue'
import EmptyState from '../components/ui/EmptyState.vue'
import ErrorState from '../components/ui/ErrorState.vue'
import Pagination from '../components/ui/Pagination.vue'
import SearchInput from '../components/ui/SearchInput.vue'
import SkeletonLoader from '../components/ui/SkeletonLoader.vue'
import type { BoatResponse } from '../services/api-client/generated/models'

/**
 * Paginated, searchable boat list page (UC2 + UC5).
 *
 * The URL is the single source of truth: `?page=`, `?size=`, `?search=`
 * are parsed into a derived fetch key and a single watcher drives
 * `store.fetchBoats`. User actions (typing, paging, resizing) call
 * `pushQuery()` to write the URL; the watcher then re-fetches. A
 * deduplication key prevents `router.replace` from causing a double
 * fetch, and the store's `requestId` token discards out-of-order
 * responses.
 *
 * Browser back/forward "just works" because the URL is canonical: a
 * separate watcher reflects URL search changes back into the input
 * field, so navigating preserves both the page and the search text.
 */

const router = useRouter()
const route = useRoute()
const { t } = useI18n()

const store = useBoatsStore()
const { boats, totalElements, totalPages, currentPage, pageSize, loading, error } =
  storeToRefs(store)

const PAGE_SIZE_OPTIONS = [12, 24, 48] as const
const DEFAULT_PAGE_SIZE = 12

/**
 * Coerce a raw query-string value (string | string[] | null) to a
 * single string. Used for `search` which may legitimately be empty.
 */
function parseSearch(raw: unknown): string {
  if (typeof raw === 'string') return raw
  if (Array.isArray(raw) && typeof raw[0] === 'string') return raw[0]
  return ''
}

/**
 * Parse and clamp a `page` query value. Negative or non-numeric values
 * fall back to 0; the upper bound is enforced server-side via the
 * cold-load page-out-of-range recovery in the post-fetch handler.
 */
function parsePage(raw: unknown): number {
  const n = Number(parseSearch(raw))
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0
}

/**
 * Parse and clamp a `size` query value to one of `PAGE_SIZE_OPTIONS`.
 * Out-of-range values silently fall back to the default rather than
 * being clamped to the nearest allowed value, since silent corruption
 * (e.g. `?size=999` becoming `48`) is harder for users to diagnose.
 */
function parseSize(raw: unknown): number {
  const n = Number(parseSearch(raw))
  return PAGE_SIZE_OPTIONS.includes(n as 12 | 24 | 48) ? n : DEFAULT_PAGE_SIZE
}

const searchInput = ref(parseSearch(route.query.search))
const [debouncedSearch, flushDebouncedSearch] = useDebouncedRef(searchInput, 300)

let lastFetchKey = ''

/**
 * Derived view of the current URL state, used both as the fetch input
 * and as the dedup key.
 */
const queryState = computed(() => ({
  page: parsePage(route.query.page),
  size: parseSize(route.query.size),
  search: parseSearch(route.query.search).trim(),
}))

/**
 * Build a clean query object: defaults are omitted so URLs stay short
 * (`/boats` rather than `/boats?page=0&size=12&search=`).
 */
function buildQuery(next: { page: number; size: number; search: string }): LocationQuery {
  const out: LocationQuery = {}
  if (next.page > 0) out.page = String(next.page)
  if (next.size !== DEFAULT_PAGE_SIZE) out.size = String(next.size)
  if (next.search) out.search = next.search
  return out
}

/**
 * Write `next` to the URL via `router.replace`. The single
 * `queryState` watcher below picks the change up and runs the fetch.
 */
function pushQuery(next: { page: number; size: number; search: string }): void {
  void router.replace({ query: buildQuery(next) })
}

/**
 * Run a fetch unconditionally (bypassing the dedup key). Used by the
 * retry button and the post-delete refresh, where we explicitly want
 * to re-issue the same query that's already in the URL. Updates the
 * dedup key so the watcher's next echo no-ops.
 *
 * After the fetch lands, recover from the cold-load page-out-of-range
 * case (`?page=99` against a dataset with fewer pages) by pushing the
 * user to the last real page.
 */
function runFetch(state: { page: number; size: number; search: string }): Promise<void> {
  lastFetchKey = `${state.page}|${state.size}|${state.search}`
  return store
    .fetchBoats({ page: state.page, size: state.size, search: state.search || undefined })
    .then(() => {
      if (
        totalPages.value > 0 &&
        currentPage.value >= totalPages.value &&
        boats.value.length === 0
      ) {
        pushQuery({ page: totalPages.value - 1, size: state.size, search: state.search })
      }
    })
}

watch(
  queryState,
  (next) => {
    const key = `${next.page}|${next.size}|${next.search}`
    if (key === lastFetchKey) return
    void runFetch(next)
  },
  { immediate: true },
)

watch(debouncedSearch, (next) => {
  const trimmed = next.trim()
  if (trimmed === queryState.value.search) return
  pushQuery({ page: 0, size: queryState.value.size, search: trimmed })
})

watch(
  () => route.query.search,
  (q) => {
    const v = parseSearch(q)
    if (v === searchInput.value) return
    searchInput.value = v
    // Cancel any in-flight debounced write (e.g. from a partial type)
    // so back/forward navigations are not undone after the debounce
    // window elapses.
    flushDebouncedSearch()
  },
)

// Skeleton appears only on the very first fetch; subsequent fetches
// (paging, filtering an existing list) keep the previous results
// visible until the new ones arrive, avoiding a flicker. As a side
// effect, filtering down to "no matches" jumps straight to the empty
// state with no skeleton — that's intentional, not an oversight.
const initialLoading = computed(() => loading.value && boats.value.length === 0)

const summary = computed(() => {
  if (totalElements.value === 0) return ''
  const from = currentPage.value * pageSize.value + 1
  const to = Math.min(from + boats.value.length - 1, totalElements.value)
  return t('boats.list.pagination.summary', {
    from,
    to,
    total: totalElements.value,
  })
})

/**
 * Update the URL with a new page index. Bounds are enforced by the
 * `Pagination` component; this just writes the URL.
 */
function onPageChange(page: number): void {
  pushQuery({ page, size: queryState.value.size, search: queryState.value.search })
}

/**
 * Update the URL with a new page size and reset to page 0 — keeping a
 * non-zero page after a size change would put the user on a page that
 * may no longer exist (smaller pages mean more pages, larger means
 * fewer).
 */
function onSizeChange(size: number): void {
  pushQuery({ page: 0, size, search: queryState.value.search })
}

/**
 * Synchronously clear both the immediate input and the URL so the
 * filter UX feels instant — without this the URL would lag the
 * visible input clear by the 300 ms debounce window.
 */
function onClearSearch(): void {
  searchInput.value = ''
  pushQuery({ page: 0, size: queryState.value.size, search: '' })
}

/**
 * Navigate to the create page when the empty-state CTA is activated.
 */
function goCreate(): void {
  void router.push({ name: 'boats.create' })
}

/**
 * Navigate to the edit page for the supplied boat.
 */
function onEdit(boat: BoatResponse): void {
  void router.push({ name: 'boats.edit', params: { id: boat.id } })
}

const dialogOpen = ref(false)
const pendingDelete = ref<BoatResponse | null>(null)
const deleting = ref(false)

/**
 * Open the confirm dialog for a boat the user wants to delete.
 */
function askDelete(boat: BoatResponse): void {
  pendingDelete.value = boat
  dialogOpen.value = true
}

/**
 * Cancel a pending delete, dismissing the dialog. No-op while a
 * delete is already in flight (the dialog renders the spinner and
 * disables both buttons).
 */
function cancelDelete(): void {
  if (deleting.value) return
  dialogOpen.value = false
  pendingDelete.value = null
}

/**
 * Perform the delete, then either decrement the page (if we just
 * removed the last boat on a non-first page) or refetch the current
 * page so totals and the visible row reconcile with the server.
 *
 * The dialog stays mounted while the request is in flight so the
 * user sees the spinner; we only close it on success / failure.
 *
 * On failure, surface a one-shot error toast — re-using the page's
 * fetch `error` ref would replace the grid with the "couldn't load
 * your boats" panel and a Retry button that retries the *fetch*, not
 * the delete. Auth failures (401) are swallowed by the axios
 * interceptor's redirect-and-toast UX; we don't double-handle them
 * here.
 */
async function confirmDelete(): Promise<void> {
  if (!pendingDelete.value) return
  const id = pendingDelete.value.id
  deleting.value = true
  try {
    await store.deleteBoat(id)
  } catch {
    deleting.value = false
    dialogOpen.value = false
    pendingDelete.value = null
    showError(t('errors.generic'))
    return
  }
  deleting.value = false
  dialogOpen.value = false
  pendingDelete.value = null
  showSuccess(t('boats.success.deleted'))
  // Both branches go through `runFetch` so the dedup key is primed
  // either way — a future same-key write between delete and the
  // watcher's tick can't silently skip the refetch.
  const next =
    boats.value.length === 1 && queryState.value.page > 0
      ? { ...queryState.value, page: queryState.value.page - 1 }
      : queryState.value
  pushQuery(next)
  void runFetch(next)
}

/**
 * Retry handler for the error-state panel: re-issue the current query
 * unconditionally.
 */
function refetch(): void {
  void runFetch(queryState.value)
}
</script>

<template>
  <section>
    <header class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div class="flex items-center gap-3">
        <h1 class="text-2xl font-bold text-slate-900 dark:text-slate-100">
          {{ t('boats.list.title') }}
        </h1>
        <Badge v-if="!initialLoading && totalElements > 0">
          {{ t('boats.list.count', { count: totalElements }, totalElements) }}
        </Badge>
      </div>
      <RouterLink
        :to="{ name: 'boats.create' }"
        class="inline-flex items-center gap-2 rounded-md bg-amber-700 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-amber-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-700"
      >
        <PlusIcon class="h-4 w-4" aria-hidden="true" />
        {{ t('boats.list.newBoat') }}
      </RouterLink>
    </header>

    <SearchInput
      v-model="searchInput"
      class="mt-6"
      :label="t('boats.list.search.label')"
      :placeholder="t('boats.list.search.placeholder')"
      :clear-label="t('boats.list.search.clear')"
      @clear="onClearSearch"
    />

    <p
      v-if="summary && !initialLoading && !error"
      aria-live="polite"
      class="mt-4 text-sm text-slate-500 dark:text-slate-400"
    >
      {{ summary }}
    </p>

    <div class="mt-6">
      <div
        v-if="initialLoading"
        class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
        aria-busy="true"
        :aria-label="t('boats.list.loading')"
      >
        <div
          v-for="n in 6"
          :key="n"
          class="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800"
        >
          <SkeletonLoader class="h-5 w-2/3" />
          <SkeletonLoader class="mt-3 h-3 w-full" />
          <SkeletonLoader class="mt-2 h-3 w-5/6" />
          <SkeletonLoader class="mt-4 h-3 w-1/3" />
        </div>
      </div>

      <ErrorState
        v-else-if="error"
        :title="t('boats.list.error.title')"
        :message="t('boats.list.error.message')"
        :retry-label="t('boats.list.error.retry')"
        @retry="refetch"
      />

      <EmptyState
        v-else-if="boats.length === 0 && !queryState.search"
        :icon="InboxIcon"
        :title="t('boats.list.empty.title')"
        :message="t('boats.list.empty.message')"
        :cta-label="t('boats.list.empty.cta')"
        @cta="goCreate"
      />

      <EmptyState
        v-else-if="boats.length === 0"
        :icon="MagnifyingGlassIcon"
        :title="t('boats.list.noResults.title')"
        :message="t('boats.list.noResults.message')"
      />

      <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <BoatCard
          v-for="boat in boats"
          :key="boat.id"
          :boat="boat"
          @edit="onEdit"
          @delete="askDelete"
        />
      </div>
    </div>

    <Pagination
      v-if="totalPages > 1"
      class="mt-8"
      :current-page="currentPage"
      :total-pages="totalPages"
      :page-size="pageSize || DEFAULT_PAGE_SIZE"
      :page-size-options="[...PAGE_SIZE_OPTIONS]"
      :nav-label="t('boats.list.pagination.nav')"
      :prev-label="t('boats.list.pagination.prev')"
      :next-label="t('boats.list.pagination.next')"
      :page-size-label="t('boats.list.pagination.pageSize')"
      :page-of-label="t('boats.list.pagination.pageOf')"
      :page-size-option-label="t('boats.list.pagination.pageSizeOption')"
      @update:current-page="onPageChange"
      @update:page-size="onSizeChange"
    />

    <DeleteConfirmDialog
      :open="dialogOpen"
      :boat-name="pendingDelete?.name ?? ''"
      :loading="deleting"
      @cancel="cancelDelete"
      @confirm="confirmDelete"
    />
  </section>
</template>
