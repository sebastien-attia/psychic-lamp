import { expect, test } from '@playwright/test';

/**
 * SCG-migration regression guard at the e2e layer.
 *
 * After the migration the BFF (Spring Cloud Gateway, :8080) is API + auth
 * only. The Vue SPA is served by Vite (dev / local-intg) or Azure Static
 * Web Apps (staging / prod). Hitting `/`, `/index.html`, or `/assets/**`
 * directly on the BFF must NOT return the SPA bundle.
 *
 * The Java-side `BffStaticContentRegressionTest` enforces the same
 * contract from inside the BFF context (MockMvc), but this spec catches
 * regressions where someone re-introduces a static-resource handler at
 * the live HTTP boundary — the kind of mistake unit tests miss because
 * they go through different filter chains.
 *
 * Targets the BFF directly via the `BFF_BASE_URL` env var (defaults to
 * the local-intg port). In the staging Playwright project this can be
 * pointed at the BFF Container App FQDN to assert the same contract
 * across the SWA cutover.
 */

const BFF_BASE_URL = process.env.BFF_BASE_URL ?? 'http://localhost:8080';

test.describe('SCG migration — BFF does not serve the SPA', () => {
  test('GET / on the BFF must not return a 2xx with HTML', async ({ request }) => {
    const response = await request.get(`${BFF_BASE_URL}/`, {
      maxRedirects: 0,
      failOnStatusCode: false,
    });

    expect(
      response.status(),
      'BFF must not serve / — SPA hosted by Vite/SWA',
    ).not.toBeGreaterThanOrEqual(200);
  });

  test('GET /index.html on the BFF must 4xx', async ({ request }) => {
    const response = await request.get(`${BFF_BASE_URL}/index.html`, {
      maxRedirects: 0,
      failOnStatusCode: false,
    });
    // SecurityConfig dropped the permitAll matcher for /index.html, so the
    // anyRequest().authenticated() rule produces 401 (or, if the SPA
    // re-appears, a 200 OK with the index — which is the regression).
    expect(response.status()).toBeGreaterThanOrEqual(400);
    expect(response.status()).toBeLessThan(500);
  });

  test('GET /assets/anything on the BFF must 4xx', async ({ request }) => {
    const response = await request.get(`${BFF_BASE_URL}/assets/app.123abc.js`, {
      maxRedirects: 0,
      failOnStatusCode: false,
    });
    expect(response.status()).toBeGreaterThanOrEqual(400);
    expect(response.status()).toBeLessThan(500);
  });

  test('GET /.well-known/jwks.json on the BFF must 200 with a JWK Set', async ({
    request,
  }) => {
    const response = await request.get(`${BFF_BASE_URL}/.well-known/jwks.json`);
    expect(response.status()).toBe(200);
    const body = (await response.json()) as { keys: Array<{ kty: string; kid: string; use: string }> };
    expect(body.keys.length).toBeGreaterThan(0);
    expect(body.keys[0].kty).toBe('RSA');
    expect(body.keys[0].use).toBe('sig');
  });

  test('GET /actuator/health on the BFF must 200', async ({ request }) => {
    const response = await request.get(`${BFF_BASE_URL}/actuator/health`);
    expect(response.status()).toBe(200);
  });
});
