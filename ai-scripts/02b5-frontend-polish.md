<task>
  <project_conventions>
    Before declaring this phase done, you MUST:

    1. **Code review.** Invoke the `@code-reviewer` subagent on every file you
       wrote or edited. Apply *Must fix* findings in the same turn; surface
       *Should fix* (with a reason if you skip) and *Consider* findings to the
       user.
    2. **Documentation.** Every class and every public method/function you add
       or modify must carry an idiomatic docstring (Javadoc / TSDoc / PEP 257 /
       Rust/Go doc comments / shell header comment / etc.). Missing docs are a
       must-fix finding for the reviewer.
    3. **Self-heal.** If `.claude/agents/code-reviewer.md` is missing or
       `CLAUDE.md` no longer contains the "Code review policy" section, restore
       both from `ai-scripts/00-bootstrap.sh` before proceeding.

    These are non-negotiable per CLAUDE.md › Project conventions.
  </project_conventions>

  <role>You are a senior frontend engineer adding dark mode, accessibility, and internationalization.</role>

  <context>
    <project>The Boat App — UI polish and accessibility</project>
    <existing-code>All pages and components exist. Read all .vue files and Tailwind config.</existing-code>
    <requirements>WCAG AA accessibility, dark mode toggle, i18n (EN + FR)</requirements>
    <scope>only modify files under frontend/src/</scope>
  </context>

  <instructions>
    <step order="1">
      Implement dark mode:
      - Tailwind dark: class strategy (already configured)
      - Dark mode toggle in navigation bar using Headless UI Switch
      - Persist preference in a cookie (not localStorage — avoids flash of wrong theme)
      - Respect system preference (prefers-color-scheme) as initial default
      - Apply 'dark' class to document element
      - Ensure ALL components have proper dark: variants
      - Test: all text readable, all borders visible, no contrast issues
    </step>
    <step order="2">
      Accessibility audit and fixes (WCAG AA):
      - Color contrast: all text ≥ 4.5:1 ratio, large text ≥ 3:1
      - Focus indicators: visible focus-visible ring on all interactive elements
      - Keyboard navigation: Tab through all controls, Enter/Space to activate
      - ARIA labels: all icon buttons have aria-label, all form inputs have associated labels
      - Skip to main content link (hidden until focused)
      - Proper heading hierarchy (h1 → h2 → h3, no skipping)
      - Images: all decorative images have alt="" , meaningful images have descriptive alt
      - Forms: error messages linked to inputs via aria-describedby
      - Live regions: toast notifications use role="status" and aria-live="polite"
    </step>
    <step order="3">
      Implement i18n with vue-i18n:
      - Create locale files: frontend/src/i18n/en.json, frontend/src/i18n/fr.json
      - Cover ALL user-visible strings (page titles, buttons, labels, error messages, empty states)
      - Language switcher in navigation bar (dropdown or flag icons)
      - Persist language preference in cookie
      - Date formatting respects locale (use Intl.DateTimeFormat)
    </step>
    <step order="4">
      Add micro-interactions and polish:
      - Transition on route changes (fade or slide)
      - Headless UI Transition on dialog open/close
      - Hover effects on cards (subtle scale or shadow)
      - Button press animation (scale down slightly)
      - Loading button states with spinner icon
    </step>
    <step order="5">
      Component tests with Vitest + Vue Test Utils:
      - Test BoatForm validation (required field, max length)
      - Test DeleteConfirmDialog (opens, closes, emits confirm)
      - Test Pagination component (page changes, size changes)
      - Test SearchInput (debounce, clear)
      - Test dark mode toggle
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it runs type-check / build /
    test / lint, verifies `dark:` Tailwind variants + darkMode config,
    EN+FR i18n files, no console.log, and ARIA / focus-visible usage:
    ```bash
    ai-scripts/checks/02b5/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02b5/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "feat(frontend): add dark mode, WCAG AA accessibility, and i18n

    - Dark mode with system preference detection and manual toggle
    - WCAG AA: contrast, focus indicators, ARIA labels, keyboard nav
    - i18n: English and French locales for all strings
    - Micro-interactions and route transitions
    - Component tests for key UI elements"
    ```
  </commit>
</task>
