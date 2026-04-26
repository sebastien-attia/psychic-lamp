/// <reference types="vitest" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Dummy user returned by the dev-mode `/api/me` proxy bypass.
 *
 * In `dev` mode the BFF is not running (no OAuth2, no session), so the
 * `/api/me` endpoint declared in `contracts/openapi.yaml` has no real
 * server. To keep frontend code uniform across modes, the dev proxy
 * intercepts `/api/me` and returns this hardcoded payload — matching the
 * example in the OpenAPI `UserInfoResponse` schema so the contract round-trips
 * through the generated TypeScript types.
 */
const DEV_DUMMY_USER = {
  id: '99999999-9999-9999-9999-999999999999',
  username: 'jsparrow',
  email: 'jack.sparrow@example.com',
  firstName: 'Jack',
  lastName: 'Sparrow',
}

/**
 * Vite + Vitest configuration with two operating modes for the dev
 * server, and a single shared test config for component tests.
 *
 * Dev server modes:
 * - `dev` (default, `npm run dev`):
 *     proxy `/api/**` → `http://localhost:8081` (Business Service, `permitAll`).
 *     `/api/me` is intercepted and answered with `DEV_DUMMY_USER`.
 *     No `/oauth2` or `/login` proxy: there is no Keycloak in dev mode.
 *
 * - `local-intg` (`npm run dev:intg`):
 *     proxy `/api,/oauth2,/login,/logout` → BFF. The default target is
 *     `http://localhost:8080` (host-run path: `cd frontend && npm run
 *     dev:intg` against a BFF reachable on the host). The target is
 *     overridable via `VITE_BFF_TARGET`, which is what the in-compose
 *     `frontend` service uses to reach the BFF over the compose network
 *     (`http://bff:8080`).
 *     The BFF performs the OAuth2 Authorization-Code flow, sets the
 *     session cookie, and forwards Bearer tokens to the Business Service.
 *
 * Test config:
 * - `happy-dom` is faster than `jsdom` for component tests and supports
 *   the DOM APIs the SPA actually exercises (cookies, matchMedia,
 *   Headless UI's focus management).
 * - `globals: true` — `describe` / `it` / `expect` / `vi` are imported
 *   automatically; tsconfig.app.json adds the matching ambient types.
 */
export default defineConfig(({ mode }) => {
  const isLocalIntg = mode === 'local-intg'
  // Host-run default; the in-compose `frontend` service overrides this with
  // `http://bff:8080` so the proxy resolves over the compose network.
  const bffTarget = process.env.VITE_BFF_TARGET ?? 'http://localhost:8080'
  // What the BFF must see as `Host:` so Spring Security builds an
  // OAuth2 `redirect_uri` the BROWSER can resolve. Defaults to the host
  // portion of `bffTarget` (correct for the host-run path where the proxy
  // target IS the public URL). In compose the target is `bff:8080` (only
  // resolvable on the docker network) but the browser must follow the
  // OAuth callback to `localhost:8080`, so the in-compose `frontend`
  // service sets `VITE_BFF_PUBLIC_HOST=localhost:8080`.
  const bffPublicHost =
    process.env.VITE_BFF_PUBLIC_HOST ?? new URL(bffTarget).host

  const bffProxy = {
    target: bffTarget,
    changeOrigin: false,
    headers: { host: bffPublicHost },
  }

  return {
    plugins: [vue()],
    server: {
      proxy: isLocalIntg
        ? {
            // local-intg: full OAuth2 + session via BFF
            '/api':    { ...bffProxy },
            '/oauth2': { ...bffProxy },
            '/login':  { ...bffProxy },
            '/logout': { ...bffProxy },
          }
        : {
            // dev: Business Service direct, no auth, /api/me bypassed
            '/api': {
              target: 'http://localhost:8081',
              changeOrigin: true,
              bypass(req, res) {
                if (req.url === '/api/me' && req.method === 'GET') {
                  res!.setHeader('content-type', 'application/json')
                  res!.end(JSON.stringify(DEV_DUMMY_USER))
                  return false
                }
              },
            },
          },
    },
    test: {
      environment: 'happy-dom',
      globals: true,
      setupFiles: ['./src/__tests__/setup.ts'],
      include: ['src/**/*.{test,spec}.ts'],
    },
  }
})
