<script setup lang="ts">
/**
 * Animated `animate-pulse` placeholder block. Sized via the parent's
 * utility classes (`class` prop is forwarded), so a caller writes
 * `<SkeletonLoader class="h-4 w-32" />` rather than passing dimensions
 * as props.
 *
 * Used by `BoatListPage` to render a 6-card skeleton grid during the
 * very first fetch (subsequent paginations keep the existing grid
 * visible to avoid flicker).
 */
</script>

<template>
  <div
    class="animate-pulse rounded bg-slate-200 dark:bg-slate-700"
    aria-hidden="true"
  />
</template>
