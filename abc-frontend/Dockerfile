# syntax=docker/dockerfile:1
# ============================================================
# 前端镜像（Vue3 + Vite6）：node 构建静态产物 → nginx 托管
# 构建上下文 = 项目根目录（需要 docker/nginx/frontend.conf 一起进上下文）
#   docker build -f abc-frontend/Dockerfile -t abc-frontend:1.0.0 .
# 注意 1：只跑 npx vite build，不跑 package.json 里的 "build"（那是 vue-tsc && vite build）。
#         类型检查属于本地/CI 环节，若 vue-tsc 报错会连带镜像构建失败，与"能否部署"无关。
# 注意 2：前端 axios baseURL 为空串（abc-frontend/src/api/index.ts:54），
#         所有请求走同源相对路径，由 nginx 反向代理到网关，因此产物不需要写死后端地址。
# ============================================================
FROM node:20-alpine AS build
WORKDIR /app
COPY abc-frontend/package.json abc-frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; else npm install --no-audit --no-fund; fi
COPY abc-frontend/ ./
RUN npx vite build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY docker/nginx/frontend.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
# nginx 官方镜像自带 /docker-entrypoint.d 启动脚本，默认 CMD 即可
