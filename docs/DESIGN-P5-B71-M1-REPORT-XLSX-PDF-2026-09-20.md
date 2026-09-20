# DESIGN-P5-B71 M1 报表 XLSX·PDF 导出批

> **批次**：P5-B71 M1 报表 XLSX·PDF 导出批
> **类型**：B 类 功能扩展（策略模式＋新依赖＋前端启用）
> **创建**：2026-09-20
> **状态**：卡0 定案完成，待卡1 施工
> **前置**：B69 选型定案（EasyExcel 3.3.4 XLSX + openPDF 1.3.30 PDF，见 DESIGN-P5-B69 §0.3.5）

---

## §0 调研总述

### 0.1 现状盘点

| 层 | 现状 | 缺口 |
|----|------|------|
| DB schema | `report_job.format varchar(8)` 已存 CSV/XLSX/PDF；`content BYTEA` 格式无关 | **零缺口** |
| 后端生成 | `ReportCsvBuilder` 单 @Component，仅 R01/R02 | 无策略接口；无 XLSX/PDF 实现 |
| 后端分发 | `ReportService.generate()` L142-146 显式拒绝非 CSV（400） | 需放开 XLSX/PDF |
| 异步执行 | `ReportAsyncRunner.run()` 直调 `csvBuilder.build()` | 需按 format 分发 |
| 下载端点 | `ReportController.download()` 硬编码 `text/csv` + `.csv` | 需按 format 映射 Content-Type/扩展名 |
| 前端 UI | 格式选择器已迭代 `['CSV','XLSX','PDF']`，XLSX/PDF `disabled` | 仅需移除 `disabled` |
| 验真 | B56 SHA-256 对 `content` 字节算哈希，格式无关 | **零缺口** |

### 0.2 选型回顾（B69 已定案）

| 方案 | 决策 | 理由 |
|------|------|------|
| EasyExcel 3.3.4 | ✅ XLSX 首选 | 阿里开源，流式 KB 级内存，Apache 2.0 |
| openPDF 1.3.30 | ✅ PDF 首选 | iText 开源分支，LGPL 无 AGPL 风险 |
| Apache POI | ❌ | 全量加载内存瓶颈 |
| JasperReports | ❌ | 过重，不引入 |
| iText | ❌ | AGPL 许可风险 |
| FastExcel | ❌ | 生态不成熟 |

---

## §1 口径定义

### 1.1 策略模式架构

**新建 `ReportBuilder` 接口**：

```java
public interface ReportBuilder {
    String format();
    ReportBuildResult build(String templateId, String period);
}
```

**新建 `ReportBuildResult` record**：

```java
public record ReportBuildResult(byte[] content, int rowCount, String fileExtension) {}
```

**新建 `ReportBuilderRegistry` 工厂**（`@Component`）：注入全部 `ReportBuilder` 实现，按 `format()` 建 Map，提供 `forFormat(String)` 查找。

### 1.2 数据收集抽取

**新建 `ReportDataCollector`**（`@Component`）：从 `ReportCsvBuilder` 抽取 R01/R02 数据收集逻辑（aggregation.ledger + revRepo 查询 + 排序/格式化），返回中性结构：

```java
public record ReportData(List<String> headers, List<List<String>> rows) {}
```

三个 Builder 共享同一数据源，各自负责格式序列化。`ReportCsvBuilder` 保留 `build(templateId, period)` 返回 `CsvData`（preview 路径用），同时实现 `ReportBuilder` 接口（async 路径用），两条路径共用 `ReportDataCollector`。

### 1.3 XLSX 构建

`ReportXlsxBuilder implements ReportBuilder`：
- `format()` → `"XLSX"`
- `build()` → 用 EasyExcel `EasyExcel.write(ByteArrayOutputStream).sheet(templateName).doWrite(rows)`
- 表头从 `ReportData.headers()` 取
- 数据行 `List<List<String>>` 直接传入
- 返回 `ReportBuildResult(bytes, rows.size(), "xlsx")`

### 1.4 PDF 构建

`ReportPdfBuilder implements ReportBuilder`：
- `format()` → `"PDF"`
- `build()` → 用 openPDF `Document` + `PdfWriter` + `PdfPTable`
- 表头加粗居中，数据行左对齐
- 中文字体：使用 openPDF 内置 `STSong-Light`（UniGB-UCS2-H 编码），零外部字体文件依赖
- 返回 `ReportBuildResult(bytes, rows.size(), "pdf")`

### 1.5 Content-Type 映射

| format | Content-Type | 扩展名 |
|--------|-------------|--------|
| CSV | `text/csv; charset=UTF-8` | `.csv` |
| XLSX | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | `.xlsx` |
| PDF | `application/pdf` | `.pdf` |

映射放在 `ReportService`（与 `SUPPORTED` 同层），Controller 读取。

### 1.6 文件名扩展名适配

`ReportCsvBuilder.downloadFileName()` 当前硬编码 `.csv`。改为接受 format 参数：

```java
static String downloadFileName(String templateName, String period, OffsetDateTime createdAt, String format) {
    String ext = switch (format.toUpperCase()) {
        case "XLSX" -> ".xlsx";
        case "PDF" -> ".pdf";
        default -> ".csv";
    };
    // ... 其余逻辑不变
    return templateName + "-" + period + "-" + ts + ext;
}
```

---

## §2 影响面

### 2.1 新建文件（4 个 Java）

| 文件 | 说明 |
|------|------|
| `ReportBuilder.java` | 策略接口 |
| `ReportBuildResult.java` | 构建结果 record |
| `ReportDataCollector.java` | 数据收集共享组件（从 CsvBuilder 抽取） |
| `ReportBuilderRegistry.java` | 工厂：format → Builder 分发 |
| `ReportXlsxBuilder.java` | EasyExcel XLSX 实现 |
| `ReportPdfBuilder.java` | openPDF PDF 实现 |

### 2.2 修改文件

| 文件 | 改动 | 说明 |
|------|------|------|
| `pom.xml` (finance-service) | +2 dependency | EasyExcel 3.3.4 + openPDF 1.3.30 |
| `ReportCsvBuilder.java` | 重构 | 数据收集迁至 ReportDataCollector；实现 ReportBuilder 接口；downloadFileName 加 format 参 |
| `ReportAsyncRunner.java` | 改注入 | csvBuilder → ReportBuilderRegistry；按 job.getFormat() 分发 |
| `ReportService.java` | 放开格式守卫 | 删除 L142-146 非 CSV 400；generate 存真实 format；download 返回 contentType |
| `ReportController.java` | 下载端点 | Content-Type 按 dl.contentType()；filename 扩展名按 format |
| `M1ReportView.vue` L68 | 移除 disabled | `:disabled="f !== 'CSV'"` → 不 disabled |

### 2.3 不动

| 项 | 说明 |
|----|------|
| DB schema | report_job.format/content/content_hash/file_name 全部零改 |
| Flyway | 零迁移脚本 |
| 权限码 | report:view/report:export 不动，矩阵不改 |
| 路由/页面 | /m1-report 不动，零新页面 |
| CSS | 零新增（:disabled 移除后既有样式自动适配） |
| 网关 | 零改 |
| 审计 | GENERATE payload 已含 format 字段，零改 |
| R03-R09 | 仍 422「数据源待建」，本批不接真 |
| 数字 | ✅109/🔧1/⬜55=166 不动 |

---

## §3 卡序

### 卡0：书面定案（本卡）

DESIGN 文档 + commit/push。

### 卡1：施工

**施工序**：
1. `pom.xml` 加 EasyExcel + openPDF 依赖
2. 新建 `ReportBuilder` 接口 + `ReportBuildResult` record
3. 新建 `ReportDataCollector`（从 ReportCsvBuilder 抽取 R01/R02 数据收集）
4. 重构 `ReportCsvBuilder`：实现 ReportBuilder 接口，数据收集委托 ReportDataCollector
5. 新建 `ReportXlsxBuilder`（EasyExcel）
6. 新建 `ReportPdfBuilder`（openPDF + STSong-Light 中文字体）
7. 新建 `ReportBuilderRegistry`（format → Builder Map）
8. 改 `ReportAsyncRunner`：注入 Registry，按 job.getFormat() 分发
9. 改 `ReportService`：删除非 CSV 400 守卫；generate 存真实 format；download 返回 contentType
10. 改 `ReportController`：download Content-Type 按 format
11. 改 `M1ReportView.vue`：移除 XLSX/PDF disabled
12. `mvn compile` 验证
13. 一卡一 feat commit/push

### 卡2：批末落账

DELIVERY 七章体 + 台账五册 + 哨兵 + docs 原子 commit/push。

---

## §4 不做

1. **R03-R09 接真**（L137）：各域聚合服务未就绪，远期
2. **R02 维度扩展**（L139）：需 revenue_monthly 维度字段，依赖月结流程
3. **URGENT 加急标记源**（L143）：B51/B53 已定案诚实空态
4. **ai-service Flyway 悬置**（L130）：独立批
5. **报表模板订阅推送**：现有订阅仅标记，不发邮件/站内信，远期
6. **XLSX 样式/图表**：首卡纯数据表，不加条件格式/图表/合并单元格
7. **PDF 页眉页脚/水印**：首卡纯数据表，不加页眉页脚/水印/分页符
8. **ReportDataCollector 拆分 R01/R02 到独立类**：当前两模板，单组件足够

---

## §5 关系

- **依赖 B69**：选型决策由 B69 DESIGN §0.3.5 定案（EasyExcel + openPDF）
- **依赖 B70**：ReportCsvBuilder 毛利率格式化（B70 feat `0f06204`）为数据收集抽取基线
- **独立于 B72+**：R02 维度扩展 / R03-R09 接真属后续批次

---

## §6 交付标准

1. `POST /generate` 接受 `format: "XLSX"` / `"PDF"`，不再 400
2. 异步生成落库 content 为真实 XLSX/PDF 字节（非 CSV 伪包）
3. 下载端点 Content-Type 按格式正确返回（XLSX → xlsx MIME / PDF → application/pdf）
4. 前端 XLSX/PDF 按钮可点击，生成→下载→打开文件正常
5. B56 哈希验真对 XLSX/PDF 字节同样生效（SHA-256 格式无关）
6. `mvn compile` 0 error
7. 数字 ✅109/🔧1/⬜55=166 不动

---

## §7 勾销

| 04-backlog 行 | 勾销类型 | 说明 |
|---|---|---|
| L138（M1 报表 XLSX·PDF 导出格式） | ✅ 勾销 | 本批卡1 施工 |

---

## §8 硬约束

- 零新表 / 零 Flyway / 零新权限码 / 零新页面 / 零新路由 / 零新增 CSS
- 同服务 JVM 直调不新增 internal HTTP
- 网关零改
- 金额 bigint 存分不变
- 一卡一 feat commit 紧跟 push
- 代码与 docs 分离（docs/ 整目录 gitignore）
- 数字 ✅109/🔧1/⬜55=166 不动
