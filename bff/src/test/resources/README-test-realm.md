# test-realm.json

Test-only Keycloak realm export consumed by
`bff/src/test/java/.../keycloak/KeycloakOAuthFlowIntegrationTest.java`.

This is a deliberate stand-in for the canonical
`infra/keycloak/realm.yaml` produced in phase 02c1 — keep this file
**minimal** (a single realm + the `bff-test` client + one demo user). Do
NOT mirror production realm config here; if you need richer Keycloak
fixtures for tests after 02c1 lands, switch the test class to import
`infra/keycloak/realm.yaml` via `adorsys/keycloak-config-cli` instead.

The `bff-test` client uses `client-jwt` (private_key_jwt) with
`use.jwks.url=true`, so the live OAuth2 flow needs the BFF's JWKS endpoint
to be reachable from inside the Keycloak container. The test class will
need to call `Testcontainers.exposeHostPorts(bffPort)` and rewrite the
`jwks.url` attribute at runtime once that hookup is implemented (currently
`@Disabled` pending phase 02c1).
