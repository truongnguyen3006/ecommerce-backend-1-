# syntax=docker/dockerfile:1.7
FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS maven-tooling
FROM eclipse-temurin:24.0.2_12-jdk-noble@sha256:dacac8e9a0df0d2bd24e702b4431132875c249930b70555ebd7ca285b5bee684 AS build
COPY --from=maven-tooling /usr/share/maven /usr/share/maven
ENV PATH="/usr/share/maven/bin:${PATH}"
WORKDIR /src
ARG SERVICE
COPY . .
# CI runs clean verify first. Package only the selected reactor and its shared modules.
RUN --mount=type=cache,target=/root/.m2     case "$SERVICE" in api-gateway|discovery-server|product-service|inventory-service|cart-service|order-service|payment-service|user-service|notification-service) ;; *) exit 2 ;; esac     && mvn -B -ntp -pl "$SERVICE" -am -DskipTests package     && cp "$SERVICE/target/$SERVICE-1.0-SNAPSHOT.jar" /app.jar

FROM eclipse-temurin:24.0.2_12-jre-noble@sha256:b416d02335e702b0403ff280de9475a3348e29382285969c9d4e17862ce632e7
RUN apt-get update && apt-get install -y --no-install-recommends curl     && rm -rf /var/lib/apt/lists/*     && groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app     && mkdir -p /app/data/kafka-streams && chown -R app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /app.jar /app/app.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 9091
HEALTHCHECK --interval=20s --timeout=12s --start-period=120s --retries=5     CMD curl --fail --silent --max-time 10 http://127.0.0.1:9091/actuator/health/readiness > /dev/null || exit 1
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
