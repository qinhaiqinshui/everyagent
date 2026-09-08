# 前端静态构建(上下文:仓库根)。
# 构建仅需 every-agent-web:TS 客户端 SDK 已并入其 src/sdk,@every-agent/client 别名指向该目录。
FROM node:22-alpine AS build
WORKDIR /web
COPY every-agent-web/package.json every-agent-web/package-lock.json ./
RUN npm ci
COPY every-agent-web/ ./
RUN npm run build

FROM nginx:alpine
COPY --from=build /web/dist /usr/share/nginx/html
EXPOSE 80
