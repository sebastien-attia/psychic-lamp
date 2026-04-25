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

  <role>You are a senior frontend engineer building CRUD forms with validation, optimistic locking conflict handling, and accessible dialogs.</role>

  <context>
    <project>The Boat App — create, edit, detail, delete pages</project>
    <existing-code>Boat list page, auth, stores exist. Read boatStore, API types, router.</existing-code>
    <use-cases>UC3 (create/update/delete), UC4 (detail view), UC6 (delete confirmation dialog)</use-cases>
    <ui-library>Headless UI Dialog for modals, vee-validate + zod for form validation</ui-library>
    <auth-model>
      Session-based auth. Axios sends SESSION cookie and XSRF-TOKEN automatically.
      All mutations (POST/PUT/DELETE) include the X-XSRF-TOKEN header via Axios config.
      No Bearer token logic needed.
    </auth-model>
    <optimistic-locking>
      - GET responses include ETag header with version number
      - PUT requests send If-Match header with current version
      - 409 Conflict → show friendly message + offer to refresh
    </optimistic-locking>
    <boat-constraints>name: required, 1-64 chars. description: optional, max 256 chars.</boat-constraints>
    <responsive-rule>
      ALL pages and components in this step MUST be fully responsive and mobile-friendly:
      - Use Tailwind mobile-first utilities: base styles for mobile, sm:/md:/lg: for wider screens
      - Forms: full-width on mobile (w-full), max-width container on desktop (max-w-xl mx-auto)
      - Action buttons: stack vertically on mobile (flex-col), row on tablet+ (sm:flex-row)
      - Detail page fields: single column on mobile, can use 2-column grid on desktop (md:grid-cols-2)
      - Delete dialog: full-width on small screens (w-full sm:w-auto), centered on desktop
      - Touch targets: minimum 44×44px for all interactive elements (WCAG 2.5.8)
      - No horizontal scroll on any screen size
      Test on 375px width (iPhone SE) as the minimum viewport.
    </responsive-rule>
    <scope>only modify files under frontend/src/</scope>
  </context>

  <instructions>
    <step order="1">
      Create BoatForm.vue (shared between create and edit):
      - Fields: name (text, required, 1-64 chars), description (textarea, optional, max 256 chars)
      - Zod schema:
        ```typescript
        const boatSchema = z.object({
          name: z.string().min(1, 'Name is required').max(64, 'Max 64 characters'),
          description: z.string().max(256, 'Max 256 characters').optional().or(z.literal('')),
        })
        ```
      - Real-time validation on blur + after first submit
      - Character count: "42/64" and "128/256"
      - Submit button with loading state
      - Cancel button navigates back
      - Responsive: all inputs full-width (w-full); button row stacks vertically on mobile
        (flex flex-col sm:flex-row sm:justify-end gap-3); min touch target 44px
    </step>
    <step order="2">
      Create BoatCreatePage.vue (/boats/new):
      - Uses BoatForm with empty initial values
      - On submit: boatStore.createBoat(data)
      - Success: toast "Boat created" + navigate to /boats/:newId
      - Error: show API error in form
      - Responsive: page wrapper max-w-xl mx-auto px-4 (full-width on mobile, centered on desktop)
    </step>
    <step order="3">
      Create BoatDetailPage.vue (/boats/:id):
      - Fetch boat by ID (store version from response)
      - Display: name, description, createdAt (locale-formatted), version
      - Actions: Edit button, Delete button
      - Loading: skeleton. Error: 404 → "Boat not found" with back link
      - Breadcrumb: Boats > Boat Name
      - Responsive: single-column layout on mobile; action buttons stack on mobile
        (flex flex-col sm:flex-row gap-2); detail fields use full width on all sizes
    </step>
    <step order="4">
      Create BoatEditPage.vue (/boats/:id/edit):
      - Fetch boat (store version for If-Match)
      - BoatForm pre-filled with current values
      - On submit: boatStore.updateBoat(id, data, version)
      - 409 Conflict: "This boat was modified by another user. Please refresh."
        + Refresh button that reloads data
      - Success: toast "Boat updated" + navigate to /boats/:id
      - Responsive: same wrapper as BoatCreatePage (max-w-xl mx-auto px-4)
    </step>
    <step order="5">
      Create DeleteConfirmDialog.vue:
      - Headless UI Dialog
      - "Delete «{boatName}»? This action cannot be undone."
      - Cancel + Delete (red, with loading) buttons
      - Focus trapped, Escape closes, accessible aria-labels
      - Responsive: dialog panel full-width with mx-4 on mobile (w-full max-w-md sm:mx-auto);
        buttons full-width stacked on mobile (flex flex-col-reverse sm:flex-row sm:justify-end gap-2)
    </step>
    <step order="6">
      Update boatStore:
      - createBoat(data) → POST /api/v1/boats
      - updateBoat(id, data, version) → PUT with If-Match: {version}
      - deleteBoat(id) → DELETE /api/v1/boats/{id}
      - Handle 409 specifically → throw typed ConflictError
      (CSRF token is handled by Axios automatically — no extra code)
    </step>
    <step order="7">
      Toast notification system:
      - useToast() → { showSuccess(msg), showError(msg) }
      - Top-right, auto-dismiss 4s, stacked, Headless UI Transition
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it runs type-check + build,
    confirms If-Match / 409 handling, a form-validation library, Headless
    UI Dialog usage, and toast notifications:
    ```bash
    ai-scripts/checks/02b4/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02b4/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "feat(frontend): CRUD pages — responsive, accessible, optimistic locking

    - BoatForm: zod validation (name 64, description 256), char counters, mobile-first layout
    - Create, Edit, Detail pages — full-width on mobile, max-w-xl centered on desktop
    - Optimistic locking: If-Match on PUT, 409 conflict → refresh prompt
    - Delete confirmation (Headless UI Dialog, accessible, full-width on mobile)
    - Toast notifications
    - CSRF handled automatically by Axios config
    - All touch targets min 44px, no horizontal scroll at 375px"
    ```
  </commit>
</task>
