import { ref } from 'vue'
import { defineStore } from 'pinia'
import { boatsApi } from '../services/api'
import { ApiProblemError } from '../services/problem-detail'
import type {
  BoatCreateRequest,
  BoatResponse,
  BoatUpdateRequest,
} from '../services/api-client/generated/models'

/**
 * Arguments accepted by {@link useBoatsStore.fetchBoats}.
 *
 * `page` is zero-based; `size` must match a value the page-size
 * selector exposes (the page is responsible for clamping). `search` is
 * trimmed and dropped if empty before being forwarded to the API.
 */
export interface FetchBoatsArgs {
  /** Zero-based page index. */
  page: number
  /** Page size. */
  size: number
  /** Optional case-insensitive name/description substring filter. */
  search?: string
}

/**
 * Pinia store for the paginated boat collection. Wraps the generated
 * `BusinessServiceApi` with Vue-reactive state for the list page
 * (`boats`, totals, current page/size, search, loading, error) and
 * thin pass-through actions for single-item CRUD.
 *
 * The store does **not** mutate `boats` after `createBoat` /
 * `deleteBoat`: with pagination and search active, optimistic in-place
 * mutation produces misleading totals and out-of-page items, so the
 * caller is expected to refetch after a mutation.
 *
 * Error handling differs by action:
 * - `fetchBoats` swallows non-401 errors into `error` so the page can
 *   render an `<ErrorState>`. 401 is owned by the axios interceptor in
 *   `services/http.ts` (toast + Keycloak redirect), so the store
 *   deliberately ignores 401 to avoid a flash of error UI mid-redirect.
 * - `createBoat`, `updateBoat`, `deleteBoat` rethrow because
 *   `BoatForm.vue` relies on catching `ApiProblemError` to map field
 *   errors via vee-validate's `setErrors()`.
 */
export const useBoatsStore = defineStore('boats', () => {
  /** Current page of boats, replaced on every successful fetch. */
  const boats = ref<BoatResponse[]>([])
  /** Total boats across all pages (from server). */
  const totalElements = ref(0)
  /** Total page count (from server). */
  const totalPages = ref(0)
  /** Zero-based current page index (from server). */
  const currentPage = ref(0)
  /** Active page size (from server). */
  const pageSize = ref(0)
  /** Active search filter (trimmed). */
  const search = ref('')
  /** True while a fetch is in flight. */
  const loading = ref(false)
  /** Last non-401 fetch error, or `null` when the most recent fetch succeeded. */
  const error = ref<Error | null>(null)

  /**
   * Monotonic counter incremented on every `fetchBoats` call. The
   * resolved response is discarded if the counter has moved on by the
   * time it lands, so out-of-order responses (typing fast → multiple
   * in-flight requests) cannot clobber the latest state.
   */
  let requestId = 0

  /**
   * Fetch a page of boats and replace the cached state. Errors set the
   * `error` ref instead of being rethrown, so the page can render an
   * `<ErrorState>` reactively. 401 is suppressed: the axios
   * interceptor already toasts and redirects to Keycloak.
   *
   * @param args page, size, and optional search filter.
   */
  async function fetchBoats(args: FetchBoatsArgs): Promise<void> {
    const myId = ++requestId
    loading.value = true
    error.value = null
    const trimmed = args.search?.trim() || undefined
    try {
      const { data } = await boatsApi.listBoats(args.page, args.size, undefined, trimmed)
      if (myId !== requestId) return
      boats.value = data.content
      totalElements.value = data.totalElements
      totalPages.value = data.totalPages
      currentPage.value = data.number
      pageSize.value = data.size
      search.value = trimmed ?? ''
    } catch (e) {
      if (myId !== requestId) return
      if (e instanceof ApiProblemError && e.status === 401) return
      error.value = e instanceof Error ? e : new Error(String(e))
    } finally {
      if (myId === requestId) loading.value = false
    }
  }

  /**
   * Fetch a single boat by id. Errors propagate to the caller — the
   * detail page narrows on `ApiProblemError.status === 404` to render
   * an empty state.
   *
   * @param id UUID of the boat to fetch.
   */
  async function getBoat(id: string): Promise<BoatResponse> {
    const { data } = await boatsApi.getBoat(id)
    return data
  }

  /**
   * Create a new boat. Errors propagate so `BoatForm` can map field
   * errors via vee-validate. The caller is expected to refetch the
   * list afterwards rather than relying on cache mutation here.
   *
   * @param payload validated `BoatCreateRequest`.
   */
  async function createBoat(payload: BoatCreateRequest): Promise<BoatResponse> {
    const { data } = await boatsApi.createBoat(payload)
    return data
  }

  /**
   * Update an existing boat using optimistic locking. Errors propagate
   * for the same reason as {@link createBoat}.
   *
   * @param id      UUID of the boat to update.
   * @param version `version` echoed from the previous read, sent as
   *                the `If-Match` header. Per contract this is the
   *                bare integer (e.g. `3`), not an RFC 7232 quoted
   *                entity-tag — the Business Service parses it as
   *                `Long`.
   * @param payload validated `BoatUpdateRequest`.
   */
  async function updateBoat(
    id: string,
    version: number,
    payload: BoatUpdateRequest,
  ): Promise<BoatResponse> {
    const { data } = await boatsApi.updateBoat(id, String(version), payload)
    return data
  }

  /**
   * Delete a boat by id. Errors propagate so the caller can react.
   * The caller is expected to refetch the list afterwards.
   *
   * @param id UUID of the boat to delete.
   */
  async function deleteBoat(id: string): Promise<void> {
    await boatsApi.deleteBoat(id)
  }

  return {
    boats,
    totalElements,
    totalPages,
    currentPage,
    pageSize,
    search,
    loading,
    error,
    fetchBoats,
    getBoat,
    createBoat,
    updateBoat,
    deleteBoat,
  }
})
