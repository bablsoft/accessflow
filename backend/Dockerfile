# syntax=docker/dockerfile:1.7

FROM maven:3-eclipse-temurin-25-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package && \
    mv target/*.jar target/app.jar

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /workspace/target/app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
