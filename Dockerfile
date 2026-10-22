FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp verify

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build --chown=app:app /workspace/target/LCC-hub-*.jar /app/application.jar
USER app
EXPOSE 8080 12021
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
