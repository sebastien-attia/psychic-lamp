<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import BoatForm from '../components/BoatForm.vue'
import { useBoatsStore } from '../stores/boats'
import { ApiProblemError } from '../services/problem-detail'
import type {
  BoatCreateRequest,
  BoatResponse,
} from '../services/api-client/generated/models'

/**
 * Edit page for an existing boat. Loads the current state on mount,
 * pre-fills `BoatForm`, and submits with optimistic locking using the
 * `version` echoed by the previous read (sent as `If-Match`). A 404 on
 * load renders an empty state instead of a blank page.
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
 * locking (sent as `If-Match`). Throws if the boat hasn't loaded yet —
 * the form's submit button is disabled while `boat` is null because the
 * page is wrapped in `v-if="boat"`.
 */
async function submit(payload: BoatCreateRequest): Promise<BoatResponse> {
  if (!boat.value) {
    throw new Error('boat not loaded')
  }
  return store.updateBoat(props.id, boat.value.version, payload)
}

/**
 * `BoatForm` `@saved` handler: navigates to the boat's detail page.
 */
function onSaved(saved: BoatResponse): void {
  void router.push({ name: 'boats.detail', params: { id: saved.id } })
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
    <h1 class="text-2xl font-bold text-slate-900 dark:text-slate-100">
      {{ t('boats.edit.title') }}
    </h1>
    <div class="mt-6 rounded-lg bg-white p-6 shadow-sm dark:bg-slate-800">
      <BoatForm
        :initial="boat"
        :submit="submit"
        :submit-label="t('boats.edit.submit')"
        @saved="onSaved"
      />
    </div>
  </section>
</template>
