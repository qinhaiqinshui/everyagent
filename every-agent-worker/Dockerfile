# 构建上下文须为仓库根:worker 依赖 contract,测试编译依赖 hub(reactor -am 一并构建)
# TODO: pin @sha256:<digest>（联网后 docker manifest inspect 查询;流程见 docs/ops/image-pinning.md）
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

# TODO: pin @sha256:<digest>（联网后 docker manifest inspect 查询）
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/every-agent-worker/target/every-agent-worker-*.jar app.jar
# 非 root 运行(M-14):worker 数据/日志均落 EVERYAGENT_HOME——compose 与 deploy.py docker 模式
# 都把命名卷挂到 /everyagent 并注入该 env,镜像内预建并归 appuser(命名卷首次挂载继承镜像属主);
# 未注入 env 时回退 ~appuser/.everyagent,--create-home 保证可写。jar 保持 root 属主只读即可。
RUN useradd --system --uid 1000 --create-home appuser \
    && mkdir -p /everyagent \
    && chown appuser:appuser /everyagent
USER appuser
# 6102 为本地管理端口,compose 不对外发布(架构 §2.2)
EXPOSE 6102
ENTRYPOINT ["java", "-jar", "app.jar"]
