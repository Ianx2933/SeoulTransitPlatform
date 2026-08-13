# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy the POM first so dependency resolution is cached separately from source changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Runtime stage
# (실행 스테이지)
FROM eclipse-temurin:21-jre

WORKDIR /app

# Run as a non-root user.
RUN useradd --create-home --shell /usr/sbin/nologin appuser
USER appuser

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Configuration is supplied at runtime through environment variables; see
# services/api-server/src/main/resources/application.yaml for the full list.
# Nothing is baked into the image.
ENTRYPOINT ["java", "-jar", "app.jar"]
