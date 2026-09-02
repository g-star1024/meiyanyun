#!/usr/bin/env bash
# 美研云前端+网关一键启动（幂等：已在跑的不重复启动）
# 用法：./dev-up.sh          启动并探活
#       ./dev-up.sh stop     停止
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
FE_DIR="$ROOT/frontend"
GW_BIN="$ROOT/gateway/meiyun-gateway"
PID_DIR="$ROOT/.run"
mkdir -p "$PID_DIR"
FE_PID="$PID_DIR/frontend.pid"
GW_PID="$PID_DIR/gateway.pid"
FE_LOG=/tmp/frontend.log
GW_LOG=/tmp/gateway.log

alive() { kill -0 "$1" 2>/dev/null; }
port_listen() { lsof -iTCP:"$1" -sTCP:LISTEN -n -P 2>/dev/null | grep -q LISTEN; }

stop() {
  for f in "$FE_PID" "$GW_PID"; do
    [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null && rm -f "$f"
  done
  # 兜底：按端口杀
  lsof -tiTCP:5173 -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null
  lsof -tiTCP:8443 -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null
  echo "已停止前端(5173)与网关(8443)"
}

if [ "${1:-}" = "stop" ]; then stop; exit 0; fi

# ---- 网关 ----
if port_listen 8443; then
  echo "✓ 网关 8443 已在运行"
else
  ( nohup "$GW_BIN" > "$GW_LOG" 2>&1 & echo $! > "$GW_PID" )
  echo "→ 网关启动中 (pid $(cat "$GW_PID"))"
fi

# ---- 前端 ----
if port_listen 5173; then
  echo "✓ 前端 5173 已在运行"
else
  ( cd "$FE_DIR" && nohup npm run dev > "$FE_LOG" 2>&1 & echo $! > "$FE_PID" )
  echo "→ 前端启动中 (pid $(cat "$FE_PID"))"
fi

# ---- 探活 ----
echo "等待服务就绪..."
for i in $(seq 1 15); do
  fe=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" http://127.0.0.1:5173/ --max-time 2 2>/dev/null)
  gw=$(curl -sk --noproxy '*' -o /dev/null -w "%{http_code}" https://127.0.0.1:8443/api/stores --max-time 2 2>/dev/null)
  if [ "$fe" = "200" ] && [ "$gw" = "200" ]; then
    echo ""
    echo "✅ 全部就绪"
    echo "   前端: http://localhost:5173  ($fe)"
    echo "   网关: https://127.0.0.1:8443 ($gw)"
    exit 0
  fi
  printf "."
  sleep 2
done
echo ""
echo "⚠️  启动超时，请查看日志："
echo "   前端: tail -f $FE_LOG"
echo "   网关: tail -f $GW_LOG"
exit 1
