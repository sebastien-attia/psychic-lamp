# `boat-app-realm.json` — test-only Keycloak realm fixture

Imported by `KeycloakOAuthFlowIntegrationTest` via the `dasniko/testcontainers-keycloak`
container's `withRealmImportFile(...)` hook. The filename matches the
`<realm>-realm.json` convention Keycloak 26.6 enforces on import.

## Scope

Test-only. Do **not** point at staging or prod, and do **not** import via
Ansible. The username/password (`demo` / `demo123`) and the static client
secret (`test-secret`) are throwaway credentials baked into the fixture so the
integration test can fetch a JWT in a single HTTP call.

## Divergence from `infra/keycloak/realm.yaml`

The real realm pinned for local-intg/staging/prod uses the
`boat-app-confidential` client with `client-jwt` authentication and the
authorization-code flow. That flow needs a browser; we exercise it from the
Playwright suite against `docker compose up`, not from this Java test.

This fixture instead defines a `boat-app-test` client with:

- `clientAuthenticatorType: client-secret-basic` — the test posts the secret
  directly to `/protocol/openid-connect/token` with `grant_type=password`.
- `directAccessGrantsEnabled: true` — required for the password grant.

The realm name (`boat-app`) and the `user` role mirror the production realm so
that the BFF code under test sees the same issuer URI shape.

## When to update

Bump in lockstep with `infra/keycloak/realm.yaml` for any change that affects
the issuer URI, default scopes, or token claims. Schema changes that
`KeycloakOAuthFlowIntegrationTest` does not assert on can be ignored — the
test only checks that signing, claims (`iss`, `preferred_username`, `azp`),
and expiry land where Spring's resource-server expects them.
