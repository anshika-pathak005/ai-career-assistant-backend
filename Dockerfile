# Build stage
FROM maven:3.9.8-amazoncorretto-21 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM amazoncorretto:21-alpine3.20
WORKDIR /app

# Install curl for health checks
RUN apk add --no-cache curl

# Copy jar from builder
COPY --from=builder /app/target/career-assistant-*.jar app.jar

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:${PORT:-8080}/actuator/health || exit 1

# Runtime configuration
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV SPRING_PROFILES_ACTIVE=prod

# Render provides PORT environment variable
EXPOSE ${PORT:-8080}

# Start the application
ENTRYPOINT ["java", "-jar", "app.jar"]