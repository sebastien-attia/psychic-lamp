<script setup lang="ts">
import { ref } from 'vue'
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { z } from 'zod'
import { useI18n } from 'vue-i18n'
import { ApiProblemError } from '../services/problem-detail'
import type {
  BoatCreateRequest,
  BoatResponse,
} from '../services/api-client/generated/models'

/**
 * Reactive form for creating or editing a boat. Wraps vee-validate +
 * zod and surfaces server-side `ProblemDetail` findings: per-field
 * messages flow into vee-validate, global messages and unexpected
 * errors render as a top-of-form banner.
 */
const props = defineProps<{
  /** Pre-fill values when editing; omitted when creating. */
  initial?: BoatResponse
  /**
   * Submit handler that performs the API call. Resolves with the
   * persisted boat on success; throws (typically `ApiProblemError`) on
   * failure. The form is responsible for catching that and mapping
   * field errors back into vee-validate.
   */
  submit: (payload: BoatCreateRequest) => Promise<BoatResponse>
  /** Label for the primary submit button. */
  submitLabel: string
}>()

const emit = defineEmits<{
  /** Fired with the persisted boat after a successful save. */
  (e: 'saved', boat: BoatResponse): void
}>()

const { t } = useI18n()

/**
 * Top-of-form banner content. Populated from `ApiProblemError.globalErrors()`
 * or a generic message for unexpected (non-`ProblemDetail`) rejections.
 */
const formError = ref<string | null>(null)

/** Validation schema mirroring the OpenAPI constraints (1..64 / ≤256). */
const schema = toTypedSchema(
  z.object({
    name: z
      .string()
      .min(1, t('errors.required'))
      .max(64, t('errors.tooLong', { max: 64 })),
    description: z
      .string()
      .max(256, t('errors.tooLong', { max: 256 }))
      .optional()
      .or(z.literal('')),
  }),
)

const { handleSubmit, errors, defineField, setErrors, isSubmitting } = useForm({
  validationSchema: schema,
  initialValues: {
    name: props.initial?.name ?? '',
    description: props.initial?.description ?? '',
  },
})

const [name, nameAttrs] = defineField('name')
const [description, descriptionAttrs] = defineField('description')

const onSubmit = handleSubmit(async (values) => {
  formError.value = null
  const trimmedName = values.name.trim()
  const trimmedDesc = values.description?.trim()
  try {
    const saved = await props.submit({
      name: trimmedName,
      description: trimmedDesc ? trimmedDesc : null,
    })
    emit('saved', saved)
  } catch (e) {
    if (e instanceof ApiProblemError) {
      const fieldErrs = e.fieldErrors()
      if (Object.keys(fieldErrs).length > 0) {
        setErrors(fieldErrs)
      }
      const globals = e.globalErrors()
      formError.value = globals.length > 0 ? globals.join(' ') : (e.detail ?? e.title)
      return
    }
    formError.value = t('errors.generic')
  }
})
</script>

<template>
  <form class="space-y-4" novalidate @submit="onSubmit">
    <div
      v-if="formError"
      role="alert"
      class="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-700 dark:bg-red-900/30 dark:text-red-200"
    >
      {{ formError }}
    </div>

    <div>
      <label
        for="boat-name"
        class="block text-sm font-medium text-slate-700 dark:text-slate-200"
      >
        {{ t('boats.fields.name') }}
      </label>
      <input
        id="boat-name"
        v-model="name"
        v-bind="nameAttrs"
        type="text"
        maxlength="64"
        :aria-invalid="!!errors.name"
        aria-describedby="boat-name-error"
        class="mt-1 block w-full rounded-md border-slate-300 shadow-sm focus:border-nautical-500 focus:ring-nautical-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
      />
      <p
        id="boat-name-error"
        v-if="errors.name"
        class="mt-1 text-sm text-red-600"
        aria-live="polite"
      >
        {{ errors.name }}
      </p>
    </div>

    <div>
      <label
        for="boat-description"
        class="block text-sm font-medium text-slate-700 dark:text-slate-200"
      >
        {{ t('boats.fields.description') }}
      </label>
      <textarea
        id="boat-description"
        v-model="description"
        v-bind="descriptionAttrs"
        rows="3"
        maxlength="256"
        :aria-invalid="!!errors.description"
        aria-describedby="boat-description-error"
        class="mt-1 block w-full rounded-md border-slate-300 shadow-sm focus:border-nautical-500 focus:ring-nautical-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
      />
      <p
        id="boat-description-error"
        v-if="errors.description"
        class="mt-1 text-sm text-red-600"
        aria-live="polite"
      >
        {{ errors.description }}
      </p>
    </div>

    <div class="flex justify-end gap-2">
      <button
        type="submit"
        :disabled="isSubmitting"
        class="inline-flex justify-center rounded-md bg-nautical-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-nautical-700 focus:outline-none focus:ring-2 focus:ring-nautical-500 disabled:opacity-50"
      >
        {{ submitLabel }}
      </button>
    </div>
  </form>
</template>
