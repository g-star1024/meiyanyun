# DESIGN-P5-B72 M1 报表 R02 区域维度扩展批

> **批次**：P5-B72 M1 报表 R02 区域维度扩展批
> **类型**：B 类 功能扩展（内部端点＋数据采集扩展）
> **创建**：2026-09-20
> **状态**：卡0 定案完成，待卡1 施工
> **前置**：B71 策略模式 ReportBuilder 架构已就绪；R02 当前仅门店维度，缺区域维度

---

## §0 现状与缺口

### 0.1 R02 模板定义 vs 实际输出

| 维度 | 模板定义（ReportDataInitializer R02） | 当前实际输出 | 缺口 |
|------|---------------------------------------|-------------|------|
| 区域 | ✅ 声明 | ❌ 未实现 | **本批施工** |
| 门店 | ✅ 声明 | ✅ 已实现 | 零缺口 |
| 项目品类 | ✅ 声明 | ❌ 未实现 | revenue_monthly 无品类维度列，需新建事实表，属数据仓库批（T2），本批不施工 |

| 指标 | 模板定义 | 当前实际输出 | 缺口 |
|------|---------|-------------|------|
| 营收(元) | ✅ | ✅ | 零缺口 |
| 成本(元) | ✅ | ✅ | 零缺口 |
| 毛利率(%) | ✅ | ✅（B70 已修百分比格式化） | 零缺口 |
| 环比(%) | ✅ | ✅ | 零缺口 |

### 0.2 数据源分析

- `RevenueMonthly` 表：复合主键 `(store_code, period_month)`，含 revenue/cost/gross_profit/cost_rate/gross_rate
- `Store` 实体：含 `region` 字段（华东/华南/华北/华中/西南/西北）
- `FinanceAggregationService.resolveStoreNames()`：已有 store-service 内部调用范式（`GET /api/stores/name-map`）
- **缺口**：无 store_code → region 映射的内部端点

### 0.3 项目品类维度说明

`revenue_monthly` 表当前仅存储门店级月度汇总，无项目品类拆分。要支持品类维度需：
1. 新建 `revenue_monthly_category` 事实表（store_code + period_month + category_code 复合主键）
2. 月结流程按 SKU 品类聚合写入
3. 依赖月结流程（P3-B11）推广与品类数据源就绪

**本批不施工品类维度**，登记 backlog 待数据仓库批（T2）。

---

## §1 口径定义

### 1.1 store-service 新增内部端点

```
GET /api/stores/internal/region-map?codes=SST01,SST02
权限：internal:name-map（复用既有权限码）
响应：{"SST01":"华东","SST02":"华东","SST03":"华北"}
```

- 复用 `StoreController` 既有 `@RequirePerm("internal:name-map")` 权限码
- 不存在的门店码不返回（与 name-map 行为一致）
- 空参数返回空 JSON `{}`

### 1.2 FinanceAggregationService 新增方法

```java
public Map<String, String> resolveStoreRegions(List<String> storeCodes)
```

- 模式与 `resolveStoreNames()` 完全一致（RestTemplate + X-Internal-Token + 降级空 Map）
- 调用 `GET /api/stores/internal/region-map`

### 1.3 ReportDataCollector.collectR02() 扩展

**扩展前（5 列）**：
```
门店 | 营收(元) | 成本(元) | 毛利率(%) | 环比(%)
```

**扩展后（6 列）**：
```
区域 | 门店 | 营收(元) | 成本(元) | 毛利率(%) | 环比(%)
```

- 区域列插为第一列（维度层级：区域 > 门店）
- 排序：先按区域升序，再按门店升序（替换当前纯门店升序）
- DataScope 过滤逻辑不变（canReadStore 逐行收敛）
- store-service 不可用时区域列降级显示门店码前缀「未知区域」

---

## §2 不动

- R01 数据采集逻辑（collectR01）
- 三种格式 Builder（CSV/XLSX/PDF 均数据驱动，headers/rows 自动适配新列）
- 前端 M1ReportView.vue（预览/导出完全数据驱动，零改动）
- ReportService SUPPORTED 集合（R02 已在其中）
- revenue_monthly 表结构（不新增列）
- ReportTemplate 种子数据（R02 dimensions 已含「区域」）

---

## §3 不做

- 项目品类维度（revenue_monthly 无品类列，属数据仓库批 T2）
- 区域小计行（需改变 ReportData 结构，当前纯表格不支持小计行语义）
- 同比（YoY）列（需查去年同月数据，逻辑类似环比但优先级低于区域维度）
- R03-R09 接真（后续批次逐一施工）

---

## §4 施工清单

### 卡1 施工项

| # | 事项 | 文件 | 服务 |
|---|------|------|------|
| 1 | 新增 region-map 内部端点 | `StoreController.java` | store-service |
| 2 | 新增 resolveStoreRegions() | `FinanceAggregationService.java` | finance-service |
| 3 | collectR02() 增加区域列 | `ReportDataCollector.java` | finance-service |

### 卡2 批末落账

- DELIVERY 七章体
- 台账四册勾销（04-backlog L139 部分勾销区域维度部分）
- 哨兵更新

---

## §5 三轨真验计划

1. **curl 轨**：seed 18443 调用 region-map 端点验证映射正确性；preview R02 验证 6 列输出
2. **PG 轨**：核实 store 表 region 字段值与端点返回一致
3. **Chrome 轨**：M1 报表页 R02 预览/导出 CSV/XLSX/PDF 三格式验证区域列渲染

---

## §6 数字影响

- ✅109/🔧1/⬜55=166 数字一律不变
- 零新页面/零新路由/零新增 CSS
- 零 Flyway（不新建表/不修改表结构）
- 零新权限码（复用 internal:name-map）
