#!/usr/bin/env bash
# ============================================================
# 美研云门店中台 · 独立测试库 meiyun_seed 一键建库 + 灌种子（幂等，可重复执行 = reset）
# ------------------------------------------------------------
# 做法：
#   1) DROP DATABASE IF EXISTS meiyun_seed; CREATE DATABASE meiyun_seed TEMPLATE meiyun_core
#      → 结构、sys_* 系统基线、sys_dictionary 字典、flyway_schema_history 迁移历史一并克隆，
#        Flyway 视 V1/V2 已应用，不会重跑；业务表随后清空。
#   2) TRUNCATE 全部业务表（RESTART IDENTITY CASCADE），保留 sys_* / flyway / schema_version。
#   3) 灌入 backend/db/seed/01_master.sql（主数据）+ 02_customer_full.sql（100 客户富画像）。
#
# 用法：bash scripts/setup-seed-db.sh
# 配套：docker-compose.seed.yml 可起指向 meiyun_seed 的服务做联调。
# ============================================================
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-meiyun-pg}"
PG_USER="${PG_USER:-meiyun}"
SRC_DB="${SRC_DB:-meiyun_core}"
SEED_DB="${SEED_DB:-meiyun_seed}"

# 脚本所在目录 → 项目根
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SEED_DIR="$ROOT_DIR/backend/db/seed"

echo "==> [1/4] 重建数据库 $SEED_DB 并从 $SRC_DB 在线克隆（结构+系统基线+字典+迁移历史）"
# 不用 TEMPLATE（源库正被运行中的服务连接会报错）；改用 pg_dump|psql 在线克隆（MVCC 一致性快照，无需停服务）。
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d postgres -v ON_ERROR_STOP=1 <<SQL
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$SEED_DB' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS $SEED_DB;
CREATE DATABASE $SEED_DB;
SQL
echo "    正在克隆 $SRC_DB → $SEED_DB ..."
docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$SRC_DB" --no-owner --no-privileges \
  | docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$SEED_DB" -v ON_ERROR_STOP=1 >/dev/null

echo "==> [2/4] 清空业务表（保留 sys_* / flyway_schema_history / schema_version）"
# order_item 为新增业务表（订单收费子项）；marketing_asset / poster_template / poster_record /
# live_session / short_video 为 M5 营销内容生产链路（素材库/海报/直播团购）新增业务表。
# 克隆源库可能尚未由 JPA ddl-auto 建出，这里幂等补建，保证后续 TRUNCATE / 灌种子不依赖服务启动顺序。
# 逻辑外键（不建物理 FK）；金额 bigint 存「分」；时间列按实体口径 date/timestamp/timestamptz。
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$SEED_DB" -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE IF NOT EXISTS order_item (
  item_id    bigserial PRIMARY KEY,
  order_no   varchar(24)  NOT NULL,
  line_no    integer      NOT NULL,
  item_name  varchar(64)  NOT NULL,
  qty        integer      NOT NULL,
  unit_price bigint       NOT NULL,
  amount     bigint       NOT NULL,
  created_at timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_order_item_order_no ON order_item(order_no);

CREATE TABLE IF NOT EXISTS marketing_asset (
  asset_id    varchar(24)  PRIMARY KEY,
  asset_name  varchar(64)  NOT NULL,
  type        varchar(8)   NOT NULL,
  tags        varchar(512) NOT NULL,
  scope       varchar(10)  NOT NULL,
  store_codes varchar(512) NOT NULL,
  expire_at   date         NOT NULL,
  ref_count   integer      NOT NULL,
  accent      varchar(8)   NOT NULL,
  content     varchar(1000),
  created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS poster_template (
  template_id      varchar(24)  PRIMARY KEY,
  template_name    varchar(64)  NOT NULL,
  style            varchar(12)  NOT NULL,
  status           varchar(8)   NOT NULL,
  uses             integer      NOT NULL,
  accent           varchar(8)   NOT NULL,
  default_title    varchar(64)  NOT NULL,
  default_subtitle varchar(128) NOT NULL,
  created_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS poster_record (
  poster_id       varchar(24)  PRIMARY KEY,
  template_id     varchar(24)  NOT NULL,
  template_name   varchar(64)  NOT NULL,
  style           varchar(12)  NOT NULL,
  accent          varchar(8)   NOT NULL,
  title           varchar(64)  NOT NULL,
  subtitle        varchar(128),
  project         varchar(64)  NOT NULL,
  referrer_name   varchar(32),
  status          varchar(10)  NOT NULL,
  share           integer      NOT NULL,
  scan            integer      NOT NULL,
  lead            integer      NOT NULL,
  visit           integer      NOT NULL,
  deal            integer      NOT NULL,
  deal_amount     bigint       NOT NULL,
  commission_rate integer      NOT NULL,
  created_at      timestamptz  NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS live_session (
  session_id         varchar(24)  PRIMARY KEY,
  title              varchar(64)  NOT NULL,
  platform           varchar(16)  NOT NULL,
  status             varchar(12)  NOT NULL,
  start_time         timestamp    NOT NULL,
  viewers            integer      NOT NULL,
  link_clicks        integer      NOT NULL,
  deal_count         integer      NOT NULL,
  deal_amount        bigint       NOT NULL,
  mounted_coupon_ids varchar(512) NOT NULL,
  intro              varchar(500),
  host               varchar(32),
  created_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS short_video (
  video_id     varchar(24)  PRIMARY KEY,
  title        varchar(64)  NOT NULL,
  platform     varchar(16)  NOT NULL,
  plays        integer      NOT NULL,
  likes        integer      NOT NULL,
  deal_count   integer      NOT NULL,
  deal_amount  bigint       NOT NULL,
  tags         varchar(256) NOT NULL,
  published_at date         NOT NULL
);
SQL
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$SEED_DB" -v ON_ERROR_STOP=1 <<'SQL'
TRUNCATE TABLE
  account_mirror, appointment, appointment_month, audit_log, campaign, consultation,
  contraindication, coupon_grant, coupon_template, coupon_writeoff_chain, cross_domain_coeff,
  customer, customer_tag, customer_tag_rel, dual_sign_ticket,
  inventory_item, inventory_log, mall_exchange, mall_product, marketing_cfg,
  member_card, member_level, org_unit, outbox_record, point_rule,
  points_ledger, points_pool, prepay_pool, push_record, region_dist, repurchase,
  revenue_monthly, role_def, sign_role_pair, sign_tier, staff, store, tax, tenant,
  txn_card_cancel, txn_order, txn_refund, txn_writeoff, verification, writeoff_record,
  order_item, marketing_asset, poster_template, poster_record, live_session, short_video
RESTART IDENTITY CASCADE;
SQL

echo "==> [3/4] 灌入主数据 01_master.sql"
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$SEED_DB" -v ON_ERROR_STOP=1 < "$SEED_DIR/01_master.sql"

echo "==> [4/4] 灌入客户富画像 02_customer_full.sql"
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$SEED_DB" -v ON_ERROR_STOP=1 < "$SEED_DIR/02_customer_full.sql"

echo ""
echo "==> [可选] 若 seed 联调栈 org-service 在运行，重启它以触发 RBAC 启动播种"
# reset 会 TRUNCATE staff/role_def 并重灌不含登录凭证的 01_master.sql；
# 登录凭证（login_name/password_hash=meiyun123）、SE101-SE105/E001-E014 演示员工、
# 新角色码迁移都由 org-service 的 RbacDataInitializer 在启动时幂等补齐。
# 不重启则测试账号登录会报「工号或密码错误」。容器未起则跳过（纯建库场景）。
SEED_ORG_CONTAINER="${SEED_ORG_CONTAINER:-meiyun-seed-org-service}"
if docker ps --format '{{.Names}}' | grep -qx "$SEED_ORG_CONTAINER"; then
  echo "    重启 $SEED_ORG_CONTAINER 触发凭证/角色播种 ..."
  docker restart "$SEED_ORG_CONTAINER" >/dev/null
  echo "    等待 healthy ..."
  for _ in $(seq 1 40); do
    [ "$(docker inspect "$SEED_ORG_CONTAINER" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ] && break
    sleep 3
  done
  echo "    $SEED_ORG_CONTAINER 已就绪，测试账号（SE101 等 / 密码 meiyun123）可登录。"
else
  echo "    $SEED_ORG_CONTAINER 未运行，跳过（起栈后 org-service 启动即自动播种）。"
fi

echo ""
echo "==> [可选] 若 seed 联调栈 marketing-service 在运行，重启它以触发 M5 营销数据启动播种"
# reset 会 TRUNCATE marketing_asset / poster_template / poster_record / live_session / short_video；
# 素材库(10)/海报(6 模板+6 记录)/直播团购(7 场次+5 短视频) 的演示种子由 marketing-service 的
# 三个 DataInitializer（@Order 30/31/32）在启动时幂等补齐。容器未起或为旧镜像（无播种器）则跳过。
SEED_MKT_CONTAINER="${SEED_MKT_CONTAINER:-meiyun-seed-marketing-service}"
if docker ps --format '{{.Names}}' | grep -qx "$SEED_MKT_CONTAINER"; then
  echo "    重启 $SEED_MKT_CONTAINER 触发素材/海报/直播种子 ..."
  docker restart "$SEED_MKT_CONTAINER" >/dev/null
  echo "    等待 healthy ..."
  for _ in $(seq 1 40); do
    [ "$(docker inspect "$SEED_MKT_CONTAINER" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ] && break
    sleep 3
  done
  echo "    $SEED_MKT_CONTAINER 已就绪，M5 营销种子可查。"
else
  echo "    $SEED_MKT_CONTAINER 未运行，跳过（起栈后 marketing-service 启动即自动播种）。"
fi

echo ""
echo "✅ 完成。测试库 $SEED_DB 已就绪（可随时重跑本脚本 reset）。"
echo "   行数核对："
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$SEED_DB" -t -c \
  "SELECT 'customer='||count(*) FROM customer
   UNION ALL SELECT 'txn_order='||count(*) FROM txn_order
   UNION ALL SELECT 'order_item='||count(*) FROM order_item
   UNION ALL SELECT 'member_card='||count(*) FROM member_card
   UNION ALL SELECT 'points_ledger='||count(*) FROM points_ledger
   UNION ALL SELECT 'appointment='||count(*) FROM appointment
   UNION ALL SELECT 'consultation='||count(*) FROM consultation
   UNION ALL SELECT 'staff='||count(*) FROM staff
   UNION ALL SELECT 'marketing_asset='||count(*) FROM marketing_asset
   UNION ALL SELECT 'poster_template='||count(*) FROM poster_template
   UNION ALL SELECT 'poster_record='||count(*) FROM poster_record
   UNION ALL SELECT 'live_session='||count(*) FROM live_session
   UNION ALL SELECT 'short_video='||count(*) FROM short_video
   UNION ALL SELECT 'sys_dictionary(保留)='||count(*) FROM sys_dictionary;"
