#!/usr/bin/env bash
# 美研云双栈一键部署脚本（固化版）
#
# 解决的问题：双栈（正式 prod + 种子 seed）部署靠手工 docker cp + restart，
# 曾两次漏部署（seed 前端旧包、seed org-service 旧 jar 缺类致端点 500）。
# 本脚本：构建 → 部署 → 版本指纹校验 → 健康探活 → 网关验活，一条龙且失败即停。
#
# 用法：
#   scripts/deploy.sh <stack> <component> [--skip-build]
#
#   stack：      prod | seed
#   component：  frontend              前端（pnpm build → cp dist → 校验 bundle hash）
#                <service>             后端服务：customer|txn|audit|store|org|finance|marketing
#                backend               全部 7 个后端服务（逐个构建部署校验）
#                all                   frontend + backend
#   --skip-build：不重新构建，直接把当前本地产物部署进容器（用于仅漏部署的补救）
#
# 示例：
#   scripts/deploy.sh seed org-service        # 重建 org-service 并部署到 seed 栈
#   scripts/deploy.sh prod frontend           # 构建前端并部署到正式栈
#   scripts/deploy.sh seed all                # seed 栈全量部署
#   scripts/deploy.sh seed org-service --skip-build   # 只把现有 jar 补进 seed 容器
set -u
set -o pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_DIR="$ROOT/backend"
FE_DIR="$ROOT/frontend"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✓${NC} $*"; }
info() { echo -e "${CYAN}→${NC} $*"; }
warn() { echo -e "${YELLOW}!${NC} $*"; }
die()  { echo -e "${RED}✗ $*${NC}" >&2; exit 1; }

STACK="${1:-}"; COMPONENT="${2:-}"; SKIP_BUILD=""
[ "${3:-}" = "--skip-build" ] && SKIP_BUILD=1
[ "$STACK" = "prod" ] || [ "$STACK" = "seed" ] || die "第一个参数必须是 prod 或 seed（当前：'$STACK'）"
[ -n "$COMPONENT" ] || die "缺少 component 参数（frontend / <service> / backend / all）"

# 服务 → 宿主端口（正式/种子）
port_of() {
  local svc="$1"
  if [ "$STACK" = "prod" ]; then
    case "$svc" in
      customer) echo 8082;; txn) echo 8083;; audit) echo 8084;; store) echo 8085;;
      org) echo 8086;; finance) echo 8087;; marketing) echo 8088;;
    esac
  else
    case "$svc" in
      customer) echo 18082;; txn) echo 18083;; audit) echo 18084;; store) echo 18085;;
      org) echo 18086;; finance) echo 18087;; marketing) echo 18088;;
    esac
  fi
}
# 网关验活用「确定存在的 GET 端点」。
# 注意：不能用服务根路径（如 /api/org）——无根映射会抛 NoResourceFoundException，
# 被 GlobalExceptionHandler 兜成 500，与「旧 jar 缺类」的 500 无法区分。
probe_path_of() {
  case "$1" in
    customer) echo "/api/customer/member-levels";;
    txn) echo "/api/txn/refund";;
    audit) echo "/api/audit/verify";;
    store) echo "/api/stores/name-map";;
    org) echo "/api/org/roles";;
    finance) echo "/api/finance/accounts";;
    marketing) echo "/api/marketing/config";;
  esac
}
container_of() {
  local kind="$1" svc="${2:-}"
  local name
  if [ "$STACK" = "prod" ]; then
    name="meiyun-frontend"; [ "$kind" != "fe" ] && name="meiyun-${svc}-service"
  else
    name="meiyun-seed-frontend"; [ "$kind" != "fe" ] && name="meiyun-seed-${svc}-service"
  fi
  # docker compose 可能给容器名加项目哈希前缀（如 b4aaeb2bf0fd_meiyun-seed-store-service）；
  # 精确名不在运行时，按「服务名结尾」解析实际容器名（精确名或 _<服务名> 结尾均可）。
  if ! docker inspect -f '{{.State.Running}}' "$name" >/dev/null 2>&1; then
    local resolved
    resolved="$(docker ps --format '{{.Names}}' | grep -E "(^|_)${name}\$" | head -1)"
    [ -n "$resolved" ] && name="$resolved"
  fi
  echo "$name"
}
gw_port() { [ "$STACK" = "prod" ] && echo 8443 || echo 18443; }
fe_port() { [ "$STACK" = "prod" ] && echo 8080 || echo 18080; }

container_running() { docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -q true; }

# ---------- 前端 ----------
build_frontend() {
  info "前端构建：vue-tsc 类型检查 + vite build"
  ( cd "$FE_DIR" && ./node_modules/.bin/vue-tsc --noEmit -p tsconfig.json ) || die "前端类型检查失败"
  ( cd "$FE_DIR" && pnpm build ) || die "前端构建失败"
  [ -f "$FE_DIR/dist/index.html" ] || die "dist/index.html 不存在，构建异常"
  ok "前端构建完成：$(ls "$FE_DIR/dist/assets" | grep -c 'index-.*\.js$') 个入口 bundle"
}

deploy_frontend() {
  local c; c="$(container_of fe)"
  container_running "$c" || die "容器 $c 未运行"
  info "部署前端 → $c"
  docker cp "$FE_DIR/dist/." "$c:/usr/share/nginx/html/" || die "前端 cp 失败"

  local local_hash remote_hash
  local_hash=$(grep -o 'index-[A-Za-z0-9_-]*\.js' "$FE_DIR/dist/index.html" | head -1)
  remote_hash=$(docker exec "$c" sh -c "grep -o 'index-[A-Za-z0-9_-]*\.js' /usr/share/nginx/html/index.html | head -1")
  [ -n "$local_hash" ] || die "本地 index.html 未解析到 bundle hash"
  [ "$local_hash" = "$remote_hash" ] || die "版本指纹不一致：本地=$local_hash 容器=$remote_hash（部署未生效）"
  ok "bundle 指纹一致：$local_hash"

  local code
  for i in $(seq 1 10); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$(fe_port)/" --max-time 3)
    [ "$code" = "200" ] && break; sleep 1
  done
  [ "$code" = "200" ] || die "前端探活失败（HTTP $code）"
  ok "前端 $c 探活 200（http://127.0.0.1:$(fe_port)/）"
}

# ---------- 后端 ----------
build_backend() {
  local svc="$1"
  info "构建后端 $svc-service（Maven package，-am 连带构建共享模块如 meiyun-security）"
  mvn -q -f "$BACKEND_DIR/pom.xml" -pl "$svc-service" -am package -DskipTests || die "$svc-service 构建失败"
  local jar="$BACKEND_DIR/$svc-service/target/$svc-service-1.0.0-SNAPSHOT.jar"
  [ -f "$jar" ] || die "未找到 $jar"
  local size; size=$(stat -f%z "$jar" 2>/dev/null || stat -c%s "$jar")
  [ "$size" -gt 20000000 ] || die "jar 仅 $size 字节（<20MB），疑似瘦 jar，拒绝部署"
  ok "$svc-service fat jar 就绪（$((size/1024/1024)) MB）"
}

deploy_backend() {
  local svc="$1"
  local c; c="$(container_of be "$svc")"
  container_running "$c" || die "容器 $c 未运行"
  local jar="$BACKEND_DIR/$svc-service/target/$svc-service-1.0.0-SNAPSHOT.jar"
  [ -f "$jar" ] || die "本地 jar 不存在：$jar（先去掉 --skip-build 构建）"

  info "部署 $svc-service → $c"
  docker cp "$jar" "$c:/app/app.jar" || die "jar cp 失败"

  local local_md5 remote_md5
  local_md5=$(md5 -q "$jar" 2>/dev/null || md5sum "$jar" | awk '{print $1}')
  remote_md5=$(docker exec "$c" md5sum /app/app.jar | awk '{print $1}')
  [ "$local_md5" = "$remote_md5" ] || die "版本指纹不一致：本地=$local_md5 容器=$remote_md5（cp 未生效）"
  ok "jar 指纹一致：${local_md5:0:12}…"

  docker restart "$c" >/dev/null || die "重启 $c 失败"
  info "等待 $c 启动（Spring Boot 约 20-30s）…"

  local port; port="$(port_of "$svc")"
  local probe; probe="$(probe_path_of "$svc")"
  # 直连后端端口探活用去掉 /api 前缀的路径（后端 Controller 带 /api，直连同样带 /api）
  local up=0 code
  for i in $(seq 1 45); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port$probe" --max-time 2 2>/dev/null)
    if [ -n "$code" ] && [ "$code" != "000" ] && [ "$code" != "502" ] && [ "$code" != "503" ] && [ "$code" != "500" ]; then up=1; break; fi
    sleep 2
  done
  [ "$up" = "1" ] || die "$c 启动超时（45 轮询未就绪，最后 HTTP $code），查 docker logs $c"
  ok "$c 端口 $port 已就绪（$probe → HTTP $code）"

  # 经网关验活：401=端点存在且安全拦截（正常）；200=放行；500=旧 jar 缺类/异常
  local gw; gw="$(gw_port)"
  sleep 2
  local gwcode
  gwcode=$(curl -sk -o /dev/null -w '%{http_code}' "https://127.0.0.1:$gw$probe" --max-time 5)
  if [ "$gwcode" = "500" ]; then
    die "经网关访问 $probe 返回 500——可能仍是旧 jar 或缺类，查 docker logs $c"
  fi
  ok "经网关验活 $probe → HTTP $gwcode（401/200 均为正常）"
}

SERVICES="customer txn audit store org finance marketing"

case "$COMPONENT" in
  frontend)
    [ -z "$SKIP_BUILD" ] && build_frontend
    deploy_frontend
    ;;
  backend)
    for s in $SERVICES; do
      echo ""
      [ -z "$SKIP_BUILD" ] && build_backend "$s"
      deploy_backend "$s"
    done
    ;;
  all)
    [ -z "$SKIP_BUILD" ] && build_frontend
    deploy_frontend
    for s in $SERVICES; do
      echo ""
      [ -z "$SKIP_BUILD" ] && build_backend "$s"
      deploy_backend "$s"
    done
    ;;
  customer-service|txn-service|audit-service|store-service|org-service|finance-service|marketing-service)
    svc="${COMPONENT%-service}"
    [ -z "$SKIP_BUILD" ] && build_backend "$svc"
    deploy_backend "$svc"
    ;;
  gateway)
    die "网关为 Go 静态二进制，走镜像重建：DOCKER_BUILDKIT=0 docker build -t meiyun/gateway:latest gateway/ 后 compose up -d gateway（双栈分别重建）"
    ;;
  *)
    die "未知 component：'$COMPONENT'（支持 frontend / backend / all / <svc>-service / gateway）"
    ;;
esac

echo ""
ok "[$STACK] $COMPONENT 部署完成"
