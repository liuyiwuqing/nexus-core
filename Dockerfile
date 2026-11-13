# syntax=docker/dockerfile:1
FROM node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS console
WORKDIR /source/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run check && npm run build

FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS package
WORKDIR /source
COPY .mvn/settings.xml .mvn/settings.xml
COPY pom.xml ./
COPY src/ src/
COPY --from=console /source/target/frontend/ target/frontend/
# Full integration tests run separately with isolated Docker dependencies.
RUN mvn -s .mvn/settings.xml -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy@sha256:dff6eb46527bd293a3c06bbbe9e64e8c9f91f01905b5abbdad31761c03059d2c
WORKDIR /app
COPY --from=package --chown=65534:65534 /source/target/nexus-core-*.jar /app/core.jar
USER 65534:65534
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/core.jar"]
