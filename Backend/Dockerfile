FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build --chown=1000:1000 /app/build/libs/*.jar app.jar
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
