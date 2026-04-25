import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import {
  createBoatViaAPI,
  deleteAllBoatsViaAPI,
  seedBoats,
} from './helpers/api.helper';
import { sel } from './helpers/selectors';

/**
 * Automated accessibility checks (WCAG AA) on the key pages, plus a
 * keyboard-navigation smoke test and a dark-mode rerun. Each test seeds
 * its own minimal dataset to keep assertions deterministic.
 */

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

test.describe('Accessibility — axe-core scans', () => {
  test.beforeEach(async ({ page, context }) => {
    await deleteAllBoatsViaAPI(page.request, context);
  });

  test.afterEach(async ({ page, context }) => {
    await deleteAllBoatsViaAPI(page.request, context);
  });

  test('boat list passes axe checks', async ({ page, context }) => {
    await seedBoats(page.request, context, 4);
    await page.goto('/boats');
    await expect(sel.boatsHeading(page)).toBeVisible();
    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
    expect(results.violations).toEqual([]);
  });

  test('boat create form passes axe checks', async ({ page }) => {
    await page.goto('/boats/new');
    await expect(sel.boatNameInput(page)).toBeVisible();
    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
    expect(results.violations).toEqual([]);
  });

  test('delete confirm dialog passes axe checks', async ({ page, context }) => {
    const created = await createBoatViaAPI(page.request, context, {
      name: 'Axe Dialog Boat',
    });
    await page.goto('/boats');
    await sel.cardDeleteButton(sel.boatCardByName(page, created.name)).click();
    await expect(sel.deleteDialog(page)).toBeVisible();
    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
    expect(results.violations).toEqual([]);
  });

  test('keyboard navigation reaches the create-boat link', async ({
    page,
    context,
  }) => {
    await seedBoats(page.request, context, 1);
    await page.goto('/boats');
    await expect(sel.newBoatLink(page)).toBeVisible();
    await sel.newBoatLink(page).focus();
    await expect(sel.newBoatLink(page)).toBeFocused();
    await page.keyboard.press('Enter');
    await page.waitForURL(/\/boats\/new$/);
    await expect(sel.boatNameInput(page)).toBeVisible();
  });

  test('dark mode preserves accessibility on the list', async ({
    page,
    context,
  }) => {
    await seedBoats(page.request, context, 3);
    await page.goto('/boats');
    const toggle = sel.darkModeToggle(page);
    await toggle.click();
    // Wait one frame for Tailwind's `dark` class to settle.
    await page.waitForFunction(() =>
      document.documentElement.classList.contains('dark'),
    );
    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
    expect(results.violations).toEqual([]);
  });
});
