# DELIVERY · P5-B72 · M1 报表 R02 区域维度扩展

> **批次**：P5-B72（M1 纵深批首卡）
> **日期**：2026-09-20
> **主题**：R02 门店盈亏报告增加「区域」维度列（5 列→6 列），品类维度属 T2 数据仓库批远期
> **commit**：feat `4dbede4`（3 files +38/-3）
> **数字**：✅109/🔧1/⬜55=166 不变

---

## §1 现状与缺口

R02 模板定义声明维度为「区域,门店,项目品类」，但实际输出仅 5 列（门店/营收/成本/毛利率/环比），缺区域和项目品类两维度。

**本批收窄**：仅补区域维度（Store 实体已有 region 字段），品类维度属 T2 数据仓库批。

## §2 口径定义

### 2.1 新增端点

`GET /api/stores/internal/region-map?codes=...`
- 权限码：复用 `internal:name-map`（与 name-map 同矩阵，零新权限码）
- 返回：`{storeCode: region}`，region 为 null 时返回空串
- 模式：与 name-map 完全一致（findAllById + stream filter + distinct）

### 2.2 新增聚合方法

`FinanceAggregationService.resolveStoreRegions(List<String> storeCodes)`
- 调 store-service `/api/stores/internal/region-map`
- UriComponentsBuilder + internalEntity() + MAP_TYPE 范式与 resolveStoreNames 完全一致
- 降级：异常返回空 Map（调用方 getOrDefault 补空串）

### 2.3 collectR02 扩展

| 序号 | 改前（5 列） | 改后（6 列） |
|------|-------------|-------------|
| 1 | 门店 | **区域**（新增） |
| 2 | 营收(元) | 门店 |
| 3 | 成本(元) | 营收(元) |
| 4 | 毛利率(%) | 成本(元) |
| 5 | 环比(%) | 毛利率(%) |
| 6 | — | 环比(%) |

区域列插为第一列，其余列顺序不变。

## §3 不动项

- R01 collectR01 逻辑零改动
- 三种格式 Builder（CSV/XLSX/PDF）零改动——均消费统一 ReportData(headers, rows)
- 前端零改动
- SUPPORTED 集合零改动（R02 已在集合内）
- revenue_monthly 表结构零改动（零 Flyway）
- ReportTemplate 种子数据零改动

## §4 不做项

- 品类维度（属 T2 数据仓库批）
- 区域小计行
- 同比（YoY）
- R03-R09 接真

## §5 施工清单

| # | 文件 | 改动 |
|---|------|------|
| 1 | StoreController.java | +13 行：regionMap 端点 |
| 2 | FinanceAggregationService.java | +20 行：resolveStoreRegions 方法 |
| 3 | ReportDataCollector.java | +5/-3 行：collectR02 六列扩展 |

## §6 三轨真验计划

1. **curl 轨**：GET /api/stores/internal/region-map?codes=SST01,SST02（internal token）→ 华东/华南
2. **PG 轨**：store 表 region 列与端点返回对照
3. **Chrome 轨**：R02 报表预览/导出 CSV，确认六列含区域

## §7 数字影响

- ✅109/🔧1/⬜55=166 **不变**（纯纵深扩展，无新页面/无新模块状态跃迁）
- 域⑥ 财务 11✅/0🔧/1⬜ 不变
- 04-backlog L139 区域维度半项勾销（品类维度保留）
