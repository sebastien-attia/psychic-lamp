<script setup lang="ts">
import { computed, useId } from 'vue'
import { ChevronLeftIcon, ChevronRightIcon } from '@heroicons/vue/24/outline'

/**
 * Pagination control with prev/next, numbered page pills, and a
 * page-size selector.
 *
 * Two-way bindings: `v-model:current-page` (zero-based page index) and
 * `v-model:page-size`. The component emits the standard
 * `update:currentPage` / `update:pageSize` events; templates use
 * kebab-case (`v-model:current-page`).
 *
 * The numbered pills are windowed: the first and last page are always
 * shown, plus the current page ±2, with `…` separators where there are
 * gaps. On viewports below `sm` the pills collapse to a "Page X of Y"
 * label so a 47-page list does not overflow on a 375 px screen.
 *
 * The page-size selector is a native `<select>` styled by
 * `@tailwindcss/forms` — accessible, keyboard-friendly, and uses the
 * native picker on mobile.
 */
const props = defineProps<{
  /** Zero-based current page index. */
  currentPage: number
  /** Total number of pages (≥ 1; the page does not render this when 1). */
  totalPages: number
  /** Current page size. */
  pageSize: number
  /** Allowed page sizes for the size selector. */
  pageSizeOptions: number[]
  /** Localized accessible name for the surrounding `<nav>` landmark. */
  navLabel: string
  /** Localized "Previous" button label. */
  prevLabel: string
  /** Localized "Next" button label. */
  nextLabel: string
  /** Localized page-size selector label (rendered visually hidden). */
  pageSizeLabel: string
  /** Localized "Page X of Y" template, with `{current}` and `{total}`. */
  pageOfLabel: string
  /** Localized template for one page-size `<option>`, with `{size}`. */
  pageSizeOptionLabel: string
}>()

const emit = defineEmits<{
  'update:currentPage': [page: number]
  'update:pageSize': [size: number]
}>()

const sizeId = useId()

/** Total pill count above which the windowing algorithm collapses
 *  intermediate pages into ellipses — set so 1 + radius*2 + 1 + 2 (one
 *  ellipsis on each side) fits comfortably on a small viewport. */
const MAX_VISIBLE_WITHOUT_GAPS = 7
/** Pages shown on either side of the current page when windowed. */
const WINDOW_RADIUS = 2

const isFirst = computed(() => props.currentPage <= 0)
const isLast = computed(() => props.currentPage >= props.totalPages - 1)

const pageOfText = computed(() =>
  props.pageOfLabel
    .replace('{current}', String(props.currentPage + 1))
    .replace('{total}', String(props.totalPages)),
)

/**
 * Compute the windowed pill sequence: first and last page anchors plus
 * `current ± 2`, joined by `'gap'` markers where there are missing
 * numbers. Returned as an array of zero-based page indices and the
 * literal `'gap'` sentinel so the template can render `…` ellipses
 * without re-deriving the structure.
 */
const pages = computed<(number | 'gap')[]>(() => {
  const total = props.totalPages
  const current = props.currentPage
  if (total <= MAX_VISIBLE_WITHOUT_GAPS) {
    return Array.from({ length: total }, (_, i) => i)
  }
  const out: (number | 'gap')[] = [0]
  const start = Math.max(1, current - WINDOW_RADIUS)
  const end = Math.min(total - 2, current + WINDOW_RADIUS)
  // Only emit a gap if it would skip ≥ 2 numbers — collapsing a
  // single missing page into "…" looks like a bug, so we render it
  // inline instead.
  if (start > 2) out.push('gap')
  else for (let i = 1; i < start; i++) out.push(i)
  for (let i = start; i <= end; i++) out.push(i)
  if (end < total - 3) out.push('gap')
  else for (let i = end + 1; i < total - 1; i++) out.push(i)
  out.push(total - 1)
  return out
})

/**
 * Emit a `currentPage` update unless the target page is out of range
 * or the same as the current page.
 */
function goTo(page: number): void {
  if (page < 0 || page >= props.totalPages || page === props.currentPage) return
  emit('update:currentPage', page)
}

/**
 * Forward a `pageSize` change to the parent. Resetting `currentPage`
 * to zero on a size change is the parent's responsibility.
 */
function onSize(e: Event): void {
  const v = Number((e.target as HTMLSelectElement).value)
  emit('update:pageSize', v)
}
</script>

<template>
  <nav
    :aria-label="navLabel"
    class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"
  >
    <!-- Mobile: collapsed prev/next + page-of-total -->
    <div class="flex items-center justify-between gap-2 sm:hidden">
      <button
        type="button"
        :disabled="isFirst"
        class="inline-flex items-center gap-1 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-40 disabled:hover:bg-white dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700"
        @click="goTo(currentPage - 1)"
      >
        <ChevronLeftIcon class="h-4 w-4" aria-hidden="true" />
        {{ prevLabel }}
      </button>
      <span class="text-sm text-slate-600 dark:text-slate-300">{{ pageOfText }}</span>
      <button
        type="button"
        :disabled="isLast"
        class="inline-flex items-center gap-1 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-40 disabled:hover:bg-white dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700"
        @click="goTo(currentPage + 1)"
      >
        {{ nextLabel }}
        <ChevronRightIcon class="h-4 w-4" aria-hidden="true" />
      </button>
    </div>

    <!-- Desktop: full pill row -->
    <ul class="hidden items-center gap-1 sm:flex">
      <li>
        <button
          type="button"
          :disabled="isFirst"
          :aria-label="prevLabel"
          class="inline-flex items-center rounded-md p-1.5 text-slate-700 hover:bg-slate-100 disabled:opacity-40 disabled:hover:bg-transparent dark:text-slate-300 dark:hover:bg-slate-800"
          @click="goTo(currentPage - 1)"
        >
          <ChevronLeftIcon class="h-5 w-5" aria-hidden="true" />
        </button>
      </li>
      <li v-for="(p, idx) in pages" :key="`${p}-${idx}`">
        <span
          v-if="p === 'gap'"
          class="px-2 text-slate-400"
          aria-hidden="true"
        >…</span>
        <button
          v-else
          type="button"
          :aria-current="p === currentPage ? 'page' : undefined"
          :class="[
            'min-w-[2rem] rounded-md px-2.5 py-1 text-sm font-medium',
            p === currentPage
              ? 'bg-amber-500 text-white shadow-sm'
              : 'text-slate-700 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800',
          ]"
          @click="goTo(p)"
        >
          {{ p + 1 }}
        </button>
      </li>
      <li>
        <button
          type="button"
          :disabled="isLast"
          :aria-label="nextLabel"
          class="inline-flex items-center rounded-md p-1.5 text-slate-700 hover:bg-slate-100 disabled:opacity-40 disabled:hover:bg-transparent dark:text-slate-300 dark:hover:bg-slate-800"
          @click="goTo(currentPage + 1)"
        >
          <ChevronRightIcon class="h-5 w-5" aria-hidden="true" />
        </button>
      </li>
    </ul>

    <div class="flex items-center gap-2">
      <label :for="sizeId" class="sr-only">{{ pageSizeLabel }}</label>
      <select
        :id="sizeId"
        :value="pageSize"
        class="rounded-md border-slate-300 bg-white py-1.5 pl-3 pr-8 text-sm text-slate-700 shadow-sm focus:border-nautical-500 focus:ring-nautical-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
        @change="onSize"
      >
        <option v-for="opt in pageSizeOptions" :key="opt" :value="opt">
          {{ pageSizeOptionLabel.replace('{size}', String(opt)) }}
        </option>
      </select>
    </div>
  </nav>
</template>
