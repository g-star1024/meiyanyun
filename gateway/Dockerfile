# 国密网关运行镜像
# 复用宿主交叉编译好的 linux/amd64 二进制（macOS 二进制无法在 Linux 容器内运行）。
# 构建前需在宿主执行：
#   cd gateway && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o meiyun-gateway-linux .
FROM alpine:3.20

RUN apk add --no-cache ca-certificates libgcc

WORKDIR /app

COPY gateway/meiyun-gateway-linux /app/meiyun-gateway
RUN chmod +x /app/meiyun-gateway

EXPOSE 8443

ENTRYPOINT ["/app/meiyun-gateway"]
