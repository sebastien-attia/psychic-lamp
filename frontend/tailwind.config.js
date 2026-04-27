/**
 * Tailwind CSS configuration for The Boat App SPA.
 *
 * - `darkMode: 'class'` — dark mode is toggled by adding the `dark` class on
 *   `<html>` (driven by the `useDarkMode` composable). This avoids
 *   media-query–only theming so users can override their OS preference.
 * - Three brand ramps — `bleu`, `brique`, `olive` — keyed at the
 *   conventional Tailwind 50→900 shade scale. Each base (`*-600`) is
 *   the supplied brand hex; lighter shades are tinted toward white,
 *   darker ones toward black, to keep contrast predictable in both
 *   light and dark mode. Roles: `bleu` = primary (CTAs, links, focus
 *   rings, brand), `brique` = destructive / error / warning, `olive`
 *   = accent / success / "create" CTAs.
 * - `nautical` is kept as a transitional alias mapped to `bleu` so any
 *   un-swept reference keeps rendering; it can be removed once the
 *   sweep is verified.
 * - `@tailwindcss/forms` — opinionated reset for form controls so vee-validate
 *   inputs render consistently across browsers.
 */

import forms from '@tailwindcss/forms'

const bleu = {
  50:  '#eaf0fb',
  100: '#cdd9f3',
  200: '#9bb2e6',
  300: '#688cd9',
  400: '#3666cd',
  500: '#0d49bf',
  600: '#0038B8',
  700: '#002e96',
  800: '#002374',
  900: '#001a55',
}

const brique = {
  50:  '#fbeae6',
  100: '#f4cabe',
  200: '#e9947d',
  300: '#dd5e3c',
  400: '#cf3a14',
  500: '#c52a05',
  600: '#B82200',
  700: '#931b00',
  800: '#6f1500',
  900: '#4d0e00',
}

const olive = {
  50:  '#f3f9e1',
  100: '#e3f1b8',
  200: '#c8e373',
  300: '#aed42d',
  400: '#a1c711',
  500: '#9bc108',
  600: '#94B800',
  700: '#769200',
  800: '#587000',
  900: '#3d4d00',
}

/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        bleu,
        brique,
        olive,
        // Transitional alias — `nautical-*` was the original primary
        // ramp; keep it pointing at `bleu` until every component has
        // been swept, then delete this entry.
        nautical: bleu,
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [forms],
}
