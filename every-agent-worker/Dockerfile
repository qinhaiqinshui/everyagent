# 构建上下文须为仓库根:worker 依赖 contract,测试编译依赖 hub(reactor -am 一并构建)
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY every-agent-contract/pom.xml every-agent-contract/
COPY every-agent-hub/pom.xml every-agent-hub/
COPY every-agent-worker/pom.xml every-agent-worker/
RUN mvn -B -pl every-agent-worker -am dependency:go-offline > /dev/null 2>&1 || true
COPY every-agent-contract every-agent-contract
COPY every-agent-hub every-agent-hub
COPY every-agent-worker every-agent-worker
RUN mvn -B -pl every-agent-worker -am package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/every-agent-worker/target/every-agent-worker-*.jar app.jar
# 9200 为本地管理端口,compose 不对外发布(架构 §2.2)
EXPOSE 9200
ENTRYPOINT ["java", "-jar", "app.jar"]
