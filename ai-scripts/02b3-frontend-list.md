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

  <role>You are a senior frontend engineer building a polished, accessible boat list page.</role>

  <context>
    <project>The Boat App — boat list page with pagination and search</project>
    <existing-code>Vue scaffold, auth flow, API client exist. Read boatStore and generated API types.</existing-code>
    <use-cases>UC2 (paginated list), UC5 (search/filter by name or description)</use-cases>
    <ui-library>Headless UI (@headlessui/vue) for accessible primitives, Tailwind CSS for styling</ui-library>
    <design>
      Nautical-inspired design. Clean, professional, NOT generic.
      Light theme: white/slate backgrounds, deep navy text, warm amber accents.
      Responsive: cards on mobile, table on desktop.
    </design>
    <scope>only modify files under frontend/src/</scope>
  </context>

  <instructions>
    <step order="1">
      Create reusable UI components (frontend/src/components/ui/):
      - SkeletonLoader.vue: animated pulse skeleton for loading states
      - EmptyState.vue: illustration + message + action button
      - ErrorState.vue: error message + retry button
      - Pagination.vue: page controls (prev/next, page numbers, page size selector) using Headless UI Listbox for size selector
      - SearchInput.vue: debounced search input with clear button and search icon
      - Badge.vue: small colored label
    </step>
    <step order="2">
      Create BoatCard.vue (frontend/src/components/boats/):
      - Displays: name, description (truncated), createdAt (relative time)
      - Click navigates to boat detail (/boats/:id)
      - Edit and Delete action buttons (icon buttons)
      - Hover effect, focus-visible ring for accessibility
      - Responsive: full-width card on mobile, grid item on desktop
    </step>
    <step order="3">
      Create BoatListPage.vue (frontend/src/pages/BoatListPage.vue):
      - Header with page title and "New Boat" button (+ icon)
      - SearchInput for filtering
      - Grid of BoatCards (responsive: 1 col mobile, 2 cols tablet, 3 cols desktop)
      - Pagination controls at the bottom
      - States:
        - Loading: show 6 skeleton cards
        - Empty (no boats at all): EmptyState with "Create your first boat" CTA
        - Empty (search with no results): EmptyState with "No boats match your search"
        - Error: ErrorState with retry button
        - Data: grid of BoatCards + pagination
      - URL params sync: page, size, search reflected in URL query string
      - Debounced search (300ms) — resets to page 0 on new search
    </step>
    <step order="4">
      Update boatStore to handle:
      - fetchBoats(page, size, search) → calls API, stores result
      - Reactive: boats, totalPages, totalElements, currentPage, loading, error
    </step>
    <step order="5">
      Wire everything together:
      - BoatListPage uses boatStore
      - Pagination updates URL and refetches
      - Search debounces and refetches
      - Loading/error/empty states work correctly
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it runs type-check + build,
    checks presence of pagination/search/skeleton/empty/error UX tokens,
    debounced search, and URL query sync:
    ```bash
    ai-scripts/checks/02b3/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02b3/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "feat(frontend): implement boat list page with pagination and search

    - Responsive card grid (1/2/3 columns)
    - Debounced search with URL sync
    - Pagination with page size selector
    - Loading skeletons, empty state, error state
    - Reusable UI components (Skeleton, EmptyState, Pagination, SearchInput)"
    ```
  </commit>
</task>
