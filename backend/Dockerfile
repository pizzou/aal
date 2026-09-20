# Production backend image.
# Build with Maven, run as non-root, use JVM container sizing.

FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml .

RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src

RUN mvn -q clean package -DskipTests


FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --uid 10001 --create-home appuser

COPY --from=build /build/target/*.jar /app/app.jar

RUN chown -R appuser:appuser /app

USER 10001

ENV SPRING_PROFILES_ACTIVE=prod \
    APP_ENVIRONMENT=production \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]