# ==============================================================================
# PulseChat Multi-Stage Production Dockerfile
# ==============================================================================

# Stage 1: Build JAR with Maven & OpenJDK 21
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy Maven wrapper and POM for dependency pre-caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy application source code and compile production artifact
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Minimal Production Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install curl for container health check
RUN apk add --no-cache curl

# Create non-privileged service user
RUN addgroup -S pulsegroup && adduser -S pulseuser -G pulsegroup

# Copy executable jar from builder stage
COPY --from=builder /workspace/target/*.jar app.jar
RUN mkdir -p /app/data && chown -R pulseuser:pulsegroup /app

USER pulseuser

# Expose default HTTP/WebSocket port
EXPOSE 8080

# Configure JVM flags optimized for 512MB containerized environments (Render Free Tier)
ENV JAVA_OPTS="-XX:+UseContainerSupport -Xmx350m -Xss512k -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE="prod"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
