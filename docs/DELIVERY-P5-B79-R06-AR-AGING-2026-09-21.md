# DELIVERY · P5-B79 · R06 应收账款账龄分析接真批

> 批次编号：P5-B79
> 创建日期：2026-09-21
> 状态：✅ DONE
> feat commit：`e329bbb`（5 files +103/-2）
> docs commit：卡0 DESIGN `ffacbd3`

---

## §1 现状与缺口

R06 应收账款账龄分析报表原 422 提示「数据源待建」，前端常驻 gen--note 提示块。
txn-service `txn_order` 表已有 `originalAmount`（折前应收合计，bigint 分）、`amount`（实收金额）、`status` 五态（待签核/待收款/已收款/已核销/已取消）、`createdAt` 时间戳，但无内部聚合端点供 finance-service 跨域取数。

**本批闭合**：
- txn-service `InternalFinanceController` 新建 `aging-report` 端点（`GET /api/txn/internal/aging-report`），权限复用 `internal:finance-flow`
- 按 status='待收款' 全量订单 → 按 storeCode 分组 → 计算每笔账龄（Asia/Shanghai today - createdAt 天数）→ 分入四档 0-30/31-60/61-90/90+
- finance-service `FinanceAggregationService` 新增 `fetchAgingStats`（RestTemplate 跨域取数，软降级空列表）
- `ReportDataCollector` 新增 `case "R06"` + `collectR06`（表头：门店/应收余额(元)/0-30天(元)/31-60天(元)/61-90天(元)/逾期金额(元)/逾期率(%)）
- `ReportService` SUPPORTED 扩展含 R06（R01-R09 九模板全部注册）
- 前端 `m1Report.ts` SUPPORTED_IDS 加 `'R06'`

## §2 口径定义

| 指标 | 口径 |
|------|------|
| 应收账款 | status='待收款' 的订单，应收余额 = originalAmount ?? amount（分） |
| 账龄 | 当前日期（Asia/Shanghai）- createdAt LocalDate 的天数 |
| 四档分桶 | 0-30 天 / 31-60 天 / 61-90 天 / 90+ 天（逾期） |
| 逾期率 | overdueFen / receivableFen × 100（保留 1 位小数，应收为 0 显 "—"） |

权限复用 `internal:finance-flow`（与 R04/R07/R08 同码），不新增权限码。

## §3 不动项

- R01-R05/R07-R09 数据收集逻辑零改动
- ReportXlsxBuilder / ReportPdfBuilder / ReportCsvBuilder 三种 Builder 零改动
- 零新表 / 零 Flyway / 零新权限码 / 零网关改动
- 零新页面 / 零新路由 / 零新增 CSS

## §4 不做项

- 应收账款独立台账表（保持读时聚合，不落库）
- 账龄快照定时归档（随订单状态实时计算）
- DataScope 门店域收敛（报表层统一不收窄）

## §5 施工清单

| 文件 | 变更 |
|------|------|
| txn-service InternalFinanceController.java | +aging-report 端点 ~48 行 |
| finance-service FinanceAggregationService.java | +fetchAgingStats ~24 行 |
| finance-service ReportDataCollector.java | +case "R06" +collectR06 ~29 行 |
| finance-service ReportService.java | SUPPORTED 加 "R06" |
| frontend m1Report.ts | SUPPORTED_IDS 加 'R06' |

合计 5 files +103/-2

## §6 三轨真验计划

1. **curl 轨**：`GET /api/finance/reports/R06?period=MONTH&from=...&to=...` 经网关 8443，验证门店×四档账龄七列统计 JSON
2. **PG 轨**：`SELECT store_code, original_amount, created_at FROM txn_order WHERE status='待收款'` 与 API 输出交叉勾稽
3. **Chrome 轨**：M1 报表页选 R06 + MONTH 周期，验证表格渲染七列表头＋数据行

## §7 数字影响

- 纵深批记账：✅109 / 🔧1 / ⬜55 = 166 **一律不动**
- 0 新页面 / 0 新表 / 0 新权限码
- **R03-R09 七模板全部接真完成**（B73 R07 → B74 R05 → B75 R09 → B76 R03 → B77 R08 → B78 R04 → B79 R06）
- 04-backlog L137 R03-R09 整行勾销
