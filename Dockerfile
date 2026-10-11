# syntax=docker/dockerfile:1
# -----------------------------------------------------------------------------
# ms-ticket container image.
#
# One image, two runtime modes: the same artefact runs as the HTTP API or as the
# SQS/sweeper worker depending on TICKETING_RUNTIMEMODE (API | WORKER | ALL), so
# both ECS services deploy exactly the same, scanned digest.
#
#   Build : docker build -t ms-ticket:local .
#   Lint  : docker build --check .
#   Run   : docker compose up -d   (see docker/README.md)
#
# Requires BuildKit (default since Docker 23) for the Gradle cache mount.
# -----------------------------------------------------------------------------

# Base images are build arguments so CI can pin them by digest (Renovate/Dependabot).
ARG JDK_IMAGE=eclipse-temurin:25-jdk
ARG JRE_IMAGE=eclipse-temurin:25-jre

# =============================================================================
# Stage 1 - build: compile the Spring Boot jar and split it into layers.
# =============================================================================
FROM ${JDK_IMAGE} AS build
WORKDIR /workspace

ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.welcome=never -Dorg.gradle.vfs.watch=false"

# Build scripts and wrapper first: they change far less often than the sources.
COPY --chmod=0755 gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
# Only production sources: tests are executed by CI, never inside the image build.
COPY src/main/ src/main/

# The Gradle user home (wrapper distribution, dependency cache, build cache) lives
# in a BuildKit cache mount: it survives between builds without bloating any layer.
RUN --mount=type=cache,id=ms-ticket-gradle,target=/root/.gradle,sharing=locked \
    ./gradlew bootJar -x test --console=plain

# Explode the fat jar into Spring Boot layers (dependencies change rarely, the
# application layer on every commit) so registries and nodes reuse cached layers.
RUN java -Djarmode=tools -jar build/libs/ms-ticket.jar extract --layers --launcher --destination extracted \
    && for layer in dependencies spring-boot-loader snapshot-dependencies application; do \
         mkdir -p "extracted/${layer}"; \
       done

# =============================================================================
# Stage 2 - runtime: JRE only, non-root, immutable application files.
# =============================================================================
FROM ${JRE_IMAGE} AS runtime

ARG JRE_IMAGE
ARG APP_UID=10001
ARG APP_GID=10001
ARG VERSION=1.0.0
ARG REVISION=unknown
ARG CREATED=unknown

LABEL org.opencontainers.image.title="ms-ticket" \
      org.opencontainers.image.description="Reactive ticketing platform (Spring Boot WebFlux, DynamoDB, SQS)" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.vendor="ms-ticket" \
      org.opencontainers.image.base.name="${JRE_IMAGE}"

# Dedicated unprivileged system account without a login shell or home directory.
RUN groupadd --system --gid "${APP_GID}" app \
    && useradd --system --uid "${APP_UID}" --gid app --no-create-home --home-dir /nonexistent \
       --shell /usr/sbin/nologin app

# /tmp is the only path the application writes to (Netty extracts its native epoll
# transport there, the JVM keeps hsperfdata/temp files there). Sticky world-writable
# so UID 10001 can use it, and declared as a VOLUME so that with a read-only root
# filesystem (ECS readonlyRootFilesystem, compose read_only) /tmp is still a writable,
# exec-capable mount. Compose replaces it with a size-capped tmpfs at the same path.
# pebble (Ubuntu service manager, unused: ENTRYPOINT is java) ships a Go stdlib with fixable HIGH CVEs.
RUN rm -f /usr/bin/pebble && mkdir -p /tmp && chmod 1777 /tmp
VOLUME ["/tmp"]

WORKDIR /app

# Least volatile layer first. Files stay owned by root (read-only for the app user),
# so a compromised process cannot tamper with its own code.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# The JRE image ships neither curl nor wget: the probe uses bash's /dev/tcp instead.
COPY --chmod=0555 docker/app/healthcheck.sh /usr/local/bin/healthcheck

# - Container-aware heap sizing.
# - On OOM the JVM exits so the orchestrator restarts a clean task instead of
#   keeping a degraded one alive.
# - Explicit native access for Netty's epoll transport (JEP 472: Java 25 warns about
#   restricted methods today and will block them by default in a future release).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED"

# Numeric UID so Kubernetes/ECS "runAsNonRoot" style checks can verify it.
USER ${APP_UID}:${APP_GID}

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --start-interval=3s --retries=3 \
    CMD ["/usr/local/bin/healthcheck"]

# Exec form (no shell in between): SIGTERM reaches the JVM directly -> Spring graceful
# shutdown drains HTTP requests and in-flight SQS messages before the process exits.
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
