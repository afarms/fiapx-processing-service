# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.12_8-jdk-alpine-3.24 AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24 AS runtime
WORKDIR /app
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app \
    && mkdir -p /app/.local/processing && chown -R 10001:10001 /app/.local
COPY --from=build --chown=10001:10001 /workspace/target/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT:-8082}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
