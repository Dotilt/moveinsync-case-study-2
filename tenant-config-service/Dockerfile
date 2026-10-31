# -------------------------------------------------------------
# Stage 1: Build JAR using official Maven + Temurin 17 image
# Self-contained: No local Maven or mvnw installation needed!
# -------------------------------------------------------------
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /workspace

# Copy root POM first for dependency caching
COPY pom.xml .
COPY config-core/pom.xml config-core/
COPY config-client/pom.xml config-client/
COPY config-server/pom.xml config-server/
COPY config-benchmarks-and-tests/pom.xml config-benchmarks-and-tests/

# Copy all source modules
COPY config-core config-core
COPY config-client config-client
COPY config-server config-server
COPY config-benchmarks-and-tests config-benchmarks-and-tests

# Compile, build and package executable spring-boot JAR
RUN mvn clean package -DskipTests -B

# -------------------------------------------------------------
# Stage 2: Runtime image using lightweight eclipse-temurin:17-jre
# -------------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy packaged fat JAR from builder stage
COPY --from=builder /workspace/config-server/target/config-server-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseG1GC", "-Xms256m", "-Xmx512m", "-jar", "app.jar"]
