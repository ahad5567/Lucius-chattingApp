   FROM maven:3.9-eclipse-temurin-17 AS build
   WORKDIR /app
   COPY server/pom.xml .
   RUN mvn -q dependency:go-offline
   COPY server/src ./src
   RUN mvn -q package -DskipTests

   FROM eclipse-temurin:17-jre
   WORKDIR /app
   COPY --from=build /app/target/lucius-server.jar .
   CMD ["java","-jar","lucius-server.jar"]
