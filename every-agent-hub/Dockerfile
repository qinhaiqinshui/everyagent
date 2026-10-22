# 构建上下文须为仓库根:hub 依赖 contract(reactor -am)
# TODO: pin @sha256:<digest>（联网后 docker manifest inspect 查询;流程见 docs/ops/image-pinning.md）
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

# TODO: pin @sha256:<digest>（联网后 docker manifest inspect 查询）
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/every-agent-hub/target/every-agent-hub-*-exec.jar app.jar
# 非 root 运行(M-14):hub 业务零状态,唯一落盘是 FILE 日志
# ${EVERYAGENT_HOME:-~appuser/.everyagent}/logs(compose 未给 hub 设 env → 回退家目录),
# --create-home 保证 logback 能自建该目录;jar 保持 root 属主只读即可。
RUN useradd --system --uid 1000 --create-home appuser
USER appuser
EXPOSE 6101
ENTRYPOINT ["java", "-jar", "app.jar"]
