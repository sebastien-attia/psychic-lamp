<script setup lang="ts">
import { computed, ref } from 'vue'
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
 *
 * Validation timing — vee-validate's defaults already match the
 * "blur + after first submit" UX we want: blur triggers a single
 * field validation; once `submitCount > 0`, validation switches to
 * eager-on-input. No extra config is required.
 */
const NAME_MAX = 64
const DESCRIPTION_MAX = 256

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
  /**
   * Fired when the user clicks the form's Cancel button. Pages decide
   * what to do (typically `router.back()` or navigate to the list).
   */
  (e: 'cancel'): void
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
      .max(NAME_MAX, t('errors.tooLong', { max: NAME_MAX })),
    description: z
      .string()
      .max(DESCRIPTION_MAX, t('errors.tooLong', { max: DESCRIPTION_MAX }))
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

/** Live character count for the name input — used by the "X/64" counter. */
const nameLength = computed(() => name.value?.length ?? 0)
/** Live character count for the description textarea — "X/256". */
const descriptionLength = computed(() => description.value?.length ?? 0)

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
      class="rounded-md border border-brique-200 bg-brique-50 px-3 py-2 text-sm text-brique-700 dark:border-brique-700 dark:bg-brique-900/30 dark:text-brique-200"
    >
      {{ formError }}
    </div>

    <div>
      <div class="flex items-baseline justify-between">
        <label
          for="boat-name"
          class="block text-sm font-medium text-slate-700 dark:text-slate-200"
        >
          {{ t('boats.fields.name') }}
        </label>
        <span
          aria-hidden="true"
          class="text-xs tabular-nums text-slate-500 dark:text-slate-400"
        >
          {{ nameLength }}/{{ NAME_MAX }}
        </span>
      </div>
      <input
        id="boat-name"
        v-model="name"
        v-bind="nameAttrs"
        type="text"
        :maxlength="NAME_MAX"
        :aria-invalid="!!errors.name"
        :aria-describedby="errors.name ? 'boat-name-error' : undefined"
        class="mt-1 block w-full rounded-md border-slate-300 py-2.5 shadow-sm focus:border-bleu-500 focus:ring-bleu-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
      />
      <p
        id="boat-name-error"
        v-if="errors.name"
        class="mt-1 text-sm text-brique-600"
        aria-live="polite"
      >
        {{ errors.name }}
      </p>
    </div>

    <div>
      <div class="flex items-baseline justify-between">
        <label
          for="boat-description"
          class="block text-sm font-medium text-slate-700 dark:text-slate-200"
        >
          {{ t('boats.fields.description') }}
        </label>
        <span
          aria-hidden="true"
          class="text-xs tabular-nums text-slate-500 dark:text-slate-400"
        >
          {{ descriptionLength }}/{{ DESCRIPTION_MAX }}
        </span>
      </div>
      <textarea
        id="boat-description"
        v-model="description"
        v-bind="descriptionAttrs"
        rows="3"
        :maxlength="DESCRIPTION_MAX"
        :aria-invalid="!!errors.description"
        :aria-describedby="errors.description ? 'boat-description-error' : undefined"
        class="mt-1 block w-full rounded-md border-slate-300 shadow-sm focus:border-bleu-500 focus:ring-bleu-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
      />
      <p
        id="boat-description-error"
        v-if="errors.description"
        class="mt-1 text-sm text-brique-600"
        aria-live="polite"
      >
        {{ errors.description }}
      </p>
    </div>

    <div
      class="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end"
    >
      <button
        type="button"
        :disabled="isSubmitting"
        class="inline-flex w-full items-center justify-center rounded-md border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50 sm:w-auto dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-700"
        @click="emit('cancel')"
      >
        {{ t('actions.cancel') }}
      </button>
      <button
        type="submit"
        :disabled="isSubmitting"
        class="inline-flex w-full items-center justify-center gap-2 rounded-md bg-bleu-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm transition-transform hover:bg-bleu-700 focus:outline-none focus:ring-2 focus:ring-bleu-500 active:scale-[0.98] disabled:opacity-50 disabled:active:scale-100 sm:w-auto"
      >
        <svg
          v-if="isSubmitting"
          class="h-4 w-4 animate-spin"
          viewBox="0 0 24 24"
          fill="none"
          aria-hidden="true"
        >
          <circle
            class="opacity-25"
            cx="12"
            cy="12"
            r="10"
            stroke="currentColor"
            stroke-width="4"
          />
          <path
            class="opacity-75"
            fill="currentColor"
            d="M4 12a8 8 0 0 1 8-8v4a4 4 0 0 0-4 4H4z"
          />
        </svg>
        {{ submitLabel }}
      </button>
    </div>
  </form>
</template>
