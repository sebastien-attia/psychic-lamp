<script setup lang="ts">
/**
 * Three-colour brand glyph: a Bleu hull and mast, an Olive main sail,
 * and a tiny Brique pennant on top of the mast. Used in the nav next
 * to the wordmark and shipped as `public/favicon.svg`.
 *
 * Colours are baked into the SVG (not `currentColor`) because the
 * glyph is intentionally polychrome — every brand colour shows up at
 * once, so the mark reads the same on light and dark backgrounds.
 */
withDefaults(
  defineProps<{
    /** Pixel size of the rendered square. */
    size?: number
  }>(),
  { size: 28 },
)
</script>

<template>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 48 48"
    :width="size"
    :height="size"
    aria-hidden="true"
    focusable="false"
  >
    <path d="M4 34 L44 34 L36 42 L12 42 Z" fill="#0038B8" />
    <rect x="23" y="6" width="2" height="28" fill="#0038B8" />
    <path d="M25 10 L40 32 L25 32 Z" fill="#94B800" />
    <path d="M23 6 L32 9 L23 12 Z" fill="#B82200" />
  </svg>
</template>
