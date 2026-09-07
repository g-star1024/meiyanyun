# P5-B14 落地方案设计：项目目录（品牌→品类→SKU）+ 门店价目 + 调价审批

> 时间：2026-09-07
> 状态：**用户已拍板 E1~E4 决策（均按推荐），按本文档实施**
> 上位设计：[DESIGN-P4-STORE-MASTERDATA-2026-09-07.md](DESIGN-P4-STORE-MASTERDATA-2026-09-07.md) §4.3/§4.4/§5.3/§5.4（表与端点总框架以 P4 为准，本文档只记录 B14 落地细化与决策偏离）
> 批次定位：P4 批次 D4 之 B14（B13 房间床位+设备仪器已交付，见 DELIVERY-P4-B13）；B15 卡项疗程目录为下一棒。

---

## 一、目标与范围

把两个纯 mock 页面域落到 store-service（8085）真实库表与 API：

1. **项目目录三级主数据**（集团统一定义）：品牌 → 品类（二级树）→ 项目 SKU。承接页面：M1BrandView（/m1-brand 集团品牌品类）与 M2ProjectView（/m2-projects 门店医美项目库），两页共用 `m1Brand` store。
2. **门店价目 + 调价审批**：门店对 SKU 的定价（原价/会员价/活动价）与「提交调价 → 待审批 → 通过/驳回」三态状态机。承接页面：PricelistView（/m2-pricelist），store 为 `pricelist`。

边界（承 P4 §八）：
- 品牌/品类/SKU 为**集团级**数据（无 store_code）；门店价目为**门店级**数据（store_code，空串=集团指导价模板，本批不播种模板行）。
- 网关零改动（全部挂 `/api/stores/**`，router.go 已有 8085 路由）；权限码零新增（brand:view/edit/approve、pricelist:view/edit 均已在 PermissionMatrix 预留）；业务表 JPA ddl-auto，不进 Flyway。
- 金额后端 bigint 存分、读接口出参元（字段名带 Yuan）、写接口入参分（字段名带 Fen）；审计复用 ConsumableAuditRecorder，bizType 用 `BRAND`（品牌/品类/SKU 动作）与 `PRICE`（价目/调价动作），payload 为合法 JSON。

## 二、决策记录（2026-09-07 用户拍板）

| 编号 | 议题 | 决策 |
|---|---|---|
| E1 | 价目页「审批通过/驳回」按钮权限 | **按钮显隐与 action 校验改 `brand:approve`**。门店店长（pricelist:edit）只见「申请调价/停用」；区域经理/超管（brand:approve）才见「通过/驳回」。模板仅换权限码绑定值，结构样式零改动。 |
| E2 | 价目页「服务分类」5 大类（注射美容/光电仪器/皮肤管理/形体管理/检测咨询）与品牌品类树对不上 | **product_sku 增加 `service_category` 列**（varchar(16)，枚举 INJECTION/LASER/SKINCARE/BODY/EXAM，可空）。品牌品类树管「品牌→产品线」归属，服务大类管「门店经营分类」，两者并存；价目筛选/展示用 service_category（后端富化时从 SKU 带出）。 |
| E3 | 调价是否接统一审批中心（txn-service approval_todo） | **页内轻审批，不接审批中心**。store_price 的 PENDING 状态机 + brand:approve 在价目表页内闭环；审批中心 PRICE_CHANGE 与本批不互通，列后续批次。 |
| E4 | 提交人/审批人分离验证账号 | **不新增账号，反向验证**。seed 库 SE001 许店长（STORE_MGR，有 pricelist:edit、无 brand:approve）提交调价并验证其调 approve/reject 被 403 拒绝；SE101 周岚（SUPER_ADMIN）/SE102 陈野（REGION_MGR，持 brand:approve）审批通过。org-service 种子零改动。 |

补充落地裁决（设计内细化，不改变用户决策）：
- **SKU 新建/编辑表单不加字段**：M2ProjectView 新建项目弹窗无「服务大类/风险标签」录入项，为守样式零改动，`service_category`/`risk_tags` 由种子全量播种、新建 SKU 时暂为 null（更新接口不覆盖该两列）；待未来「门店建档价目」交互上线时再补录入。价目行一期全部来自种子，筛选不受影响。
- **价目行编码 = SKU 编码**：store_price 不设独立 price_code，列表「项目编码」列展示 sku（适配层 `code=sku`）；价目项目名取 SKU name（D3 收敛后项目名统一）。
- **审批人数据域**：approve/reject 除 @RequirePerm("brand:approve") 外，REGION scope 审批人经 DataScope.canReadStore(price.storeCode) 校验，越区审批 400；SUPER/GROUP 放行。

## 三、数据模型（store-service，JPA 实体 ddl-auto）

### 3.1 product_brand（品牌，集团级）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| brand_code | varchar(32) not null | 如 BR-ALLERGAN，uk_brand_code 唯一 |
| name | varchar(64) not null | 品牌名 |
| short_name | varchar(32) | 简称 |
| origin | varchar(64) | 产地 |
| supplier | varchar(128) | 供应商 |
| status | varchar(16) not null default 'ACTIVE' | ACTIVE/INACTIVE |
| logo_color | varchar(16) | 头像色（种子轮起色；新建可空，前端兜底轮色） |
| remark | varchar(255) | |
| 审计四字段 | created_by/created_at/updated_by/updated_at | 仿 B13 @PrePersist |

### 3.2 product_category（品类，二级树，集团级）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| category_code | varchar(32) not null | uk_category_code 唯一 |
| name | varchar(64) not null | |
| brand_id | bigint not null | 所属品牌（逻辑外键） |
| parent_id | bigint | 父品类（空=一级） |
| status | varchar(16) not null default 'ACTIVE' | |
| sort | int not null default 0 | |
| remark | varchar(255) | |
| 审计四字段 | | |

删除规则：本品类**或其子品类**名下仍有 SKU → 422「该品类（含子品类）下仍有项目，无法删除」；通过校验后连带删除子品类（比 mock 仅查直接挂项更严，防孤儿 SKU）。

### 3.3 product_sku（项目/产品 SKU，集团级）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| sku | varchar(40) not null | uk_sku 唯一，如 AGN-BTX-100 |
| name | varchar(64) not null | 项目/产品名 |
| brand_id | bigint not null | |
| category_id | bigint not null | |
| unit | varchar(8) not null | 次/支/盒/部位/疗程/小时 |
| list_price_fen | bigint not null default 0 | 集团挂牌/指导价（分） |
| cost_price_fen | bigint not null default 0 | 成本价（分） |
| status | varchar(16) not null default 'ACTIVE' | ACTIVE/INACTIVE（受控停用，不物理删） |
| store_types | varchar(32) not null default '' | 适用门店类型逗号串 FLAGSHIP,COMMUNITY,CLINIC |
| duration_min | int not null default 0 | 预计时长（分钟） |
| service_category | varchar(16) | **E2 新增**：INJECTION/LASER/SKINCARE/BODY/EXAM，可空 |
| risk_tags | varchar(64) | 风险标签逗号串 INJECTION/LASER/HIGH_ENERGY/ANESTHESIA/PREGNANCY_RISK，可空 |
| remark | varchar(255) | |
| 审计四字段 | | |

### 3.4 store_price（门店价目，门店级）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint IDENTITY PK | |
| store_code | varchar(16) not null | 门店码；空串=集团指导价模板（本批不播模板） |
| sku | varchar(40) not null | 逻辑外键 product_sku.sku，uk_price_store_sku(store_code, sku) 唯一 |
| original_price_fen | bigint not null default 0 | 原价（划线，分） |
| member_price_fen | bigint not null default 0 | 会员价/执行价（分） |
| promo_price_fen | bigint | 活动价（分，可空） |
| status | varchar(16) not null default 'ACTIVE' | ACTIVE 启用 / DISABLED 停用 / PENDING 待审批 |
| pending_member_price_fen | bigint | 待审批会员价 |
| pending_promo_price_fen | bigint | 待审批活动价（可空） |
| pending_reason | varchar(255) | 调价原因 |
| requested_by | varchar(32) | 申请人（JWT staffName） |
| requested_at | timestamptz | 申请时间 |
| 审计四字段 | | updated_by/updated_at 随审批/切换更新 |

**调价状态机**（对齐 mock requestPriceChange/approve/reject/toggleStatus）：

```
ACTIVE ──change-request(pricelist:edit)──▶ PENDING ──approve(brand:approve)──▶ ACTIVE（pending 覆盖正式价）
  ▲                                          │
  └─toggle(pricelist:edit)─ DISABLED         └──reject(brand:approve)──▶ ACTIVE（清 pending，原价保留）
PENDING 态：不可再提交、不可 toggle、不可重复审批（409 幂等，不重复记审计）
```

## 四、API 契约（/api/stores，网关零改动）

入参 record 嵌套在 Controller；读接口出参裸 List/Map（store-service 无 Result 壳，同 B13）；金额读元（Yuan）/写分（Fen）；storeTypes/riskTags 出参数组化、入参逗号串或数组均可（后端统一 split/join）。

### 4.1 项目目录（brand:view 读 / brand:edit 写；集团数据不做门店裁决）

- `GET /api/stores/brands` → 品牌列表，每项含 stats：categoryCount/productCount/activeProductCount/avgListPriceYuan（Service 聚合）。
- `POST /api/stores/brands`（{code,name,shortName,origin,supplier,remark,status?}）→ code 重复 409。
- `POST /api/stores/brands/{id}`（更新同名字段）→ 不存在 404。
- `POST /api/stores/brands/{id}/status`（{status}）→ 启停；已是目标态幂等返回不审计。
- `GET /api/stores/categories?brandId=` → 品类列表（树形由前端按 parentId 组）。
- `POST /api/stores/categories`（{code,name,brandId,parentId,sort?,remark,status?}）→ code 重复 409；brandId 不存在 400；sort 缺省同品牌 max+1。
- `POST /api/stores/categories/{id}`（更新）、`POST /api/stores/categories/{id}/status`（{status}）。
- `DELETE /api/stores/categories/{id}` → 名下（含子品类）有 SKU → 422；否则连带删子品类。
- `GET /api/stores/skus?brandId=&categoryId=&keyword=&status=` → SKU 列表（出参 listPriceYuan/costPriceYuan、storeTypes[]/riskTags[]/serviceCategory）。
- `POST /api/stores/skus`（{sku,name,brandId,categoryId,unit,listPriceFen,costPriceFen,storeTypes[],durationMin,status?,remark}）→ sku 重复 409；品牌/品类不存在 400。
- `POST /api/stores/skus/{id}`（更新；不覆盖 serviceCategory/riskTags）、`POST /api/stores/skus/{id}/status`（{status}）。

### 4.2 门店价目（pricelist:view 读；pricelist:edit 提交/建档/切换；brand:approve 审批）

- `GET /api/stores/prices?storeCode=&category=&status=&keyword=` → 价目列表，富化 skuName(=name)/code(=sku)/category(=sku.serviceCategory)/unit/durationMin/riskTags（join product_sku）；金额元 originalPriceYuan/memberPriceYuan/promoPriceYuan；pendingPrice 嵌套对象（memberPrice/promoPrice/reason/requestedAt/requestedBy，元）。读裁决复用 resolveStoreCode（门店角色强制本店，越店读返回 `__NONE__` 哨兵空集；高权限可传 storeCode）。
- `POST /api/stores/prices`（{storeCode,sku,originalPriceFen,memberPriceFen,promoPriceFen}）→ 门店建档定价；同店同 SKU 409；sku 不存在 400；写裁决 resolveWriteStoreCode。
- `POST /api/stores/prices/{id}/change-request`（{storeCode,memberPriceFen,promoPriceFen,reason}）→ reason 必填（空 400「请填写调价原因」）；非 ACTIVE 态 409；写后 status=PENDING、记 pending_*、requested_by=JWT staffName。
- `POST /api/stores/prices/{id}/approve` → 需 brand:approve；REGION 越区 400；非 PENDING 409（幂等不审计）；通过后 pending 覆盖正式价（promo 可空覆盖）、清 pending、status=ACTIVE。
- `POST /api/stores/prices/{id}/reject` → 需 brand:approve；非 PENDING 409；清 pending、status=ACTIVE。
- `POST /api/stores/prices/{id}/toggle`（{storeCode}）→ 需 pricelist:edit；PENDING 态 409「调价审批中，不可停用/启用」；ACTIVE↔DISABLED。

审计动作文案（bizType=PRICE）：「门店价目建档」「提交调价申请」「审批通过调价」「驳回调价申请」「启用价目」「停用价目」；BRAND 域：「新建品牌」「更新品牌」「品牌启停」「新建品类」「更新品类」「品类启停」「删除品类」「新建项目SKU」「更新项目SKU」「项目SKU启停」。幂等动作不重复记审计。

## 五、种子设计（StorePriceCatalogDataInitializer，@Order(70)，count 门控幂等）

全环境播种（主数据两栈都需要，同 B13 惯例）；种子方法不写审计。OP="system"；两条 PENDING 价目 requested_by 播 SST01 店长「许店长」。

**品牌 9**（复刻 m1Brand 4 个 + 新增 5 个）：

| brand_code | 名称 | 产地 | 状态 |
|---|---|---|---|
| BR-ALLERGAN | 艾尔建 | 美国/爱尔兰 | ACTIVE |
| BR-BLOOMAGE | 华熙生物 | 中国山东 | ACTIVE |
| BR-SINOGEN | 中韩光电 | 中国北京 | ACTIVE |
| BR-LUMENIS | 科医人 | 以色列 | INACTIVE |
| BR-GALD | 高德美 | 瑞士 | ACTIVE（瑞蓝） |
| BR-SBM | 圣博玛 | 中国长春 | ACTIVE（艾维岚） |
| BR-PENINSULA | 半岛医疗 | 中国深圳 | ACTIVE（超声炮） |
| BR-BTL | BTL | 英国 | ACTIVE（美修斯） |
| BR-CANFIELD | Canfield | 美国 | ACTIVE（VISIA） |

**品类 16**：艾尔建 4（注射美容 CT-INJECT 一级 / 肉毒素 CT-BTX / 玻尿酸填充 CT-FILLER / 形体减脂 CT-BODY）；华熙 2（水光补水 CT-HA / 功能性护肤 CT-SKINCARE）；中韩光电 2（激光治疗 CT-LASER / 射频紧致 CT-THERMO）；科医人 1（光子嫩肤 CT-IPL）；高德美 2（注射美容 CT-GD-INJECT / 玻尿酸填充 CT-GD-FILLER 子）；圣博玛 2（注射美容 CT-SBM-INJECT / 再生抗衰 CT-SBM-REGEN 子）；半岛 1（超声抗衰 CT-PEN-ULTRA）；BTL 1（形体管理 CT-BTL-BODY）；Canfield 1（检测咨询 CT-CF-EXAM）。

**SKU 15**（复刻 m1Brand 7 个 + 新增 8 个；价格照 m1Brand mock 原样，挂牌价与门店价两层允许不同）：

| sku | 名称 | 品牌 | 品类 | service | 单位 | 挂牌价 | 成本 | 时长 | 状态 |
|---|---|---|---|---|---|---|---|---|---|
| AGN-BTX-100 | 保妥适 100U 瘦脸针 | 艾尔建 | CT-INJECT | INJECTION | 次 | 3800 | 1650 | 30 | ACTIVE |
| AGN-JUV-1ML | 乔雅登极致 1ml 玻尿酸 | 艾尔建 | CT-INJECT | INJECTION | 支 | 6800 | 3200 | 45 | ACTIVE |
| AGN-COOL-BODY | 酷塑冷冻溶脂（单部位） | 艾尔建 | CT-BODY | BODY | 部位 | 8800 | 3600 | 60 | ACTIVE |
| HX-RST-2.5ML | 润致娃娃针 2.5ml | 华熙 | CT-HA | INJECTION | 支 | 1980 | 680 | 40 | ACTIVE |
| HX-QUADHA | 润百颜次抛精华（疗程） | 华熙 | CT-SKINCARE | SKINCARE | 盒 | 880 | 220 | 0 | ACTIVE |
| HX-AQUA-BASE | 基础水光针 | 华熙 | CT-HA | INJECTION | 次 | 980 | 260 | 30 | ACTIVE |
| HX-BUBBLE | 小气泡深层清洁护理 | 华熙 | CT-SKINCARE | SKINCARE | 次 | 380 | 90 | 45 | ACTIVE |
| ZH-THERMAGE-FL | 热玛吉FLX 面部900发 | 中韩光电 | CT-THERMO | LASER | 部位 | 19800 | 7200 | 90 | ACTIVE |
| ZH-PICOWAY | 超皮秒全模式 | 中韩光电 | CT-LASER | LASER | 次 | 2980 | 980 | 40 | ACTIVE |
| LUM-M22 | M22王者之冠 光子嫩肤 | 科医人 | CT-IPL | LASER | 次 | 1280 | 420 | 30 | INACTIVE |
| GD-RESTYLANE-2 | 瑞蓝2号玻尿酸 1ml | 高德美 | CT-GD-FILLER | INJECTION | 支 | 6800 | 3100 | 30 | ACTIVE |
| SBM-AETHETE | 艾维岚童颜针（少女针） | 圣博玛 | CT-SBM-REGEN | INJECTION | 支 | 18800 | 8600 | 40 | ACTIVE |
| PEN-ULTRA-PRO | 半岛超声炮（面部） | 半岛 | CT-PEN-ULTRA | LASER | 次 | 19800 | 7600 | 70 | ACTIVE |
| BTL-EMSCULPT | BTL美修斯美体塑形 | BTL | CT-BTL-BODY | BODY | 次 | 1280 | 480 | 40 | ACTIVE |
| CF-VISIA | VISIA 皮肤检测 | Canfield | CT-CF-EXAM | EXAM | 次 | 200 | 60 | 15 | ACTIVE |

风险标签随 SKU 播种（保妥适/乔雅登/瑞蓝/艾维岚/娃娃针/基础水光=INJECTION；光电类=LASER；热玛吉/超声炮/酷塑=HIGH_ENERGY；热玛吉/超声炮/酷塑/BTL=PREGNANCY_RISK；水光类=ANESTHESIA）。

**SST01 门店价目 11 条**（复刻 pricelist mock 11 条全字段，sku 关联如上；8 ACTIVE / 2 PENDING / 1 DISABLED）：

| 价目项目（mock 名） | 关联 sku | 原价 | 会员价 | 活动价 | 状态 |
|---|---|---|---|---|---|
| 玻尿酸填充（瑞蓝2号） | GD-RESTYLANE-2 | 6800 | 5800 | 5280 | ACTIVE |
| 肉毒素（保妥适） | AGN-BTX-100 | 4800 | 4200 | null | ACTIVE |
| 少女针（艾维岚） | SBM-AETHETE | 18800 | 16800 | null | PENDING（申请会员 15800/活动 14800，原因「暑期抗衰活动，需配合整体促销方案下调」，许店长） |
| 热玛吉FLX 面部 | ZH-THERMAGE-FL | 28800 | 25800 | 23800 | ACTIVE |
| 超声炮（半岛） | PEN-ULTRA-PRO | 19800 | 17800 | 16800 | ACTIVE |
| 光子嫩肤（M22） | LUM-M22 | 1980 | 1680 | 1280 | ACTIVE |
| 水光针（基础） | HX-AQUA-BASE | 980 | 780 | 580 | ACTIVE |
| 小气泡深层清洁 | HX-BUBBLE | 380 | 280 | 198 | DISABLED |
| 冷冻溶脂（单部位） | AGN-COOL-BODY | 8800 | 7800 | null | ACTIVE |
| BTL 美体塑形 | BTL-EMSCULPT | 1280 | 980 | 780 | PENDING（申请会员 880/活动 null，原因「新客拓客，下调体验价」，许店长） |
| VISIA 皮肤检测 | CF-VISIA | 200 | 0 | null | ACTIVE |

> 说明：价目项目名以 SKU name 为准（D3 收敛）；上表左列 mock 名仅作播种对照，实际页面展示 SKU 名（如「瑞蓝2号玻尿酸 1ml」）。未被价目引用的 4 个 SKU（乔雅登/润百颜/娃娃针/超皮秒）只出现在项目库、不出现在价目表（门店未建档定价）。

setup-seed-db.sh 追加：4 表 `CREATE TABLE IF NOT EXISTS` DDL（与实体一致）+ TRUNCATE 列表加 product_brand/product_category/product_sku/store_price + sanity 4 行计数（品牌 9/品类 16/SKU 15/价目 11）+ store-service 重启段注释更新。

## 六、前端去 mock（样式零改动）

1. **新增 api**（仿 api/equipment.ts 范式，client 基础路径已带 /api）：
   - `api/projectMaster.ts`：listBrands/createBrand/updateBrand/setBrandStatus；listCategories/createCategory/updateCategory/setCategoryStatus/deleteCategory；listSkus/createSku/updateSku/setSkuStatus。DTO 金额元（listPriceYuan/costPriceYuan）、id number→适配层转字符串、storeTypes/riskTags 数组↔逗号串、serviceCategory 透传。
   - `api/price.ts`：listPrices/createPrice/requestPriceChange/approvePriceChange/rejectPriceChange/togglePrice。DTO 金额元；pendingPrice 嵌套；富化字段摊平（code=sku、name=skuName、category=serviceCategory、unit、duration=durationMin、riskTags）。
2. **store 改造（对外 action/getter 签名不变，页面 script 适配层消化差异）**：
   - `stores/m1Brand.ts`：加 `load()`（调 3 个 list 接口填充 brands/categories/products，加载失败 catch → 回落 seed() 并置 demoMode，控制台告警）；createBrand/updateBrand/setBrandStatus/createCategory/updateCategory/setCategoryStatus/deleteCategory/createProduct/updateProduct/setProductStatus 改 async 调 api 后 `await load()` 刷新；错误透传（中文 message 由全局错误体给出）。logoColor 后端为空时前端轮色兜底。
   - `stores/pricelist.ts`：加 `load()`（拉价目适配成 PriceItem 同形状，失败回落 seed）；requestPriceChange/approvePriceChange/rejectPriceChange/toggleStatus 改 async 调 api 后刷新；**approve/reject 内 `auth.can('pricelist:edit')` 改为 `auth.can('brand:approve')`（E1）**；activity.log 保留。
3. **页面改动（仅 script/绑定值，不动 template 结构与 style）**：
   - PricelistView：审批通过/驳回按钮的权限判断由 `pricelist:edit` 改 `brand:approve`（E1）；onMounted 的 seed() 改 store.load()。
   - M1BrandView / M2ProjectView：onMounted seed() 改 load()；其余零改动。
   - 失败回落演示数据时不新增角标 UI（页面无角标位），仅控制台告警（同 P4 §六.2）。
4. **类型检查**：`pnpm build`（vue-tsc + vite）0 error。

## 七、验证计划（双栈真实通过，拒假交付）

1. 后端 `mvn -q -f backend/pom.xml -pl store-service package -DskipTests` 出 fat jar；双栈部署 store-service + frontend。
2. **curl E2E（seed 18080/18443 起，prod 8080/8443 回归）**：
   - 登录 SE101 周岚：GET /brands（9 品牌含 stats）、/categories（16）、/skus（15，serviceCategory/storeTypes 正确）、/prices?storeCode=SST01（11 条富化字段齐、金额元、2 条 PENDING 嵌套）。
   - 写链路：新建品牌（409 重码）→ 新建品类 → 新建 SKU；SKU 启停；品类删除 422（有项目）。
   - 调价全流程：店长 SE001 提交 change-request（200，PENDING）→ SE001 调 approve → **403（E4 反向验证）** → SE101 approve → 200 新价生效；再对第二条 PENDING 走 reject → 回原价；toggle 停用/启用；PENDING 态 toggle → 409。
   - 鉴权：无 token 401；SE104 钱进（FINANCE，无 pricelist:view）GET /prices → 403；SE103 白桥（OPERATOR，无 brand:view）GET /brands → 403。
   - 审计：audit_log 查 BRAND/PRICE  biz_type 各动作落库、payload 为合法 JSON、幂等动作无重复审计。
   - DB 三对口：页面/接口填了什么 → store_price/product_sku 落了什么。
3. **浏览器联调 + 截图基线比对**：/m1-brand、/m2-projects、/m2-pricelist 三页接入前后截图并排（布局/列表行/详情/弹窗一致，字段不丢）；价目页用店长账号验按钮显隐（无审批按钮）、超管账号验审批通过/驳回；新建品牌/品类/SKU、申请调价、审批、停用各跑一次真实写。
4. 种子重置：重跑 setup-seed-db.sh，sanity 9/16/15/11，UI 残留与审计清零。

## 八、本批不做

1. 卡项疗程目录（catalog_product，B15）。
2. 调价接入统一审批中心 approval_todo / 审批中心 PRICE_CHANGE 回写价目（E3 后续）。
3. SKU 新建/编辑表单的服务大类、风险标签录入项（待门店建档价目交互上线一并补）。
4. 门店建档价目（POST /prices）的前端入口（价目页无「新增价目」按钮，端点先备，种子播 11 条）。
5. 交易侧关联 SKU（txn order_item / consult_plan_item / writeoff 回改外键）、卡项 includes 改 SKU 外键。
6. PrescriptionView 静态项目数组改读 SKU（可选收尾，不阻断）。
7. 新增权限码 / Flyway 迁移 / 网关路由改动（均不需要）。
