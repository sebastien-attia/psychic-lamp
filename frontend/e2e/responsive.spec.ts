import { expect, test } from '@playwright/test';
import {
  deleteAllBoatsViaAPI,
  seedBoats,
} from './helpers/api.helper';
import { sel } from './helpers/selectors';

/**
 * Responsive smoke tests — the `mobile` Playwright project sets the
 * iPhone-13 viewport globally, but a couple of cases override the
 * viewport inline to exercise the tablet breakpoint as well.
 */

test.describe('Responsive layouts', () => {
  test.beforeEach(async ({ page, context }) => {
    await deleteAllBoatsViaAPI(page.request, context);
    await seedBoats(page.request, context, 14);
  });

  test.afterEach(async ({ page, context }) => {
    await deleteAllBoatsViaAPI(page.request, context);
  });

  test('mobile viewport: boat cards stack and pagination collapses', async ({
    page,
  }) => {
    await page.goto('/boats');
    const cards = sel.boatCards(page);
    // Pre-assert the seeded count so a layout pass cannot mask a seeding
    // regression that left only one or two cards on the page.
    await expect(cards).toHaveCount(12);

    // All visible cards should share the same x position when stacked.
    const boxes = await cards.evaluateAll((nodes) =>
      nodes.slice(0, 3).map((node) => (node as HTMLElement).getBoundingClientRect().x),
    );
    expect(new Set(boxes.map((x) => Math.round(x))).size).toBe(1);

    // Mobile pagination shows the "Page X of Y" label instead of pills.
    await expect(page.getByText(/page 1 of 2/i)).toBeVisible();
  });

  test('mobile viewport: header user-menu remains reachable', async ({ page }) => {
    await page.goto('/boats');
    const menu = sel.userMenuButton(page);
    await expect(menu).toBeVisible();
    await menu.click();
    await expect(sel.signOutItem(page)).toBeVisible();
  });

  test.describe('Tablet viewport', () => {
    test.use({ viewport: { width: 820, height: 1180 } });

    test('create form is usable at tablet width', async ({ page }) => {
      await page.goto('/boats/new');
      await expect(sel.boatNameInput(page)).toBeVisible();
      await sel.boatNameInput(page).fill('Tablet Boat');
      await sel.boatDescriptionInput(page).fill('Tablet flow');
      await expect(sel.formSubmit(page)).toBeEnabled();
    });
  });
});
