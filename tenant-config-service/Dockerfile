FROM openjdk:17-slim AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw mvnw
COPY pom.xml pom.xml
COPY config-core config-core
COPY config-client config-client
COPY config-server config-server
COPY config-benchmarks-and-tests config-benchmarks-and-tests

# Compile and package executable jar
RUN chmod +x ./mvnw || true

FROM openjdk:17-slim
WORKDIR /app
COPY config-server/target/config-server-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseG1GC", "-Xms256m", "-Xmx512m", "-jar", "app.jar"]
