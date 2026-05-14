FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY service-discovery/pom.xml service-discovery/
COPY api-gateway/pom.xml api-gateway/
COPY user-service/pom.xml user-service/
COPY account-service/pom.xml account-service/
COPY transaction-service/pom.xml transaction-service/
COPY notification-service/pom.xml notification-service/
COPY auth-service/pom.xml auth-service/
COPY audit-service/pom.xml audit-service/
COPY auth-service/src auth-service/src
RUN mvn -pl auth-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/auth-service/target/auth-service-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
