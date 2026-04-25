import { expect, test as setup } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { loginAsDemo, STORAGE_STATE_PATH } from './helpers/auth.helper';
import { deleteAllBoatsViaAPI } from './helpers/api.helper';

/**
 * Playwright "setup" project: runs once before the rest of the suite.
 *
 * Drives a real OAuth2 login through Keycloak, hits `/boats` to ensure the
 * `XSRF-TOKEN` cookie is set by the BFF, scrubs any leftover seed data,
 * and persists the resulting cookies to {@link STORAGE_STATE_PATH}. The
 * `chromium` and `mobile` projects then load that state so individual
 * specs do not pay the OAuth round-trip on every test.
 */
setup('authenticate and reset', async ({ page, context }) => {
  await loginAsDemo(page);
  await page.goto('/boats');
  await expect(page).toHaveURL(/\/boats/);
  // Ensure the BFF has flushed the XSRF cookie before we snapshot state.
  const cookies = await context.cookies();
  expect(cookies.map((c) => c.name)).toContain('XSRF-TOKEN');

  // Wipe any boats left behind by a previous run so tests start clean.
  // Cleanup is best-effort: a flaky DELETE here would block every
  // dependent project (`chromium`, `mobile`), and individual specs run
  // their own per-test reset, so a partial wipe is safe.
  try {
    await deleteAllBoatsViaAPI(page.request, context);
  } catch (error) {
    console.warn('[auth.setup] best-effort cleanup failed (non-fatal):', error);
  }

  mkdirSync(dirname(STORAGE_STATE_PATH), { recursive: true });
  await context.storageState({ path: STORAGE_STATE_PATH });
});
