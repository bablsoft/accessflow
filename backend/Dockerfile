# syntax=docker/dockerfile:1.7
# Build context is the repository root (see release.yml / docker-compose) so the connector
# catalog at ./connectors can be bundled into the jar. Build with: docker build -f backend/Dockerfile .

FROM maven:3-eclipse-temurin-25-alpine AS build
WORKDIR /workspace
COPY backend/pom.xml ./
RUN mvn -B -q -DskipTests -Dmaven.test.skip=true dependency:go-offline
COPY backend/src src
COPY connectors /workspace/connectors
RUN mvn -B -DskipTests -Dmaven.test.skip=true -Dconnectors.dir=/workspace/connectors package && \
    mv target/*-exec.jar target/app.jar

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /workspace/target/app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
