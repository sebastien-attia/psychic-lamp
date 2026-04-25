import { expect, type Page } from '@playwright/test';
import { sel } from './selectors';

/**
 * Filesystem path of the saved Playwright storageState. Single source of
 * truth shared between `auth.setup.ts` and `playwright.config.ts`.
 */
export const STORAGE_STATE_PATH = 'playwright/.auth/user.json';

/** Regex matching the Keycloak login URL for the boat-app realm. */
export const KEYCLOAK_LOGIN_URL_RE =
  /\/realms\/boat-app\/protocol\/openid-connect\/auth/;

/**
 * Demo credentials seeded by `infra/keycloak/realm.users.local-intg.yaml`.
 * Override via `E2E_USER` / `E2E_PASSWORD` if a different realm user is
 * provisioned in CI.
 */
export const DEMO_USER = process.env.E2E_USER ?? 'demo';
export const DEMO_PASSWORD = process.env.E2E_PASSWORD ?? 'demo123';

/**
 * Drive the real OAuth2 Authorization-Code flow against Keycloak: navigate
 * to the SPA root, get redirected to the Keycloak login form, post the
 * demo credentials, and wait for the BFF callback to drop us back at
 * `/boats` with a SESSION cookie.
 *
 * Requires a Page that starts with an empty storage state — call from
 * `auth.setup.ts` (which then persists the cookies for reuse) or from a
 * test that needs to exercise the login flow itself.
 */
export async function loginAsDemo(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page).toHaveURL(KEYCLOAK_LOGIN_URL_RE);
  const username = page.locator('#username');
  const password = page.locator('#password');
  await expect(username).toBeVisible();
  await username.fill(DEMO_USER);
  await password.fill(DEMO_PASSWORD);
  await password.press('Enter');
  await expect(page).toHaveURL(/\/boats(?:\?|$|\/)/, { timeout: 20_000 });
  await expect(sel.boatsHeading(page)).toBeVisible();
}

/**
 * Sign out of the SPA via the user menu and wait until the browser lands
 * back at the Keycloak login form. The BFF's `POST /api/logout` invalidates
 * the session, the SPA reloads to `/`, the router guard kicks the user to
 * `/oauth2/authorization/keycloak`, Keycloak redirects to its login screen.
 */
export async function logout(page: Page): Promise<void> {
  await sel.userMenuButton(page).click();
  await sel.signOutItem(page).click();
  await expect(page).toHaveURL(KEYCLOAK_LOGIN_URL_RE, { timeout: 20_000 });
  await expect(page.locator('#username')).toBeVisible();
}

/**
 * Lightweight session probe — `GET /api/me` returns 200 when the session
 * cookie still resolves to an authenticated principal, 401 otherwise.
 */
export async function isLoggedIn(page: Page): Promise<boolean> {
  const response = await page.request.get('/api/me', { failOnStatusCode: false });
  return response.ok();
}
