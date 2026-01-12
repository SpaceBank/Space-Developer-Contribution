FROM openjdk:17-jdk-slim

WORKDIR /app

# Copy gradle files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Copy source code
COPY src src

# Make gradlew executable
RUN chmod +x gradlew

# Build the application
RUN ./gradlew build -x test --no-daemon

# Expose port
EXPOSE 8081

# Run the application
ENTRYPOINT ["java", "-jar", "build/libs/GitDeveloperContribution-0.0.1-SNAPSHOT.jar", "--server.port=8081"]

