FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the fat JAR produced by spring-boot-maven-plugin repackage goal.
# The JAR contains all dependencies, so no classpath setup needed.
COPY target/user-service-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
