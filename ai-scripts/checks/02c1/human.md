# Human checks — Phase 02c1 Docker Compose
□ `make dev` starts postgres + business-service locally and nothing else (no BFF, no Keycloak).
□ `make up` starts the full local-intg stack, browser login at http://localhost:5173 lands on /boats. (The SPA is served by Vite on :5173 — the BFF on :8080 is API + auth only.)
□ Keycloak issuer-uri in application-local-intg.yml uses Docker DNS (keycloak:8080), not localhost:8180.
