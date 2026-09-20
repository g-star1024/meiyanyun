# DELIVERY-P5-B71 M1 报表 XLSX·PDF 导出批

> 批次：P5-B71 | 类型：策略模式重构＋双格式导出 | 日期：2026-09-20
> DESIGN：[DESIGN-P5-B71-M1-REPORT-XLSX-PDF-2026-09-20](DESIGN-P5-B71-M1-REPORT-XLSX-PDF-2026-09-20.md)（233 行，commit `7273820`）

---

## §1 背景与口径

B71 属 C 阶段 M1 集团管控——M1 报表 XLSX·PDF 导出批。用户授权原文：「B. 产品口径/状态机/选型决策按照你推荐的来就可以，做好竞品调研选择最合适的就行；以上全部完成后转入 C. 大阶段/专项开工」，C 阶段用户拍板「按 M1、M2、M3 的顺序进行吧」。

B69 已选型 EasyExcel（XLSX，流式低内存）＋openPDF（PDF，LGPL 无 AGPL 风险），策略模式 ReportBuilder 扩展（ReportCsvBuilder 保持＋ReportXlsxBuilder＋ReportPdfBuilder），不引入 JasperReports 重依赖。本批实施该选型，完成策略模式重构与双格式导出。

**范围**：DESIGN 定案，零新表/零 Flyway/零新权限码/零新页面/零新路由/零新增 CSS。

**数字锁定**：✅109/🔧1/⬜55=166（约66%）一律不动，0 新页面。

---

## §2 影响面

### 后端（finance-service，11 文件）

| 文件 | 变更 | 说明 |
|------|------|------|
| pom.xml | +10/-0 | 新增 EasyExcel＋openPDF 依赖 |
| ReportBuilder.java | +6/-0 | 策略接口（buildReport 返回 ReportBuildResult） |
| ReportBuildResult.java | +3/-0 | 统一构建结果（byte[]＋contentType＋filename） |
| ReportBuilderRegistry.java | +27/-0 | Spring 自动收集所有 ReportBuilder 实现，按 format 路由 |
| ReportDataCollector.java | +121/-0 | 报表数据收集器，封装 R01/R02 数据查询逻辑 |
| ReportXlsxBuilder.java | +39/-0 | EasyExcel XLSX 构建器 |
| ReportPdfBuilder.java | +68/-0 | openPDF PDF 构建器 |
| ReportCsvBuilder.java | +29/-152 | 重构：实现 ReportBuilder 接口，删旧内联逻辑 |
| ReportService.java | +16/-56 | 瘦身：委托 Registry 路由，删旧格式硬编码 |
| ReportController.java | +3/-19 | 瘦身：接受 format 参数，委托 Service |
| ReportAsyncRunner.java | +12/-25 | 适配新 ReportBuildResult 返回类型 |

### 前端（1 文件）

| 文件 | 变更 | 说明 |
|------|------|------|
| M1ReportView.vue | +1/-2 | 启用 XLSX/PDF 导出按钮 |

### 不涉及

- 网关零改（/api/finance 前缀整段透传）
- 零新表/零 Flyway/零新权限码
- 零新页面/零新路由/零新增 CSS
- 同服务 JVM 直调不新增 internal HTTP

---

## §3 代码 numstat 权威表

### feat commit `d118952`（12 files, +335/-254）

```
10      0       backend/finance-service/pom.xml
12      25      backend/finance-service/.../ReportAsyncRunner.java
3       0       backend/finance-service/.../ReportBuildResult.java
6       0       backend/finance-service/.../ReportBuilder.java
27      0       backend/finance-service/.../ReportBuilderRegistry.java
3       19      backend/finance-service/.../ReportController.java
29      152     backend/finance-service/.../ReportCsvBuilder.java
121     0       backend/finance-service/.../ReportDataCollector.java
68      0       backend/finance-service/.../ReportPdfBuilder.java
16      56      backend/finance-service/.../ReportService.java
39      0       backend/finance-service/.../ReportXlsxBuilder.java
1       2       frontend/src/views/M1ReportView.vue
```

### 合计（12 files, +335/-254）

---

## §4 编译验证

| 轨道 | 结果 | 详情 |
|------|------|------|
| 后端编译 | ✅ OK | mvn compile 通过 |

---

## §5 如实说明

1. **策略模式重构**：ReportBuilder 接口统一 buildReport() 返回 ReportBuildResult（byte[]＋contentType＋filename），ReportBuilderRegistry 通过 Spring 自动收集所有 ReportBuilder 实现按 format 路由，新增格式只需实现接口＋@Component 即可自动注册。
2. **EasyExcel XLSX**：流式写入低内存，ReportXlsxBuilder 复用 ReportDataCollector 数据源，Content-Type `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`。
3. **openPDF PDF**：LGPL 无 AGPL 风险，ReportPdfBuilder 复用 ReportDataCollector 数据源，Content-Type `application/pdf`。
4. **ReportCsvBuilder 重构**：从原有内联逻辑瘦身为实现 ReportBuilder 接口，删 152 行冗余代码，CSV 行为不变。
5. **后续 M1 批次**：R03-R09 七模板接真（L137）/R02 维度扩展（L139）属后续独立批，本批仅 R01/R02 两模板具备 XLSX/PDF 导出能力。

---

## §6 五落点

| 落点 | 文件 | 状态 |
|------|------|------|
| 施工简报 | 00-history.md L6 上方 | ✅ |
| 六列批次行 | 03-timeline.md B71 行 | ✅ |
| 注记勾销 | 04-backlog.md L138 勾销 | ✅ |
| 哨兵更新 | HANDOFF-AUTO.md L6-L10 | ✅ |
| 01 数字 | 01-dashboard.md | 不动（✅109/🔧1/⬜55=166） |

---

## §7 下一批

B71 闭合后，M1 集团管控后续候选：
- **B72 R02 维度扩展**（L139）：revenue_monthly 维度字段补齐需月结流程先落地
- **B73+ R03-R09 七模板逐一接真**（L137）：各域业务表/聚合服务未就绪

M1 全部完成后转 **M2 门店运营平台**（排班已落地 B54，工单/日结/申购/报损/绩效等 13 页）。
