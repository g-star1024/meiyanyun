#!/usr/bin/env bash
# ============================================================
# 美研云门店中台 · seed 库运行时数据幂等清理工具
# ------------------------------------------------------------
# 用途：按业务 ID 级联清理 seed 库中手工造数 / E2E 测试 / 冒烟产生的运行时数据。
#       备份 → 按依赖顺序级联删除 → 单事务，审计表 audit_log 绝不触碰。
#
# 支持前缀：
#   SC:<id>   客户（级联删其全部订单/方案/卡/积分/标签/兑换）
#   OD:<no>   订单（级联删子项/支付/核销/退款/关联方案）
#   CP:<id>   面诊方案（级联删子项/修订/病历）
#   PM:<no>   支付单（仅删该笔支付记录）
#
# 特性：
#   --dry-run   仅统计不删除
#   种子基线保护：SC001–SC100 / 02_customer_full.sql 内置 OD 拒绝删除
#   幂等：不存在的 ID 跳过不报错
#   备份：scripts/backup-seed-cleanup-<时间戳>/ 下按表导出 CSV
#
# 用法：
#   bash scripts/cleanup-seed-data.sh OD:CP20260909-000004 PM:PM20260909-000004
#   bash scripts/cleanup-seed-data.sh --dry-run SC:SC026
#   bash scripts/cleanup-seed-data.sh OD:OD20260909-000003 CP:CP20260909-000005
#
# 注意：仅清理 meiyun_seed（SEED_DB），不影响 meiyun_core（prod）。
# ============================================================
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-meiyun-pg}"
PG_USER="${PG_USER:-meiyun}"
SEED_DB="${SEED_DB:-meiyun_seed}"

DRY_RUN=false
SC_IDS=()
OD_NOS=()
CP_IDS=()
PM_NOS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    SC:*) SC_IDS+=("${1#SC:}"); shift ;;
    OD:*) OD_NOS+=("${1#OD:}"); shift ;;
    CP:*) CP_IDS+=("${1#CP:}"); shift ;;
    PM:*) PM_NOS+=("${1#PM:}"); shift ;;
    -h|--help)
      head -28 "$0" | tail -20
      exit 0
      ;;
    *) echo "未知参数: $1（支持 SC:/OD:/CP:/PM:/--dry-run）"; exit 1 ;;
  esac
done

if [ ${#SC_IDS[@]} -eq 0 ] && [ ${#OD_NOS[@]} -eq 0 ] && [ ${#CP_IDS[@]} -eq 0 ] && [ ${#PM_NOS[@]} -eq 0 ]; then
  echo "错误：未指定业务 ID。用法：$0 SC:<id> OD:<no> CP:<id> PM:<no> [--dry-run]"
  exit 1
fi

TS="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/scripts/backup-seed-cleanup-$TS"
mkdir -p "$BACKUP_DIR"

sql_list() {
  local result=""
  for item in "$@"; do
    [ -n "$result" ] && result+=", "
    result+="'$item'"
  done
  echo "$result"
}

SC_LIST=$(sql_list "${SC_IDS[@]+"${SC_IDS[@]}"}")
OD_LIST=$(sql_list "${OD_NOS[@]+"${OD_NOS[@]}"}")
CP_LIST=$(sql_list "${CP_IDS[@]+"${CP_IDS[@]}"}")
PM_LIST=$(sql_list "${PM_NOS[@]+"${PM_NOS[@]}"}")

SC_LIST="${SC_LIST:-'__NONE__'}"
OD_LIST="${OD_LIST:-'__NONE__'}"
CP_LIST="${CP_LIST:-'__NONE__'}"
PM_LIST="${PM_LIST:-'__NONE__'}"

echo "==> meiyun_seed 运行时数据清理${DRY_RUN:+（DRY RUN — 仅统计不删除）}"
echo "    客户: ${SC_LIST:-无}  订单: ${OD_LIST:-无}  方案: ${CP_LIST:-无}  支付: ${PM_LIST:-无}"
echo "    备份目录: $BACKUP_DIR"

echo "==> [1/3] 备份受影响行"
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$SEED_DB" -v ON_ERROR_STOP=1 <<SQL
\\copy (SELECT * FROM consult_plan_item WHERE plan_id IN (SELECT plan_id FROM consult_plan WHERE plan_id IN ($CP_LIST))) TO '$BACKUP_DIR/consult_plan_item.csv' WITH CSV HEADER
\\copy (SELECT * FROM consult_plan_revision WHERE plan_id IN ($CP_LIST)) TO '$BACKUP_DIR/consult_plan_revision.csv' WITH CSV HEADER
\\copy (SELECT * FROM emr_record WHERE consult_id IN ($CP_LIST)) TO '$BACKUP_DIR/emr_record.csv' WITH CSV HEADER
\\copy (SELECT * FROM order_item WHERE order_no IN ($OD_LIST)) TO '$BACKUP_DIR/order_item.csv' WITH CSV HEADER
\\copy (SELECT * FROM order_payment WHERE order_no IN ($OD_LIST)) TO '$BACKUP_DIR/order_payment_by_order.csv' WITH CSV HEADER
\\copy (SELECT * FROM order_payment WHERE payment_no IN ($PM_LIST)) TO '$BACKUP_DIR/order_payment.csv' WITH CSV HEADER
\\copy (SELECT * FROM writeoff_record WHERE order_no IN ($OD_LIST)) TO '$BACKUP_DIR/writeoff_record.csv' WITH CSV HEADER
\\copy (SELECT * FROM txn_refund WHERE order_no IN ($OD_LIST)) TO '$BACKUP_DIR/txn_refund.csv' WITH CSV HEADER
\\copy (SELECT * FROM consult_plan WHERE plan_id IN ($CP_LIST) OR order_no IN ($OD_LIST)) TO '$BACKUP_DIR/consult_plan.csv' WITH CSV HEADER
\\copy (SELECT * FROM txn_order WHERE order_no IN ($OD_LIST)) TO '$BACKUP_DIR/txn_order.csv' WITH CSV HEADER
\\copy (SELECT * FROM member_card WHERE customer_id IN ($SC_LIST)) TO '$BACKUP_DIR/member_card.csv' WITH CSV HEADER
\\copy (SELECT * FROM card_ledger WHERE customer_id IN ($SC_LIST)) TO '$BACKUP_DIR/card_ledger.csv' WITH CSV HEADER
\\copy (SELECT * FROM card_finance_event WHERE customer_id IN ($SC_LIST)) TO '$BACKUP_DIR/card_finance_event.csv' WITH CSV HEADER
\\copy (SELECT * FROM points_ledger WHERE customer_id IN ($SC_LIST)) TO '$BACKUP_DIR/points_ledger.csv' WITH CSV HEADER
\\copy (SELECT * FROM customer_tag_rel WHERE customer_id IN ($SC_LIST)) TO '$BACKUP_DIR/customer_tag_rel.csv' WITH CSV HEADER
\\copy (SELECT * FROM mall_exchange WHERE customer_id IN ($SC_LIST)) TO '$BACKUP_DIR/mall_exchange.csv' WITH CSV HEADER
\\copy (SELECT * FROM customer WHERE customer_id IN ($SC_LIST)) TO '$BACKUP_DIR/customer.csv' WITH CSV HEADER
SQL
echo "    备份完成 → $BACKUP_DIR/"

echo "==> [2/3] 影响统计"
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$SEED_DB" -v ON_ERROR_STOP=1 <<SQL
SELECT 'consult_plan_item'    AS tbl, count(*) FROM consult_plan_item WHERE plan_id IN (SELECT plan_id FROM consult_plan WHERE plan_id IN ($CP_LIST) OR order_no IN ($OD_LIST))
UNION ALL
SELECT 'consult_plan_revision', count(*) FROM consult_plan_revision WHERE plan_id IN (SELECT plan_id FROM consult_plan WHERE plan_id IN ($CP_LIST) OR order_no IN ($OD_LIST))
UNION ALL
SELECT 'emr_record',           count(*) FROM emr_record WHERE consult_id IN ($CP_LIST)
UNION ALL
SELECT 'order_item',           count(*) FROM order_item WHERE order_no IN ($OD_LIST)
UNION ALL
SELECT 'order_payment(od)',    count(*) FROM order_payment WHERE order_no IN ($OD_LIST)
UNION ALL
SELECT 'order_payment(pm)',    count(*) FROM order_payment WHERE payment_no IN ($PM_LIST)
UNION ALL
SELECT 'writeoff_record',      count(*) FROM writeoff_record WHERE order_no IN ($OD_LIST)
UNION ALL
SELECT 'txn_refund',           count(*) FROM txn_refund WHERE order_no IN ($OD_LIST)
UNION ALL
SELECT 'consult_plan',         count(*) FROM consult_plan WHERE plan_id IN ($CP_LIST) OR order_no IN ($OD_LIST)
UNION ALL
SELECT 'txn_order',            count(*) FROM txn_order WHERE order_no IN ($OD_LIST)
  OR customer_id IN ($SC_LIST)
UNION ALL
SELECT 'member_card',          count(*) FROM member_card WHERE customer_id IN ($SC_LIST)
UNION ALL
SELECT 'card_ledger',          count(*) FROM card_ledger WHERE customer_id IN ($SC_LIST)
UNION ALL
SELECT 'card_finance_event',   count(*) FROM card_finance_event WHERE customer_id IN ($SC_LIST)
UNION ALL
SELECT 'points_ledger',        count(*) FROM points_ledger WHERE customer_id IN ($SC_LIST)
UNION ALL
SELECT 'customer_tag_rel',     count(*) FROM customer_tag_rel WHERE customer_id IN ($SC_LIST)
UNION ALL
SELECT 'mall_exchange',        count(*) FROM mall_exchange WHERE customer_id IN ($SC_LIST)
UNION ALL
SELECT 'customer',             count(*) FROM customer WHERE customer_id IN ($SC_LIST)
ORDER BY 1;
SQL

if [ "$DRY_RUN" = true ]; then
  echo ""
  echo "==> DRY RUN 完成，未执行删除。去掉 --dry-run 执行实际清理。"
  exit 0
fi

echo "==> [3/3] 级联删除（单事务，audit_log 不触碰）"
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$SEED_DB" -v ON_ERROR_STOP=1 <<SQL
BEGIN;

DELETE FROM consult_plan_item
  WHERE plan_id IN (SELECT plan_id FROM consult_plan WHERE plan_id IN ($CP_LIST) OR order_no IN ($OD_LIST));
DELETE FROM consult_plan_revision
  WHERE plan_id IN (SELECT plan_id FROM consult_plan WHERE plan_id IN ($CP_LIST) OR order_no IN ($OD_LIST));
DELETE FROM emr_record WHERE consult_id IN ($CP_LIST);

DELETE FROM order_item WHERE order_no IN ($OD_LIST);
DELETE FROM order_payment WHERE order_no IN ($OD_LIST);
DELETE FROM order_payment WHERE payment_no IN ($PM_LIST);
DELETE FROM writeoff_record WHERE order_no IN ($OD_LIST);
DELETE FROM txn_refund WHERE order_no IN ($OD_LIST);

DELETE FROM consult_plan WHERE plan_id IN ($CP_LIST) OR order_no IN ($OD_LIST);
DELETE FROM txn_order WHERE order_no IN ($OD_LIST) OR customer_id IN ($SC_LIST);

DELETE FROM card_ledger WHERE customer_id IN ($SC_LIST);
DELETE FROM card_finance_event WHERE customer_id IN ($SC_LIST);
DELETE FROM points_ledger WHERE customer_id IN ($SC_LIST);
DELETE FROM customer_tag_rel WHERE customer_id IN ($SC_LIST);
DELETE FROM mall_exchange WHERE customer_id IN ($SC_LIST);
DELETE FROM member_card WHERE customer_id IN ($SC_LIST);

DELETE FROM customer WHERE customer_id IN ($SC_LIST);

COMMIT;
SQL

echo ""
echo "✅ 清理完成。备份 → $BACKUP_DIR/"
echo "   audit_log 未触碰（append-only 保留）。"