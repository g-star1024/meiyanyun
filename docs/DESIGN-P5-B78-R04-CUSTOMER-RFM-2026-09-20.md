# DESIGN · P5-B78 · R04 客户复购与 RFM 分层接真

> 日期：2026-09-20 ｜ 状态：卡0 定案（只读侦察完成，待卡1 施工）
> 铁律：零新表 / 零 Flyway / 零新权限码 / 零新页面 / 零新路由 / 零新增 CSS
> 数字约束：✅109 / 🔧1 / ⬜55 = 166 一律不动

---

## §1 现状与缺口

### R04 模板定义（种子数据 ReportDataInitializer L66-67）

| 字段 | 值 |
|---|---|
| id | R04 |
| name | 客户复购与 RFM 分层 |
| category | CUSTOMER |
| period | MONTH |
| dimensions | 门店,客户分层 |
| metrics | 复购率,客单价,LTV |

### 当前状态

- ReportService.SUPPORTED = `Set.of("R01","R02","R03","R05","R07","R08","R09")`，**不含 R04**
- ReportDataCollector.collect() switch 无 `case "R04"` 分支
- 前端 m1Report.ts SUPPORTED_IDS 不含 R04
- 预览/生成 R04 → 422「该模板数据源待建，已登记 backlog」

### 已有基础设施（txn-service）

| 组件 | 路径 | 可复用内容 |
|---|---|---|
| RfmCalculator | txn-service | 完整 RFM 评分（R/F/M 各 1-5 分）＋八象限分层（重要价值/发展/保持/挽留 × 一般价值/发展/保持/挽留）＋生命周期（新客/活跃/沉默/沉睡/流失） |
| InternalRepurchaseController | /api/txn/internal/repurchase-candidates | 已输出逐客户 RFM 信号（rScore/fScore/mScore/segment/lifecycle/recencyDays/freq365/monetary365Yuan），但 limit 上限 20 不适合报表聚合 |
| TxnOrderRepository | txn-service | findByStatusOrderByCreatedAtDesc / findByStoreCodeAndStatusOrderByCreatedAtDesc |
| Customer 表 | customer-service | total_spend（累计消费）、visit_count（到店次数）、status（活跃/沉睡/流失）、level（会员等级） |

### 缺口

1. txn-service 无按「门店 × RFM 分层」聚合的报表端点
2. finance-service 无 fetchRfmStats 跨域取数方法
3. ReportDataCollector 无 collectR04
4. ReportService.SUPPORTED / 前端 SUPPORTED_IDS 不含 R04

---

## §2 口径定义

### 报表表头（6 列）

| 列 | 口径 | 数据来源 |
|---|---|---|
| 门店 | 门店中文名称 | resolveStoreNames 跨域解析 |
| 客户分层 | RFM 八象限分层（RfmCalculator.segment()） | txn_order 已收款订单读时计算 |
| 客户数 | 当月有消费的去重客户数 | COUNT(DISTINCT customer_id) WHERE status='已收款' AND 月内 |
| 复购率(%) | 当月 2+ 笔已收款订单的客户数 / 当月总消费客户数 × 100 | 同上月内订单聚合 |
| 客单价(元) | 当月总成交金额（分→元）/ 当月总订单数 | txn_order.amount（bigint 存分） |
| LTV(元) | 当月该分层客户 total_spend 均值（customer 表） | customer-service total_spend 字段 |

### RFM 分层口径（复用 RfmCalculator 既有逻辑，零修改）

- R（Recency）：最近消费距今天数 ≤30/90/180/365/>365 → 5/4/3/2/1 分
- F（Frequency）：近 365 天成交次数 ≥8/6/4/2/<2 → 5/4/3/2/1 分
- M（Monetary）：近 365 天消费金额（元）≥15000/8000/4000/1000/<1000 → 5/4/3/2/1 分
- 八象限：R≥4 为「近期」、F≥4 为「高」、M≥4 为「高」，2³=8 组合

### 复购率口径

- 当月（MONTH 周期 from→to）内 status='已收款' 的订单
- 按 customer_id 分组，订单数 ≥ 2 的客户视为「复购客户」
- 复购率 = 复购客户数 / 总消费客户数 × 100

### LTV 口径

- customer 表 total_spend 字段（decimal 12,2 元）
- 按当月该分层的客户集合取 AVG(total_spend)
- 需跨域调 customer-service 取 customer list（storeCode + customerId → totalSpend）

### 权限复用

- 新端点复用 `internal:finance-flow` 权限码（与既有 repurchase-candidates / refund-summary 一致）
- 前端不新增任何权限码

---

## §3 不动项

- R01/R02/R03/R05/R07/R08/R09 七模板 collect 方法零改动
- ReportCsvBuilder / ReportXlsxBuilder / ReportPdfBuilder 三种 Builder 零改动
- RfmCalculator 零改动（完全复用既有评分逻辑）
- InternalRepurchaseController 零改动（既有端点不动）
- 零新表 / 零 Flyway 迁移 / 零新权限码 / 零网关改动 / 零新页面 / 零新路由

---

## §4 不做项

- R06 应收账款账龄（B79 后续批）
- AI 复购预测结果聚合（ai_repurchase_prediction 表不参与，保持 AI 侧独立）
- 客户下钻明细（报表仅聚合层，不展开到单客户）
- RFM 评分规则调整（阈值沿用既有 RfmCalculator）
- customer-service 新增内部端点——LTV 改为从 txn_order 历史订单聚合（SUM amount per customer 全部已收款），避免新增跨域依赖

### LTV 口径简化决策

经侦察，customer.total_spend 虽已落表但需跨 customer-service 取数（finance-service 当前无 customerBaseUrl，需新增 @Value + fetch 方法）。为控制本批范围：

- **LTV 简化为「当月该分层客户的历史累计消费均值」**——从 txn_order 全量已收款订单按 customer_id SUM(amount) 得到每客户 LTV，再按分层 AVG
- 这样完全在 txn-service 内闭环，零新增跨域依赖
- 与 customer.total_spend 的差异：txn_order 仅含本平台订单（不含手工调整），但口径一致且可审计

---

## §5 施工清单（预估）

### txn-service（3 files）

1. **TxnOrderRepository.java**：新增 `findByStatusAndCreatedAtBetween` 或 JPQL 聚合查询
   - 查当月全部已收款订单（按 customer_id 分组，含每客户订单数/总金额/全部历史订单用于 LTV）
2. **InternalFinanceController.java**（或新建 InternalRfmController）：新增 `GET /api/txn/internal/rfm-report?from=&to=`
   - 权限 `@RequirePerm("internal:finance-flow")`
   - 返回 List<RfmReportRow>（storeCode, segment, customerCount, repurchaseCount, repurchaseRate, avgTicketFen, ltvFen）
3. **RfmCalculator 复用**：对每客户调用 calc() 获取 segment，然后按 storeCode × segment 聚合

### finance-service（3 files）

4. **FinanceAggregationService.java**：新增 `fetchRfmStats(from, to)`
   - RestTemplate 调 `/api/txn/internal/rfm-report?from=&to=`
   - 软降级返回空集合
5. **ReportDataCollector.java**：新增 `case "R04" -> collectR04(period)` + `collectR04(String month)`
   - 表头：门店, 客户分层, 客户数, 复购率(%), 客单价(元), LTV(元)
6. **ReportService.java**：SUPPORTED 加 "R04"

### frontend（1 file）

7. **m1Report.ts**：SUPPORTED_IDS 加 'R04'

### 预估：7 files, ~+160/-3

---

## §6 三轨真验计划

### 轨1 curl

```bash
# txn-service 内部端点
curl -H "X-Internal-Token: ..." "http://localhost:8083/api/txn/internal/rfm-report?from=2026-09-01&to=2026-10-01"
# 预期：按门店×八象限分层返回聚合行

# finance 报表预览
curl -H "Authorization: Bearer ..." "http://localhost:8443/api/finance/reports/R04/preview?period=2026-09"
# 预期：6 列表头＋真实数据行
```

### 轨2 PG

```sql
-- 验证 R04 聚合口径
SELECT o.store_code, COUNT(DISTINCT o.customer_id) AS customers,
       SUM(CASE WHEN cnt >= 2 THEN 1 ELSE 0 END) AS repurchase_customers
FROM (SELECT store_code, customer_id, COUNT(*) AS cnt FROM txn_order WHERE status='已收款' AND created_at >= '2026-09-01' AND created_at < '2026-10-01' GROUP BY store_code, customer_id) o
GROUP BY o.store_code;
```

### 轨3 Chrome

- M1 报表页 → R04 卡片「预览」按钮可点击（非灰禁用）
- 渲染 6 列表头，数据行按门店×分层展示
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
