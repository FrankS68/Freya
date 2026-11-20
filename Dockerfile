FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /app/data
COPY --from=build /build/target/source-scout-0.1.0.jar /app/source-scout.jar
ENV SCOUT_STORE_PATH=/app/data/processed-items.json
ENTRYPOINT ["java", "-Xmx200m", "-jar", "/app/source-scout.jar"]
