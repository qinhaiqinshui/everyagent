# 容器镜像 digest pin 流程与更新纪律

> 对应安全审计 **M-14**:基础镜像此前全部使用可变 tag(`maven:3.9-eclipse-temurin-25` /
> `eclipse-temurin:25-jre` / `nginx:alpine` / `node:22-alpine`),存在供应链替换与构建漂移风险。
> 离线开发环境无法查询真实 sha256,为**不编造 digest**,先以 `# TODO: pin @sha256:<digest>`
> 注释占位,待联网环境按本文流程逐个 pin。

## 1. 待 pin 清单(共 6 处)

| 位置 | 引用 |
| --- | --- |
| `every-agent-hub/Dockerfile` | `maven:3.9-eclipse-temurin-25`(构建阶段)、`eclipse-temurin:25-jre`(运行阶段) |
| `every-agent-worker/Dockerfile` | 同上 |
| `every-agent-web/Dockerfile` | `node:22-alpine`(构建阶段)、`nginx:alpine`(运行阶段) |
| `deploy.py` | `HUB_IMAGE` / `WORKER_IMAGE` / `WEB_IMAGE`(docker 旧模式直接拉官方镜像) |

每处引用上方已有 TODO 注释,pin 落地后删除对应注释。

## 2. pin 步骤(需联网)

1. 查询**多架构 manifest 列表**的 digest(不是某一平台的 digest):
   ```bash
   docker manifest inspect eclipse-temurin:25-jre --verbose   # 或
   docker buildx imagetools inspect eclipse-temurin:25-jre
   ```
   取根 Descriptor 的 `sha256:...`。`docker inspect --format '{{.Image}}'` 拿到的是本机单平台
   镜像 digest,跨机器不可复现,**不要用**。
2. Dockerfile 改为 tag+digest 双写(保留 tag 便于人读):
   ```dockerfile
   FROM eclipse-temurin:25-jre@sha256:<digest>
   ```
3. `deploy.py` 三个常量同步改为 `"eclipse-temurin:25-jre@sha256:<digest>"` 字符串。
4. 全量构建 + 冒烟(见 §4)确认无回归再提交,提交信息注明变更原因,
   如 `chore: 更新基础镜像 digest(eclipse-temurin 25-jre → CVE-XXXX 补丁)`。

## 3. 更新纪律

- digest 只在明确的更新窗口变更:安全补丁(CVE)、月度例行、或上游 tag 指向变化;
  日常需求不得顺手改镜像。
- 每次更新走独立 PR:新旧 digest、变更原因(Release Notes / CVE 链接)、CI 构建结果缺一不可。
- Dependabot:仓库已接入 maven / npm / github-actions 生态([.github/dependabot.yml](../../.github/dependabot.yml));
  建议再补 docker 生态条目,由其自动提 `FROM` digest 升级 PR:
  ```yaml
    - package-ecosystem: docker
      directory: /
      schedule:
        interval: weekly
      commit-message:
        prefix: "chore(deps-docker)"
  ```
- hub 与 worker 共用 `eclipse-temurin:25-jre`,必须**同批更新**,避免两服务运行时基镜像不一致;
  web 的 `nginx:alpine` / `node:22-alpine` 各自独立。

## 4. 需联网 / 有 docker daemon 环境的人工验证清单

- [ ] `docker build`:hub / worker / web 三镜像各自构建通过(重点:web 的 sed 改写无报错);
- [ ] 非 root 生效:`docker run --rm --entrypoint id <hub|worker镜像> -u` 输出 `1000`,
      `docker run --rm --entrypoint id <web镜像> -u` 输出 nginx 用户 uid(非 0);
- [ ] `docker run --rm --entrypoint nginx <web镜像> -T 2>&1 | grep listen`:
      仅见 `8080`(IPv4 与 IPv6 两行),无残留 `80`;
- [ ] `docker compose up --build` 冒烟:浏览器打开 `http://localhost:5174` 页面正常,
      任务下发 / 文件树 / git 遥控链路可用;
- [ ] hub / worker 容器内 FILE 日志落盘正常(`logs/hub.log`、`logs/worker.log` 可写);
      worker 命名卷 `/everyagent` 属主为 appuser(uid 1000),工作区文件操作正常;
- [ ] pin 落地后 `docker image inspect` 核对镜像 digest 与 manifest 查询值一致。
