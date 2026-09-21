FROM eclipse-temurin:17-jdk-alpine-3.24 AS build

WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine-3.24

WORKDIR /app
COPY --from=build --chown=1000:1000 /app/build/libs/*.jar app.jar
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
