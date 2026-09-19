# P5-B63 卡4（04-backlog L86）进项税抵扣链路 —— 卡0 只读侦察与 B 类口径书面定案

> 时间：2026-09-19
> 状态：**方案定案（卡0 产出，定案前不写业务代码）**
> 用户决策原文（逐字）：
> 「1、先处理第二层近线，可以全部开始；2、然后处理core 库 24 行营销演示残留仿 B57 备份+物理删除；3、B. 产品口径 / 状态机 / 选型决策 按照你推荐的来就可以，做好竞品调研选择最合适的就行；以上全部完成后转入C. 大阶段 / 专项开工」
> 事实基础：六册台账已读齐；代码实证（finance-service 实体/迁移/服务/控制器、org-service 权限矩阵与幂等播种、store-service 供应商、前端三收口点、网关透传规则）；联网政策与竞品调研六项（国税总局公告 2019 年第 45 号、增值税法及实施条例国令第 826 号、2026 年度申报期限日历、留抵与进项转出口径、金蝶云星空/发票云/税务云、用友 YonSuite）。
> 铁律遵循：链路先行＋样式零改（-1）；真实库/接口>代码>文档（0）；API 三处一致（1）；Flyway 系统表、金额存分（2）；中文枚举（3）；Page+Specification（4）；三轨真验（7）；卡片级 commit/push、代码与 docs 分离（8）；台账六册开发前读交付后写（9/10）；哨兵续跑（11）；OCR 门禁（12）。
> 纵深批数字锁定：✅109 / 🔧1 / ⬜55 ＝ 166；域⑥ 财务 11✅ / 0🔧 / 1⬜。本卡**无新页面、无 ⬜→✅、无 🔧 消解**，闭合仅勾 04-backlog L86 一行，数字一律不变。

---

## 〇、调研总述

### 0.1 法规环境（2026-09 项目时点适用新法）

| 事项 | 结论 | 出处 |
|---|---|---|
| 增值税基本法 | 《中华人民共和国增值税法》及《增值税法实施条例》**国令第 826 号，2025-12-25 公布、2026-01-01 施行**；原《增值税暂行条例》同步废止。本卡口径一律以新法＋实施条例为准 | 国令第 826 号 |
| 扣税凭证五类 | (一)增值税专用发票（含税控机动车销售统一发票）；(二)海关进口增值税专用缴款书；(三)自境外购进劳务/服务/无形资产/不动产的完税凭证；(四)农产品收购发票或销售发票；(五)其他 | 实施条例**第十一条** |
| 可抵扣进项五项正列举 | (一)销售方专票注明税额；(二)海关缴款书注明税额；(三)境外购进完税凭证注明税额；(四)农产品按买价×扣除率计算；(五)其他凭规定准予抵扣 | 实施条例**第十二条** |
| 折让/中止/退回 | 销售方扣减当期销项、购进方扣减当期进项（双向红字调整） | 实施条例**第十三条** |
| 认证（用途确认）期限 | 2017-01-01 后开具的专票/海关缴款书/机动车发票/通行费电子普票，**取消 360 日认证确认与申报抵扣期限**（2020-03-01 起）；改增值税发票综合服务平台「用途确认（勾选）」 | 国税总局公告 **2019 年第 45 号** |
| 不得抵扣七项 | 见 §3.3 定案枚举（实体内容延续原 36 号文附件，新法下按实施条例第十二条反向口径执行） | 现行规定 |
| 申报期限 | 按月或按季申报，期满之日起 **15 日内**；2026 年度顺延（税总办征科函〔2025〕64 号）：1/20、2/24、3/16、4/20、5/22、8/17、10/26、11/16，6/7/9/12 月为 15 日；逾期走电子税务局逾期补申报，符合条件首违不罚 | 64 号函 |
| 留抵 | 应纳税额＝当期销项−当期可抵扣进项；进项＞销项差额为**期末留抵结转下期**（申报表第 20 项→次期第 13 项）；留抵退税另有限定条件（制造业等行业、A/B 信用等，2025 年第 7 号公告） | 现行规定 |
| 税率 | 医美属生活服务业：一般纳税人 **6%**，小规模 **3%**（医疗服务归教育医疗服务、美容美发归居民日常服务）；一般纳税人门槛年应税销售额 500 万 | 现行规定 |

### 0.2 竞品对标（进项模块形态）

| 产品 | 进项链路关键形态 | 本卡取舍 |
|---|---|---|
| 金蝶云·星空 | 采购发票登记 → **认证管理**（勾选/勾选确认/可抵扣额维护）→ 进项明细表、账龄分析 | 采纳「登记→用途确认→抵扣」主链与「可抵扣额」字段 |
| 金蝶星瀚发票云（F12006） | 直连税局；预勾选/**抵扣勾选/不抵扣勾选/退税勾选**四类用途；已生成统计表后不得再勾选；小规模不支持抵扣勾选；入账状态 01 未入账/02 已入账/03 已入账撤销 | 采纳「抵扣/不抵扣/退税」三用途与「确认后锁定」；**税局直连不做** |
| 金蝶税务云 | 全票种闭环、OCR、三单匹配（订单/入库/发票）、自动凭证 | OCR/三单匹配/自动凭证全部列远期 Backlog |
| 用友 YonSuite | 财务云＞税务服务＞进项发票管理＞**抵扣类勾选**；销项/进项分列；角色＝税务会计 | 采纳「进项独立菜单/页签、不与销项混表」；角色映射 FINANCE |

**推荐路线（定案）**：在既有 M6 财务域内做**内部进项台账 + 用途确认状态机 + 抵扣汇总 + 申报期管理**，挂载既有发票页/税务页（样式零改），不新增页面、不做税局直连/OCR/三单匹配/自动凭证/留抵退税办理。

---

## 一、现有基础设施盘点（实证事实，2026-09-19 复核）

| # | 事实 | 位置 | 对本卡影响 |
|---|---|---|---|
| 1 | Flyway 迁移仅 V4/V6/V7/V8/V33/V37/**V38** 七版，**V39 空位** | `finance-service/src/main/resources/db/migration/` | 本卡下版 **V39**，双库（meiyun_core/meiyun_seed）可重入纯 DDL |
| 2 | `fin_invoice` 为**销项**表（JPA ddl-auto，无 Flyway）：发票号 INV-yyyyMMdd-0001、type/category、title/taxNo（销方为本公司）、amount/tax_amount/tax_rate、buyer_name、store_code、status=DRAFT/ISSUED/VOIDED/RED_FLUSHED、operator/reviewer、idem_key；**无 direction、无供应商、无认证/抵扣字段** | `FinInvoice.java`（91 行） | **不与销项混表**：字段语义与状态机差异大，独立新建进项实体 |
| 3 | `tax` 表仅四列（cat/base/rate/amount），无种子无 save，全库空表；`GET /api/finance/tax` 返 `List<Tax>`、`/tax/total` 返 `{totalAmount,catCount}` | `Tax.java`、`FinanceController.java:139-153` | 申报期另建实体，**不动此两端点**；进项抵扣走新端点 |
| 4 | 税率白名单五档：`0 / 0.01 / 0.03 / 0.06 / 0.13`；`computeTaxFen(amountFen, rate)` 价税分离（HALF_UP：net=round(amount/(1+rate))，tax=amount−net）；非五档 `parseInvoiceRate` 抛中文 422 | `FinConfigService.java:60-63, 469-494` | **税基口径直接复用**，不自造算法；前端 RATES 三处一致 |
| 5 | 审计记录器 `FinanceAuditRecorder.record(bizType, txnNo, actor, action, payload)`：POST audit-service，失败回落 `INSERT audit_outbox`；**payload 必须合法 JSON 字符串** | `FinanceAuditRecorder.java`（68 行） | 新 biz_type 复用，见定案⑦ |
| 6 | 权限真源 `PermissionMatrix`：finance view 码 15 个（L91-105）＋写码（invoice:edit/approve 等 L312-316）＋FINANCE 角色授权 L990-1023；**全仓无 input/deduct 码** | org-service `PermissionMatrix.java` | 新增三码见定案⑥ |
| 7 | 幂等播种：`RbacDataInitializer` 启动时 findAll 补缺 permission_def，并按矩阵对账式增删 role_permission（内置角色回收删除项，"*" 不落库） | `RbacDataInitializer.java:263-305` | **改矩阵即代码，重启自动播种，不写权限种子 SQL** |
| 8 | 前端税务 store：`inputDeduct = ref(0)`，注释明写「进项抵扣无数据源（采购/供应商发票未建），诚实为 0」；`taxPayable = max(0, outputTax − inputDeduct)`；四者均参与导出 | `stores/finReports.ts:432-438, 602` | 收口点①：inputDeduct 接真，导出形状不变 |
| 9 | 税务页 4 KPI（含「进项抵扣」teal 卡）、donut、税种明细表；表头**硬编码「税种明细（2026-08）」**、每行**硬编码 CStatusPill「待申报」**、红线文案在 L90-93 | `views/FinTaxView.vue`（132 行） | 收口点②：期间标签＋申报状态 pill 由后端驱动 |
| 10 | 发票页 472 行，**无 Tab 结构**（4 KPI＋左列表/右详情/弹窗）；canEdit=`finance:invoice:edit`；双签 reviewer trim≥2 字符 | `views/FinInvoiceView.vue` | 收口点③：挂「进项」tab，.tabs/.tab 样式整组照抄同类页 |
| 11 | 供应商为**集团级**表（无 store_code）：code/name/contact/phone/payment_terms/qualified/status/remark；**无税号/地址电话/开户行** | store-service `procurement/Supplier.java`（59 行） | 不跨服务改表；销方字段在进项实体内冗余＋supplier_id 选填软关联 |
| 12 | 控制器范式：`@RestController @RequestMapping("/api/finance") @RequirePerm("finance:view")` 类级兜底，方法级细码覆盖；Map 视图（元）薄调 Service；写接口四件套收敛在 Service | `FinConfigController.java` 等 | 新 controller 照此范式 |
| 13 | 网关前缀整段透传 `{"/api/finance", FINANCE_SERVICE_URL, ...}` | `gateway/internal/proxy/router.go:33` | 新 `/api/finance/input-invoices/**`、`/api/finance/tax-periods/**` **自动覆盖，网关零改** |
| 14 | 前端 client baseURL `/api`，发票 API 范式 getInvoices/createInvoice/issue/void/red-flush 齐备 | `api/finance.ts:525-550` | 新增进项 API 同文件同风格追加 |
| 15 | V38 DDL 范式：头注释块（背景/首启安全/幂等）→`CREATE TABLE IF NOT EXISTS`→VARCHAR 定长/BIGINT 分/TIMESTAMPTZ→`chk_` CHECK→`idx_`/`uk_`（含 WHERE 部分唯一索引）→纯 DDL 零 INSERT→审计四列 created_by/updated_by VARCHAR(16) DEFAULT 'system' | `V38__prepay_monitor.sql`（76 行） | **V39 严格照此范式** |

---

## 二、定案①：实体与表（V39，两表，纯 DDL 零种子）

### 2.1 `fin_input_invoice`（进项发票登记簿）

系统表走 Flyway（铁律 2）；金额三列全部 BIGINT 存分。

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT GENERATED ALWAYS AS IDENTITY PK | | 主键 |
| register_no | VARCHAR(24) NOT NULL | UNIQUE | 内部登记号 `PINV-yyyyMMdd-0001`（仿销项 nextInvoiceNo，独立序列，synchronized） |
| invoice_code | VARCHAR(32) | | 发票代码（专票有代码；电子/海关票据可空） |
| invoice_no | VARCHAR(32) NOT NULL | | 发票号码（外部税局号码，原样登记） |
| invoice_kind | VARCHAR(16) NOT NULL | chk IN ('SPECIAL','CUSTOMS','TOLL','PASSENGER','OTHER') | 扣税凭证五类（实施条例 §11）：专票/海关缴款书/通行费电子普票/旅客运输/其他 |
| seller_name | VARCHAR(128) NOT NULL | | 开票方名称（冗余，录入即活） |
| seller_tax_no | VARCHAR(32) NOT NULL | | 开票方纳税人识别号（NOT NULL；个人/农产品等无税号场景登记为申报方证件号占位，避免松散可空） |
| supplier_id | BIGINT | | 可选软关联 store-service supplier.id；**无物理外键**（跨服务），仅前端联想辅助 |
| amount | BIGINT NOT NULL | chk ≥ 0 | 价税合计（分） |
| net_amount | BIGINT NOT NULL | chk ≥ 0 | 不含税净额（分） |
| tax_amount | BIGINT NOT NULL | chk ≥ 0 | 税额（分），恒等式 `amount = net_amount + tax_amount` 落 chk |
| tax_rate | DECIMAL(5,4) NOT NULL | chk IN (0,0.01,0.03,0.06,0.13) | 五档白名单，与销项/FinConfigService 一致 |
| category | VARCHAR(16) NOT NULL | chk IN ('SERVICE','PRODUCT','MEMBERSHIP') | **沿用销项三档采购用途**，不自造进项专属枚举（前端选项三处一致） |
| purpose | VARCHAR(16) NOT NULL DEFAULT 'PENDING' | chk IN ('PENDING','DEDUCT','NO_DEDUCT','REFUND') | 用途确认：待确认/抵扣/不抵扣/退税（仿发票云四类勾选） |
| status | VARCHAR(20) NOT NULL DEFAULT 'UNCONFIRMED' | chk IN ('UNCONFIRMED','CONFIRMED','DEDUCTED','TRANSFERRED_OUT','NON_DEDUCTIBLE') | 状态机，见定案② |
| nondeduct_reason | VARCHAR(16) | chk 七项码或 NULL，见 §3.3 | 不抵扣/转出的法定情形码 |
| transfer_out_amount | BIGINT NOT NULL DEFAULT 0 | chk ≥ 0 | 进项转出额（分） |
| period_id | BIGINT | | 抵扣归属申报期（软关联 fin_tax_period.id，无物理 FK；加 idx） |
| invoice_date | DATE NOT NULL | | 开票日期 |
| confirmed_at / deducted_at / transferred_at | TIMESTAMPTZ | | 状态机三个时间戳 |
| store_code | VARCHAR(16) NOT NULL | | 数据域（DataScope.storeSpec 强制） |
| operator | VARCHAR(64) NOT NULL | | 登记人 |
| confirmer | VARCHAR(64) | | 用途确认人（双岗留痕；本卡不强制双签，确认即勾选动作） |
| remark | VARCHAR(256) | | 备注 |
| idem_key | VARCHAR(80) NOT NULL | | 幂等键（客户端生成 UUID 或 register_no 维度去重） |
| created_by/updated_by | VARCHAR(16) NOT NULL DEFAULT 'system'；created_at/updated_at TIMESTAMPTZ NOT NULL DEFAULT now() | | V38 审计四列范式 |

**重复入账防控（部分唯一索引，零 INSERT 种子）：**

```sql
uk_input_invoice_dedup ON fin_input_invoice (seller_tax_no, invoice_no)
  WHERE status IN ('UNCONFIRMED','CONFIRMED','DEDUCTED','TRANSFERRED_OUT');
-- NON_DEDUCTIBLE（不抵扣）不参与唯一约束：同票允许以"不抵扣"重复登记留痕
```

辅助索引：`idx_input_invoice_store_status(store_code, status)`、`idx_input_invoice_period(period_id)`、`idx_input_invoice_date(invoice_date)`。

### 2.2 `fin_tax_period`（增值税申报期登记簿）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT IDENTITY PK | | |
| period_type | VARCHAR(8) NOT NULL | chk IN ('MONTH','QUARTER') | 申报频率；演示环境默认 MONTH |
| period | VARCHAR(16) NOT NULL | | 标识：月 `2026-08`、季 `2026-Q3` |
| period_start / period_end | DATE NOT NULL | | 所属税款起止 |
| deadline | DATE NOT NULL | | 申报截止日（按 64 号函 2026 日历；小月 15，顺延月见 §0.1） |
| status | VARCHAR(16) NOT NULL DEFAULT 'OPEN' | chk IN ('OPEN','FILED','LATE_FILED','AMENDED','CLOSED') | 见定案② |
| output_amount / input_amount / transfer_out_amount / payable_amount / retained_amount | BIGINT NOT NULL DEFAULT 0 | chk ≥ 0 | 申报快照（分）：销项/进项/转出/应纳/期末留抵 |
| filed_at | TIMESTAMPTZ | | 申报动作时间 |
| filer | VARCHAR(64) | | 申报人 |
| remark | VARCHAR(256) | | |
| 审计四列 | 同上 | | |

约束：`uk_tax_period(type, period)` 部分唯一索引 `WHERE status <> 'CLOSED'`（同期仅一条开放记录，更正后 AMENDED 仍唯一）；`chk_tax_period_range CHECK (period_end >= period_start)`。

**与 SettlementPeriod 边界**：`settlement_period`（V7，DAY/MONTH、CLOSED 封账）是内部对账封账期；`fin_tax_period` 是对税局的增值税申报期。两者语义不同，不复用。**税务申报不封业务账**。

期间行**懒创建**：提供 `POST /api/finance/tax-periods/ensure`（body：periodType/period 缺省当前期）幂等 upsert；前端首次进入进项页或点申报时确保当期存在。零 Flyway 种子。

---

## 三、定案②③：状态机与不得抵扣

### 3.1 发票状态机（service 层校验，CHECK 只兜合法值域）

```
                 录入
                  │
                  ▼
           UNCONFIRMED（待确认）
          ╱          │                    ╲
   confirm(DEDUCT)   │ markNonDeductible     │ confirm(NO_DEDUCT)
          ╲          ▼                      ╱
           CONFIRMED（已用途确认/勾选）   NON_DEDUCTIBLE（不抵扣，终态旁路）
                  │  ▲
       revokeConfirm（仅未抵扣可撤销，回到 UNCONFIRMED）
                  │
            deduct(period_id)
                  ▼
             DEDUCTED（已抵扣，计入申报期）
                  │
        transferOut(amount, reason)
                  ▼
          TRANSFERRED_OUT（已做进项转出；金额不回池，留抵/应纳重算）
```

| 流转 | 前置态 | 权限 | 副作用 |
|---|---|---|---|
| confirm | UNCONFIRMED | finance:input:confirm | purpose=DEDUCT/NO_DEDUCT/REFUND；DEDUCT→CONFIRMED 且 confirmed_at；NO_DEDUCT→直接 NON_DEDUCTIBLE 终态；REFUND→CONFIRMED 但**不计入本卡抵扣汇总**（退税勾选，远期列 Backlog，本卡只留状态） |
| revoke-confirm | CONFIRMED | finance:input:confirm | 清 confirmer/confirmed_at，回 UNCONFIRMED；已 DEDUCTED 拒绝（中文 422「已抵扣发票不可撤销确认，请先做进项转出」） |
| deduct | CONFIRMED(purpose=DEDUCT) | finance:input:confirm | 必须带开放申报期（OPEN）；写 period_id/deducted_at；**期间一经 FILED 拒绝新抵扣**（仿发票云「已生成统计表不得勾选」） |
| transfer-out | DEDUCTED | finance:input:confirm | transfer_out_amount 1..tax_amount；nondeduct_reason 必填；转后期末汇总重算 |
| mark-non-deductible | UNCONFIRMED | finance:input:edit | nondeduct_reason 必填，终态旁路 |

**不设 360 日硬卡**（45 号公告已取消期限）；列表仅以「票龄」软提示（invoice_date 距今天数），不阻断任何流转。

### 3.2 申报期状态机

`OPEN（未申报）→ FILED（已申报，按期）`；逾期日后申报 → `LATE_FILED（逾期补申报）`；`FILED/LATE_FILED → AMENDED（更正申报）`；`→ CLOSED` 为归档终态（本卡可不做关闭动作，保留枚举）。申报动作对五金额做**快照**（后续发票变动不改历史申报），并写 filed_at/filer。

### 3.3 不得抵扣七项枚举（定案③，nondeduct_reason 码表）

| 码 | 中文标签 | 法定口径要点 |
|---|---|---|
| WELFARE | 简易计税/免税/集体福利/个人消费 | 交际应酬消费属个人消费；对应取得的进项不得抵扣 |
| LOSS_GOODS | 非正常损失购进货物及相关劳务/运输服务 | 管理不善被盗、霉变等 |
| LOSS_PRODUCT | 非正常损失在产品/产成品耗用的购进 | 不含正常经营损耗 |
| LOSS_REAL_ESTATE | 非正常损失不动产及其所耗购进/设计/建筑 | |
| LOSS_CONSTRUCTION | 非正常损失不动产在建工程所耗货物/设计/建筑 | |
| LOAN_DAILY | 贷款服务/餐饮服务/居民日常服务/娱乐服务 | 含与贷款直接相关的投融资顾问费、手续费、咨询费 |
| OTHER | 其他依法不得抵扣情形 | 兜底，须 remark 说明 |

交互口径（定案）：七项作为「不抵扣原因/转出原因」下拉。**系统不做自动判定阻断**（业务定性判断归会计），仅在选择 LOAN_DAILY/WELFARE 等高频情形时前端软提示。医美机构自身经营所需耗材/设备/医疗服务采购正常可抵。

---

## 四、定案④⑤：税基口径与供应商落点

1. **税基（定案④）**：录入传价税合计 amount＋taxRate（五档），服务端复用 `FinConfigService.computeTaxFen`（HALF_UP）算 net/tax；允许同时传 taxAmount 覆盖（海关缴款书税额票面制），服务端校验 `|传入tax−计算tax| ≤ 1 分` 且 `amount=net+tax`，不符中文 422。农产品核定扣除（买价×扣除率）属 §11(四) 特殊情形，本卡不扩白名单，统一以 OTHER 凭证登记，远期 Backlog。
2. **供应商（定案⑤）**：**不给 store-service supplier 表加列**（跨服务表零改）。seller_name/seller_tax_no 在进项实体冗余落库；supplier_id 选填软关联，前端可由 `GET /api/stores/suppliers`（既有，inventory 码）联想带回名称——进项页本身只用 finance:input:view 可见，联想接口 403 时降级为纯手填，不做后端跨服务 HTTP 强依赖（比 FinanceAbnormalBillClient 更弱：本卡干脆不建 client）。

---

## 五、定案⑥⑦：权限与审计

### 5.1 权限三码（PermissionMatrix 为唯一真源，随代码提交）

| 新码 | 定义段位置 | FINANCE 角色 | 其他角色 |
|---|---|---|---|
| `finance:input:view` | finance view 段（L91-105 区内追加） | ✅ | 不授（门店/区域不看进项税簿；数据域敏感） |
| `finance:input:edit` | 写码段（L312-316 区内追加） | ✅ 登记/修改/标记不抵扣 | 不授 |
| `finance:input:confirm` | 同上 | ✅ 用途确认/抵扣/进项转出（税务会计岗） | 不授 |

机制：仅改 org-service `PermissionMatrix` 两处（ALL_PERMISSIONS 定义＋FINANCE 授权集）；重启经 `RbacDataInitializer` 幂等补 permission_def 与 role_permission，**不写 Flyway 权限种子**。前端 auth.can() 直接生效。

### 5.2 端点设计（两新 controller，类级 finance:view 兜底）

**InputInvoiceController（`/api/finance/input-invoices`）**

| 方法/路径 | 权限 | 说明 |
|---|---|---|
| GET `` | finance:input:view | Page＋Specification（storeCode 数据域＋status/kind/purpose/keyword/period 过滤），视图元 |
| POST `` | finance:input:edit | 登记草稿（idem_key 幂等 409 中文；register_no/税额服务端生成） |
| GET `/{id}` | finance:input:view | 详情 |
| POST `/{id}/confirm` | finance:input:confirm | body：purpose；NO_DEDUCT 需 reason |
| POST `/{id}/revoke-confirm` | finance:input:confirm | 撤销确认 |
| POST `/{id}/deduct` | finance:input:confirm | body：periodId（或 period 标识懒解析） |
| POST `/{id}/transfer-out` | finance:input:confirm | body：amount(元)、reason、remark |
| POST `/{id}/non-deductible` | finance:input:edit | body：reason、remark |
| GET `/summary` | finance:tax:view | 参数 period 起止或 periodId；返 `{inputAmount, deductibleConfirmed, deducted, transferredOut, netDeductible}`（分/元双视图按既有风格给元） |

**TaxPeriodController（`/api/finance/tax-periods`）**

| 方法/路径 | 权限 | 说明 |
|---|---|---|
| GET `` | finance:tax:view | 期间列表（分页/按 type/year） |
| POST `/ensure` | finance:input:edit | 幂等确保当前期（或指定期）存在 |
| GET `/current` | finance:tax:view | 当前开放期＋五金额快照＋**retainedAmount（期末留抵）** |
| POST `/{id}/file` | finance:input:confirm | 申报：OPEN→FILED/LATE_FILED（按 deadline 与服务器日期判定），快照五金额，锁抵扣 |
| POST `/{id}/amend` | finance:input:confirm | →AMENDED 留痕（不改快照原值，备注追加） |

**审计（定案⑦）**：biz_type=`FIN_INPUT_INVOICE`，txnNo=register_no，action ∈ `CREATE / CONFIRM / REVOKE_CONFIRM / DEDUCT / TRANSFER_OUT / NON_DEDUCTIBLE`；申报动作 biz_type=`FIN_TAX_PERIOD`，action=`FILE / AMEND`。全部走 FinanceAuditRecorder（audit-service→audit_outbox 双通道），payload 为合法 JSON（含 id/period/amount/purpose/reason）。**不引入 COMPLIANCE 告警**（无定时扫描、无法定期限硬卡，告警是远期税局直连后的事）。

---

## 六、定案⑧：前端三收口（样式零改，零新路由）

1. **finReports.ts（数据源接真）**：`inputDeduct = ref(0)` 替换为新 summary 端点口径（按当前税款所属期：已 DEDUCTED 税额 − TRANSFERRED_OUT 累计转出额）；`taxPayable=max(0, output−input)` 公式不动；`output−input<0` 时在税务页以既有 KPI 卡副文案诚实展示「期末留抵 ¥x（结转下期）」——**不新增 KPI 卡、不改布局**；store return 形状（L602 导出）保持不变，模板零改。seed 段 Promise.all 并入新请求。
2. **FinInvoiceView.vue（进项 tab）**：顶层加标准 `.tabs/.tab`（整组照抄同类页既有 tab 样式，铁律 -1-B），「销项发票 / 进项发票」两页签；销项现有 4 KPI＋列表/详情/弹窗整块不动；进项 tab＝列表（register_no/票种中文/销方/价税合计/税额/用途中文/状态色标）＋筛选＋登记弹窗＋确认/抵扣/转出操作区，全部复用既有组件与 class。状态/用途/票种/七项中文 options 落新 store（同 finInvoice.ts 风格新建 finInputInvoice.ts）。
3. **FinTaxView.vue（期间接真）**：L51「税种明细（2026-08）」期间标签由 `/tax-periods/current` 的 period 驱动；L73 每行「待申报」由期间 status 映射（OPEN 待申报/info，FILED 已申报/success，LATE_FILED 逾期补申报/warning，AMENDED 更正申报/warning）；进项抵扣卡经 store 自动接真。红线文案（申报辅助、单向镜像、不碰资金）保留不动。
4. API 三处一致：`api/finance.ts` 追加 InputInvoice/TaxPeriod DTO 与端点函数；baseURL `/api` 既有；网关前缀透传零改；路由表零改（不新增页面）。

---

## 七、边界（远期 Backlog，本卡一律不做）

税局综合服务平台直连与勾选回写；OCR 识票（铁律 12 门禁，远期启用）；采购三单匹配（PO/入库单/发票，依赖采购域深化）；自动会计凭证；留抵退税**办理**（本卡只诚实计算并展示结转留抵额）；农产品核定扣除特殊计算；REFUND 退税勾选的实际退税链路；small-scale 纳税人身份切换。

---

## 八、施工序、真验与还原

### 8.1 施工序（定案批准后）

1. V39 迁移（两表＋全部 chk/uk/idx/审计四列，纯 DDL 零 INSERT），双库手动执行核对首启安全；
2. 实体 FinInputInvoice / FinTaxPeriod＋Repository（Specification/Page）；
3. Service：单号序列、复用 computeTaxFen、状态机流转校验、幂等、汇总/留抵计算、期间 ensure/file 快照、中文 422；
4. 两 Controller＋org-service PermissionMatrix 三码（矩阵为代码提交，重启幂等播种）；
5. 前端 api/finance.ts 追加 → finInputInvoice.ts 新 store → FinInvoiceView 进项 tab → finReports inputDeduct 接真 → FinTaxView 期间标签/状态 pill；
6. 双构建：finance/org 服务 `mvn package` fat jar；前端 `pnpm build`（含 vue-tsc 类型检查）。

### 8.2 三轨真验（铁律 7，双栈 prod 8087/8443/8080、seed 18087/18443/18080）

- **curl 经网关**（-k，大写工号登录）：权限三码矩阵（无码 403、FINANCE 全通）；登记幂等（同 idem_key 二次 409）；五档外税率 422；全状态流转 happy path＋非法流转 422（已抵扣撤销确认、FILED 期再抵扣、税额恒等式不符）；重复票部分唯一索引冲突；summary 口径（含进项转出后留抵变化）；申报 FILED 后锁抵扣；
- **PG 双库核对**：表/约束/索引存在、check 拒绝非法枚举、金额恒等 chk、审计落 audit-service 库与失败回落 audit_outbox（构造下游不可达场景验证 outbox 行）；
- **Chrome 双前端**：进项 tab 挂载且销项页样式像素级无变化；登记/确认/抵扣/转出全链；税务页期间标签与申报 pill 随状态变化；进项抵扣 KPI 与留抵副文案；无控制台报错。
- OCR 铁律 12：本卡无 OCR 功能（远期 Backlog），DELIVERY 注明「不适用/无 OCR 通道，人工正则＋目检代替」。

### 8.3 物理还原（单事务，禁 TRUNCATE）

- 受影响表：`fin_input_invoice`、`fin_tax_period`（验后整表 DELETE 测试行——两表为 V39 新建、基线为空，直接按 idem_key/register_no 条件删除即精确回落空表基线）；audit_log 追加行**不删**（append-only），仅登记起止序号供核对；audit_outbox 回落行按 txn_no 前缀条件删除；rbac 侧权限行为**幂等播种结果，保留不回滚**（新增三码是交付物本身，非测试垃圾）；
- 备份：执行前双份（CSV＋pg_dump）置 `scripts/backup-b63-input-20260919/`，不入库；单事务 DELETE＋计数核对基线精确回落；
- 全程提交元数据/哨兵/DELIVERY **零凭据明文**。

### 8.4 提交与落账

- 一卡一 feat：代码一个 commit（V39＋后端＋org 矩阵＋前端），docs 单独一个 commit（台账五分册＋DELIVERY＋本 DESIGN 已先行）；分别 push origin/main；
- 台账五落点（数字一律不变，仅勾 04 L86）：00-history 倒序顶条、01-dashboard L19 前插卡4 bullet（L12-15 数字表/L44 域行不动）、02-modules L100 行长单元格末尾追加（旧注零改写）、03-timeline L96 后新增 L97、04-backlog 仅 L86 勾销；
- DELIVERY-P5-B63-卡4 七章体；哨兵机器字段转 B64＋人读区 append；B63 置 DONE 转 **B64 诊疗四行**。
