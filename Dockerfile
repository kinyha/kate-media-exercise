# syntax=docker/dockerfile:1
FROM eclipse-temurin:22-jdk-jammy AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :server:installDist :client:installDist

FROM eclipse-temurin:22-jre-jammy AS runtime
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/server/build/install/server ./server
COPY --from=build --chown=app:app /workspace/client/build/install/client ./client
USER 10001:10001
EXPOSE 8443
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["/app/server/bin/server"]
