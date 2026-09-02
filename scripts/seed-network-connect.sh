#!/usr/bin/env bash
# ============================================================
# 美研云 seed 联调栈：把共享中间件容器接入 seed 独立网络
# ------------------------------------------------------------
# 背景：seed 栈用独占 Docker 网络 meiyun-seed_seed-net（与正式栈 10-_meiyun 隔离，
#   防止 compose「服务名别名」在共享网络上撞名，导致正式栈网关把请求轮询路由到 seed 容器）。
#   但中间件（PG/Redis/ES/MinIO/RocketMQ）由 docker-compose.middleware.yml 管理，不在 seed compose 内。
#   `docker compose -f docker-compose.seed.yml down` 会删除自建 seed 网络，中间件接入关系随之丢失，
#   故每次 seed 栈 up 后需重跑本脚本（幂等，已接入会跳过）。
#
# 用法：bash scripts/seed-network-connect.sh
# ============================================================
set -euo pipefail

NET="${SEED_NET:-meiyun-seed_seed-net}"

# 网络不存在则提示先 up seed 栈
if ! docker network inspect "$NET" >/dev/null 2>&1; then
  echo "网络 $NET 不存在。请先：docker compose -f docker-compose.seed.yml up -d"
  exit 1
fi

for c in meiyun-pg meiyun-redis meiyun-es meiyun-minio meiyun-rocketmq; do
  if docker network inspect "$NET" --format '{{range .Containers}}{{.Name}} {{end}}' | grep -qw "$c"; then
    echo "  $c -> 已在 $NET，跳过"
  else
    docker network connect "$NET" "$c" && echo "  $c -> 已接入 $NET"
  fi
done

echo "✅ 中间件已接入 seed 网络。当前 $NET 成员："
docker network inspect "$NET" --format '{{range .Containers}}  {{.Name}}{{"\n"}}{{end}}' | sort
