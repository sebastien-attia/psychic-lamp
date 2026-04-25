import { ref } from 'vue'
import { defineStore } from 'pinia'
import { boatsApi } from '../services/api'
import type {
  BoatCreateRequest,
  BoatResponse,
  BoatUpdateRequest,
} from '../services/api-client/generated/models'

/**
 * Pinia store for the boat collection — wraps the generated
 * `BusinessServiceApi` with Vue-reactive caching.
 *
 * Pages call the action methods rather than the API client directly so that
 * list refreshes after mutations are centralized here.
 */
export const useBoatsStore = defineStore('boats', () => {
  /** Cached boats from the most recent `fetchBoats()` call. */
  const list = ref<BoatResponse[]>([])

  /** True while a request is in flight. Used by pages for spinner state. */
  const loading = ref(false)

  /**
   * Fetch the first page of boats and replace the cached list. Errors are
   * propagated to the caller (vue-router guards / pages), not stored —
   * keeping a single source of truth for failure handling.
   *
   * @param search optional case-insensitive name substring filter.
   */
  async function fetchBoats(search?: string): Promise<void> {
    loading.value = true
    try {
      const { data } = await boatsApi.listBoats(0, 50, undefined, search)
      list.value = data.content
    } finally {
      loading.value = false
    }
  }

  /**
   * Fetch a single boat by id (does not touch the list cache).
   *
   * @param id UUID of the boat to fetch.
   * @returns the freshly-fetched `BoatResponse`.
   */
  async function getBoat(id: string): Promise<BoatResponse> {
    const { data } = await boatsApi.getBoat(id)
    return data
  }

  /**
   * Create a new boat and prepend it to the cached list.
   *
   * @param payload validated `BoatCreateRequest`.
   * @returns the persisted `BoatResponse` (with server-assigned id/version).
   */
  async function createBoat(payload: BoatCreateRequest): Promise<BoatResponse> {
    const { data } = await boatsApi.createBoat(payload)
    list.value = [data, ...list.value]
    return data
  }

  /**
   * Update an existing boat using optimistic locking.
   *
   * @param id      UUID of the boat to update.
   * @param version the `version` echoed from the previous read (sent as `If-Match`).
   *                Per the contract this is the bare integer (e.g. `3`), not
   *                an RFC 7232 quoted entity-tag — the Business Service parses
   *                it as `Long`.
   * @param payload validated `BoatUpdateRequest`.
   * @returns the updated `BoatResponse` with bumped `version`.
   */
  async function updateBoat(
    id: string,
    version: number,
    payload: BoatUpdateRequest,
  ): Promise<BoatResponse> {
    const { data } = await boatsApi.updateBoat(id, String(version), payload)
    list.value = list.value.map((b) => (b.id === data.id ? data : b))
    return data
  }

  /**
   * Delete a boat by id and drop it from the cached list.
   *
   * @param id UUID of the boat to delete.
   */
  async function deleteBoat(id: string): Promise<void> {
    await boatsApi.deleteBoat(id)
    list.value = list.value.filter((b) => b.id !== id)
  }

  return {
    list,
    loading,
    fetchBoats,
    getBoat,
    createBoat,
    updateBoat,
    deleteBoat,
  }
})
