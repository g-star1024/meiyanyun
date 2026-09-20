> **批次**：P5-B73（M1 纵深批第二卡）
> **日期**：2026-09-20
> **主题**：R07 退款与纠纷台账接真——422 占位→TxnRefund 真实数据，按门店×退款原因聚合
> **commit**：feat `01b71bc`（4 files +89/-1）
> **数字**：✅109/🔧1/⬜55=166 不变

---

## §1 现状与缺口

R07 退款与纠纷台账此前与 R01-R09 其余六模板一样停留在 422 占位阶段——ReportDataCollector 返回空列表，前端 gen--note 提示块显示"数据源待建"。

缺口分析：
- txn-service 已有 TxnRefund 实体（storeCode/reason/refundAmt/status/createdAt/refundedAt/channel/customerName），字段完美匹配 R07 聚合需求
- finance-service 缺少跨域取数方法（fetchRefundSummary）
- ReportDataCollector 缺少 collectR07 分支
- ReportService.SUPPORTED 未包含 R07

## §2 口径定义

### 2.1 新增端点

`GET /api/txn/internal/refund-summary?month=yyyy-MM`
- 按 createdAt UTC 月界查 TxnRefund
- Java 层 GROUP BY (storeCode, reason)
- 输出 `[{storeCode, reason, count, totalRefundAmt, avgProcessingDays}]`
- 仅 internal 调用（X-Internal-Token 校验）

### 2.2 新增聚合方法

FinanceAggregationService.fetchRefundSummary(String month)
- RestTemplate + UriComponentsBuilder 调 txn-service internal 端点
- MAP_TYPE / LIST_MAP_TYPE 反序列化范式

### 2.3 collectR07 扩展

ReportDataCollector 新增 `case "R07" -> collectR07(period)`
- 调用 fetchRefundSummary 获取退款汇总数据
- 返回 List<Map<String, Object>> 供 Builder 渲染

### 2.4 SUPPORTED 扩展

ReportService.SUPPORTED = Set.of("R01", "R02", "R07")

## §3 不动项

- R01/R02/R03/R04/R05/R06/R08/R09 零改动
- ReportXlsxBuilder / ReportPdfBuilder / ReportBuilder 零改动
- 前端零改动（gen--note 提示块由 SUPPORTED 集合自动解除）
- revenue_monthly 表结构零改动
- 零新表 / 零 Flyway / 零新权限码 / 零新页面 / 零新路由 / 零新增 CSS

## §4 不做项

- R03/R04/R05/R06/R08/R09 接真（分卡 B74-B79）
- 退款原因标准化（reason 原值透传）
- 纠纷工单流程
- 月度趋势图
- 渠道维度聚合
- 明细行导出

## §5 施工清单

| # | 文件 | 改动 |
|---|------|------|
| 1 | txn-service InternalFinanceController.java | +50 refund-summary 端点 |
| 2 | finance-service FinanceAggregationService.java | +14 fetchRefundSummary |
| 3 | finance-service ReportDataCollector.java | +24 collectR07 + switch case "R07" |
| 4 | finance-service ReportService.java | +1/-1 SUPPORTED 扩展含 R07 |

合计 4 files +89/-1

## §6 三轨真验计划

### 6.1 curl 轨

```bash
curl -H "X-Internal-Token: ${TOKEN}" "http://localhost:18083/api/txn/internal/refund-summary?month=2026-09"
```

### 6.2 PG 轨

```sql
SELECT store_code, reason, COUNT(*), SUM(refund_amt), AVG(EXTRACT(EPOCH FROM (refunded_at - created_at))/86400)
FROM txn_refund WHERE created_at >= '2026-09-01' AND created_at < '2026-10-01'
GROUP BY store_code, reason;
```

### 6.3 Chrome 轨

前端报表页选 R07 → 下载 XLSX → 验证数据与 PG 一致

## §7 数字影响

| 指标 | 值 |
|------|-----|
| ✅ 已落地 | 109 不变 |
| 🔧 远期 | 1 不变 |
| ⬜ 待建 | 55 不变 |
| 合计 | 166 不变 |

M1 集团管控 17 条目：10✅ / 0⬜ / 7远期，不变。
报表 9 模板有真实数据能力：R01 / R02 / R07（+1）。
