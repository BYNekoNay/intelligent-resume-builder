FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY server/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY server/src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 resume
WORKDIR /app
COPY --from=build /workspace/target/intelligent-resume-server-*.jar app.jar
USER resume
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
