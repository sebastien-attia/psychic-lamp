# Human checks — Phase 02c1 Docker Compose
□ `make dev` starts postgres + business-service locally and nothing else (no BFF, no Keycloak).
□ `make up` starts the full local-intg stack, browser login at http://localhost:8080 lands on /boats.
□ Keycloak issuer-uri in application-local-intg.yml uses Docker DNS (keycloak:8080), not localhost:8180.
