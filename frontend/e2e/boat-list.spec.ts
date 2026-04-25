import { expect, test } from '@playwright/test';
import {
  deleteAllBoatsViaAPI,
  seedBoats,
  type BoatRecord,
} from './helpers/api.helper';
import { sel } from './helpers/selectors';

/**
 * UC2 + UC5 — list, paginate, search.
 *
 * Each test resets the dataset via API helpers to keep ordering and totals
 * deterministic. The browser already has a session from the `setup`
 * project, so `page.request` calls inherit the SESSION + XSRF cookies.
 */

test.describe('UC2/UC5 — boat list, pagination, search', () => {
  let seeded: BoatRecord[] = [];

  test.beforeEach(async ({ page, context }) => {
    await deleteAllBoatsViaAPI(page.request, context);
    seeded = await seedBoats(page.request, context, 15);
  });

  test.afterEach(async ({ page, context }) => {
    await deleteAllBoatsViaAPI(page.request, context);
  });

  test('UC2: paginated list shows 12 cards on page 1', async ({ page }) => {
    await page.goto('/boats');
    await expect(sel.boatsHeading(page)).toBeVisible();
    await expect(sel.boatCards(page)).toHaveCount(12);
    await expect(page.getByText(/showing 1.*of 15/i)).toBeVisible();
  });

  test('UC2: pagination next/prev navigates and updates aria-current', async ({
    page,
  }) => {
    await page.goto('/boats');
    await expect(sel.currentPageButton(page)).toHaveText('1');

    await sel.nextPageButton(page).click();
    await expect.poll(() => page.url()).toMatch(/page=1/);
    await expect(sel.boatCards(page)).toHaveCount(3);
    await expect(sel.currentPageButton(page)).toHaveText('2');

    await sel.prevPageButton(page).click();
    await expect(sel.currentPageButton(page)).toHaveText('1');
    await expect(sel.boatCards(page)).toHaveCount(12);
  });

  test('UC2: changing page size to 24 collapses to a single page', async ({
    page,
  }) => {
    await page.goto('/boats');
    await expect(sel.boatCards(page)).toHaveCount(12);
    await sel.pageSizeSelect(page).selectOption('24');
    await expect(sel.boatCards(page)).toHaveCount(15);
    // Pagination nav hides itself when totalPages <= 1.
    await expect(
      page.getByRole('navigation', { name: /pagination/i }),
    ).toHaveCount(0);
  });

  test('UC5: search filters boats by name', async ({ page }) => {
    await page.goto('/boats');
    const target = seeded[0]!;
    await sel.searchInput(page).fill(target.name);
    await expect(sel.boatCards(page)).toHaveCount(1);
    await expect(sel.boatCardByName(page, target.name)).toBeVisible();
  });

  test('UC5: search with no matches shows the no-results state', async ({ page }) => {
    await page.goto('/boats');
    await sel.searchInput(page).fill('zzz-no-match-xyz');
    await expect(sel.noResultsHeading(page)).toBeVisible();
    await expect(sel.boatCards(page)).toHaveCount(0);
  });

  test('empty state is shown when no boats exist', async ({ page, context }) => {
    await deleteAllBoatsViaAPI(page.request, context);
    await page.goto('/boats');
    await expect(sel.emptyStateHeading(page)).toBeVisible();
    await expect(sel.emptyStateCta(page)).toBeVisible();
  });
});
