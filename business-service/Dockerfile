# business-service/Dockerfile
#
# Two-stage build for the Business Service (no frontend).
#
#   1. build   — eclipse-temurin:25-jdk. Runs `./mvnw package -DskipTests`.
#   2. runtime — eclipse-temurin:25-jre. Non-root user, container-aware
#                JVM flags, HEALTHCHECK on /actuator/health.
#
# Build context: REPO ROOT (compose passes context=.). The business-service/
# directory must be present at the context root.
#
# Build:
#   docker build -t boatapp/business-service:local -f business-service/Dockerfile .

# ── Stage 1: Maven build ──────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Copy Maven wrapper + every submodule pom first to leverage the Docker
# layer cache when only sources change. The four submodules
# (domain → application → infrastructure → bootstrap) make up the
# multi-module reactor; their poms must all be present before
# `dependency:go-offline` can resolve the in-repo dependency graph.
# The root pom.xml (boatapp-parent) is the parent of
# business-service/pom.xml via <relativePath>../pom.xml</relativePath>;
# without it on disk, parent resolution fails before any plugin runs.
COPY pom.xml ./
COPY business-service/.mvn ./business-service/.mvn
COPY business-service/mvnw business-service/mvnw.cmd ./business-service/
COPY business-service/pom.xml                ./business-service/
COPY business-service/domain/pom.xml         ./business-service/domain/
COPY business-service/application/pom.xml    ./business-service/application/
COPY business-service/infrastructure/pom.xml ./business-service/infrastructure/
COPY business-service/bootstrap/pom.xml      ./business-service/bootstrap/
# Honour CI overrides like `--build-arg REVISION=1.0.0` so the JAR baked
# into the image carries the release version. Defaults to the in-pom
# SNAPSHOT (no flag → boatapp-parent's <revision> wins). NOT passed to
# go-offline below: dependency resolution is version-agnostic, so keeping
# REVISION off the warm-cache layer means a release rebuild only invalidates
# the final `package` step, not the (slow) dependency download.
ARG REVISION
RUN cd business-service && ./mvnw -B -ntp dependency:go-offline
COPY business-service/domain/src         ./business-service/domain/src
COPY business-service/application/src    ./business-service/application/src
COPY business-service/infrastructure/src ./business-service/infrastructure/src
COPY business-service/bootstrap/src      ./business-service/bootstrap/src
COPY business-service/spotbugs-security-include.xml business-service/spotbugs-exclude-generated.xml ./business-service/
COPY contracts ./contracts
# `-pl bootstrap -am` builds bootstrap and everything it depends on
# (domain → application → infrastructure → bootstrap), skipping any
# unrelated reactor work.
RUN cd business-service && ./mvnw -B -ntp ${REVISION:+-Drevision=${REVISION}} -pl bootstrap -am package -DskipTests

# ── Stage 2: runtime ──────────────────────────────────────────────────────
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
COPY --from=build /workspace/business-service/bootstrap/target/business-service.jar /app/app.jar
USER spring:spring
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -sf http://localhost:8081/actuator/health || exit 1
# Container-aware JVM tuning. UseContainerSupport is on by default since
# JDK 11+, kept explicit for clarity. MaxRAMPercentage caps heap relative
# to the container memory limit.
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
