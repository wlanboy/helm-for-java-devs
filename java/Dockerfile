FROM docker.io/library/maven:3-eclipse-temurin-25 AS builder
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests compile spring-boot:process-aot package

FROM docker.io/library/eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Dspring.aot.enabled=true", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
