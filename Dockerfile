## ---- Build Stage ----
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY src src
RUN chmod +x gradlew && ./gradlew build -x test --no-daemon

## ---- Runtime Stage ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends git curl && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/build/libs/GitDeveloperContribution-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD curl -sf http://localhost:8081/ || exit 1
ENTRYPOINT ["java", "-Xms256m", "-Xmx1024m", "-jar", "app.jar", "--server.port=8081"]

