# Human checks — Phase 02a4 Backend Auth
□ In dev mode, GET /api/v1/boats works with no Authorization header (no 401/403).
□ In local-intg (docker compose up), open http://localhost:8080, click Login, finish Keycloak flow with demo/demo123 — you land on /boats with a session cookie.
□ Keycloak admin console shows `boat-app-confidential` client with "Client Authenticator = Signed JWT" and a JWKS URL pointing at the BFF (no shared secret field).
□ Tail `docker compose logs bff` during login — you see `client_assertion_type` and `client_assertion=<jwt>` on the token endpoint call.
□ Stopping/restarting the BFF container does NOT break already-authenticated sessions (Spring Session JDBC persists them in Postgres).
