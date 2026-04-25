# Human checks — Phase 02b2 Frontend Auth
□ Clicking "Login" navigates to /oauth2/authorization/keycloak (handled by BFF, not by a client-side OAuth flow).
□ While fetchUser is pending, the UI shows a loading state — no flash of unauthenticated content.
□ After logout, GET /api/me returns 401 and the UI routes back to the login prompt.
