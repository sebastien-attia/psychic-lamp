# bff/Dockerfile
#
# Three-stage build for the BFF service.
#
#   1. frontend  — node:22-alpine. Runs `npm ci && npm run build` against
#                  ../frontend, producing the Vue SPA bundle in dist/.
#   2. bff-build — eclipse-temurin:25-jdk. Copies the SPA into
#                  bff/src/main/resources/static/ so the Spring Boot JAR
#                  serves it as static files at the application root, then
#                  runs `./mvnw package -DskipTests`.
#   3. runtime   — eclipse-temurin:25-jre. Non-root user, container-aware
#                  JVM flags, HEALTHCHECK on /actuator/health.
#
# Build context: REPO ROOT (compose passes context=.). The frontend/ and
# bff/ directories must both be present at the context root.
#
# Build:
#   docker build -t boatapp/bff:local -f bff/Dockerfile .
# Run (compose handles this; standalone for sanity):
#   docker run --rm -p 8080:8080 boatapp/bff:local

# ── Stage 1: frontend bundle ──────────────────────────────────────────────
FROM node:22-alpine AS frontend
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ── Stage 2: BFF Maven build ──────────────────────────────────────────────
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

# ── Stage 3: runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime
# curl is needed by HEALTHCHECK below; --no-install-recommends keeps the
# image lean. The temurin image is Ubuntu-based.
RUN apt-get update \
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
