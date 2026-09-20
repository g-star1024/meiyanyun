# DELIVERY · P5-B77 · R08 合规检查月报接真批

> 批次编号：P5-B77
> 创建日期：2026-09-20
> 状态：✅ DONE
> feat commit：`7cd8b74`（6 files +141/-2）
> docs commit：卡0 DESIGN `627d145`

---

## §1 现状与缺口

R08 合规检查月报原 422 提示「数据源待建」，前端常驻 gen--note 提示块。
audit-service 已有 `compliance_check` 表（六类检查项×四态），但无内部聚合端点供 finance-service 跨域取数。

**本批闭合**：
- audit-service 新建 `InternalComplianceController`（`/api/audit/internal/compliance-stats`），权限复用 `internal:finance-flow`
- `ComplianceCheckRepository` 新增 `complianceStats` 聚合查询（六类×四态 GROUP BY storeName+category，含整改率启发式）
- finance-service `FinanceAggregationService` 新增 `auditBaseUrl` @Value + `fetchComplianceStats`（RestTemplate 跨域取数，软降级空集合）
- `ReportDataCollector` 新增 `case "R08"` + `collectR08`（表头：门店/合规项/检查总数/通过数/通过率%/问题数/整改率%）
- `ReportService` SUPPORTED 扩展含 R08
- 前端 `m1Report.ts` SUPPORTED_IDS 加 `'R08'`

## §2 口径定义

| 指标 | 口径 |
|------|------|
| 检查总数 | compliance_check 按 storeName+category COUNT |
| 通过数 | status='PASS' 的 COUNT |
| 通过率 | 通过数/检查总数×100，保留1位小数 |
| 问题数 | status='FAIL' 的 COUNT |
| 整改率 | status='PASS' AND updatedAt > createdAt 的 COUNT / 检查总数×100（启发式：经复检从非PASS转为PASS） |

六类中文映射：QUALIFICATION=资质证照 / CONSENT=知情同意 / DRUG_TRACE=药品溯源 / PRIVACY=隐私合规 / AD=医疗广告 / INFECTION=院感管理

权限复用 `internal:finance-flow`（与 R07 refund-summary 同码），不新增权限码。

## §3 不动项

- R01/R02/R03/R05/R07/R09 数据收集逻辑零改动
- ReportXlsxBuilder / ReportPdfBuilder / ReportCsvBuilder 三种 Builder 零改动
- 零新表 / 零 Flyway / 零新权限码 / 零网关改动
- 零新页面 / 零新路由 / 零新增 CSS

## §4 不做项

- R04 客户复购与 RFM / R06 应收账款账龄（后续批 B78-B79）
- 合规趋势图（折线跨月）
- 合规项下钻（单门店明细）
- DataScope 门店域收敛（报表层统一不收窄）

## §5 施工清单

| 文件 | 变更 |
|------|------|
| audit-service ComplianceCheckRepository.java | +complianceStats 聚合查询 |
| audit-service InternalComplianceController.java | **新建** ~55 行 compliance-stats 端点 |
| finance-service FinanceAggregationService.java | +auditBaseUrl +fetchComplianceStats ~29 行 |
| finance-service ReportDataCollector.java | +case "R08" +collectR08 ~39 行 |
| finance-service ReportService.java | SUPPORTED 加 "R08" |
| frontend m1Report.ts | SUPPORTED_IDS 加 'R08' |

合计 6 files +141/-2

## §6 三轨真验计划

1. **curl 轨**：`GET /api/finance/reports/R08?period=MONTH&from=...&to=...` 经网关 8443，验证六类×门店合规统计 JSON
2. **PG 轨**：`SELECT store_name, category, status, COUNT(*) FROM compliance_check GROUP BY 1,2,3` 与 API 输出交叉勾稽
3. **Chrome 轨**：M1 报表页选 R08 + MONTH 周期，验证表格渲染六列表头＋数据行

## §7 数字影响

- 纵深批记账：✅109 / 🔧1 / ⬜55 = 166 **一律不动**
- 0 新页面 / 0 新表 / 0 新权限码
- 04-backlog L137 R08 接真注记，剩余 R04/R06 两模板待接真（B78-B79）
