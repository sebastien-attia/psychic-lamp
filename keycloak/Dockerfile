# Keycloak optimized image — bakes the PostgreSQL DB vendor into the
# distribution so the Container App can launch with `start --optimized`.
#
# The stock quay.io/keycloak/keycloak image is built for the dev-file
# database. Running `start --optimized` against it crashes immediately
# with "Unable to find the database vendor" because the
# `kc.sh build --db=postgres` step has not been performed. This image
# performs that build, then copies the augmented `/opt/keycloak/` into
# a fresh runtime layer so build-only artefacts (caches, intermediate
# files) don't ship to production.
#
# Version is pinned inline (not via ARG) so that `terraform plan` /
# `docker build` invocations from any caller — CI, a workstation,
# Renovate's bump PRs — see the same source of truth. Bumping Keycloak
# means editing both this literal and docker-compose.yml's local-intg
# image tag in lockstep.

FROM quay.io/keycloak/keycloak:26.6.1 AS builder

# DB vendor is the only build-time customisation. KC_HEALTH_ENABLED and
# KC_METRICS_ENABLED are runtime concerns — the Container App passes them
# as env vars at start time so they don't need to be baked here.
ENV KC_DB=postgres

RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.6.1

# Re-declare KC_DB in the runtime stage so the image is self-contained:
# `docker run <this-image> start --optimized` works without the caller
# having to pass `-e KC_DB=postgres`. The Container App template passes
# it explicitly anyway, but the redeclaration removes a footgun for
# operators debugging the production image with `docker run`.
ENV KC_DB=postgres

COPY --from=builder /opt/keycloak/ /opt/keycloak/

# Same entrypoint as upstream — the Container App template overrides
# `command` to `["start", "--optimized"]`, which now succeeds because
# the PostgreSQL JDBC vendor was baked in by the builder stage above.
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
