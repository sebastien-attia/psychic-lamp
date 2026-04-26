# Human checks — Phase 02a4 Backend Auth
□ In dev mode, GET /api/v1/boats works with no Authorization header (no 401/403).
□ In local-intg (docker compose up), open http://localhost:5173, click Login, finish Keycloak flow with demo/demo123 — you land on /boats with a session cookie. (The SPA is served by Vite on :5173; Vite proxies /oauth2,/login,/logout,/api to the BFF on :8080. Keycloak's redirect_uri also points at :5173 so the callback flows back through Vite.)
□ Keycloak admin console shows `boat-app-confidential` client with "Client Authenticator = Signed JWT" and a JWKS URL pointing at the BFF (no shared secret field).
□ Tail `docker compose logs bff` during login — you see `client_assertion_type` and `client_assertion=<jwt>` on the token endpoint call.
□ Stopping/restarting the BFF container does NOT break already-authenticated sessions (Spring Session JDBC persists them in Postgres).
