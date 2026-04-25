import { expect, test } from '@playwright/test';
import {
  KEYCLOAK_LOGIN_URL_RE,
  loginAsDemo,
  logout,
} from './helpers/auth.helper';
import { sel } from './helpers/selectors';

/**
 * UC1 — Authentication flow.
 *
 * Runs in the `chromium-fresh` project (empty storageState) so each test
 * exercises the real OAuth2 round-trip through Keycloak rather than
 * resuming a saved session.
 */

test.describe('UC1 — authentication', () => {
  test('unauthenticated user is redirected to Keycloak login', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(KEYCLOAK_LOGIN_URL_RE);
    await expect(page.locator('#username')).toBeVisible();
    await expect(page.locator('#password')).toBeVisible();
  });

  test('user can login via Keycloak and reach the boat list', async ({ page }) => {
    await loginAsDemo(page);
    await expect(page).toHaveURL(/\/boats/);
    await expect(sel.boatsHeading(page)).toBeVisible();
    const meResponse = await page.request.get('/api/me');
    expect(meResponse.ok()).toBeTruthy();
  });

  test('user can logout and is redirected to the Keycloak login form', async ({
    page,
  }) => {
    await loginAsDemo(page);
    await logout(page);
    await expect(page).toHaveURL(KEYCLOAK_LOGIN_URL_RE);
  });

  test('after logout, accessing /boats redirects to Keycloak again', async ({
    page,
  }) => {
    await loginAsDemo(page);
    await logout(page);
    await page.goto('/boats');
    await expect(page).toHaveURL(KEYCLOAK_LOGIN_URL_RE);
    await expect(page.locator('#username')).toBeVisible();
  });
});
