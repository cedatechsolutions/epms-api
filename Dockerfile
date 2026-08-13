# Multi-stage build for the CEMS API.
#
# Target platform is linux/arm64 (AWS Graviton, t4g.*). Build from an x86 workstation with:
#   docker buildx build --platform linux/arm64 -t cems-api:latest .
# Both base images are multi-arch, so the same Dockerfile also builds natively on amd64.

# ---- Stage 1: build ----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Resolve dependencies against the POM alone first. This layer is cached and only
# reinvalidated when the POM changes, so ordinary source edits skip the download.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI, not here: the build image has no database service and a failed
# deploy build should surface as a test failure upstream, not at image build time.
RUN mvn -B -DskipTests clean package \
    && mv target/api-*.jar target/app.jar

# ---- Stage 2: runtime --------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl backs the container healthcheck below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Unprivileged runtime user. The uid is fixed so the bind-mounted storage directory
# on the host can be chowned to a matching owner (see deploy/AWS_DEPLOYMENT.md).
RUN groupadd --gid 10001 cems \
    && useradd --uid 10001 --gid cems --shell /usr/sbin/nologin --create-home cems

WORKDIR /app
COPY --from=build /build/target/app.jar ./app.jar

# Uploads and generated reports land here; mounted from the host so they survive
# container replacement. Created up front so the mount inherits the right owner.
RUN mkdir -p /var/lib/cems/storage && chown -R cems:cems /var/lib/cems /app
VOLUME ["/var/lib/cems/storage"]

USER cems
EXPOSE 8080

ENV APP_STORAGE_LOCAL_BASE_PATH=/var/lib/cems/storage \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

# MaxRAMPercentage (not a fixed -Xmx) keeps the heap proportional to whatever memory limit the
# container is given, which matters on a 2 GiB instance shared with Postgres and nginx.
# SerialGC has the smallest footprint for a single small container; ExitOnOutOfMemoryError makes
# systemd restart a wedged JVM instead of leaving it thrashing.

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
