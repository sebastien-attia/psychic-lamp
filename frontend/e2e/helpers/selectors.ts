import type { Locator, Page } from '@playwright/test';

/**
 * Centralised, semantic selectors for the boat-app SPA.
 *
 * The codebase deliberately avoids `data-testid` attributes — the policy
 * is to drive tests off accessible roles, ARIA labels, stable element ids
 * (e.g. `#boat-name`) and i18n-rendered text. Concentrating them here means
 * a UI rename only touches this file.
 *
 * Text matchers use case-insensitive regular expressions so a wording
 * tweak in `src/locales/en.json` does not snap the suite.
 */
export const sel = {
  // ─── Boat list ──────────────────────────────────────────────────
  /** The "Boats" page heading. */
  boatsHeading: (page: Page): Locator => page.getByRole('heading', { name: /^boats$/i, level: 1 }),
  /** The "New boat" CTA in the list header (RouterLink to `/boats/new`). */
  newBoatLink: (page: Page): Locator => page.getByRole('link', { name: /new boat/i }),
  /** All rendered boat cards (one `<article>` per boat). */
  boatCards: (page: Page): Locator => page.locator('main article'),
  /** A specific boat card located by the boat name shown in its `<h2>`. */
  boatCardByName: (page: Page, name: string): Locator =>
    page.locator('main article', { hasText: name }),
  /** "No boats in your fleet yet" empty-state heading. */
  emptyStateHeading: (page: Page): Locator =>
    page.getByRole('heading', { name: /no boats in your fleet yet/i }),
  /** Empty-state CTA button: "Create your first boat". */
  emptyStateCta: (page: Page): Locator =>
    page.getByRole('button', { name: /create your first boat/i }),
  /** "No boats match your search" heading shown when a search returns nothing. */
  noResultsHeading: (page: Page): Locator =>
    page.getByRole('heading', { name: /no boats match your search/i }),

  // ─── Search ─────────────────────────────────────────────────────
  /** The search input (`<input type="search">`). */
  searchInput: (page: Page): Locator => page.locator('input[type="search"]'),

  // ─── Boat form ──────────────────────────────────────────────────
  /** Name input (`#boat-name`). */
  boatNameInput: (page: Page): Locator => page.locator('#boat-name'),
  /** Description textarea (`#boat-description`). */
  boatDescriptionInput: (page: Page): Locator => page.locator('#boat-description'),
  /** Inline name validation error (`#boat-name-error`). */
  boatNameError: (page: Page): Locator => page.locator('#boat-name-error'),
  /** Inline description validation error (`#boat-description-error`). */
  boatDescriptionError: (page: Page): Locator => page.locator('#boat-description-error'),
  /** Top-of-form server error banner. */
  formErrorBanner: (page: Page): Locator => page.locator('form [role="alert"]'),
  /** Form submit button (`<button type="submit">`). */
  formSubmit: (page: Page): Locator => page.locator('form button[type="submit"]'),
  /** Form cancel button (the only non-submit button inside the form). */
  formCancel: (page: Page): Locator => page.locator('form button[type="button"]'),

  // ─── Boat card actions ──────────────────────────────────────────
  /** Edit (pencil) icon button on a card. */
  cardEditButton: (card: Locator): Locator =>
    card.getByRole('button', { name: /edit boat/i }),
  /** Delete (trash) icon button on a card. */
  cardDeleteButton: (card: Locator): Locator =>
    card.getByRole('button', { name: /delete boat/i }),

  // ─── Delete confirm dialog ──────────────────────────────────────
  /** The delete-confirmation modal panel. */
  deleteDialog: (page: Page): Locator => page.getByRole('dialog'),
  /** The destructive "Delete" button inside the dialog. */
  deleteDialogConfirm: (page: Page): Locator =>
    page.getByRole('dialog').getByRole('button', { name: /^delete$/i }),
  /** The "Cancel" button inside the dialog. */
  deleteDialogCancel: (page: Page): Locator =>
    page.getByRole('dialog').getByRole('button', { name: /^cancel$/i }),

  // ─── Pagination ─────────────────────────────────────────────────
  // The pagination nav renders TWO prev/next pairs — a `sm:hidden`
  // mobile row with text labels and a desktop pill row with icon-only
  // buttons (`aria-label="Previous|Next"`). Scope the helpers to
  // whichever row is visible at the active viewport so we never click a
  // hidden element.
  /** "Previous" page button (visible at the current viewport). */
  prevPageButton: (page: Page): Locator =>
    page
      .getByRole('navigation', { name: /pagination/i })
      .getByRole('button', { name: /^previous$/i })
      .locator('visible=true'),
  /** "Next" page button (visible at the current viewport). */
  nextPageButton: (page: Page): Locator =>
    page
      .getByRole('navigation', { name: /pagination/i })
      .getByRole('button', { name: /^next$/i })
      .locator('visible=true'),
  /** Whichever numbered pill carries `aria-current="page"`. */
  currentPageButton: (page: Page): Locator =>
    page
      .getByRole('navigation', { name: /pagination/i })
      .locator('button[aria-current="page"]'),
  /** Page-size `<select>` inside the pagination nav. */
  pageSizeSelect: (page: Page): Locator =>
    page.getByRole('navigation', { name: /pagination/i }).locator('select'),

  // ─── User menu / sign-out ───────────────────────────────────────
  /** Header user-menu button (Headless UI MenuButton). */
  userMenuButton: (page: Page): Locator => page.getByRole('button', { name: /^menu$/i }),
  /** "Sign out" item revealed once the user menu is open. */
  signOutItem: (page: Page): Locator => page.getByRole('menuitem', { name: /sign out/i }),

  // ─── Dark-mode toggle ───────────────────────────────────────────
  /** Headless UI Switch for dark mode. */
  darkModeToggle: (page: Page): Locator =>
    page.getByRole('switch', { name: /toggle dark mode/i }),

  // ─── Detail / edit pages ────────────────────────────────────────
  /** Detail-page "Edit" RouterLink. */
  detailEditLink: (page: Page): Locator => page.getByRole('link', { name: /^edit$/i }),
  /** Detail-page "Delete" button (the one outside the dialog). */
  detailDeleteButton: (page: Page): Locator =>
    page.getByRole('button', { name: /^delete$/i }).first(),
};
