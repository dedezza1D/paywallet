FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Numeric user, so Kubernetes can enforce runAsNonRoot.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
COPY --from=build /app/target/paywallet-*.jar app.jar
USER 10001
# Heap sized from the container memory limit; an OutOfMemoryError restarts the pod instead of leaving it half alive.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
