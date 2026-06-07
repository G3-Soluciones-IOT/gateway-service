FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/*.jar /app/app.jar
ENV SPRING_PROFILES_ACTIVE=default
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
