FROM gradle:9.2.1-jdk21 AS build
WORKDIR /src
COPY build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon -q || true
COPY src ./src
RUN gradle installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/build/install/gateway /app
EXPOSE 8080
ENTRYPOINT ["/app/bin/gateway"]
