---
paths: ["infra/**", "docker-compose*.yml", "Dockerfile*", ".github/**"]
---
# Infrastructure Rules
- Docker: multi-stage builds, non-root, pinned versions, health checks
- 5 long-running containers (local-intg): frontend (Vite dev server), bff (Spring Cloud Gateway, OAuth + TokenRelay), business-service, postgres, keycloak — plus three one-shot sidecars: bff-keygen (RSA key), keycloak-config (realm import), frontend-codegen (SPA TypeScript API client codegen).
- 2 containers (dev): postgres-dev, business-service-dev (auth bypass)
- docker-compose.yml: local-intg (full stack). Browser opens the SPA at http://localhost:5173, served by the `frontend` service; the BFF on :8080 is API + auth only.
- docker-compose.dev.yml: dev mode (business-service+postgres, no Keycloak, no BFF)
- BFF Dockerfile: 2-stage (JDK BFF jar → JRE runtime). The Vue SPA is NOT baked in — it is served by Vite in dev / local-intg and by Azure Static Web Apps (Bring-Your-Own-Backend) in staging / prod. See ai-scripts/02c1-docker.md.
- Business Service Dockerfile: 2-stage (JDK→jar, JRE runtime)
- Terraform: modular, Azure remote state, pinned providers. Staging / prod adds an `azurerm_static_site` + `azurerm_static_site_linked_backend` pair pointing at the BFF Container App.
- GitHub Actions: OIDC federation, staging auto-deploy, prod on release. SPA deploy via `Azure/static-web-apps-deploy@v1` runs alongside the BFF + BS image deploys.
