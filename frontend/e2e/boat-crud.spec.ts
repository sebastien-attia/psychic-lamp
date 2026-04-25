import { expect, test } from '@playwright/test';
import {
  createBoatViaAPI,
  deleteAllBoatsViaAPI,
} from './helpers/api.helper';
import { sel } from './helpers/selectors';

/**
 * UC3 / UC4 / UC6 — create, view, edit, delete with confirmation.
 *
 * Tests start from a clean dataset; UI-driven creates run end-to-end while
 * detail / edit / delete tests pre-seed via API to keep them focused.
 */

test.describe('UC3/UC4/UC6 — CRUD', () => {
  test.beforeEach(async ({ page, context }) => {
    await deleteAllBoatsViaAPI(page.request, context);
  });

  test.afterEach(async ({ page, context }) => {
    await deleteAllBoatsViaAPI(page.request, context);
  });

  test('UC3: create a new boat — appears in the list', async ({ page }) => {
    await page.goto('/boats/new');
    await expect(
      page.getByRole('heading', { name: /create a new boat/i, level: 1 }),
    ).toBeVisible();
    const name = `CRUD Boat ${Date.now()}`;
    await sel.boatNameInput(page).fill(name);
    await sel.boatDescriptionInput(page).fill('Created by Playwright');
    await sel.formSubmit(page).click();

    await page.waitForURL(/\/boats\/[0-9a-f-]{36}$/, { timeout: 20_000 });
    await expect(
      page.getByRole('heading', { name, level: 1 }),
    ).toBeVisible();

    await page.goto('/boats');
    await expect(sel.boatCardByName(page, name)).toBeVisible();
  });

  test('UC3: create form rejects an empty name', async ({ page }) => {
    await page.goto('/boats/new');
    // vee-validate runs the full schema on submit, so a click is enough
    // to surface the "required" error — no manual blur dance needed.
    await sel.formSubmit(page).click();
    await expect(sel.boatNameError(page)).toBeVisible();
    await expect(sel.boatNameError(page)).toHaveText(/required/i);
    await expect(page).toHaveURL(/\/boats\/new$/);
  });

  test('UC3: name input enforces the 64-character maximum', async ({ page }) => {
    await page.goto('/boats/new');
    const overlong = 'x'.repeat(80);
    await sel.boatNameInput(page).fill(overlong);
    // The native maxlength clamps the field to 64 characters.
    await expect(sel.boatNameInput(page)).toHaveValue(/^x{64}$/);
  });

  test('UC4: detail page displays the boat', async ({ page, context }) => {
    const created = await createBoatViaAPI(page.request, context, {
      name: 'Detail Visible',
      description: 'Detail description',
    });
    await page.goto('/boats');
    await sel.boatCardByName(page, created.name).getByRole('link').first().click();
    await page.waitForURL(new RegExp(`/boats/${created.id}$`), { timeout: 20_000 });
    await expect(
      page.getByRole('heading', { name: created.name, level: 1 }),
    ).toBeVisible();
    await expect(page.getByText('Detail description')).toBeVisible();
  });

  test('UC3: editing a boat updates the list', async ({ page, context }) => {
    const created = await createBoatViaAPI(page.request, context, {
      name: 'Old Name',
      description: 'old',
    });
    await page.goto('/boats');
    await sel.cardEditButton(sel.boatCardByName(page, created.name)).click();
    await page.waitForURL(new RegExp(`/boats/${created.id}/edit$`));
    await sel.boatNameInput(page).fill('New Name');
    await sel.formSubmit(page).click();
    await page.waitForURL(new RegExp(`/boats/${created.id}$`), { timeout: 20_000 });
    await expect(
      page.getByRole('heading', { name: 'New Name', level: 1 }),
    ).toBeVisible();

    await page.goto('/boats');
    await expect(sel.boatCardByName(page, 'New Name')).toBeVisible();
    await expect(sel.boatCardByName(page, 'Old Name')).toHaveCount(0);
  });

  test('UC3+UC6: deleting via the dialog removes the boat', async ({
    page,
    context,
  }) => {
    const created = await createBoatViaAPI(page.request, context, {
      name: 'Delete Me',
    });
    await page.goto('/boats');
    await sel.cardDeleteButton(sel.boatCardByName(page, created.name)).click();
    await expect(sel.deleteDialog(page)).toBeVisible();
    await expect(
      sel.deleteDialog(page).getByText(`Delete «${created.name}»?`),
    ).toBeVisible();
    await sel.deleteDialogConfirm(page).click();
    await expect(sel.deleteDialog(page)).toBeHidden();
    await expect(sel.boatCardByName(page, created.name)).toHaveCount(0);
  });

  test('UC6: cancelling the delete dialog keeps the boat', async ({
    page,
    context,
  }) => {
    const created = await createBoatViaAPI(page.request, context, {
      name: 'Keep Me',
    });
    await page.goto('/boats');
    await sel.cardDeleteButton(sel.boatCardByName(page, created.name)).click();
    await expect(sel.deleteDialog(page)).toBeVisible();
    await sel.deleteDialogCancel(page).click();
    await expect(sel.deleteDialog(page)).toBeHidden();
    await expect(sel.boatCardByName(page, created.name)).toBeVisible();
  });
});
