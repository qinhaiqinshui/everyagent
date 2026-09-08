# 构建上下文须为仓库根:hub 依赖 contract(reactor -am)
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY every-agent-contract/pom.xml every-agent-contract/
COPY every-agent-hub/pom.xml every-agent-hub/
# 父 pom 声明了 worker 模块,reactor 解析需要其 pom(仅 pom,不含源码)
COPY every-agent-worker/pom.xml every-agent-worker/
RUN mvn -B -pl every-agent-hub -am dependency:go-offline > /dev/null 2>&1 || true
COPY every-agent-contract every-agent-contract
COPY every-agent-hub every-agent-hub
RUN mvn -B -pl every-agent-hub -am package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/every-agent-hub/target/every-agent-hub-*-exec.jar app.jar
EXPOSE 9100
ENTRYPOINT ["java", "-jar", "app.jar"]
