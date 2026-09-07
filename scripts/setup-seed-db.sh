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
CREATE TABLE IF NOT EXISTS coupon_writeoff_record (
  writeoff_id      varchar(24)  PRIMARY KEY,
  coupon_code      varchar(32)  NOT NULL,
  coupon_id        varchar(24),
  coupon_name      varchar(64)  NOT NULL,
  customer_name    varchar(32)  NOT NULL,
  customer_phone   varchar(20)  NOT NULL,
  store_code       varchar(16)  NOT NULL,
  store_name       varchar(64)  NOT NULL,
  order_amount_fen bigint       NOT NULL,
  discount_fen     bigint       NOT NULL,
  channel          varchar(16)  NOT NULL,
  status           varchar(10)  NOT NULL,
  reason           varchar(200),
  operator         varchar(32)  NOT NULL,
  verified_at      timestamptz  NOT NULL
);
-- B9 薪酬提成域（finance-service JPA ddl-auto 业务表；金额 bigint 存「分」，月份 date 存 yyyy-MM-01）
CREATE TABLE IF NOT EXISTS commission_rule (
  rule_id     varchar(24)  PRIMARY KEY,
  rule_name   varchar(64)  NOT NULL,
  base        varchar(16)  NOT NULL,
  role        varchar(16)  NOT NULL,
  tiers_json  text         NOT NULL,
  active      boolean      NOT NULL,
  created_by  varchar(32),
  created_at  timestamptz  NOT NULL,
  updated_by  varchar(32),
  updated_at  timestamptz
);
CREATE TABLE IF NOT EXISTS staff_comp_config (
  comp_id             varchar(24)  PRIMARY KEY,
  staff_id            varchar(16)  NOT NULL,
  staff_name          varchar(32)  NOT NULL,
  store_code          varchar(16)  NOT NULL,
  base_salary         bigint       NOT NULL,
  commission_rule_id  varchar(24),
  effective_month     date         NOT NULL,
  status              varchar(16)  NOT NULL,
  created_by          varchar(32),
  created_at          timestamptz  NOT NULL,
  updated_by          varchar(32),
  updated_at          timestamptz
);
CREATE TABLE IF NOT EXISTS commission_record (
  record_id    varchar(24)  PRIMARY KEY,
  period       date         NOT NULL,
  staff_id     varchar(16)  NOT NULL,
  staff_name   varchar(32)  NOT NULL,
  store_code   varchar(16)  NOT NULL,
  rule_id      varchar(24),
  rule_name    varchar(64),
  base_amount  bigint       NOT NULL,
  order_count  integer      NOT NULL,
  tiers_json   text,
  commission   bigint       NOT NULL,
  status       varchar(16)  NOT NULL,
  remark       varchar(256),
  approver     varchar(32),
  approved_at  timestamptz,
  paid_at      timestamptz,
  created_at   timestamptz  NOT NULL,
  CONSTRAINT uk_commission_period_staff UNIQUE (period, staff_id)
);
-- B10 BOM 自动扣耗材域（store-service project_bom 配方表；store_code 空串=集团模板）
CREATE TABLE IF NOT EXISTS project_bom (
  bom_id        varchar(24)  PRIMARY KEY,
  project_name  varchar(64)  NOT NULL,
  store_code    varchar(16)  NOT NULL DEFAULT '',
  sku_code      varchar(32)  NOT NULL,
  qty           integer      NOT NULL,
  enabled       boolean      NOT NULL DEFAULT true,
  created_by    varchar(32),
  created_at    timestamptz  NOT NULL,
  updated_by    varchar(32),
  updated_at    timestamptz,
  CONSTRAINT uk_project_bom UNIQUE (project_name, store_code, sku_code)
);
-- B10 BOM 扣料异常登记（txn-service bom_deduct_exception；一个划扣单最多一条）
CREATE TABLE IF NOT EXISTS bom_deduct_exception (
  exc_id        varchar(24)  PRIMARY KEY,
  writeoff_id   varchar(24)  NOT NULL,
  store_code    varchar(16)  NOT NULL,
  project_name  varchar(64),
  reason        varchar(256) NOT NULL,
  detail_json   varchar(2048),
  status        varchar(16)  NOT NULL,
  fail_count    integer      NOT NULL DEFAULT 0,
  created_at    timestamptz  NOT NULL,
  resolved_at   timestamptz,
  resolved_by   varchar(32),
  CONSTRAINT uk_bom_exc_writeoff UNIQUE (writeoff_id)
);
-- B11 月结成本结转域（finance-service JPA ddl-auto 业务表；金额 bigint 存「分」，月份 date 存 yyyy-MM-01）
CREATE TABLE IF NOT EXISTS cost_carry_rule (
  rule_id       varchar(24)  PRIMARY KEY,
  rule_name     varchar(64)  NOT NULL,
  cost_type     varchar(16)  NOT NULL,
  calc_mode     varchar(16)  NOT NULL,
  fixed_amount  bigint,
  store_code    varchar(16),
  enabled       boolean      NOT NULL DEFAULT true,
  run_on_close  boolean      NOT NULL DEFAULT true,
  remark        varchar(256),
  created_by    varchar(32),
  created_at    timestamptz  NOT NULL,
  updated_by    varchar(32),
  updated_at    timestamptz
);
-- B11 设备资产台账（直线法月折旧；salvage_rate 百分比整数，start_month 起折月，DISPOSED 后停折）
CREATE TABLE IF NOT EXISTS fin_asset (
  asset_id       varchar(24)  PRIMARY KEY,
  asset_name     varchar(64)  NOT NULL,
  store_code     varchar(16)  NOT NULL,
  original_value bigint       NOT NULL,
  salvage_rate   integer      NOT NULL DEFAULT 5,
  useful_months  integer      NOT NULL,
  start_month    date         NOT NULL,
  status         varchar(16)  NOT NULL DEFAULT 'IN_USE',
  created_by     varchar(32),
  created_at     timestamptz  NOT NULL,
  updated_by     varchar(32),
  updated_at     timestamptz
);
-- B12 非现金渠道账实接入：渠道配置（txn-service；store_code 空串=集团模板；api_v3_key 写后不可读回）
CREATE TABLE IF NOT EXISTS pay_channel_config (
  config_id      varchar(24)  PRIMARY KEY,
  channel_code   varchar(16)  NOT NULL,
  channel_name   varchar(32)  NOT NULL,
  store_code     varchar(16)  NOT NULL DEFAULT '',
  enabled        boolean      NOT NULL DEFAULT true,
  app_id         varchar(64),
  mch_id         varchar(64),
  api_v3_key     varchar(128),
  cert_serial    varchar(128),
  notify_url     varchar(256),
  reconcile_mode varchar(16)  NOT NULL DEFAULT 'IMPORT',
  fee_rate       integer      NOT NULL DEFAULT 0,
  remark         varchar(256),
  created_by     varchar(32),
  created_at     timestamptz  NOT NULL,
  updated_by     varchar(32),
  updated_at     timestamptz,
  CONSTRAINT uk_pay_channel_config UNIQUE (channel_code, store_code)
);
-- B12 渠道账单（finance-service；金额 bigint 存「分」；order_no 空=手续费行；仅勾兑台账，不产生实付分录）
CREATE TABLE IF NOT EXISTS pay_channel_bill (
  bill_id      varchar(24)  PRIMARY KEY,
  channel_code varchar(16)  NOT NULL,
  store_code   varchar(16)  NOT NULL,
  order_no     varchar(24),
  txn_amount   bigint       NOT NULL,
  fee_amount   bigint       NOT NULL DEFAULT 0,
  net_amount   bigint       NOT NULL,
  bill_status  varchar(16)  NOT NULL,
  bill_time    timestamptz  NOT NULL,
  settle_batch varchar(32)  NOT NULL,
  import_batch varchar(24)  NOT NULL,
  created_at   timestamptz  NOT NULL,
  CONSTRAINT uk_pay_channel_bill UNIQUE (channel_code, order_no, settle_batch)
);
CREATE INDEX IF NOT EXISTS idx_pay_channel_bill_time ON pay_channel_bill(bill_time);
-- B13 房间床位主数据（store-service JPA ddl-auto 业务表）
-- treatment_room 房间档案：room_type 用途（TREATMENT/CONSULT/OBSERVE/RECOVERY），status 房间停用态（一期恒 ACTIVE）；
-- 床位实时占用态（FREE/IN_USE/SANITIZING）一期不持久化，仅 treatment_bed.maint_status 维护态落库（OK/MAINTENANCE）。
-- room_id 为逻辑外键（不建物理 FK）；金额列不在本域。
CREATE TABLE IF NOT EXISTS treatment_room (
  id          bigserial    PRIMARY KEY,
  store_code  varchar(16)  NOT NULL,
  room_code   varchar(32)  NOT NULL,
  name        varchar(64)  NOT NULL,
  room_type   varchar(16)  NOT NULL,
  status      varchar(16)  NOT NULL,
  remark      varchar(255),
  created_by  varchar(32),
  created_at  timestamptz  NOT NULL,
  updated_by  varchar(32),
  updated_at  timestamptz,
  CONSTRAINT uk_room_store_code UNIQUE (store_code, room_code)
);
CREATE TABLE IF NOT EXISTS treatment_bed (
  id            bigserial    PRIMARY KEY,
  store_code    varchar(16)  NOT NULL,
  room_id       bigint       NOT NULL,
  bed_code      varchar(32)  NOT NULL,
  maint_status  varchar(16)  NOT NULL,
  maint_reason  varchar(255),
  created_by    varchar(32),
  created_at    timestamptz  NOT NULL,
  updated_by    varchar(32),
  updated_at    timestamptz,
  CONSTRAINT uk_bed_store_code UNIQUE (store_code, bed_code)
);
CREATE INDEX IF NOT EXISTS idx_treatment_bed_room ON treatment_bed(room_id);
-- B13 房间/床位操作日志：仅记真实落库动作 ADD_ROOM/SET_MAINTENANCE/RESTORE（入住/退房/消毒为前端演示态，不写本表）
CREATE TABLE IF NOT EXISTS room_operation_log (
  id          bigserial    PRIMARY KEY,
  store_code  varchar(16)  NOT NULL,
  room_code   varchar(32),
  bed_code    varchar(32),
  action      varchar(32)  NOT NULL,
  text        varchar(255),
  actor       varchar(32),
  created_at  timestamptz  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_room_op_log_store ON room_operation_log(store_code, created_at);
-- B13 设备仪器台账（store-service；金额 bigint 存「分」：purchase_amount_fen/depreciated_fen；
-- 日期 date 仅精确到天：购置日/下次校准日/下次维保日；状态 NORMAL/CALIBRATING/REPAIRING/DISABLED 全量持久化。
-- 与 finance fin_asset 一期分口不打通，独立运营台账）
CREATE TABLE IF NOT EXISTS equipment (
  id                   bigserial    PRIMARY KEY,
  store_code           varchar(16)  NOT NULL,
  asset_no             varchar(32)  NOT NULL,
  name                 varchar(64)  NOT NULL,
  brand                varchar(64),
  model                varchar(64),
  category             varchar(16)  NOT NULL,
  location             varchar(64)  NOT NULL,
  status               varchar(16)  NOT NULL,
  purchased_at         date         NOT NULL,
  purchase_amount_fen  bigint       NOT NULL,
  lifespan_years       integer      NOT NULL,
  depreciated_fen      bigint       NOT NULL DEFAULT 0,
  next_calibration_at  date,
  next_maintenance_at  date,
  note                 varchar(255),
  created_by           varchar(32),
  created_at           timestamptz  NOT NULL,
  updated_by           varchar(32),
  updated_at           timestamptz,
  CONSTRAINT uk_eq_store_asset UNIQUE (store_code, asset_no)
);
-- B13 设备校准/维保/维修明细（equipment 子表；type CALIBRATION/MAINTENANCE/REPAIR；cost_fen 费用分，无费用记 0）
CREATE TABLE IF NOT EXISTS equipment_maintenance (
  id            bigserial    PRIMARY KEY,
  store_code    varchar(16)  NOT NULL,
  equipment_id  bigint       NOT NULL,
  type          varchar(16)  NOT NULL,
  occurred_at   date         NOT NULL,
  actor         varchar(32)  NOT NULL,
  vendor        varchar(64),
  summary       varchar(255) NOT NULL,
  next_at       date,
  cost_fen      bigint       NOT NULL DEFAULT 0,
  created_at    timestamptz  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_equipment_maint_eq ON equipment_maintenance(equipment_id, occurred_at);
-- B14 项目目录集团主数据（store-service JPA ddl-auto 业务表；品牌→品类→SKU 三级，无 store_code 集团统一定义）
-- product_brand 品牌档案：brand_code 全局唯一；status ACTIVE/INACTIVE 受控停用不物理删；logo_color 前端卡片轮色。
CREATE TABLE IF NOT EXISTS product_brand (
  id          bigserial    PRIMARY KEY,
  brand_code  varchar(32)  NOT NULL,
  name        varchar(64)  NOT NULL,
  short_name  varchar(32),
  origin      varchar(64),
  supplier    varchar(128),
  status      varchar(16)  NOT NULL,
  logo_color  varchar(16),
  remark      varchar(255),
  created_by  varchar(32),
  created_at  timestamptz  NOT NULL,
  updated_by  varchar(32),
  updated_at  timestamptz,
  CONSTRAINT uk_brand_code UNIQUE (brand_code)
);
-- product_category 品类档案（二级树）：category_code 全局唯一；brand_id 归属品牌；parent_id 空=一级品类。
-- 删除规则：本品类或子品类名下有 SKU 时后端 422 拒绝，通过后连带删子品类。
CREATE TABLE IF NOT EXISTS product_category (
  id             bigserial    PRIMARY KEY,
  category_code  varchar(32)  NOT NULL,
  name           varchar(64)  NOT NULL,
  brand_id       bigint       NOT NULL,
  parent_id      bigint,
  status         varchar(16)  NOT NULL,
  sort           integer      NOT NULL,
  remark         varchar(255),
  created_by     varchar(32),
  created_at     timestamptz  NOT NULL,
  updated_by     varchar(32),
  updated_at     timestamptz,
  CONSTRAINT uk_category_code UNIQUE (category_code)
);
CREATE INDEX IF NOT EXISTS idx_product_category_brand ON product_category(brand_id);
-- product_sku 项目/产品 SKU（集团级）：sku 全局唯一；brand_id/category_id 逻辑外键；金额 bigint 存「分」；
-- service_category 门店经营服务大类（INJECTION/LASER/SKINCARE/BODY/EXAM，E2 新增，可空），与品牌品类树并存；
-- store_types 适用门店类型逗号串（FLAGSHIP/COMMUNITY/CLINIC）；risk_tags 风险标签逗号串可空。
CREATE TABLE IF NOT EXISTS product_sku (
  id                 bigserial    PRIMARY KEY,
  sku                varchar(40)  NOT NULL,
  name               varchar(64)  NOT NULL,
  brand_id           bigint       NOT NULL,
  category_id        bigint       NOT NULL,
  unit               varchar(8)   NOT NULL,
  list_price_fen     bigint       NOT NULL,
  cost_price_fen     bigint       NOT NULL,
  status             varchar(16)  NOT NULL,
  store_types        varchar(32)  NOT NULL,
  duration_min       integer      NOT NULL,
  service_category   varchar(16),
  risk_tags          varchar(64),
  remark             varchar(255),
  created_by         varchar(32),
  created_at         timestamptz  NOT NULL,
  updated_by         varchar(32),
  updated_at         timestamptz,
  CONSTRAINT uk_sku UNIQUE (sku)
);
CREATE INDEX IF NOT EXISTS idx_product_sku_brand ON product_sku(brand_id);
CREATE INDEX IF NOT EXISTS idx_product_sku_category ON product_sku(category_id);
-- B14 门店价目（门店级；金额 bigint 存「分」；同店同 SKU 唯一）。门店对集团 SKU 定价：原价/会员价/活动价。
-- 调价三态：ACTIVE ─change-request→ PENDING ─approve→ ACTIVE（pending 覆盖正式价）/ ─reject→ ACTIVE（清 pending）；
-- DISABLED 经 toggle 与 ACTIVE 互切；pending_* 为待审批价（分），requested_by/requested_at 记申请人与时间。
CREATE TABLE IF NOT EXISTS store_price (
  id                        bigserial    PRIMARY KEY,
  store_code                varchar(16)  NOT NULL,
  sku                       varchar(40)  NOT NULL,
  original_price_fen        bigint       NOT NULL,
  member_price_fen          bigint       NOT NULL,
  promo_price_fen           bigint,
  status                    varchar(16)  NOT NULL,
  pending_member_price_fen  bigint,
  pending_promo_price_fen   bigint,
  pending_reason            varchar(255),
  requested_by              varchar(32),
  requested_at              timestamptz,
  created_by                varchar(32),
  created_at                timestamptz  NOT NULL,
  updated_by                varchar(32),
  updated_at                timestamptz,
  CONSTRAINT uk_price_store_sku UNIQUE (store_code, sku)
);
CREATE INDEX IF NOT EXISTS idx_store_price_store ON store_price(store_code);
SQL
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$SEED_DB" -v ON_ERROR_STOP=1 <<'SQL'
TRUNCATE TABLE
  account_mirror, appointment, appointment_month, approval_todo, audit_log, campaign,
  card_finance_event, card_ledger, commission_record, commission_rule, consult_plan, consult_plan_item, consult_plan_revision,
  consultation, consumable, consumable_movement, consumable_stock, contraindication,
  cost_allocation, coupon_grant, coupon_template, coupon_writeoff_chain, coupon_writeoff_record,
  cross_domain_coeff, customer, customer_tag, customer_tag_rel, dual_sign_ticket,
  fin_budget, fin_change_log, fin_invoice, fin_setting, fin_subject_enable, finance_event,
  fund_entry, inventory_item, inventory_log, mall_exchange, mall_product, marketing_cfg,
  member_card, member_level, order_payment, org_unit, outbox_record, point_rule,
  points_ledger, points_pool, prepay_pool, push_record, region_dist, repurchase,
  revenue_monthly, role_def, settlement_period, sign_role_pair, sign_tier, staff, staff_comp_config, store,
  tax, tenant, txn_card_cancel, txn_order, txn_refund, txn_writeoff, verification,
  writeoff_desk_task, writeoff_record,
  order_item, marketing_asset, poster_template, poster_record, live_session, short_video,
  project_bom, bom_deduct_exception, cost_carry_rule, fin_asset,
  pay_channel_config, pay_channel_bill,
  treatment_room, treatment_bed, room_operation_log, equipment, equipment_maintenance,
  product_brand, product_category, product_sku, store_price
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
# docker compose 可能给容器名加项目哈希前缀（如 c2bf5413b2b6_meiyun-seed-org-service），
# 精确名匹配会漏重启；按「服务名结尾」解析实际容器名（精确名或 _<服务名> 结尾均可）。
SEED_ORG_CONTAINER="$(docker ps --format '{{.Names}}' | grep -E '(^|_)meiyun-seed-org-service$' | head -1)"
if [ -n "$SEED_ORG_CONTAINER" ]; then
  echo "    重启 $SEED_ORG_CONTAINER 触发凭证/角色播种 ..."
  docker restart "$SEED_ORG_CONTAINER" >/dev/null
  echo "    等待 healthy ..."
  for _ in $(seq 1 40); do
    [ "$(docker inspect "$SEED_ORG_CONTAINER" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ] && break
    sleep 3
  done
  echo "    $SEED_ORG_CONTAINER 已就绪，测试账号（SE101 等 / 密码 meiyun123）可登录。"
else
  echo "    meiyun-seed-org-service 未运行，跳过（起栈后 org-service 启动即自动播种）。"
fi

echo ""
echo "==> [可选] 若 seed 联调栈 txn-service 在运行，重启它以触发 B12 支付渠道配置启动播种"
# reset 会 TRUNCATE pay_channel_config；wxpay/alipay enabled（测试参数、密钥占位非真实凭证）、
# transfer disabled 三行集团模板由 txn-service 的 PayChannelDataInitializer（@Order 60）启动时幂等补齐。
# 渠道配置经收银/对账链路读取，必须在 finance 勾兑与 E2E 导入账单前就绪。容器未起或旧镜像则跳过。
SEED_TXN_CONTAINER="$(docker ps --format '{{.Names}}' | grep -E '(^|_)meiyun-seed-txn-service$' | head -1)"
if [ -n "$SEED_TXN_CONTAINER" ]; then
  echo "    重启 $SEED_TXN_CONTAINER 触发支付渠道配置种子 ..."
  docker restart "$SEED_TXN_CONTAINER" >/dev/null
  echo "    等待 healthy ..."
  for _ in $(seq 1 40); do
    [ "$(docker inspect "$SEED_TXN_CONTAINER" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ] && break
    sleep 3
  done
  echo "    $SEED_TXN_CONTAINER 已就绪，支付渠道配置（wxpay/alipay/transfer）可查。"
else
  echo "    meiyun-seed-txn-service 未运行，跳过（起栈后 txn-service 启动即自动播种）。"
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
echo "==> [可选] 若 seed 联调栈 finance-service 在运行，重启它以触发 B9 薪酬提成 + B11 成本结转启动播种"
# reset 会 TRUNCATE commission_rule / staff_comp_config / commission_record / cost_carry_rule / fin_asset；
# 提成规则（咨询师 6/8/10/12% 阶梯 / 医生 10/12% 阶梯）与 12 条员工薪酬配置（底薪+适用规则）
# 由 finance-service 的 CommissionDataInitializer（@Order 40）在启动时幂等补齐；
# B11 三条结转规则（设备折旧/底薪/提成）与徐汇店三台设备资产由 CostCarryDataInitializer（@Order 50）幂等补齐。
# 容器未起或为旧镜像（无播种器）则跳过。
SEED_FIN_CONTAINER="$(docker ps --format '{{.Names}}' | grep -E '(^|_)meiyun-seed-finance-service$' | head -1)"
if [ -n "$SEED_FIN_CONTAINER" ]; then
  echo "    重启 $SEED_FIN_CONTAINER 触发提成规则/薪酬配置/结转规则/资产台账种子 ..."
  docker restart "$SEED_FIN_CONTAINER" >/dev/null
  echo "    等待 healthy ..."
  for _ in $(seq 1 40); do
    [ "$(docker inspect "$SEED_FIN_CONTAINER" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ] && break
    sleep 3
  done
  echo "    $SEED_FIN_CONTAINER 已就绪，B9 提成规则/薪酬配置 + B11 结转规则/资产台账种子可查。"
else
  echo "    meiyun-seed-finance-service 未运行，跳过（起栈后 finance-service 启动即自动播种）。"
fi

echo ""
echo "==> [可选] 若 seed 联调栈 store-service 在运行，重启它以触发 B13 房间床位/设备仪器 + B14 项目目录/门店价目启动播种"
# reset 会 TRUNCATE treatment_room / treatment_bed / room_operation_log / equipment / equipment_maintenance /
# product_brand / product_category / product_sku / store_price；
# 9 间房 16 张床位（A03-2 维护中）与 8 台设备仪器（含 11 条校准维保记录）由 store-service 的
# StoreMasterDataInitializer（@Order 60）在启动时幂等补齐（门控：床位/设备已存在则跳过）；
# 9 品牌 / 16 品类（二级树）/ 15 项目 SKU / SST01 门店价目 11 条（8 ACTIVE/2 PENDING/1 DISABLED）
# 由 StorePriceCatalogDataInitializer（@Order 70）幂等补齐（门控：品牌/价目已存在则跳过）。
# 房间操作日志无静态种子，仅由真实建房/设维护/恢复动作产生。容器未起或为旧镜像（无播种器）则跳过。
SEED_STORE_CONTAINER="$(docker ps --format '{{.Names}}' | grep -E '(^|_)meiyun-seed-store-service$' | head -1)"
if [ -n "$SEED_STORE_CONTAINER" ]; then
  echo "    重启 $SEED_STORE_CONTAINER 触发房间床位/设备仪器/项目目录/门店价目种子 ..."
  docker restart "$SEED_STORE_CONTAINER" >/dev/null
  echo "    等待 healthy ..."
  for _ in $(seq 1 40); do
    [ "$(docker inspect "$SEED_STORE_CONTAINER" --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ] && break
    sleep 3
  done
  echo "    $SEED_STORE_CONTAINER 已就绪，B13 房间床位（9 房 16 床）/设备仪器（8 台）+ B14 项目目录（9 品牌/16 品类/15 SKU）/价目（11 条）种子可查。"
else
  echo "    meiyun-seed-store-service 未运行，跳过（起栈后 store-service 启动即自动播种）。"
fi

echo ""
echo "==> 灌入 B12 渠道账单演示种子（pay_channel_bill，1 条故意「账单有系统无」差异）"
# 设计 §3.5：按 seed 已收款订单造匹配账单 + 故意 1 条漏单差异。但 seed 静态 txn_order 无配套
# order_payment/fund_entry（收款分录由真实收银动作产生），匹配账单请在 E2E 中走「导入账单 CSV」
# 生成（不伪造 fund_entry，守资金红线）。这里仅种 1 条 2026-09 wxpay 成功账单，订单号在系统侧
# 不存在 → 勾兑稳定演示「多单/未入账」差异；重跑 reset 前 TRUNCATE 已清空，幂等可重复执行。
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$SEED_DB" -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO pay_channel_bill
  (bill_id, channel_code, store_code, order_no, txn_amount, fee_amount, net_amount,
   bill_status, bill_time, settle_batch, import_batch, created_at)
VALUES
  ('PCB-SEED-000001', 'wxpay', 'SST01', 'WX-SEED-DIFF-20260901', 128000, 768, 127232,
   'SUCCESS', '2026-09-01 10:30:00+08:00', 'WX20260902', 'IMP-SEED-000001', now());
SQL

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
   UNION ALL SELECT 'commission_rule(启动播种)='||count(*) FROM commission_rule
   UNION ALL SELECT 'staff_comp_config(启动播种)='||count(*) FROM staff_comp_config
   UNION ALL SELECT 'cost_carry_rule(启动播种)='||count(*) FROM cost_carry_rule
   UNION ALL SELECT 'fin_asset(启动播种)='||count(*) FROM fin_asset
   UNION ALL SELECT 'pay_channel_config(启动播种)='||count(*) FROM pay_channel_config
   UNION ALL SELECT 'pay_channel_bill(演示种子)='||count(*) FROM pay_channel_bill
   UNION ALL SELECT 'treatment_room(启动播种)='||count(*) FROM treatment_room
   UNION ALL SELECT 'treatment_bed(启动播种)='||count(*) FROM treatment_bed
   UNION ALL SELECT 'room_operation_log(写操作产生)='||count(*) FROM room_operation_log
   UNION ALL SELECT 'equipment(启动播种)='||count(*) FROM equipment
   UNION ALL SELECT 'equipment_maintenance(启动播种)='||count(*) FROM equipment_maintenance
   UNION ALL SELECT 'product_brand(启动播种)='||count(*) FROM product_brand
   UNION ALL SELECT 'product_category(启动播种)='||count(*) FROM product_category
   UNION ALL SELECT 'product_sku(启动播种)='||count(*) FROM product_sku
   UNION ALL SELECT 'store_price(启动播种)='||count(*) FROM store_price
   UNION ALL SELECT 'sys_dictionary(保留)='||count(*) FROM sys_dictionary;"
