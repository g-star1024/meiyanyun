#!/bin/bash
# 美研云后端微服务统一启动脚本（macOS 版，无 setsid）
# 用法：
#   ./start-all-services.sh run     # 前台启动全部服务并 wait（配合后台任务使用，持稳）
#   ./start-all-services.sh status  # 查看端口状态
#   ./start-all-services.sh stop    # 按端口停止全部服务
BACKEND_DIR="/Users/huluobo/WorkBuddy/2026-08-15-23-51-02/meiyun-platform/backend"
LOG_DIR="/tmp/meiyun-logs"
mkdir -p "$LOG_DIR"

SERVICES=(
  "customer-service:8082"
  "txn-service:8083"
  "audit-service:8084"
  "store-service:8085"
  "org-service:8086"
  "finance-service:8087"
  "marketing-service:8088"
)

run_all() {
  pids=()
  for entry in "${SERVICES[@]}"; do
    svc="${entry%%:*}"; port="${entry##*:}"
    jar="$BACKEND_DIR/$svc/target/$svc-1.0.0-SNAPSHOT.jar"
    log="$LOG_DIR/$svc.log"
    if lsof -iTCP:$port -sTCP:LISTEN -P >/dev/null 2>&1; then
      echo "[SKIP] $svc :$port 已在监听"
      continue
    fi
    echo "[START] $svc :$port"
    java -jar "$jar" \
      --server.port=$port \
      --logging.level.root=INFO \
      > "$log" 2>&1 &
    pids+=($!)
  done
  echo ""
  echo "全部服务已发起启动（PID: ${pids[*]}），进入 wait 持稳..."
  # wait 阻塞，让本脚本作为后台任务持稳持有全部 java 子进程
  wait
}

status_all() {
  for entry in "${SERVICES[@]}"; do
    svc="${entry%%:*}"; port="${entry##*:}"
    if lsof -iTCP:$port -sTCP:LISTEN -P >/dev/null 2>&1; then
      pid=$(lsof -tiTCP:$port -sTCP:LISTEN 2>/dev/null | head -1)
      echo "[UP]   $svc :$port (PID $pid)"
    else
      echo "[DOWN] $svc :$port"
    fi
  done
}

stop_all() {
  for entry in "${SERVICES[@]}"; do
    svc="${entry%%:*}"; port="${entry##*:}"
    pids=$(lsof -tiTCP:$port -sTCP:LISTEN 2>/dev/null)
    if [ -n "$pids" ]; then
      echo "[STOP] $svc :$port (PID $pids)"
      kill $pids 2>/dev/null
    else
      echo "[--]   $svc :$port 未运行"
    fi
  done
  sleep 3
  # 强残留
  for entry in "${SERVICES[@]}"; do
    port="${entry##*:}"
    pids=$(lsof -tiTCP:$port -sTCP:LISTEN 2>/dev/null)
    [ -n "$pids" ] && kill -9 $pids 2>/dev/null
  done
  echo "已停止"
}

case "${1:-run}" in
  run)    run_all ;;
  status) status_all ;;
  stop)   stop_all ;;
  *) echo "用法: $0 [run|status|stop]" ;;
esac
