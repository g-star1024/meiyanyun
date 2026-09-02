# 前端生产镜像：nginx 托管 vite 构建产物，并把 /api 反代到网关。
# 多阶段：先 node 构建，再拷 dist 进 nginx。
# 依赖用 pnpm（项目无 package-lock.json，npm ci 不可用）。
FROM node:18-alpine AS build
WORKDIR /app
RUN npm install -g pnpm@9 --no-audit --no-fund
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
