/**
 * Tailwind CSS configuration for The Boat App SPA.
 *
 * - `darkMode: 'class'` — dark mode is toggled by adding the `dark` class on
 *   `<html>` (driven by the `useDarkMode` composable). This avoids
 *   media-query–only theming so users can override their OS preference.
 * - Nautical palette — a blue-slate ramp keyed on `nautical.{50..900}` for
 *   primary surfaces; usable as `bg-nautical-600 text-nautical-50` etc.
 * - `@tailwindcss/forms` — opinionated reset for form controls so vee-validate
 *   inputs render consistently across browsers.
 */

import forms from '@tailwindcss/forms'

/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        nautical: {
          50:  '#f1f5fb',
          100: '#dde6f3',
          200: '#bccde7',
          300: '#90abd5',
          400: '#6585be',
          500: '#456aa8',
          600: '#34548c',
          700: '#2c4472',
          800: '#283a5e',
          900: '#243250',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [forms],
}
