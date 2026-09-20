# DESIGN · P5-B79 · R06 应收账款账龄分析接真

> 日期：2026-09-21 ｜ 状态：卡0 定案（只读侦察完成，待卡1 施工）
> 铁律：零新表 / 零 Flyway / 零新权限码 / 零新页面 / 零新路由 / 零新增 CSS
> 数字约束：✅109 / 🔧1 / ⬜55 = 166 一律不动

---

## §1 现状与缺口

### R06 模板定义（种子数据 ReportDataInitializer L70-71）

| 字段 | 值 |
|---|---|
| id | R06 |
| name | 应收账款账龄分析 |
| category | FINANCE |
| period | MONTH |
| dimensions | 门店,账龄区间 |
| metrics | 应收余额,逾期金额,逾期率 |
| subscribed | true |

### 当前状态

- ReportService.SUPPORTED = `Set.of("R01","R02","R03","R04","R05","R07","R08","R09")`，**不含 R06**
- ReportDataCollector.collect() switch 无 `case "R06"` 分支
- 前端 m1Report.ts SUPPORTED_IDS = `Set('R01','R02','R03','R04','R08')`，不含 R06
- 预览/生成 R06 → 422「该模板数据源待建，已登记 backlog」

### 已有基础设施（txn-service）

| 组件 | 路径 | 可复用内容 |
|---|---|---|
| TxnOrder 实体 | txn-service | `status`（待签核/待收款/已收款/已核销/已取消）、`originalAmount`（折前应收合计 bigint 分）、`storeCode`、`createdAt`（OffsetDateTime） |
| TxnOrderRepository | txn-service | findByStatusOrderByCreatedAtDesc / findByStoreCodeAndStatusOrderByCreatedAtDesc |
| InternalFinanceController | /api/txn/internal/* | 已有 finance-flows / writeoff-details / cash-settle / commission-base / refund-summary / rfm-report / writeoff-reconcile / writeoff-backfill / abnormal-approvals 九个内部端点，权限 `@RequirePerm("internal:finance-flow")` |
| FinanceAggregationService | finance-service | 已有 fetchFlows / fetchCashSettle / fetchCards / fetchCardLedger / fetchWriteoffDetails / fetchFunnelStats / fetchRfmStats / fetchRefundSummary / fetchComplianceStats / fetchCommissionBase 十个跨域取数方法，RestTemplate + try-catch 降级模式 |

### 缺口

1. txn-service 无按「门店 × 账龄区间」聚合的应收账龄报表端点
2. finance-service 无 fetchAgingStats 跨域取数方法
3. ReportDataCollector 无 collectR06
4. ReportService.SUPPORTED / 前端 SUPPORTED_IDS 不含 R06

---

## §2 口径定义

### 应收账款定义

- **无独立应收账款表**——遵循铁律「派生分析数据读时实时计算、不建表」
- 应收账款 = `txn_order` 表 `status='待收款'` 的订单（已开单但尚未收款）
- 应收余额 = `originalAmount`（折前应收合计，bigint 分）；`originalAmount` 为空时回落 `amount`
- 账龄 = 当前日期（Asia/Shanghai） − `createdAt`  LocalDate 的天数

### 账龄分档（四档）

| 区间 | 天数范围 | 含义 |
|---|---|---|
| 0-30天 | 0 ≤ age ≤ 30 | 正常应收 |
| 31-60天 | 31 ≤ age ≤ 60 | 关注 |
| 61-90天 | 61 ≤ age ≤ 90 | 预警 |
| 90天以上 | age > 90 | 逾期（坏账风险） |

### 逾期定义

- **逾期 = 账龄 > 90 天的待收款订单**（行业惯例：90 天以上应收视为逾期/坏账风险）
- 逾期金额 = 90+ 天档的应收余额合计
- 逾期率(%) = 逾期金额 / 该门店全部应收余额 × 100

### 报表表头（7 列）

| 列 | 口径 | 数据来源 |
|---|---|---|
| 门店 | 门店中文名称 | resolveStoreNames 跨域解析 |
| 应收余额(元) | 该门店全部待收款订单 originalAmount 合计（分→元） | txn_order status='待收款' |
| 0-30天(元) | 账龄 0-30 天的应收余额（分→元） | 按 createdAt 计算 |
| 31-60天(元) | 账龄 31-60 天的应收余额（分→元） | 同上 |
| 61-90天(元) | 账龄 61-90 天的应收余额（分→元） | 同上 |
| 逾期金额(元) | 账龄 > 90 天的应收余额（分→元） | 同上 |
| 逾期率(%) | 逾期金额 / 应收余额 × 100 | 计算列 |

### 报表行结构

- 每行 = 一个门店（按门店码升序）
- 无待收款订单的门店不出现在报表中
- DataScope.canReadStore 收敛（集团管控报表按可见门店过滤）

### 权限复用

- 新端点复用 `internal:finance-flow` 权限码（与既有 rfm-report / refund-summary 一致）
- 前端不新增任何权限码

---

## §3 不动项

- R01/R02/R03/R04/R05/R07/R08/R09 八模板 collect 方法零改动
- ReportCsvBuilder / ReportXlsxBuilder / ReportPdfBuilder 三种 Builder 零改动
- TxnOrder 实体零改动（不新增字段/列）
- TxnOrderRepository 零改动（新增查询在 Controller 内用既有 findByStatus 或 JPQL）
- 零新表 / 零 Flyway 迁移 / 零新权限码 / 零网关改动 / 零新页面 / 零新路由

---

## §4 不做项

- 不建独立 AR 表 / 不建 aging 物化视图（读时实时聚合，符合铁律）
- 不做逾期催收工作流（属 M2 门店运营平台远期）
- 不做坏账拨备计算（模板名提及但 metrics 仅三列，拨备留远期）
- 不做按客户维度的应收下钻（报表仅门店聚合层）
- 不新增 customer-service 跨域调用（完全在 txn-service 内闭环）
- 不调整 R06 模板定义（种子数据 ReportDataInitializer 不动）

---

## §5 施工清单（预估）

### txn-service（1 file）

1. **InternalFinanceController.java**：新增 `GET /api/txn/internal/aging-report`
   - 权限 `@RequirePerm("internal:finance-flow")`
   - 查 txn_order status='待收款' 全部订单
   - 按 storeCode 分组，每组计算四档账龄金额
   - 返回 List<Map<String, Object>>（storeCode / totalFen / bucket0_30 / bucket31_60 / bucket61_90 / bucket90Plus / overdueFen / overdueRate）

### finance-service（3 files）

2. **FinanceAggregationService.java**：新增 `fetchAgingStats()`
   - RestTemplate 调 `/api/txn/internal/aging-report`
   - 软降级返回空集合
3. **ReportDataCollector.java**：新增 `case "R06" -> collectR06(period)` + `collectR06(String month)`
   - 调 fetchAgingStats() → 解析门店名 → 组装 7 列表头 + 数据行
   - 表头：门店, 应收余额(元), 0-30天(元), 31-60天(元), 61-90天(元), 逾期金额(元), 逾期率(%)
4. **ReportService.java**：SUPPORTED 加 "R06"

### frontend（1 file）

5. **m1Report.ts**：SUPPORTED_IDS 加 'R06'

### 预估：5 files, ~+100/-5

---

## §6 三轨真验计划

### 轨1 curl

```bash
# txn-service 内部端点
curl -H "X-Internal-Token: ..." "http://localhost:8083/api/txn/internal/aging-report"
# 预期：按门店返回账龄聚合行（totalFen/bucket0_30/.../overdueFen/overdueRate）

# finance 报表预览
curl -H "Authorization: Bearer ..." "http://localhost:8443/api/finance/reports/R06/preview?period=2026-09"
# 预期：7 列表头＋真实数据行
```

### 轨2 PG

```sql
-- 验证 R06 应收账龄口径
SELECT store_code,
       COUNT(*) AS pending_orders,
       SUM(COALESCE(original_amount, amount)) AS total_receivable_fen,
       SUM(CASE WHEN CURRENT_DATE - created_at::date <= 30 THEN COALESCE(original_amount, amount) ELSE 0 END) AS bucket_0_30,
       SUM(CASE WHEN CURRENT_DATE - created_at::date BETWEEN 31 AND 60 THEN COALESCE(original_amount, amount) ELSE 0 END) AS bucket_31_60,
       SUM(CASE WHEN CURRENT_DATE - created_at::date BETWEEN 61 AND 90 THEN COALESCE(original_amount, amount) ELSE 0 END) AS bucket_61_90,
       SUM(CASE WHEN CURRENT_DATE - created_at::date > 90 THEN COALESCE(original_amount, amount) ELSE 0 END) AS bucket_90_plus
FROM txn_order
WHERE status = '待收款'
GROUP BY store_code
ORDER BY store_code;
```

### 轨3 Chrome

- M1 报表页 → R06 卡片「预览」按钮可点击（非灰禁用）
- 渲染 7 列表头，数据行按门店展示
- 导出 XLSX/PDF 正常

---

## §7 数字影响

| 指标 | 值 |
|---|---|
| ✅ 已勾销 | 109（不变） |
| 🔧 进行中 | 1（不变） |
| ⬜ 待建 | 55（不变） |
| 合计 | 166（不变） |
| 新页面 | 0 |
| 新表 | 0 |
| 新权限码 | 0 |
| 新路由 | 0 |
| 新 CSS | 0 |
| 新 Flyway | 0 |
