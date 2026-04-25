<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import BoatForm from '../components/BoatForm.vue'
import { useBoatsStore } from '../stores/boats'
import type {
  BoatCreateRequest,
  BoatResponse,
} from '../services/api-client/generated/models'

/**
 * Create-boat page. Delegates field rendering to `BoatForm`; the submit
 * handler invokes the boats store and redirects to the new boat's
 * detail page on success.
 */
const router = useRouter()
const store = useBoatsStore()
const { t } = useI18n()

/**
 * Submit handler passed to `BoatForm`: forwards the payload to the
 * Pinia boats store. The form translates any `ApiProblemError` into
 * per-field validation messages.
 */
async function submit(payload: BoatCreateRequest): Promise<BoatResponse> {
  return store.createBoat(payload)
}

/**
 * `BoatForm` `@saved` handler: navigates to the new boat's detail page.
 */
function onSaved(boat: BoatResponse): void {
  void router.push({ name: 'boats.detail', params: { id: boat.id } })
}
</script>

<template>
  <section class="mx-auto max-w-2xl">
    <h1 class="text-2xl font-bold text-slate-900 dark:text-slate-100">
      {{ t('boats.create.title') }}
    </h1>
    <div class="mt-6 rounded-lg bg-white p-6 shadow-sm dark:bg-slate-800">
      <BoatForm
        :submit="submit"
        :submit-label="t('boats.create.submit')"
        @saved="onSaved"
      />
    </div>
  </section>
</template>
