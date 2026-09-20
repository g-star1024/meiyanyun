# DELIVERY · P5-B78 · R04 客户复购与RFM分层接真批

> 批次编号：P5-B78
> 创建日期：2026-09-20
> 状态：✅ DONE
> feat commit：`9bdc92f`（5 files +123/-3）
> docs commit：卡0 DESIGN `481b83c`

---

## §1 现状与缺口

R04 客户复购与 RFM 分层报表原 422 提示「数据源待建」，前端常驻 gen--note 提示块。
txn-service 已有 `txn_order` 表与 `RfmCalculator`（读时计算 R/F/M 三值→八象限分层），但无内部聚合端点供 finance-service 跨域取数。

**本批闭合**：
- txn-service `InternalFinanceController` 新建 `rfm-report` 端点（`GET /api/txn/internal/rfm-report?from=&to=`），权限复用 `internal:finance-flow`
- 按 customer_id 分组当月已收款订单 → RfmCalculator.calc() 获取 segment → 按 storeCode × segment 聚合
- finance-service `FinanceAggregationService` 新增 `fetchRfmStats`（RestTemplate 跨域取数，软降级空集合）
- `ReportDataCollector` 新增 `case "R04"` + `collectR04`（表头：门店/客户分层/客户数/复购率(%)/客单价(元)/LTV(元)）
- `ReportService` SUPPORTED 扩展含 R04
- 前端 `m1Report.ts` SUPPORTED_IDS 加 `'R04'`

## §2 口径定义

| 指标 | 口径 |
|------|------|
| 客户分层 | RfmCalculator 八象限（重要价值/发展/保持/挽留 × 一般价值/发展/保持/挽留） |
| 客户数 | 该 storeCode × segment 当月已收款去重 customer_id COUNT |
| 复购率 | repurchaseCount / customerCount × 100（repurchaseCount = 当月订单数 ≥ 2 的客户数） |
| 客单价 | totalAmountFen / orderCount（分转元，保留2位小数） |
| LTV | totalAmountFen / customerCount（分转元，保留2位小数） |

**LTV 简化口径**：从 txn_order 全量已收款 SUM(amount) per customer 聚合，不跨 customer-service，完全在 txn-service 内闭环。

权限复用 `internal:finance-flow`（与 R07/R08 同码），不新增权限码。

## §3 不动项

- R01/R02/R03/R05/R07/R08/R09 数据收集逻辑零改动
- ReportXlsxBuilder / ReportPdfBuilder / ReportCsvBuilder 三种 Builder 零改动
- 零新表 / 零 Flyway / 零新权限码 / 零网关改动
- 零新页面 / 零新路由 / 零新增 CSS

## §4 不做项

- R06 应收账款账龄（后续批 B79）
- 跨 customer-service 的完整 LTV（含非交易域消费记录）
- RFM 三值落库（保持读时计算，不落库）
- DataScope 门店域收敛（报表层统一不收窄）

## §5 施工清单

| 文件 | 变更 |
|------|------|
| txn-service InternalFinanceController.java | +RfmCalculator 注入 +rfm-report 端点 ~56 行 |
| finance-service FinanceAggregationService.java | +fetchRfmStats ~26 行 |
| finance-service ReportDataCollector.java | +case "R04" +collectR04 ~39 行 |
| finance-service ReportService.java | SUPPORTED 加 "R04" |
| frontend m1Report.ts | SUPPORTED_IDS 加 'R04' |

合计 5 files +123/-3

## §6 三轨真验计划

1. **curl 轨**：`GET /api/finance/reports/R04?period=MONTH&from=...&to=...` 经网关 8443，验证门店×客户分层六列统计 JSON
2. **PG 轨**：`SELECT store_code, customer_id, COUNT(*) FROM txn_order WHERE status='PAID' GROUP BY 1,2` 与 API 输出交叉勾稽
3. **Chrome 轨**：M1 报表页选 R04 + MONTH 周期，验证表格渲染六列表头＋数据行

## §7 数字影响

- 纵深批记账：✅109 / 🔧1 / ⬜55 = 166 **一律不动**
- 0 新页面 / 0 新表 / 0 新权限码
- 04-backlog L137 R04 接真注记，剩余 R06 一模板待接真（B79）
