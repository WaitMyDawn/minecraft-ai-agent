FROM eclipse-temurin:21-jre
WORKDIR /app

EXPOSE 8080
ENTRYPOINT ["java", "-Xmx3g", "-jar", "app.jar"]