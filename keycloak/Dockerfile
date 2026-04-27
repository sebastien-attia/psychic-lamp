# Keycloak optimized image — bakes the PostgreSQL DB vendor and the
# custom `boatapp-fun` login theme into the distribution so the App
# Service Web App can launch with `start --optimized`.
#
# The stock quay.io/keycloak/keycloak image is built for the dev-file
# database. Running `start --optimized` against it crashes immediately
# with "Unable to find the database vendor" because the
# `kc.sh build --db=postgres` step has not been performed. This image
# performs that build, then copies the augmented `/opt/keycloak/` into
# a fresh runtime layer so build-only artefacts (caches, intermediate
# files) don't ship to production.
#
# KEYCLOAK_VERSION is exposed as an ARG with a hard-coded default so a
# bare `docker build .` still works (CI, workstations, Renovate's bump
# PRs all see the same default). Override with
# `--build-arg KEYCLOAK_VERSION=…` only when intentionally pinning to a
# different upstream release. Bumping Keycloak means editing both this
# default and docker-compose.yml's local-intg image tag in lockstep.

ARG KEYCLOAK_VERSION=26.6.1

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder

# Build-time options. Under `start --optimized`, runtime values supplied
# for build-time keys are silently ignored, so anything that needs to be
# on at boot must be set HERE — not in the Web App's app_settings.
#
# - KC_DB:               picks the JDBC vendor (PostgreSQL).
# - KC_HEALTH_ENABLED:   exposes /health on the management interface;
#                        App Service health probes hit /health/ready.
# - KC_METRICS_ENABLED:  exposes /metrics for observability.
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true

RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}

# Re-declare KC_DB in the runtime stage so the image is self-contained:
# `docker run <this-image> start --optimized` works without the caller
# having to pass `-e KC_DB=postgres`. The Web App template passes it
# explicitly anyway, but the redeclaration removes a footgun for
# operators debugging the production image with `docker run`.
ENV KC_DB=postgres

COPY --from=builder /opt/keycloak/ /opt/keycloak/

# Bake the custom `boatapp-fun` login theme into the optimized image so
# `start --optimized` does not need a runtime mount. The same directory
# is volume-mounted in local-intg via docker-compose.yml; this COPY is
# the staging/production-equivalent baked path.
COPY ./themes /opt/keycloak/themes

# Same entrypoint as upstream — the Web App template overrides
# `app_command_line` to `start --optimized`, which now succeeds because
# the PostgreSQL JDBC vendor was baked in by the builder stage above.
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
