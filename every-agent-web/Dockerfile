# 前端静态构建(上下文:仓库根)。
# 构建仅需 every-agent-web:TS 客户端 SDK 已并入其 src/sdk,@every-agent/client 别名指向该目录。
# TODO: pin @sha256:<digest>（联网后 docker manifest inspect 查询;流程见 docs/ops/image-pinning.md）
FROM node:22-alpine AS build
WORKDIR /web
COPY every-agent-web/package.json every-agent-web/package-lock.json ./
RUN npm ci
COPY every-agent-web/ ./
RUN npm run build

# TODO: pin @sha256:<digest>（联网后 docker manifest inspect 查询）
FROM nginx:alpine
COPY --from=build /web/dist /usr/share/nginx/html
# 非 root 运行(M-14):官方镜像默认 root master 监听特权端口 80;改用内置 nginx 用户 + 8080:
# 1) sed 改默认站点两条 listen(IPv4+IPv6 都要改,残留 :80 时非 root 绑定会失败);
# 2) 去掉 nginx.conf 的 user 指令(非 root 下无意义,仅产生启动告警);
# 3) chown 运行时可写目录:/var/cache/nginx(temp)与 /run(pid);日志已软链 /dev/stdout|stderr。
# 宿主端口不变(docker-compose.yml 5174:8080),容器侧统一 8080。
RUN sed -i 's/listen[[:space:]]\{1,\}80;/listen 8080;/' /etc/nginx/conf.d/default.conf \
    && sed -i 's/listen[[:space:]]\{1,\}\[::\]:80;/listen [::]:8080;/' /etc/nginx/conf.d/default.conf \
    && sed -i '/^[[:space:]]*user[[:space:]]/d' /etc/nginx/nginx.conf \
    && chown -R nginx:nginx /usr/share/nginx/html /var/cache/nginx /run
USER nginx
EXPOSE 8080
