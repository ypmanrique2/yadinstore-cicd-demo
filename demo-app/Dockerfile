# ============================================================
# YadinStore — CI/CD Demo (free tier, sin secrets)
# ============================================================
# Construye la imagen del demo-app. El pipeline (Jenkins o
# GitHub Actions) la usa solo para VALIDAR que el Dockerfile
# compila — igual que en Render. No se pushea a ningun registry.
#
# Build local:
#   docker build -t yadinstore-cicd-demo:local demo-app
# ============================================================

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/cicd-demo-app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]