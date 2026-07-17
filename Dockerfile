FROM node:20.19.5-bookworm-slim AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-8 AS backend-build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
RUN cd backend && mvn -q -DskipTests dependency:go-offline
COPY backend backend
COPY --from=frontend-build /workspace/frontend/dist /workspace/frontend-dist
RUN rm -rf backend/src/main/resources/static \
    && mkdir -p backend/src/main/resources/static \
    && cp -R /workspace/frontend-dist/. backend/src/main/resources/static/
RUN cd backend && mvn -q -DskipTests package

FROM eclipse-temurin:8-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && addgroup --system sqltuner \
    && adduser --system --ingroup sqltuner sqltuner
COPY --from=backend-build /workspace/backend/target/sql-tuning-assistant-0.1.0-SNAPSHOT.jar /app/sql-tuner.jar
USER sqltuner
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=4 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/api/health/ready >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-jar", "/app/sql-tuner.jar"]
