# 产物模式: 本地已执行 mvn -DskipTests package, 产物在 target/ 下。
# 服务器无需 Maven/JDK, 仅 COPY jar 进运行镜像。
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app

# 本地构建产物, 必须存在 backend/target/store-profit-backend-*.jar
COPY target/store-profit-backend-*.jar /app/app.jar

EXPOSE 8080

USER app

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
