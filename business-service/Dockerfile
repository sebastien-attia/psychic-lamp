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
# Copy Maven wrapper + pom first to leverage the Docker layer cache when
# only sources change.
COPY business-service/.mvn ./business-service/.mvn
COPY business-service/mvnw business-service/mvnw.cmd business-service/pom.xml ./business-service/
RUN cd business-service && ./mvnw -B -ntp dependency:go-offline
COPY business-service/src ./business-service/src
COPY contracts ./contracts
RUN cd business-service && ./mvnw -B -ntp package -DskipTests

# ── Stage 2: runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime
# curl is needed by HEALTHCHECK below; --no-install-recommends keeps the
# image lean. The temurin image is Ubuntu-based.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 spring \
 && useradd  --system --uid 1001 --gid spring --no-create-home spring
WORKDIR /app
COPY --from=build /workspace/business-service/target/*.jar /app/app.jar
USER spring:spring
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -sf http://localhost:8081/actuator/health || exit 1
# Container-aware JVM tuning. UseContainerSupport is on by default since
# JDK 11+, kept explicit for clarity. MaxRAMPercentage caps heap relative
# to the container memory limit.
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
