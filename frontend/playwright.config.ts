import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for The Boat App's end-to-end tests.
 *
 * Targets the running `local-intg` docker compose stack (BFF on :8080,
 * Keycloak on :8180). The stack is NOT auto-started — `globalSetup` polls
 * health endpoints and fails fast with a clear message if anything is down.
 *
 * Project layout:
 * - `setup`           — runs `auth.setup.ts` once, persists storageState.
 * - `chromium`        — depends on `setup`; runs every spec except auth/setup.
 * - `chromium-fresh`  — runs `auth.spec.ts` with an empty storageState so the
 *                       login/logout flow is exercised for real.
 * - `mobile`          — depends on `setup`; runs `responsive.spec.ts` only.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [['html', { open: 'never' }], ['list']],
  globalSetup: './e2e/global-setup.ts',

  use: {
    baseURL: 'http://localhost:8080',
    locale: 'en-US',
    timezoneId: 'UTC',
    trace: 'on-first-retry',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
  },

  projects: [
    {
      name: 'setup',
      testMatch: /auth\.setup\.ts$/,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'chromium',
      dependencies: ['setup'],
      testIgnore: [/auth\.setup\.ts$/, /auth\.spec\.ts$/, /responsive\.spec\.ts$/],
      use: {
        ...devices['Desktop Chrome'],
        // Must mirror STORAGE_STATE_PATH in e2e/helpers/auth.helper.ts.
        storageState: 'playwright/.auth/user.json',
      },
    },
    {
      name: 'chromium-fresh',
      testMatch: /auth\.spec\.ts$/,
      use: {
        ...devices['Desktop Chrome'],
        storageState: { cookies: [], origins: [] },
      },
    },
    {
      name: 'mobile',
      dependencies: ['setup'],
      testMatch: /responsive\.spec\.ts$/,
      use: {
        ...devices['iPhone 13'],
        // Must mirror STORAGE_STATE_PATH in e2e/helpers/auth.helper.ts.
        storageState: 'playwright/.auth/user.json',
      },
    },
  ],
});
