FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home app

WORKDIR /app
ARG SERVICE
# Build the executable jar with the service's Gradle wrapper before building this image.
COPY --chown=app:app ${SERVICE}/build/libs/*-SNAPSHOT.jar /app/app.jar
USER app
ENV SERVER_PORT=8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=5 \
    CMD curl --fail --silent --max-time 4 "http://127.0.0.1:${SERVER_PORT}/v3/api-docs" > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
