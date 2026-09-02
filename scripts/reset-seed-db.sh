#!/usr/bin/env bash
# ============================================================
# 美研云门店中台 · 测试库 meiyun_seed 彻底清空
# ------------------------------------------------------------
# 开发/联调完成后，用本脚本 DROP 整个 meiyun_seed 库（测试数据 100% 清除）。
# 不影响开发主库 meiyun_core 及其数据。
# 如需重建，重跑 scripts/setup-seed-db.sh。
#
# 用法：bash scripts/reset-seed-db.sh
# ============================================================
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-meiyun-pg}"
PG_USER="${PG_USER:-meiyun}"
SEED_DB="${SEED_DB:-meiyun_seed}"

echo "==> 断开并 DROP 测试库 $SEED_DB（不影响开发主库 meiyun_core）"
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d postgres -v ON_ERROR_STOP=1 <<SQL
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$SEED_DB' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS $SEED_DB;
SQL

echo "✅ 测试库 $SEED_DB 已彻底删除。开发主库 meiyun_core 未受影响。"
