# bff/Dockerfile
#
# Four-stage build for the BFF service.
#
#   1. ts-codegen — openapitools/openapi-generator-cli:v7.21.0 (bundles Java
#                   + the generator JAR). Generates the typescript-axios
#                   client from contracts/openapi.yaml into /out, driven
#                   by contracts/codegen-typescript-axios.json (the same
#                   config consumed by `npm run generate:api` — single
#                   source of truth, no flag drift). Pulled out of the
#                   Node stage because the npm wrapper shells out to
#                   `java -jar`, and node:22-alpine has no JRE.
#   2. frontend   — node:22-alpine. Runs `npm ci && npm run build:no-codegen`
#                   against ../frontend (build:no-codegen skips
#                   generate:api — the generated client is COPYed in
#                   from ts-codegen), producing the Vue SPA bundle in
#                   dist/.
#   3. bff-build  — eclipse-temurin:25-jdk. Copies the SPA into
#                   bff/src/main/resources/static/ so the Spring Boot JAR
#                   serves it as static files at the application root,
#                   then runs `./mvnw package -DskipTests`.
#   4. runtime    — eclipse-temurin:25-jre. Non-root user, container-aware
#                   JVM flags, HEALTHCHECK on /actuator/health.
#
# Build context: REPO ROOT (compose passes context=.). The frontend/, bff/
# and contracts/ directories must all be present at the context root.
#
# Build:
#   docker build -t boatapp/bff:local -f bff/Dockerfile .
# Run (compose handles this; standalone for sanity):
#   docker run --rm -p 8080:8080 boatapp/bff:local

# ── Stage 1: TypeScript client codegen ────────────────────────────────────
# Pin matches the version the npm wrapper auto-selects (frontend log shows
# "Did set selected version to 7.21.0"). Bumping requires bumping both.
FROM openapitools/openapi-generator-cli:v7.21.0 AS ts-codegen
WORKDIR /work
# Copy the whole contracts/ dir (not just openapi.yaml) so future $ref
# splits keep working without a Dockerfile change.
COPY contracts/ ./contracts/
# `RUN` does not invoke ENTRYPOINT, so we call the entrypoint script by
# absolute path. Same script `docker run` would invoke implicitly.
RUN /usr/local/bin/docker-entrypoint.sh generate \
      -i /work/contracts/openapi.yaml \
      -c /work/contracts/codegen-typescript-axios.json \
      -o /out

# ── Stage 2: frontend bundle ──────────────────────────────────────────────
FROM node:22-alpine AS frontend
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# Drop in the pre-generated TS client so build:no-codegen can skip
# generate:api (which would otherwise need a JRE in this stage).
# --chown normalizes ownership regardless of the codegen image's user.
COPY --from=ts-codegen --chown=root:root /out/ ./src/services/api-client/generated/
RUN npm run build:no-codegen

# ── Stage 3: BFF Maven build ──────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS bff-build
WORKDIR /workspace
# Copy Maven wrapper + pom first to leverage the Docker layer cache when
# only sources change.
COPY bff/.mvn ./bff/.mvn
COPY bff/mvnw bff/mvnw.cmd bff/pom.xml ./bff/
RUN cd bff && ./mvnw -B -ntp dependency:go-offline
COPY bff/src ./bff/src
COPY contracts ./contracts
# Splice the SPA bundle into resources/static so Spring Boot serves it from
# the JAR. Wipe any committed contents of resources/static first so the
# bundle from stage 1 is the sole source of truth (prevents tracked
# placeholder files from shadowing the real index.html).
RUN rm -rf /workspace/bff/src/main/resources/static \
 && mkdir -p /workspace/bff/src/main/resources/static
COPY --from=frontend /workspace/frontend/dist/ ./bff/src/main/resources/static/
RUN cd bff && ./mvnw -B -ntp package -DskipTests

# ── Stage 4: runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime
# `apt-get upgrade` shrinks the window between an Ubuntu CVE fix and the
# next eclipse-temurin base rebuild — without it, Trivy in CI fails the
# container-scan gate on every base-image lag (e.g. CVE-2026-4878 in
# libcap2). curl is needed by HEALTHCHECK below; --no-install-recommends
# keeps the image lean. The temurin image is Ubuntu-based.
RUN apt-get update \
 && apt-get upgrade -y \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 spring \
 && useradd  --system --uid 1001 --gid spring --no-create-home spring
WORKDIR /app
COPY --from=bff-build /workspace/bff/target/*.jar /app/app.jar
USER spring:spring
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -sf http://localhost:8080/actuator/health || exit 1
# Container-aware JVM tuning. UseContainerSupport is on by default since
# JDK 11+, kept explicit for clarity. MaxRAMPercentage caps heap relative
# to the container memory limit.
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
