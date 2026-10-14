FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src src
RUN mvn package -DskipTests -B
FROM eclipse-temurin:21.0.8_9-jre-alpine
RUN addgroup -S atlas && adduser -S atlas -G atlas
WORKDIR /app
COPY --from=build /build/target/atlas-api-0.1.0.jar app.jar
USER atlas
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
