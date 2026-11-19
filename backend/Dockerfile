ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre
FROM ${MAVEN_IMAGE} AS build
WORKDIR /app
COPY pom.xml .
# Maven 预取失败不阻断构建，正式打包阶段会继续下载并重试。
RUN mvn -B -DskipTests -Dmaven.wagon.http.retryHandler.count=5 dependency:go-offline || echo "Maven 依赖预取失败，继续执行正式打包"
COPY src ./src
# Maven Central 偶发超时时重试整个打包步骤。
RUN for attempt in 1 2 3; do \
      mvn -B -DskipTests -Dmaven.wagon.http.retryHandler.count=5 package && exit 0; \
      echo "Maven 正式打包第 $attempt 次失败，10 秒后重试" >&2; \
      sleep 10; \
    done; \
    exit 1

FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 wenjin && mkdir -p /app/.local && chown wenjin /app/.local
COPY --from=build /app/target/wenjin-backend-0.1.0-exec.jar app.jar
USER wenjin
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 CMD curl --fail --silent http://localhost:8080/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar", "--spring.profiles.active=docker"]
