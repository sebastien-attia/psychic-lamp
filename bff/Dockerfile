# bff/Dockerfile
#
# Two-stage build for the BFF service.
#
#   1. bff-build — eclipse-temurin:25-jdk. Runs `./mvnw package -DskipTests`
#                  to produce the Spring Boot fat jar. The OpenAPI codegen
#                  bound to `generate-sources` runs implicitly during the
#                  Maven lifecycle; only the User-tag DTOs (UserInfoResponse)
#                  are emitted now that the BFF is a Spring Cloud Gateway
#                  and no longer hosts an outbound HTTP-Interface client.
#   2. runtime   — eclipse-temurin:25-jre. Non-root user, container-aware
#                  JVM flags, HEALTHCHECK on /actuator/health.
#
# The SPA is no longer baked into this image. In dev / local-intg the SPA
# is served by Vite at :5173; in staging / prod it is hosted on Azure
# Static Web Apps with a linked-backend pointing at this Container App.
#
# Build context: REPO ROOT (compose passes context=.). Both bff/ and
# contracts/ must be present at the context root.
#
# Build:
#   docker build -t boatapp/bff:local -f bff/Dockerfile .
# Run (compose handles this; standalone for sanity):
#   docker run --rm -p 8080:8080 boatapp/bff:local

# ── Stage 1: BFF Maven build ──────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS bff-build
WORKDIR /workspace
# Copy Maven wrapper + pom first so the dependency-resolution layer is reused
# when only `bff/src` or `contracts/` changes. Note: `dependency:go-offline`
# does not pre-resolve the openapi-generator-maven-plugin's codegen
# transitives (that plugin runs in `generate-sources`, after this layer), so
# the first `package` invocation on a clean cache will still pull a few
# codegen artifacts. Subsequent builds cache them through Maven's local repo
# layer.
COPY bff/.mvn ./bff/.mvn
COPY bff/mvnw bff/mvnw.cmd bff/pom.xml ./bff/
RUN cd bff && ./mvnw -B -ntp dependency:go-offline
COPY bff/src ./bff/src
COPY contracts ./contracts
RUN cd bff && ./mvnw -B -ntp package -DskipTests

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
