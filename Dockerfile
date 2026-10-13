# Compile in a full JDK/Maven image, then copy only the runnable artifact into
# the smaller runtime image.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Download dependencies before copying source so Docker can reuse this layer.
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q clean package -DskipTests

# The application runs as a non-root user in the production-style image.
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 meshrelay
COPY --from=build /workspace/target/meshrelay.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
