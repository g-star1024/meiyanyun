# P4 门店运营主数据域方案设计（p-store-master）：房间床位 · 设备仪器 · 项目目录 SKU · 价目调价 · 卡项疗程目录

> 时间：2026-09-07
> 状态：**方案设计，用户已拍板方向（门店运营主数据域），本文件给出落地细节与边界决策，待用户确认后按批次实施**
> 用户决策原文（2026-09-07，AskUserQuestion 四方向选型）：**「门店运营主数据域（推荐）」**——对库存/房间/设备/价目表/项目目录等门店运营主数据做后端建表 + CRUD + 前端去 mock。
> 事实基础：2026-09-07 三路只读链路调研（前端 7 页面 × 5 个纯 mock store 全字段 / store-service 现有 6 实体与控制器端点模式 / org-service 权限码矩阵），文件行号均实证；竞品调研（微盟智慧美业、医美店务 ymysoft、有赞美业/有赞医疗）。
> 铁律遵循：meiyun-dev-rules——链路先行（先出链路映射再动代码）；写接口四件套（校验/幂等/全动作审计/中文错误）；金额后端 Long 分、前端元（适配层 fen↔yuan）；业务表 JPA ddl-auto（种子不进 Flyway，DataInitializer + setup-seed-db.sh TRUNCATE 同步）；跨服务取数走 RestTemplate 内部端点 + X-Internal-Token（禁直读别域表）；样式零改动（接真实 API 只换数据源 mock store→src/api/*.ts，适配层消化差异，UI 模型对不上停下报备不砍 UI）；API 三处一致（后端 @RequestMapping 带 /api、网关 router.go、前端 api/*.ts）；诚实降级；真实双栈验证拒绝假交付；种子库 meiyun_seed 不碰 meiyun_core；白天决策时间窗方向/取舍先给建议问用户，拍板后开工。

---

## 〇、设计总原则

1. **主数据先行、交易态后置**：本域只沉淀「长期存在、跨单据复用」的基础档案（房间/床位/设备/项目 SKU/品牌品类/价目/卡项疗程模板）。高频流转的**交易态**（床位正在被谁占用、设备本次是否开机）本域不建实时占用引擎，见 §八 边界。
2. **一套项目主数据，不造三套价**：当前前端同时存在 m1Brand.Product（SKU 挂牌价/成本价）、pricelist.PriceItem（原价/会员价/活动价）、catalog.includes（自由文本项目名）三处「项目 + 价格」，语义重叠。本域统一为 **品牌→品类→项目 SKU（m1 三级主数据）+ 门店价目（SKU 在某店的定价）+ 卡项疗程模板（组合商品）** 三层；旧 pricelist mock 的独立项目收敛为 SKU + 门店价目，不新增第三套项目实体。
3. **权限码全部复用、不新增**：room/equipment/pricelist/catalog/brand 的 view + edit 已在 PermissionMatrix 预留（实证 L44/60/61/65/66 view 块、L161/181/199/200/203/204 edit 块）；调价审批**不新增 pricelist:approve**，门店提交（pricelist:edit）、集团/品牌角色审批（brand:approve 复用，与 m1 品牌管控同权），见 §七。
4. **网关零改动**：新端点全部挂 store-service 类级 `/api/stores/**`，网关 router.go 已有 `/api/stores→:8085` 且路径不重写，**无需改路由表**；内部端点走 `/api/stores/internal/**`（网关外部 404）。
5. **后端落点单一**：全部新实体建在 store-service（8085），仿现有 `consumable/`、`bom/` 分包模式，每域一个子包（entity/repo/service/controller/auditRecorder）；种子仿 StoreBomDataInitializer（ApplicationRunner + @Order + count()==0 幂等门控）。
6. **金额单位**：后端全部 bigint 存「分」；前端 mock 主数据价格均为「元」（purchaseAmount/originalPrice/memberPrice/promoPrice/listPrice/costPrice/price），由前端 api 适配层统一 fen↔yuan，页面与 store 内部仍用元，样式与交互零改动。
7. **多门店数据域**：档案分「集团模板（store_code 空串）」与「门店行（store_code=门店码）」两级，沿用 project_bom 的 GROUP_TEMPLATE 模式与 ConsumableController 的 resolveStoreCode/resolveWriteStoreCode 数据域裁决；门店角色只能读写本店，集团/品牌角色可维护集团模板与参查门店。

---

## 一、现状盘点（实证事实，设计的事实地基）

### 1.1 前端：5 个纯 mock store + 5 个页面（179 页中 149 页仍 mock 的最硬短板）

| 页面（路由/编号） | mock store | 核心模型 | 写操作 | 权限点（mock 内 auth.can） |
|---|---|---|---|---|
| RoomView.vue（/m2-rooms，M2-04） | [room.ts](../frontend/src/stores/room.ts) | Room{code,name,type: TREATMENT/CONSULT/OBSERVE/RECOVERY, beds: Bed[]}；Bed{code,status: FREE/IN_USE/SANITIZING/MAINTENANCE, customerName, project, occupiedAt, note}；RoomLog | occupy/release/clean/setMaintenance/restore/addRoom | room:edit |
| EquipmentView.vue（/m2-equipment，M2-05） | [equipment.ts](../frontend/src/stores/equipment.ts) | Equipment{assetNo,name,brand,model,category: LASER/RF/ULTRASOUND/INJECTION/MONITOR/OTHER, location, status: NORMAL/CALIBRATING/REPAIRING/DISABLED, purchasedAt, purchaseAmount(元), lifespanYears, depreciated(元), nextCalibrationAt, nextMaintenanceAt, records: MaintenanceRecord[]}；MaintenanceRecord{type: CALIBRATION/MAINTENANCE/REPAIR, at, by, vendor, summary, nextAt, cost(元)} | addEquipment/setStatus/addRecord | equipment:edit |
| PricelistView.vue（/m2-pricelist，M2-14） | [pricelist.ts](../frontend/src/stores/pricelist.ts) | PriceItem{code,name,category: INJECTION/LASER/SKINCARE/BODY/EXAM, originalPrice, memberPrice, promoPrice, unit, duration, status: ACTIVE/DISABLED/PENDING, riskTags: INJECTION/LASER/HIGH_ENERGY/ANESTHESIA/PREGNANCY_RISK, pendingPrice{memberPrice,promoPrice,reason,requestedAt,requestedBy}} | requestPriceChange/approvePriceChange/rejectPriceChange/toggleStatus | pricelist:edit（审批暂用同码） |
| CatalogView.vue（/m2-catalog，M2-15） | [catalog.ts](../frontend/src/stores/catalog.ts) | CatalogProduct{code(CD-/CS-), name, type: CARD/COURSE, category, sessions, validityDays, price, originalPrice, transferable, status: ON_SHELF/OFF_SHELF, includes: string[], description} | create/update/toggleStatus | catalog:edit |
| M2ProjectView.vue（/m2-projects，M2-15b 医美项目库） | [m1Brand.ts](../frontend/src/stores/m1Brand.ts) | 三级：Brand{code,name,shortName,origin,supplier,status,logoColor}；Category{code,name,brandId,parentId(二级),status,sort}；Product{sku,name,brandId,categoryId,unit,listPrice,costPrice,status,storeTypes: FLAGSHIP/COMMUNITY/CLINIC,durationMin} | createBrand/updateBrand/setBrandStatus；createCategory/updateCategory/setCategoryStatus/deleteCategory(有项目禁删)；createProduct/updateProduct/setProductStatus | brand:edit（mock 内未逐个 auth.can，页面按钮控权） |

- 参照模板：[inventory store](../frontend/src/stores/inventory.ts) 已接 `@/api/consumable` + `@/api/approval`，**失败回落 demo seed**——「store 接 API + 加载失败诚实回落演示数据」即为本批去 mock 的标准范式。
- api/ 目录现状：无 room/equipment/pricelist/catalog/store 封装；仅 [consumable.ts](../frontend/src/api/consumable.ts)、[bom.ts](../frontend/src/api/bom.ts) 挂 /stores 前缀（client 基础路径已带 /api）。
- BoardView.vue（/board 全院流水牌）为纯只读聚合页，消费 arrival/consultation/followup/customer/order，**无主数据、无写操作，不在本批**。
- PrescriptionView.vue L98-106 另有静态项目数组（注释「演示期静态；后续接 catalog API」），本批项目 SKU 落地后可顺手改为读 SKU 主数据（列为可选收尾，不阻断）。

### 1.2 后端：store-service 现有能力与缺口

- 现有实体（[store 包](../backend/store-service/src/main/java/com/meiyun/store)）：根包 Store/StoreRepository/StoreController、RegionDist；`consumable/` 子包 Consumable/ConsumableStock/ConsumableMovement + 三 Repository + ConsumableService + ConsumableController（类级 `/api/stores/consumables`）+ InternalConsumableController（`/api/stores/internal/consumables/deduct`，internal:consumable-write + X-Internal-Token）+ ConsumableAuditRecorder（RestTemplate 调 audit-service :8084，失败仅日志不阻断）+ StoreRestConfig；`bom/` 子包 ProjectBom + Repository + Service + ProjectBomController（`/api/stores/project-boms`）+ InternalBomController + BomNoGenerator + StoreBomDataInitializer。
- 代码模式（新代码直接复用）：
  - 实体：Lombok @Getter/@Setter/@NoArgsConstructor + JPA 注解；ID 主键 `@GeneratedValue(IDENTITY)`（Consumable）或业务单号 `@Column(length=24)` 字符串 PK（ProjectBom.bomId = `BOM+yyyyMMdd-6位`，BomNoGenerator synchronized 取当日最大序号+1）；审计四字段 created_by/created_at/updated_by/updated_at + @PrePersist；唯一约束命名 `uk_xxx`。
  - 控制器：`@RestController @RequestMapping("/api/stores/xxx")` + 方法级 `@RequirePerm("权限码")`（自定义注解，非 javax @PreAuthorize）；返回 `List<Map<String,Object>>` / `Map<String,Object>`（读接口金额在 Service 内换算为「元」出参，写接口入参金额为「分」）；入参用 controller 内嵌套 `record`；数据域裁决 resolveStoreCode（读）/resolveWriteStoreCode（写），门店/自助角色强制本店、REGION/GROUP/BRAND 可传 storeCode 参（DataScope.canReadStore 校验可见名单）。
  - 种子：StoreBomDataInitializer `@Component @Order(50) implements ApplicationRunner`，`repo.count()>0 跳过`幂等；门店码常量 SST01–SST06；集团模板 store_code 空串（ProjectBomService.GROUP_TEMPLATE）。
- **缺口**：房间/床位、设备仪器、品牌/品类/项目 SKU、门店价目、卡项疗程模板——**后端零实体、零接口、零库表**；权限码已预留但无任何端点使用。
- 邻域已存在、口径不同、**不可混用**的两张表：
  - finance-service `fin_asset`（FinAsset，端点在 CostCarryController `/api/finance/assets`）：财务口径轻量固定资产（assetId/originalValue 分/salvageRate/usefulMonths/直线月折/status IN_USE|DISPOSED），**无设备编号/品牌型号/校准/维修/位置等运营字段**——本域设备台账是「运营口径」，二者一期不打通（见 §八 决策点 D2）。
  - customer-service `mall_product`：C 端积分商城商品，非门店售卖主数据，不混用。
- 交易侧对项目主数据的引用现状（为未来关联预留，本批不改造交易）：txn-order `TxnOrder.project`(64) 存项目名字符串快照；`OrderItem`（itemName/qty/unitPrice/amount）无 SKU 外键；consult_plan_item `itemCode`(32) 可空但无主数据可关联；writeoff_record.project 项目名（BOM 勾兑键）。**本批只建主数据，不回改这些交易表外键**（列 §九 不做项），但 SKU/价目/卡项的编码设计预留未来 itemCode→sku 的关联可能。

### 1.3 权限码（org-service PermissionMatrix，已全部预留，零新增）

- view 块：brand:view L44、room:view L60、equipment:view L61、pricelist:view L65、catalog:view L66（并在各角色块重复授权 L347/363/364/368/369 等）。
- edit/approve 块：brand:edit L161、brand:approve L181、room:edit L199、equipment:edit L200、pricelist:edit L203、catalog:edit L204（角色块 L464/484/502/503/506/507、L621/637/638/642/643、L738/778/779/782/783、L890/891、L968）。
- **结论**：本批不动 PermissionMatrix、不发 Flyway 迁移；角色授权现状（店长/运营/集团等）已覆盖这些码，部署即生效。

---

## 二、竞品调研结论（2026-09-07 检索：微盟智慧美业、医美店务 ymysoft、有赞美业/有赞医疗）

1. **房间/床位资源**：竞品普遍把「房间/床位/手术室」作为可调度资源档管理（房间号、类型、容纳床位、状态），开单/预约时选空闲房；ymysoft 强调「医疗资源调度：手术室管理 + 医生排班 + 智能预约分配」。**落点**：我们一期只做房间/床位**档案 + 维护态**（能用/停用维护），不做预约自动占房/排班联动（交易态引擎列不做项）；床位编号、房间类型、床位维护原因落库，页面入住/退房/消毒的**实时流转态**一期仍为前端演示态（诚实降级，§八 D1）。
2. **设备仪器**：竞品设备台账含资产编号、品牌型号、分类、存放位置、供应商、购置日期/金额、维保校准记录与到期提醒。**落点**：与我们 equipment mock 模型几乎一一对应，原样后端化；折旧字段（depreciated/netValue）运营口径保留在设备台账，**不与财务固定资产模块自动对账**（竞品也是设备行政台账与财务资产分口管理）。
3. **项目/品项主数据**：微盟「服务项目管理 + 院线/客装产品」、有赞「品项指导」——服务项目（含时长/单价/分类/风险）与零售产品分开建档，总部统一定义、门店可售价差异。**落点**：m1Brand 三级（品牌→品类→项目 SKU）即「总部统一定义」，门店价目即「门店可售价差异」，两层分离正好对齐竞品「总部品项 + 门店定价」。
4. **卡项/疗程/套餐**：竞品卡型丰富——储值卡、次卡、时间卡（周期卡）、套餐卡、疗程包；有赞「电子卡券核销打通」。**落点**：我们 catalog mock 已有 CARD（储值/次/年卡）与 COURSE（疗程，含 sessions 次数、validityDays 有效期、includes 项目清单、transferable 转赠），字段够用，一期原样后端化；includes 一期仍存项目名文本数组（与 writeoff/txn 项目名快照口径一致），**不强制改 SKU 外键**（未来套餐核销打通时再关联）。
5. **库存/供应链**：微盟/有赞都做了供应商、耗材入出库、补货、调拨、盘点、总部统采配送、门店要货、门店间调拨、多仓库。**落点**：我们 B5 已有耗材建档/入库/移动平均/安全库存，B10 已有 BOM 自动扣料；**调拨/要货/盘点/多仓库/统采配送不在本批**（属库存交易域，列后续）。
6. **多门店/区域**：竞品总部—区域—门店多级管控，总部统一定义品项/价格模板、门店可覆盖。**落点**：集团模板（store_code 空）+ 门店行两级，与 project_bom 同构，直接复用。

---

## 三、目标与边界（一页看清）

**目标**：把预约→接待→咨询→收银→治疗主链路依赖的 5 类门店运营基础档案从「前端内存 mock、刷新即丢」升级为「store-service 落库、网关鉴权、多店数据域、全动作审计、种子可重复播种」，并让 5 个页面在**样式与交互零改动**的前提下接真实 API（失败诚实回落演示数据）。

**红线**：
- 不做交易态引擎（不做实时床位占用/预约占房/排班联动、不做设备开机联动）；
- 不回改 txn/finance/customer 任何既有表与外键（project/itemCode/writeoff 项目名快照维持现状）；
- 不打通 finance fin_asset（设备运营台账与财务固定资产一期分口）；
- 不新增权限码、不发 Flyway 迁移（业务表 JPA ddl-auto，种子 DataInitializer + setup-seed-db.sh）；
- 不在前端伪造后端没有的能力——床位实时占用态后端不持久化，页面该部分保留前端演示态并明确标注（诚实降级，不砍 UI）。

---

## 四、数据模型（store-service，JPA ddl-auto；金额 bigint 分）

> 全部表带 store_code（16）：空串=集团模板/集团级档案，门店码=门店行。审计四字段 created_by/created_at/updated_by/updated_at 统一（下文表内不重复列）。

### 4.1 房间床位域（room 子包）

**treatment_room（治疗/咨询/观察/恢复房间档案）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| store_code | varchar(16) not null | 门店码；房间为门店级资源，无集团模板（写时门店角色强制本店） |
| room_code | varchar(32) not null | 房间编号，如 A01 |
| name | varchar(64) not null | 房间名，如 激光治疗室 |
| room_type | varchar(16) not null | TREATMENT/CONSULT/OBSERVE/RECOVERY |
| status | varchar(16) not null default 'ACTIVE' | ACTIVE 启用 / MAINTENANCE 维护停用（房间级） |
| remark | varchar(255) | 备注 |
| 唯一约束 | uk_room_store_code(store_code, room_code) | |

**treatment_bed（床位档案）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| store_code | varchar(16) not null | |
| room_id | bigint not null | 所属房间（逻辑外键 treatment_room.id） |
| bed_code | varchar(32) not null | 床位编号，如 A01-1 |
| maint_status | varchar(16) not null default 'OK' | **OK 可用 / MAINTENANCE 维护中**（主数据持久化的只有维护态） |
| maint_reason | varchar(255) | 维护原因（对应 mock Bed.note） |
| 唯一约束 | uk_bed_store_code(store_code, bed_code) | |

> **状态边界**：mock Bed 的四态 FREE/IN_USE/SANITIZING/MAINTENANCE 中，仅 MAINTENANCE（及其原因）是主数据、落库；FREE/IN_USE/SANITIZING 是随治疗流转的交易态，一期不落库（§八 D1）。页面入住/退房/消毒交互保留，运行态由前端演示数据承载，床位「设维护/维护恢复」改为调后端持久化 maint_status。

**room_operation_log（房间/床位操作日志，审计留痕）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| store_code | varchar(16) not null | |
| room_code | varchar(32) | |
| bed_code | varchar(32) | |
| action | varchar(32) not null | ADD_ROOM/SET_MAINTENANCE/RESTORE 等（主数据动作） |
| text | varchar(255) | 中文描述 |
| actor | varchar(32) | 操作人 |
| created_at | timestamptz | |

> 说明：occupy/release/clean 一类交易态动作一期不写此表（无后端状态可改）；仅持久化动作（建房、设维护/恢复）落日志 + audit-service 审计双留痕。

### 4.2 设备仪器域（equipment 子包）

**equipment（设备仪器运营台账）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| store_code | varchar(16) not null | 门店码（设备为门店级资产） |
| asset_no | varchar(32) not null | 资产编号，如 EQ-L001 |
| name | varchar(64) not null | 设备名称 |
| brand | varchar(64) | 品牌 |
| model | varchar(64) | 型号 |
| category | varchar(16) not null | LASER/RF/ULTRASOUND/INJECTION/MONITOR/OTHER |
| location | varchar(64) | 所在位置 |
| status | varchar(16) not null | NORMAL/CALIBRATING/REPAIRING/DISABLED |
| purchased_at | date | 购置日期 |
| purchase_amount_fen | bigint not null default 0 | 购置金额（分） |
| lifespan_years | int | 预计使用年限 |
| depreciated_fen | bigint not null default 0 | 已累计折旧（分，运营口径手填/维保费用累加） |
| next_calibration_at | date | 下次校准日期 |
| next_maintenance_at | date | 下次维保日期 |
| note | varchar(255) | 备注 |
| 唯一约束 | uk_equip_store_asset(store_code, asset_no) | |

**equipment_maintenance（校准/维保/维修记录）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| store_code | varchar(16) not null | |
| equipment_id | bigint not null | 逻辑外键 equipment.id |
| maint_type | varchar(16) not null | CALIBRATION/MAINTENANCE/REPAIR |
| occurred_at | date not null | 发生日期 |
| actor | varchar(32) | 操作/记录人 |
| vendor | varchar(64) | 服务商/工程师 |
| summary | varchar(500) not null | 内容/结果 |
| next_at | date | 本次之后下次日期 |
| cost_fen | bigint not null default 0 | 费用（分） |

> 业务规则（对齐 mock addRecord）：新增 CALIBRATION 记录且 next_at 有值 → 回写 equipment.next_calibration_at；MAINTENANCE/REPAIR → 回写 next_maintenance_at；CALIBRATION 记录时若设备状态为 CALIBRATING → 复位 NORMAL；REPAIR 记录时若为 REPAIRING → 复位 NORMAL；cost 累加到 depreciated_fen（封顶不超过 purchase_amount_fen）。临期提醒（14 天）前端计算不变（dueCalibration/daysUntil），日期由后端持久化提供。

### 4.3 项目目录域（品牌→品类→项目 SKU，project 子包）

> 对应 m1Brand.ts 三级主数据。品牌/品类/项目为**集团统一定义**（store_code 空串，集团/品牌角色可写）；项目通过 store_types 标注适用门店类型（FLAGSHIP/COMMUNITY/CLINIC），不按门店拆行。

**product_brand（品牌）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| brand_code | varchar(32) not null | 如 BR-ALLERGAN |
| name | varchar(64) not null | 品牌名 |
| short_name | varchar(32) | 简称 |
| origin | varchar(64) | 产地 |
| supplier | varchar(128) | 供应商 |
| status | varchar(16) not null default 'ACTIVE' | ACTIVE/INACTIVE |
| logo_color | varchar(16) | 头像色（演示用，前端轮起色可后端回填） |
| remark | varchar(255) | |
| 唯一约束 | uk_brand_code(brand_code) | |

**product_category（品类，支持二级）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| category_code | varchar(32) not null | 如 CT-INJECT |
| name | varchar(64) not null | |
| brand_id | bigint not null | 所属品牌（逻辑外键） |
| parent_id | bigint | 父品类（二级；空=一级） |
| status | varchar(16) not null default 'ACTIVE' | |
| sort | int not null default 0 | |
| remark | varchar(255) | |
| 唯一约束 | uk_category_code(category_code) | |

> 删除规则（对齐 mock deleteCategory）：品类下仍有项目 SKU → 422「该品类下仍有项目，无法删除」；删除一级品类连带删除其子品类（mock 行为 filter parentId!==id）。

**product_sku（项目/产品 SKU，治疗项目主数据）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| sku | varchar(40) not null | SKU 编码，如 AGN-BTX-100 |
| name | varchar(64) not null | 项目/产品名 |
| brand_id | bigint not null | 品牌 |
| category_id | bigint not null | 品类 |
| unit | varchar(8) not null | 次/支/盒/部位 |
| list_price_fen | bigint not null default 0 | 挂牌价/指导价（分） |
| cost_price_fen | bigint not null default 0 | 成本价（分） |
| status | varchar(16) not null default 'ACTIVE' | ACTIVE/INACTIVE（停用受控，不物理删） |
| store_types | varchar(32) not null | 适用门店类型，逗号分隔 FLAGSHIP,COMMUNITY,CLINIC |
| duration_min | int not null default 0 | 预计时长（分钟） |
| risk_tags | varchar(64) | 医疗风险标签逗号分隔（INJECTION/LASER/HIGH_ENERGY/ANESTHESIA/PREGNANCY_RISK），承接旧 pricelist 风险标签，面诊禁忌初筛用 |
| remark | varchar(255) | |
| 唯一约束 | uk_sku(sku) | |

> 说明：旧 pricelist.PriceItem 的 riskTags/duration/unit/name 语义上移到 SKU；旧 PriceItem 的「价格」语义下沉到门店价目（§4.4）。一个 SKU = 一个可售卖治疗项目/产品，未来 txn order_item / consult_plan_item.itemCode 可关联 sku（本批不回改）。

### 4.4 门店价目域（pricelist 子包）

**store_price（门店项目定价/价目）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| store_code | varchar(16) not null | 门店码；空串=集团指导价模板 |
| sku | varchar(40) not null | 项目 SKU（逻辑外键 product_sku.sku） |
| original_price_fen | bigint not null default 0 | 原价（划线） |
| member_price_fen | bigint not null default 0 | 会员价 |
| promo_price_fen | bigint | 活动价（可空） |
| status | varchar(16) not null default 'ACTIVE' | ACTIVE 启用 / DISABLED 停用 / PENDING 待审批 |
| pending_member_price_fen | bigint | 待审批会员价 |
| pending_promo_price_fen | bigint | 待审批活动价 |
| pending_reason | varchar(255) | 调价原因 |
| requested_by | varchar(32) | 申请人 |
| requested_at | timestamptz | 申请时间 |
| 唯一约束 | uk_price_store_sku(store_code, sku) | |

> 调价状态机（对齐 mock requestPriceChange/approve/reject）：门店角色对本店价目提交调价 → status=PENDING、记 pending_*；集团/品牌角色审批通过 → pending_* 覆盖正式价、status=ACTIVE；驳回 → 清 pending_*、回 ACTIVE；ACTIVE↔DISABLED 可 toggle（PENDING 态不可 toggle、不可再提交）。**审批权复用 brand:approve**（§七）。读列表时后端富化 sku_name/category/unit/duration/riskTags（ join product_sku），使 PriceItemView 页面字段不缺。

### 4.5 卡项疗程目录域（catalog 子包）

**catalog_product（卡项/疗程模板）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| store_code | varchar(16) not null | 门店码；空串=集团通用模板 |
| product_code | varchar(32) not null | CD-xxx 卡 / CS-xxx 疗程 |
| name | varchar(64) not null | |
| product_type | varchar(16) not null | CARD/COURSE |
| category | varchar(32) | 分类（储值卡/次卡/年卡/抗衰疗程…自由文本） |
| sessions | int not null default 1 | 总次数 |
| validity_days | int not null | 有效期天数 |
| price_fen | bigint not null default 0 | 售价（分） |
| original_price_fen | bigint not null default 0 | 划线价（分） |
| transferable | boolean not null default false | 是否允许转赠 |
| status | varchar(16) not null default 'ON_SHELF' | ON_SHELF/OFF_SHELF |
| includes | text | 包含项目（文本数组，JSON/换行存储；一期项目名文本，不强制 SKU 外键） |
| description | varchar(1000) | 描述 |
| 唯一约束 | uk_catalog_store_code(store_code, product_code) | |

> 编码生成（对齐 mock）：CARD → `CD-3位序号`、COURSE → `CS-3位序号`，按店当前同类最大序号+1（Service 内 synchronized 或 repo 查询 max+1，仿 BomNoGenerator）。includes 一期存文本（与 writeoff/txn 项目名快照口径一致），未来套餐核销打通再关联 SKU。

---

## 五、API 契约（store-service，网关 /api/stores 零改动；金额读元/自分）

> 统一约定：读接口出参金额为「元」（Service 内 fen/100，字段名带 Yuan）；写接口入参金额为「分」（字段名带 Fen）。时间 ISO-8601。数据域裁决复用 resolveStoreCode/resolveWriteStoreCode 模式。所有写动作经 ConsumableAuditRecorder 同款 AuditRecorder 调 audit-service（bizType 用 ROOM/EQUIPMENT/BRAND/PRICE/CATALOG），失败仅日志不阻断。

### 5.1 房间床位 `/api/stores/rooms`（room:view 读 / room:edit 写）
- `GET /api/stores/rooms`（params: storeCode, type, status）→ 房间含床位嵌套列表。
- `POST /api/stores/rooms`（{storeCode, roomCode, name, roomType, bedCount}）→ 建房并按 bedCount 生成床位（bed_code=`{roomCode}-B{n}`，对齐 mock addRoom）；roomCode 同店重复 409。
- `POST /api/stores/beds/{bedId}/maintenance`（{storeCode, reason}）→ 床位设维护（maint_status=MAINTENANCE）。
- `POST /api/stores/beds/{bedId}/restore`（{storeCode}）→ 维护恢复（maint_status=OK）。
- `GET /api/stores/rooms/logs`（params: storeCode）→ 主数据操作日志。

### 5.2 设备仪器 `/api/stores/equipments`（equipment:view 读 / equipment:edit 写）
- `GET /api/stores/equipments`（params: storeCode, status, category, keyword）→ 设备台账（出参金额元：purchaseAmountYuan/depreciatedYuan/netValueYuan；富化 due 标志由前端按日期算）。
- `GET /api/stores/equipments/{id}/records` → 校准/维保/维修记录列表。
- `POST /api/stores/equipments`（{storeCode, assetNo, name, brand, model, category, location, purchasedAt, purchaseAmountFen, lifespanYears, nextCalibrationAt, nextMaintenanceAt, note}）→ 新建设备（assetNo 同店重复 409）。
- `POST /api/stores/equipments/{id}/status`（{storeCode, status, note}）→ 状态变更。
- `POST /api/stores/equipments/{id}/records`（{storeCode, maintType, occurredAt, vendor, summary, nextAt, costFen}）→ 新增维保记录（Service 内回写下次日期/复位状态/累加折旧，§4.2 规则）。

### 5.3 项目目录 `/api/stores/brands`、`/api/stores/categories`、`/api/stores/skus`（brand:view 读 / brand:edit 写；集团级）
- `GET /api/stores/brands` → 品牌列表（含 brandStats：品类数/项目数/启用数/平均挂牌价，Service 聚合）。
- `POST /api/stores/brands`、`POST /api/stores/brands/{id}`（更新）、`POST /api/stores/brands/{id}/status`（{status} 启停）。
- `GET /api/stores/categories`（params: brandId）→ 品类列表（树形 parentId）。
- `POST /api/stores/categories`、`POST /api/stores/categories/{id}`（更新）、`POST /api/stores/categories/{id}/status`、`DELETE /api/stores/categories/{id}`（有项目 422；连带子品类）。
- `GET /api/stores/skus`（params: brandId, categoryId, keyword, status）→ SKU 列表（出参金额元 listPriceYuan/costPriceYuan；storeTypes/riskTags 数组化）。
- `POST /api/stores/skus`、`POST /api/stores/skus/{id}`（更新）、`POST /api/stores/skus/{id}/status`（{status} 停用/启用，受控不物理删）。

### 5.4 门店价目 `/api/stores/prices`（pricelist:view 读 / pricelist:edit 提交 ; brand:approve 审批）
- `GET /api/stores/prices`（params: storeCode, category, status, keyword）→ 价目列表（富化 skuName/category/unit/duration/riskTags；金额元 originalPriceYuan/memberPriceYuan/promoPriceYuan）。
- `POST /api/stores/prices`（{storeCode, sku, originalPriceFen, memberPriceFen, promoPriceFen}）→ 门店给某 SKU 建档定价（同店同 SKU 重复 409）。
- `POST /api/stores/prices/{id}/change-request`（{storeCode, memberPriceFen, promoPriceFen, reason}）→ 提交调价（status→PENDING；门店角色 pricelist:edit）。
- `POST /api/stores/prices/{id}/approve` → 审批通过（需 brand:approve，集团/品牌角色；pending 覆盖正式价）。
- `POST /api/stores/prices/{id}/reject` → 驳回（需 brand:approve；清 pending 回 ACTIVE）。
- `POST /api/stores/prices/{id}/toggle` → 启用/停用切换（pricelist:edit；PENDING 态 422）。

### 5.5 卡项疗程 `/api/stores/catalog`（catalog:view 读 / catalog:edit 写）
- `GET /api/stores/catalog`（params: storeCode, type, status, keyword）→ 卡项疗程列表（金额元 priceYuan/originalPriceYuan；includes 数组化）。
- `POST /api/stores/catalog`（{storeCode, productType, name, category, sessions, validityDays, priceFen, originalPriceFen, transferable, status, includes[], description}）→ 新建（productCode 后端生成 CD-/CS-）。
- `POST /api/stores/catalog/{id}`（更新可改字段）。
- `POST /api/stores/catalog/{id}/toggle` → 上架/下架（ON_SHELF↔OFF_SHELF）。

> 无内部端点需求：本批主数据暂不被其他服务回调（交易侧本批不关联 SKU），故不建 `/internal/**`；未来 order/consult 关联 SKU 时再补内部只读端点。

---

## 六、前端去 mock 方案（样式零改动）

1. **新增 api 封装**（src/api/，仿 consumable.ts/bom.ts，client 基础路径已带 /api）：
   - `room.ts`（listRooms/createRoom/setBedMaintenance/restoreBed/listRoomLogs，DTO 房间嵌套床位）
   - `equipment.ts`（listEquipments/listEquipmentRecords/createEquipment/setEquipmentStatus/addMaintenanceRecord，DTO 金额元）
   - `projectMaster.ts`（品牌/品类/SKU 的 list + create/update/setStatus + 品类删除）
   - `price.ts`（listPrices/createPrice/requestPriceChange/approve/reject/toggle，DTO 金额元、入参 Fen）
   - `catalog.ts`（listCatalog/createCatalog/updateCatalog/toggleCatalog，DTO 金额元、入参 Fen）
2. **store 改造**（5 个 mock store 保留对外 action/getter 签名不变，页面零改动）：
   - 每个 store 增加 `load()`：调 api 拉真实数据填充 ref；加载失败 catch → 回落现有 `seed()` 演示数据并置 `demoMode=true`（对齐 inventory store 范式，诚实降级，页面可显示「演示数据」角标——若页面无角标位则不新增 UI，仅控制台告警，不砍功能）。
   - 写 action（occupy/addRoom/addRecord/requestPriceChange/create…）：能持久化的改调 api 后刷新本地；**交易态 action（room 的 occupy/release/clean）后端无端点，保留纯前端行为**（操作内存态 + activity 日志），不伪造持久化。
   - 金额适配：api 层 DTO 元 → store 直接用元（mock 本就是元）；写时 store 元 → api 入参 Fen（yuan*100 取整）。集中在 api 模块做换算，store/页面不出现 fen。
   - ID 类型：mock 用字符串 id（nextId('room')），后端 bigint。适配层把后端 id 转字符串暴露给 store（String(id)），保证页面 `bed.id/room.id` 比较逻辑不崩。
3. **字段映射差异由适配层消化**（例）：
   - 设备后端 purchaseAmountFen → DTO purchaseAmountYuan → store Equipment.purchaseAmount（元，同名）；records 的 costFen→costYuan→cost（元）。
   - 价目：后端 store_price 富化 skuName/category/unit/duration/riskTags → DTO 摊平为 PriceItem 同名字段（name/code=sku/category/unit/duration/riskTags/originalPrice/memberPrice/promoPrice/status/pendingPrice）。
   - SKU 的 storeTypes/riskTags 后端逗号串 → DTO 数组；catalog.includes 文本 → DTO 数组。
4. **页面挂载加载**：在各 View 的 onMounted（或路由进入）调对应 store.load()；若页面已有 seed() 调用点，替换为 load()（load 内部失败回落 seed）。**不动模板结构、样式、交互文案**。
5. **类型检查**：`pnpm build`（vue-tsc --noEmit + vite build）必须过；后端 `mvn -q -f backend/pom.xml -pl store-service package -DskipTests`。

---

## 七、权限与审批

- **读**：room:view / equipment:view / brand:view / pricelist:view / catalog:view；**写**：room:edit / equipment:edit / brand:edit / catalog:edit / pricelist:edit（提交调价、建档、启停）。
- **调价审批**：不新增权限码。门店角色（店长/运营，持 pricelist:edit）可**提交**调价（PENDING）；**审批通过/驳回需 brand:approve**（集团/品牌角色持有，PermissionMatrix L181 已预留）。后端 approve/reject 端点标注 `@RequirePerm("brand:approve")`；change-request/toggle/建档标 `@RequirePerm("pricelist:edit")`。这样「门店调价、集团批价」职责分离，且与 m1 品牌/品项集团管控同权，语义一致。
- 控制器层 @RequirePerm + 网关鉴权双层；数据域在 Service/Controller 裁决（门店角色强制本店、集团模板仅集团/品牌可写）。

---

## 八、边界与决策点（需用户拍板）

> 以下为我基于竞品与现状的**建议方案**，请确认；确认后按 §九 批次开工。

- **D1 床位实时占用态（FREE/IN_USE/SANITIZING）**：
  - 建议【一期不持久化】。理由：占用态是随预约/治疗流转的交易态，真正做好需要预约占房 + 划扣退房联动（竞品 ymysoft 的「资源调度/智能预约分配」是独立大模块）。本批只把**主数据**（房间/床位档案、维护停用态）落库；页面入住/退房/消毒交互保留为前端演示态（刷新复位），床位「设维护/维护恢复」真实持久化。
  - 备选【本期连占用态一起落库】：bed 加 status/customer_name/project/occupied_at 字段，occupy/release/clean 调后端，但不与预约/治疗单据联动（仍手工点），刷新不丢。工作量增加且是「半套交易态」，易与未来预约调度冲突。
  - **我的推荐：一期不持久化占用态（选建议）**，把「预约—房间—治疗」联动作为独立后续批次。

- **D2 设备台账与 finance fin_asset 关系**：
  - 建议【一期分口、不打通】。store-service 设备台账管运营（编号/型号/位置/校准/维保/到期提醒/运营折旧），finance fin_asset 管财务折旧与月结；两套字段口径不同，竞品也多为行政台账与财务资产分口。本批设备台账独立落库，不回写 fin_asset、不做对账。
  - 备选【设备建档同步生成 fin_asset】：跨服务写财务资产，触碰资金/成本红线，需财务域审批，复杂度高。
  - **我的推荐：一期分口（选建议）**，未来如需「设备折旧进成本月结」再走 finance 成本镜像通道评估。

- **D3 三套项目/价格模型收敛**：
  - 建议【统一为 SKU + 门店价目两层】：m1Brand 的 Product 升级为 product_sku（项目主数据，含 riskTags/duration/unit）；旧 pricelist 的独立 PriceItem 不再单建项目实体，改为「store_price 引用 sku」的门店定价；catalog.includes 一期仍存项目名文本。品牌/品类/SKU 集团统一定义，门店价目门店可差异。
  - 备选【保留三张独立项目表】：pricelist 项目与 SKU 并存，数据重复、未来关联交易更乱。
  - **我的推荐：统一两层（选建议）**。旧 pricelist mock 的 11 条种子价目，迁移为「SKU + SST01 门店价」播种（项目名/SKU 对齐）。

- **D4 批次粒度**：
  - 建议【分 3 批，每批独立可交付、可双栈验证】：
    - **B13 房间床位 + 设备仪器**（room/equipment 两子包，4 张表，2 页面去 mock）——最独立、无审批、无跨模型收敛，先打通端到端范式。
    - **B14 项目目录三级主数据（品牌/品类/SKU）+ 门店价目 + 调价审批**（project/pricelist 两子包，4 张表，2 页面去 mock，含 D3 收敛与 brand:approve 审批）。
    - **B15 卡项疗程目录**（catalog 子包，1 张表，1 页面去 mock）。
  - **我的推荐：按 B13→B14→B15 顺序**，每批走「后端建表+端点 → 前端 api+store 去 mock（样式零改动）→ setup-seed-db.sh 种子 → seed/prod 双栈部署 curl 验证 → 交付文档」完整闭环。

---

## 九、批次实施计划与「本批不做」

**批次（待 D4 确认）**：
- **B13**：room 子包（treatment_room/treatment_bed/room_operation_log）+ equipment 子包（equipment/equipment_maintenance）；前端 api/room.ts、api/equipment.ts + room/equipment store 去 mock；种子（9 房 17 床、8 台设备，mock 数据原样入库 SST01）；RoomView/EquipmentView 接 API。
- **B14**：project 子包（product_brand/product_category/product_sku）+ pricelist 子包（store_price）；前端 api/projectMaster.ts、api/price.ts + m1Brand/pricelist store 去 mock；种子（4 品牌/8 品类/7 SKU + 11 条 SST01 门店价，含 2 条 PENDING 调价）；M2ProjectView/PricelistView 接 API；调价审批 brand:approve 落地。
- **B15**：catalog 子包（catalog_product）；前端 api/catalog.ts + catalog store 去 mock；种子（8 卡项/疗程）；CatalogView 接 API。

**种子与部署（每批）**：
- DataInitializer 仿 StoreBomDataInitializer（@Order 顺延、count()==0 幂等、仅 meiyun_seed/全环境播种门店档案——主数据两栈都需要，按现有 B5/B10 惯例 prod 也播种基础档案）。
- setup-seed-db.sh 追加新表 DDL（与 JPA 实体一致）+ TRUNCATE ... RESTART IDENTITY CASCADE + 重启播种 + sanity 计数（房间/床位/设备/品牌/品类/SKU/价目/卡项行数）。
- 部署 `bash scripts/deploy.sh <prod|seed> store-service` 与 `frontend`；双栈 curl 验证（--noproxy '*' -k，走 nginx 端口 seed 18080/prod 8080，登录 SE101/meiyun123）核对列表/写操作/权限拒绝/审计落库。

**本域明确不做（列后续）**：
1. 床位实时占用引擎、预约自动占房、治疗退房联动、医生/房间排班调度（D1 后续独立批次）。
2. 设备折旧回写 finance fin_asset / 设备折旧进成本月结（D2）。
3. 回改 txn_order.project / order_item / consult_plan_item.itemCode / writeoff_record 为 SKU 外键（交易侧关联，本批只预留编码）。
4. 卡项疗程 includes 改 SKU 外键、套餐核销与客户资产 course store 打通（电子卡券核销）。
5. 库存调拨/要货/盘点/多仓库/总部统采配送（库存交易域，B5 之后）。
6. 新增权限码 / Flyway 迁移 / 网关路由改动（均不需要）。
7. PrescriptionView 静态项目数组改读 SKU（可选收尾，不阻断）。

---

## 十、验证标准（每批交付门槛）

- 后端：store-service 编译通过；新表 JPA 自动建表；端点 @RequirePerm 生效（无权限 403）、数据域裁决生效（门店角色越店 422/__NONE__）、写接口四件套（校验 422/幂等 409/审计落 audit-service/中文错误）。
- 前端：`pnpm build` 通过；5 页面样式与交互与去 mock 前一致（截图比对）；列表/筛选/新建/状态流转/审批走真实 API；后端不可达时回落演示数据不白屏。
- 双栈：seed 栈（18080/18443）与 prod 栈（8080/8443）均 curl 核对种子行数与 CRUD；审计可查；交付文档仿 DELIVERY-P3-B11 七节体例。
