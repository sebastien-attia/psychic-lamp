<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import BoatForm from '../components/BoatForm.vue'
import { useBoatsStore } from '../stores/boats'
import { ApiProblemError, ConflictError } from '../services/problem-detail'
import { showSuccess } from '../composables/useToast'
import type {
  BoatCreateRequest,
  BoatResponse,
} from '../services/api-client/generated/models'

/**
 * Edit page for an existing boat. Loads the current state on mount,
 * pre-fills `BoatForm`, and submits with optimistic locking using the
 * `version` echoed by the previous read (sent as `If-Match`).
 *
 * Two distinct empty states:
 * - **404 on initial load** → "boat not found" with a link back.
 * - **409 on submit** (`ConflictError`) → "modified by another user"
 *   panel with a Refresh button that re-reads the boat and lets the
 *   user re-apply their changes against the new version.
 */
const props = defineProps<{
  /** UUID injected by Vue Router from `/boats/:id/edit`. */
  id: string
}>()

const router = useRouter()
const store = useBoatsStore()
const { t } = useI18n()

const boat = ref<BoatResponse | null>(null)
const notFound = ref(false)
const conflict = ref(false)

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
 * Submit handler passed to `BoatForm`. Forwards the payload to the boats
 * store along with the previously fetched `version` for optimistic
 * locking (sent as `If-Match`).
 *
 * A 409 surfaces as a typed `ConflictError`: we swap the form for a
 * recovery panel rather than let the form show a generic banner. Any
 * other error rethrows so the form can map field/global findings.
 */
async function submit(payload: BoatCreateRequest): Promise<BoatResponse> {
  if (!boat.value) {
    throw new Error('boat not loaded')
  }
  try {
    return await store.updateBoat(props.id, boat.value.version, payload)
  } catch (e) {
    if (e instanceof ConflictError) {
      // Defer the unmount until after vee-validate's `handleSubmit`
      // catch finishes resetting `isSubmitting` — otherwise the form
      // disappears in the same Vue flush as `isSubmitting = false`,
      // racing the form's own banner write against unmount.
      void nextTick().then(() => {
        conflict.value = true
      })
      throw e
    }
    throw e
  }
}

/**
 * `BoatForm` `@saved` handler: shows a success toast and navigates to
 * the boat's detail page.
 */
function onSaved(saved: BoatResponse): void {
  showSuccess(t('boats.success.updated'))
  void router.push({ name: 'boats.detail', params: { id: saved.id } })
}

/**
 * `BoatForm` `@cancel` handler: returns to the boat's detail page.
 */
function onCancel(): void {
  void router.push({ name: 'boats.detail', params: { id: props.id } })
}

/**
 * Re-fetch the boat after a 409 so the form picks up the latest
 * version. Clears the conflict banner; the user can then re-apply
 * their edits against the fresh state.
 */
async function refresh(): Promise<void> {
  try {
    boat.value = await store.getBoat(props.id)
    conflict.value = false
  } catch (e) {
    if (e instanceof ApiProblemError && e.status === 404) {
      notFound.value = true
      conflict.value = false
      return
    }
    throw e
  }
}
</script>

<template>
  <section v-if="notFound" class="mx-auto max-w-xl px-4 text-center">
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

  <section v-else-if="boat" class="mx-auto max-w-xl px-4">
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
      <RouterLink
        :to="{ name: 'boats.detail', params: { id: boat.id } }"
        class="hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-bleu-500"
      >
        {{ boat.name }}
      </RouterLink>
      <span aria-hidden="true" class="mx-2">›</span>
      <span aria-current="page" class="text-slate-700 dark:text-slate-200">
        {{ t('boats.edit.title') }}
      </span>
    </nav>
    <h1 class="mt-2 text-2xl font-bold text-slate-900 dark:text-slate-100">
      {{ t('boats.edit.title') }}
    </h1>

    <div
      v-if="conflict"
      role="alert"
      class="mt-6 rounded-md border border-brique-300 bg-brique-50 p-4 text-sm text-brique-800 dark:border-brique-700 dark:bg-brique-900/30 dark:text-brique-100"
    >
      <p class="font-semibold">{{ t('boats.edit.conflict.title') }}</p>
      <p class="mt-1">{{ t('boats.edit.conflict.message') }}</p>
      <button
        type="button"
        class="mt-3 inline-flex min-h-[44px] w-full items-center justify-center rounded-md bg-brique-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-brique-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brique-600 sm:w-auto"
        @click="refresh"
      >
        {{ t('boats.edit.conflict.refresh') }}
      </button>
    </div>

    <div
      v-else
      class="mt-6 rounded-lg bg-white p-6 shadow-sm dark:bg-slate-800"
    >
      <BoatForm
        :initial="boat"
        :submit="submit"
        :submit-label="t('boats.edit.submit')"
        @saved="onSaved"
        @cancel="onCancel"
      />
    </div>
  </section>
</template>
