---
paths: ["**/*Test.java", "**/*.test.ts", "**/*.spec.ts", "frontend/e2e/**"]
---
# Testing Rules
- ArchUnit tests enforce hexagonal architecture in both services (domain has ZERO Spring imports)
- Priority: ArchUnit > E2E > Integration > Unit
- Business Service: jwt() mock post-processor for auth (NOT oidcLogin — no session in resource server)
  - SecurityMockMvcRequestPostProcessors.jwt() from spring-security-test
  - No Keycloak container needed for Business Service integration tests
- BFF: Testcontainers Keycloak (real OAuth2 flow) + WireMock (mock Business Service)
- E2E: Playwright with real Keycloak browser login
- dev profile tests run against Business Service only, no Keycloak
