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

.PHONY: help dev dev-frontend up down down-dev logs clean test-bff test-business test e2e

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
	@test -f infra/docker/keys/bff-signing-key.pem || { \
		echo "✗ infra/docker/keys/bff-signing-key.pem missing."; \
		echo "  Run ./ai-scripts/00b-generate-bff-key.sh first."; \
		exit 1; \
	}
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
