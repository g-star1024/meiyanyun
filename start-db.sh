#!/bin/bash
# 启动 PostgreSQL 数据库容器

CONTAINER_NAME="meiyun-postgres"
IMAGE="postgres:15"
PORT=5433
DB_NAME="meiyun_core"
DB_USER="meiyun"
DB_PASS="meiyun123"

echo "🚀 启动 PostgreSQL 容器..."

# 检查容器是否存在
if docker ps -a | grep -q "$CONTAINER_NAME"; then
    echo "容器已存在，启动中..."
    docker start "$CONTAINER_NAME"
else
    echo "创建新容器..."
    docker run -d \
        --name "$CONTAINER_NAME" \
        -e POSTGRES_DB="$DB_NAME" \
        -e POSTGRES_USER="$DB_USER" \
        -e POSTGRES_PASSWORD="$DB_PASS" \
        -p ${PORT}:5432 \
        -v meiyun-postgres-data:/var/lib/postgresql/data \
        "$IMAGE"
fi

echo "等待数据库就绪..."
sleep 3

# 检查状态
if docker ps | grep -q "$CONTAINER_NAME"; then
    echo "✅ PostgreSQL 已启动"
    echo "   连接信息: localhost:$PORT/$DB_NAME"
    echo "   用户: $DB_USER / 密码: $DB_PASS"
else
    echo "❌ 启动失败"
    docker logs "$CONTAINER_NAME"
    exit 1
fi
