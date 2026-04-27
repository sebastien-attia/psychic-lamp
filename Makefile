# Makefile — convenience commands for the boat-app local workflows.
#
# Two compose stacks live in this repo (see ai-scripts/02c1-docker.md):
#   * docker-compose.dev.yml — postgres + business-service-dev (auth bypass)
#   * docker-compose.yml     — full local-intg stack (BFF + Keycloak too)
#
# Before the first `make up`, generate the BFF signing key once:
#   ./ai-scripts/00b-generate-bff-key.sh
#
# Run `make help` to list every target with its description.

.PHONY: help dev dev-frontend up down down-dev logs clean test-bff test-business test e2e \
        current-version release release-dry-run

## help: Show this help (default target).
help:
	@awk 'BEGIN {FS = ":.*?## "} /^## / {sub(/^## /, "", $$0); split($$0, a, ": "); printf "  \033[36m%-15s\033[0m %s\n", a[1], a[2]}' $(MAKEFILE_LIST)

## dev: Start dev mode (postgres + business-service-dev only).
dev:
	docker compose -f docker-compose.dev.yml up -d --build

## dev-frontend: Run Vite dev server (proxies /api to business-service:8081).
dev-frontend:
	cd frontend && npm run dev

## up: Start full local-intg stack (postgres + keycloak + business-service + bff).
up:
	docker compose up -d --build

## down: Stop the local-intg stack.
down:
	docker compose down

## down-dev: Stop the dev-mode stack.
down-dev:
	docker compose -f docker-compose.dev.yml down

## logs: Tail logs of the local-intg stack.
logs:
	docker compose logs -f

## clean: Tear down everything and drop volumes + locally-built images.
clean:
	docker compose down -v --rmi local
	docker compose -f docker-compose.dev.yml down -v --rmi local

## test-bff: Run the BFF Maven verify suite.
test-bff:
	cd bff && ./mvnw verify

## test-business: Run the Business Service Maven verify suite.
test-business:
	cd business-service && ./mvnw verify

## test: Run both Maven test suites.
test: test-bff test-business

## e2e: Run the Playwright end-to-end suite (requires running stack).
e2e:
	cd frontend && npx playwright test

# ── Versioning / release ──────────────────────────────────────────────────
# The single source of truth for the artifact version is <revision> in the
# root pom.xml. `make release` cuts a release of the current SNAPSHOT and
# publishes a GitHub release that fires deploy-production.yml.
#
# Usage:
#   make current-version                        # print the current <revision>
#   make release                                # patch bump (0.1.0-SNAPSHOT → v0.1.0, then 0.1.1-SNAPSHOT)
#   make release NEXT=0.2.0                     # explicit next snapshot (minor/major bump)
#   make release AS=0.1.5                       # release a different version than the SNAPSHOT base
#   make release AS=1.0.0 NEXT=1.1.0            # both
#   make release-dry-run [NEXT=…] [AS=…]        # print every step, no writes / pushes

## current-version: Print the current <revision> from the root pom.
current-version:
	@sed -n 's|^[[:space:]]*<revision>\([^<]*\)</revision>.*|\1|p' pom.xml | head -n1

## release: Cut a release of the current SNAPSHOT (NEXT=, AS= optional).
release:
	@./scripts/release.sh $(if $(AS),--as $(AS)) $(if $(NEXT),--next $(NEXT))

## release-dry-run: Show what `make release` would do, without writing.
release-dry-run:
	@./scripts/release.sh --dry-run $(if $(AS),--as $(AS)) $(if $(NEXT),--next $(NEXT))
