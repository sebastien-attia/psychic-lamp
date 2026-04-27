<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { PencilSquareIcon, TrashIcon } from '@heroicons/vue/24/outline'
import { useNow } from '../../composables/useNow'
import {
  classesFor as monogramClasses,
  colorFor as monogramColor,
  initials as monogramInitials,
} from '../../utils/monogram'
import type { BoatResponse } from '../../services/api-client/generated/models'

/**
 * Compact card displaying a single boat in list views.
 *
 * The whole card is clickable as a link to the detail view, implemented
 * with the "stretched link" pattern: the title's `RouterLink` paints an
 * `::after` overlay across the card, while the action buttons sit
 * inside a `relative z-10` wrapper so they capture their own clicks
 * without producing nested-interactive HTML (which screen readers and
 * the HTML spec both reject).
 *
 * Edit and Delete are surfaced as ghost icon buttons; clicking them
 * emits `edit` / `delete` so the parent page owns navigation and the
 * confirmation flow.
 */
const props = defineProps<{
  /** The boat to display. */
  boat: BoatResponse
}>()

defineEmits<{
  /** Fires when the user clicks the edit icon button. */
  edit: [boat: BoatResponse]
  /** Fires when the user clicks the delete icon button. */
  delete: [boat: BoatResponse]
}>()

const { t, locale } = useI18n()
const now = useNow(60_000)

/**
 * Localized "Created X ago" caption, switching to an absolute date
 * once the boat is older than 30 days. Uses `Intl.RelativeTimeFormat`
 * (built-in, no extra dependency) so we get correct pluralisation in
 * every locale that vue-i18n already supports.
 *
 * Depends on a ticking `now` ref (1-minute cadence) so the caption
 * refreshes while the user has the list open — without it, a
 * boat created moments ago would keep saying "a few seconds ago"
 * indefinitely.
 */
const relativeCreatedAt = computed(() => {
  const created = new Date(props.boat.createdAt).getTime()
  const diffMs = now.value - created
  const minute = 60_000
  const hour = 60 * minute
  const day = 24 * hour
  const week = 7 * day
  const thirtyDays = 30 * day

  if (Number.isNaN(created)) return ''

  if (diffMs >= thirtyDays) {
    return t('boats.card.createdRelative', {
      relative: new Date(created).toLocaleDateString(locale.value),
    })
  }

  const rtf = new Intl.RelativeTimeFormat(locale.value, { numeric: 'auto' })
  const ago = (value: number, unit: Intl.RelativeTimeFormatUnit) => rtf.format(-value, unit)

  let relative: string
  if (diffMs < minute) relative = ago(Math.floor(diffMs / 1000), 'second')
  else if (diffMs < hour) relative = ago(Math.floor(diffMs / minute), 'minute')
  else if (diffMs < day) relative = ago(Math.floor(diffMs / hour), 'hour')
  else if (diffMs < week) relative = ago(Math.floor(diffMs / day), 'day')
  else relative = ago(Math.floor(diffMs / week), 'week')

  return t('boats.card.createdRelative', { relative })
})

/**
 * One- or two-letter uppercase monogram derived from the boat name.
 * Empty for blank/whitespace-only names — the template hides the
 * avatar when the string is empty so we never render an empty pill.
 */
const initials = computed(() => monogramInitials(props.boat.name))

/**
 * Tailwind class set for the monogram tile, picked deterministically
 * from the brand ramps so reloads don't change a boat's avatar colour.
 */
const monogramClass = computed(() => monogramClasses(monogramColor(props.boat.name)))
</script>

<template>
  <article
    class="relative flex flex-col rounded-lg border border-slate-200 bg-white p-4 shadow-sm transition-all hover:-translate-y-0.5 hover:border-bleu-300 hover:shadow-md focus-within:ring-2 focus-within:ring-bleu-500 dark:border-slate-700 dark:bg-slate-800 dark:hover:border-bleu-400"
  >
    <div class="flex items-start gap-3">
      <span
        v-if="initials"
        :class="[
          'inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-md text-sm font-semibold tracking-wide shadow-sm',
          monogramClass,
        ]"
        aria-hidden="true"
      >
        {{ initials }}
      </span>
      <h2 class="min-w-0 flex-1 text-lg font-semibold text-slate-900 dark:text-slate-100">
        <RouterLink
          :to="{ name: 'boats.detail', params: { id: boat.id } }"
          :title="boat.name"
          class="rounded-sm focus:outline-none after:absolute after:inset-0 after:rounded-lg after:content-['']"
        >
          <span class="block truncate">{{ boat.name }}</span>
        </RouterLink>
      </h2>
      <div class="relative z-10 flex shrink-0 gap-1">
        <button
          type="button"
          :aria-label="t('boats.card.edit')"
          class="inline-flex h-11 w-11 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-bleu-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-bleu-500 dark:hover:bg-slate-700 dark:hover:text-bleu-300"
          @click="$emit('edit', boat)"
        >
          <PencilSquareIcon class="h-5 w-5" aria-hidden="true" />
        </button>
        <button
          type="button"
          :aria-label="t('boats.card.delete')"
          class="inline-flex h-11 w-11 items-center justify-center rounded-md text-slate-400 transition hover:bg-brique-50 hover:text-brique-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brique-500 dark:hover:bg-brique-900/40 dark:hover:text-brique-300"
          @click="$emit('delete', boat)"
        >
          <TrashIcon class="h-5 w-5" aria-hidden="true" />
        </button>
      </div>
    </div>

    <p
      v-if="boat.description"
      class="mt-2 text-sm text-slate-600 line-clamp-2 dark:text-slate-300"
    >
      {{ boat.description }}
    </p>

    <p class="mt-3 text-xs text-slate-500 dark:text-slate-400">
      {{ relativeCreatedAt }}
    </p>
  </article>
</template>
