/**
 * Helpers that turn a boat's display name into a stable, decorative
 * monogram avatar shown next to the title on `BoatCard`.
 *
 * The avatar is two pieces of state derived purely from the name:
 *
 * - {@link initials} — one or two uppercase letters extracted from the
 *   first words of the name.
 * - {@link colorFor} — a palette slot picked from a hash of the name,
 *   so the same boat keeps the same colour across reloads and across
 *   pages without any persisted state.
 *
 * Picking from the brand ramps (Bleu / Brique / Olive) instead of a
 * generic rainbow keeps the list cohesive — the avatar is an anchor
 * for the eye, not a competing accent.
 */

const PALETTE = ['bleu', 'brique', 'olive'] as const

/** Tailwind colour family used to render the monogram background. */
export type MonogramColor = (typeof PALETTE)[number]

/**
 * Cheap djb2-style 32-bit hash. Stable across browser engines, fast
 * enough to call once per render without memoisation. The bit-shift
 * keeps the result in 32-bit integer range so `% PALETTE.length` is
 * deterministic regardless of input length.
 *
 * @param input the string to hash.
 */
function hash(input: string): number {
  let h = 5381
  for (let i = 0; i < input.length; i++) {
    h = ((h << 5) + h + input.charCodeAt(i)) | 0
  }
  return Math.abs(h)
}

/**
 * Extract the first Unicode code point of `word`. Iterating with
 * `Array.from` (or `[...word]`) yields whole code points instead of
 * UTF-16 code units, so a surrogate-pair emoji like `🚤` is returned
 * intact rather than as the broken lead surrogate `\uD83D`.
 *
 * @param word a non-empty string fragment.
 * @returns the first code point as a string, or `''` if `word` is empty.
 */
function firstCodePoint(word: string): string {
  for (const ch of word) return ch
  return ''
}

/**
 * Extract one-or-two-character initials from a boat name.
 *
 * - Splits on whitespace, drops empty fragments produced by leading,
 *   trailing or doubled spaces.
 * - Uses the first code point of the first word, plus the first code
 *   point of the second word when present, both uppercased.
 * - Code-point-aware: emoji and astral-plane characters survive
 *   intact rather than being clipped at the UTF-16 surrogate boundary.
 * - Returns an empty string for an empty/whitespace-only name so the
 *   caller can hide the avatar instead of rendering an empty pill.
 *
 * @param name display name, may include leading/trailing whitespace.
 */
export function initials(name: string): string {
  const trimmed = name.trim()
  if (!trimmed) return ''
  const words = trimmed.split(/\s+/).filter(Boolean)
  if (words.length === 0) return ''
  const first = firstCodePoint(words[0] ?? '')
  const second = words.length > 1 ? firstCodePoint(words[1] ?? '') : ''
  return (first + second).toUpperCase()
}

/**
 * Pick the brand colour family used for `name`'s avatar background.
 * Deterministic for a given name — the hash uses every character so
 * "Skippy" and "Skipper" land on different slots even though they
 * share initials.
 *
 * @param name display name; whitespace-trimmed before hashing.
 */
export function colorFor(name: string): MonogramColor {
  const slot = hash(name.trim()) % PALETTE.length
  return PALETTE[slot] ?? 'bleu'
}

/**
 * Resolve the Tailwind class set for a given monogram colour. The
 * class strings are listed verbatim so Tailwind's content scan picks
 * them up at build time — composing them dynamically (e.g. via
 * `bg-${family}-600`) would defeat tree-shaking and produce missing
 * styles.
 *
 * @param family the colour slot, typically the result of {@link colorFor}.
 */
export function classesFor(family: MonogramColor): string {
  switch (family) {
    case 'brique':
      return 'bg-brique-600 text-white'
    case 'olive':
      return 'bg-olive-600 text-white'
    case 'bleu':
    default:
      return 'bg-bleu-600 text-white'
  }
}
