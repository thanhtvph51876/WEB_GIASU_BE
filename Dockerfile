#FROM maven:3.9.9-eclipse-temurin-21 AS build
#WORKDIR /workspace
#COPY pom.xml .
#COPY src ./src
#RUN mvn -q -DskipTests package
#
#FROM eclipse-temurin:21-jre
#WORKDIR /app
#COPY --from=build /workspace/target/tutor-platform-0.1.0.jar app.jar
#EXPOSE 8080
#ENTRYPOINT ["java", "-jar", "/app/app.jar"]
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]