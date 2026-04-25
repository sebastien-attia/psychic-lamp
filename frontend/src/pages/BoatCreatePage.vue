<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import BoatForm from '../components/BoatForm.vue'
import { useBoatsStore } from '../stores/boats'
import { showSuccess } from '../composables/useToast'
import type {
  BoatCreateRequest,
  BoatResponse,
} from '../services/api-client/generated/models'

/**
 * Create-boat page. Delegates field rendering to `BoatForm`; the submit
 * handler invokes the boats store, surfaces a success toast, and
 * redirects to the new boat's detail page on success.
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
 * `BoatForm` `@saved` handler: shows a success toast and navigates to
 * the new boat's detail page.
 */
function onSaved(boat: BoatResponse): void {
  showSuccess(t('boats.success.created'))
  void router.push({ name: 'boats.detail', params: { id: boat.id } })
}

/**
 * `BoatForm` `@cancel` handler: returns the user to the list view.
 * `router.back()` would also work, but a deep-link directly to
 * `/boats/new` would otherwise reset the SPA.
 */
function onCancel(): void {
  void router.push({ name: 'boats.list' })
}
</script>

<template>
  <section class="mx-auto max-w-xl px-4">
    <h1 class="text-2xl font-bold text-slate-900 dark:text-slate-100">
      {{ t('boats.create.title') }}
    </h1>
    <div class="mt-6 rounded-lg bg-white p-6 shadow-sm dark:bg-slate-800">
      <BoatForm
        :submit="submit"
        :submit-label="t('boats.create.submit')"
        @saved="onSaved"
        @cancel="onCancel"
      />
    </div>
  </section>
</template>
