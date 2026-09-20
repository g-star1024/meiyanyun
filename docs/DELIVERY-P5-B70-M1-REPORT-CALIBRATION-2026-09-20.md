# DELIVERY-P5-B70 M1 报表口径微调批

> 批次：P5-B70 | 类型：B 类 bug 修复＋台账勾销 | 日期：2026-09-20
> DESIGN：[DESIGN-P5-B70-M1-REPORT-CALIBRATION-2026-09-20](DESIGN-P5-B70-M1-REPORT-CALIBRATION-2026-09-20.md)（131 行，commit `d95fec6`）

---

## §1 背景与口径

B70 属 C 阶段 M1 集团管控首批——M1 报表口径微调批。用户授权原文：「B. 产品口径/状态机/选型决策按照你推荐的来就可以，做好竞品调研选择最合适的就行；以上全部完成后转入 C. 大阶段/专项开工」，C 阶段用户拍板「按 M1、M2、M3 的顺序进行吧」。

B69 已施工 L132/L133（feat `363bb1d`＋fix `832a1fa`）但 04-backlog 未更新勾销标记；M1 盘点发现 L140（mapPayMethod transfer 映射缺失）与 L141（R02 毛利率%格式化不匹配）两个真实 bug 可一并收口。本批收窄为 **L132/L133 补勾销＋L140/L141 两个 bug 修复**。

**范围**：DESIGN §3 单卡批，零新表/零 Flyway/零新权限码/零新页面/零新路由/零新增 CSS。

**数字锁定**：✅109/🔧1/⬜55=166（约66%）一律不动，0 新页面。

---

## §2 影响面

### 后端（finance-service，4 文件）

| 文件 | 变更 | 说明 |
|------|------|------|
| FinanceAggregationService.java L537 | +1/-1 | mapPayMethod 加 `"transfer"` 分支 |
| TripartiteReconcileService.java L309 | +1/-1 | 同上 |
| FinanceInternalOpsService.java L274 | +1/-1 | 同上 |
| ReportCsvBuilder.java L6+L145 | +2/-1 | 新增 `import java.math.RoundingMode`＋毛利率 ×100＋`"%"` 百分比格式化 |

### 不涉及

- 网关零改（/api/finance 前缀整段透传）
- 零新表/零 Flyway/零新权限码
- 零新页面/零新路由/零新增 CSS
- 同服务 JVM 直调不新增 internal HTTP
- 前端零改

---

## §3 代码 numstat 权威表

### feat commit `0f06204`（4 files, +5/-4）

```
1       1       backend/finance-service/.../FinanceAggregationService.java
1       1       backend/finance-service/.../FinanceInternalOpsService.java
2       1       backend/finance-service/.../ReportCsvBuilder.java
1       1       backend/finance-service/.../TripartiteReconcileService.java
```

### 合计（4 files, +5/-4）

---

## §4 编译验证

| 轨道 | 结果 | 详情 |
|------|------|------|
| 后端编译 | ✅ OK | mvn compile 通过 |

本批为纯后端 bug 修复，无前端改动，无需 vue-tsc/单元测试全量回归。

---

## §5 如实说明

1. **三处 mapPayMethod 副本统一**：FinanceAggregationService/TripartiteReconcileService/FinanceInternalOpsService 各自独立维护的 `mapPayMethod` 方法均加 `"transfer" -> "transfer"` 分支，使 R01 银行转账不再归为"其他"（null→未标记渠道）。ReportCsvBuilder CHANNEL_CN 已有 `"transfer" -> "银行转账"` 映射，上游修复后自然命中。
2. **R02 毛利率格式化**：`grossRate` 在 RevenueMonthly 中为 `BigDecimal(6,3)` 存储小数（如 0.352），原输出 `0.352`，改后输出 `35.2%`，与表头 `毛利率(%)` 单位一致。
3. **L132/L133 补勾销**：B69 卡1 已施工（feat `363bb1d`＋fix `832a1fa`），但 04-backlog 未更新勾销标记，本批补台账标记，不改变已完成计数。
4. **数字未变**：✅109/🔧1/⬜55=166，L132/L133 勾销不改变已完成计数（B69 已施工），L140/L141 属既有 ✅ 模块纵深修复不加行。
5. **后续 M1 批次**：R03-R09 七模板接真（L137）/XLSX·PDF 导出（L138，B69 已选型 EasyExcel+openPDF）/R02 维度扩展（L139）属后续独立批。

---

## §6 五落点

| 落点 | 文件 | 状态 |
|------|------|------|
| 施工简报 | 00-history.md L6 上方 | ✅ |
| 六列批次行 | 03-timeline.md B70 行 | ✅ |
| 注记勾销 | 04-backlog.md L132/L133 补勾销＋L140/L141 勾销 | ✅ |
| 哨兵更新 | HANDOFF-AUTO.md L6-L10 | ✅ |
| 01 数字 | 01-dashboard.md | 不动（✅109/🔧1/⬜55=166） |

---

## §7 下一批

B70 闭合后，M1 集团管控后续候选：
- **B71 XLSX/PDF 导出**（L138）：B69 已选型 EasyExcel+openPDF，实施需新建 ReportBuilder 策略模式
- **B72 R02 维度扩展**（L139）：revenue_monthly 维度字段补齐需月结流程先落地
- **B73+ R03-R09 七模板逐一接真**（L137）：各域业务表/聚合服务未就绪

M1 全部完成后转 **M2 门店运营平台**（排班已落地 B54，工单/日结/申购/报损/绩效等 13 页）。
