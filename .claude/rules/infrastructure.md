---
paths: ["infra/**", "docker-compose*.yml", "Dockerfile*", ".github/**"]
---
# Infrastructure Rules
- Docker: multi-stage builds, non-root, pinned versions, health checks
- 4 containers (local-intg): bff (serves Vue SPA + OAuth), business-service, postgres, keycloak
- 2 containers (dev): postgres-dev, business-service-dev (auth bypass)
- docker-compose.yml: local-intg (full stack)
- docker-compose.dev.yml: dev mode (business-service+postgres, no Keycloak, no BFF)
- BFF Dockerfile: 3-stage (Node→frontend dist, JDK→BFF jar with static/, JRE runtime)
- Business Service Dockerfile: 2-stage (JDK→jar, JRE runtime)
- Terraform: modular, Azure remote state, pinned providers
- GitHub Actions: OIDC federation, staging auto-deploy, prod on release
