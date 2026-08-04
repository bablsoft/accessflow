# syntax=docker/dockerfile:1.7
# Build context is the repository root (see release.yml / docker-compose) so the connector
# catalog at ./connectors can be bundled into the jar. Build with: docker build -f backend/Dockerfile .

FROM maven:3-eclipse-temurin-25-alpine AS build
WORKDIR /workspace
# Fail fast (minutes, not hours) when a remote repo is unreachable. Maven Resolver's request timeout
# defaults to 30 min, so an outage of an extra repo like build.shibboleth.net (which serves OpenSAML,
# not available on Maven Central) would otherwise hang the cold dependency resolution indefinitely.
# These bounds are generous for normal artifact downloads and only bite when a repo is actually down.
ENV MAVEN_RESOLVER_TIMEOUTS="-Daether.connector.connectTimeout=15000 -Daether.connector.requestTimeout=60000 -Daether.connector.http.retryHandler.count=1"
COPY backend/pom.xml ./
RUN mvn -B -q $MAVEN_RESOLVER_TIMEOUTS -DskipTests -Dmaven.test.skip=true dependency:go-offline
COPY backend/src src
COPY connectors /workspace/connectors
# Spotless and Checkstyle are bound to the `validate` phase, so they would otherwise run on every
# image build — including the three e2e stacks, the Terraform acceptance stack and the Actions
# smoke test. This is a packaging step for code CI has already gated in the `backend` job, and
# checkstyle.xml is not part of this build context, so skip both here.
RUN mvn -B $MAVEN_RESOLVER_TIMEOUTS -DskipTests -Dmaven.test.skip=true \
        -Dcheckstyle.skip=true -Dspotless.check.skip=true \
        -Dconnectors.dir=/workspace/connectors package && \
    mv target/*-exec.jar target/app.jar

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /workspace/target/app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
