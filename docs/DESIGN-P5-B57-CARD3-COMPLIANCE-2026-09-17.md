# P5-B57 卡3 方案设计：隐私合规延展（DSAR / consent / 合规巡检）

> 时间：2026-09-17
> 状态：**方案定案（用户总授权「按你推荐的来 + 竞品调研后推荐」，本文件给出调研结论与落地细节，远期实施）**
> 用户决策原文（2026-09-17）：
> 「其他项目按你推荐的来，若有涉及决策的，也可以先调研竞品，根据竞品信息，推荐最适合我们的，开工吧」
> 事实基础：2026-09-17 联网竞品调研（3 次 WebSearch 共 15 条结果）+ 既有代码实证（ai-service V22-V29 隐私合规三表、audit_log append-only 链路、notification 通知中心 B20、customer 表既有结构）。
> 铁律遵循：meiyun-dev-rules——业务表 JPA ddl-auto=update、系统表 Flyway 且已执行迁移禁改；写接口四件套（校验/幂等/全动作审计/中文错误）；跨服务取数 RestTemplate 内部端点 + X-Internal-Token；密钥明文不落库不进日志不下发前端；样式零改动复用既有组件。

---

## 〇、调研总述

### 0.1 监管环境（中国 2025-2026）

| 法规 | 施行日期 | 核心要求 | 美研云适用判定 |
|---|---|---|---|
| 《个人信息保护法》PIPL 第 13-17 条 | 2021-11-01 | 同意/撤回同意/不得因拒绝提供服务而拒绝交易 | **直接适用**：会员注册/预约/营销推送均需合法基础，撤回同意后须停止处理 |
| 《网络数据安全管理条例》 | 2025-01-01 | 个人信息保护规则公布、投诉举报机制、安全事件报告 | **直接适用**：需提供投诉举报渠道（现有通知中心可承载） |
| 《大型个人信息处理者个人信息保护规定》征求意见稿 | 2026-08-07 | 处理 1000 万人以上个人信息须设独立监督部门、年度合规审计 | **暂不适用**：美研云 SaaS 单租户客户量远低于 1000 万，但须关注正式稿阈值 |
| 《小型个人信息处理者简化措施》 | 2026-04-03 | 不满 10 万人可简化隐私政策/同意记录/DPIA | **当前适用**：美研云各门店 SaaS 租户处理量大概率 <10 万，可走简化路线 |

**结论**：美研云当前适用 PIPL 基本要求 + 小型处理者简化措施，暂不需大型处理者的 DPIA/独立监督/年度审计。但 DSAR（个人行使权利请求）和同意记录是 PIPL 第 44-49 条的**刚性要求**，不可省略。

### 0.2 国际合规工具调研

| 工具 | 年费 | 核心能力 | 国内适用性 |
|---|---|---|---|
| OneTrust | $40K-$500K+ | ROPA/DPIA/DSAR/CMP/第三方风险，7000+ 客户 | ❌ 面向欧美 GDPR/CCPA，PIPL 适配差，价格远超 SaaS 小产品承受力 |
| DataGrail | $30K+ | 1500+ SaaS 连接器 DSAR 自动化、数据发现 | ❌ 连接器覆盖 Salesforce/HubSpot 等欧美 SaaS，不覆盖国内微信/支付宝/抖音生态 |
| TrustArc | $25K+ | 隐私评估/CMP/DSAR/培训 | ❌ 同上，GDPR 场景为主 |
| Ketch | $20K+ | 隐私管理/同意/DSAR | ❌ 同上 |
| Enzuzo | $9K+ | 隐私政策生成/CMP/DSAR | ⚠️ 价格最低但仍是 GDPR 场景 |
| Osano | $15K+ | CMP/DSAR/供应商监控 | ❌ 同上 |

**结论**：国际工具年费 $9K-$500K+，全部面向 GDPR/CCPA 场景，对 PIPL 的同意撤回/DSAR 30 日响应/小型处理者简化均无原生支持。**不采购**。

### 0.3 国内同业调研（医美 SaaS）

| 同业 | 隐私合规实现 | 缺失项 |
|---|---|---|
| 有赞美业 | 隐私政策展示 + 首次弹窗同意 + 个人中心历史版本 | ❌ 无 DSAR 工单流、❌ 无同意生命周期管理、❌ 无合规巡检告警 |
| 微盟 | 隐私政策展示 + 首次弹窗同意 + 历史版本 | ❌ 无 DSAR 工单流、❌ 无同意生命周期管理、❌ 无合规巡检告警 |
| 美团商家端 | 隐私政策 + 同意 + 第三方 SDK 列表 | ❌ 无 DSAR、❌ 无撤回同意联动 |

**结论**：国内同业仅做「隐私政策展示 + 首次弹窗」表面层，**无真正 DSAR 闭环 / 同意生命周期 / 合规巡检**。这是美研云的差异化机会。

### 0.4 推荐路线

**轻量内建 + 合规审计外采（年度合规审计由律所/咨询机构出具）**：

1. **DSAR 工单流**（PIPL 第 44-49 条刚性要求，30 日响应）
2. **同意生命周期管理**（PIPL 第 14-16 条同意要件 + 第 15 条撤回权）
3. **合规巡检告警**（小型处理者简化措施下的最低合规保障）

**不采购理由**：
- 国际工具 $9K+/年（约 6.5 万 RMB+/年），面向 GDPR 不适配 PIPL
- 国内同业仅做表面层，美研云内建可实现差异化
- 美研云已有 audit_log append-only 链路 + notification 通知中心 + customer 域实体基础，内建成本低

---

## 一、现有基础设施盘点（实证事实）

### 1.1 ai-service 隐私合规三表（B47 卡8 已建，V22-V29）

| 表 | 列数 | 用途 | 本批复用 |
|---|---|---|---|
| `ai_desensitization_rule` | 8+ | 脱敏字段规则配置（8 类） | 不改动，DSAR 删除请求可复用脱敏规则执行 |
| `ai_compliance_checklist` | 8+ | 等保 2.0 达标台账（8 项） | 不改动，合规巡检可追加巡检项引用此表 |
| `ai_privacy_export` | 8+ | 审计区间 SHA-256 全链哈希导出 | 不改动，DSAR ACCESS 请求可直接复用导出能力 |

### 1.2 audit_log append-only 链路（B48 已加固）

- 全服务 RestAuditRecorder 双通道（JWT 直连 + X-Internal-Token outbox）
- 哈希链 broken=0 append-only，全量断链清单 `verifyChain`（B50 卡6）
- DSAR 工单全生命周期可自然落入 audit_log，零新基础设施

### 1.3 notification 通知中心（B20 已建）

- notification 表 + 4 真实端点 + 铃铛角标/跳转已读
- notify_preference 偏好持久化（五类×四渠道）
- 合规巡检告警可直接落入既有通知通道（admin 角色站内信）

### 1.4 customer 域实体基础（B23/B28 已建）

- customer 表已有 9 扩展字段（B28：年龄/肤质/诉求/过敏史/意向项目等）
- 客户 360 档案页已有 tab 结构（档案/标签/积分/卡项/方案单）
- DSAR 前端可挂载为客户档案页新 tab

---

## 二、DSAR 工单流设计

### 2.1 数据模型

新建 `dsar_request` 表（customer-service，JPA ddl-auto=update）：

| 列 | 类型 | 说明 |
|---|---|---|
| id | IDENTITY PK | 自增主键 |
| request_no | VARCHAR(20) UNIQUE | DSAR+yyyyMMdd+三位序号（仿 LV/B28 既有范式） |
| customer_id | BIGINT NOT NULL FK→customer | 请求关联客户 |
| type | VARCHAR(16) NOT NULL CHECK | ACCESS / DELETE / RECTIFY / PORTABILITY |
| status | VARCHAR(16) NOT NULL CHECK | SUBMITTED / REVIEWING / FULFILLED / REJECTED |
| description | VARCHAR(500) | 请求描述（客户填写） |
| reviewer | VARCHAR(16) | 审核人工号 |
| reject_reason | VARCHAR(200) | 驳回原因 |
| requested_at | TIMESTAMPTZ NOT NULL | 请求时间 |
| deadline_at | TIMESTAMPTZ NOT NULL | 法定截止（requested_at + 30 日） |
| fulfilled_at | TIMESTAMPTZ | 完成时间 |
| created_at | TIMESTAMPTZ NOT NULL DEFAULT now() | 记录创建 |

CHECK 约束：`type IN ('ACCESS','DELETE','RECTIFY','PORTABILITY')`、`status IN ('SUBMITTED','REVIEWING','FULFILLED','REJECTED')`。

### 2.2 端点设计

挂 customer-service `/api/customer/dsar`，权限码 `dsar:view/edit`（PermissionMatrix 已有预留位或本批播种）：

| 端点 | 方法 | 权限 | 说明 |
|---|---|---|---|
| `/dsar` | GET | dsar:view | 列表（DataScope 门店域、分页、按 status/type 过滤） |
| `/dsar` | POST | dsar:edit | 新建（校验 customer_id 存在、type 合法、description 非空；幂等：同 customer_id+type+SUBMITTED 409） |
| `/dsar/{id}` | GET | dsar:view | 详情 |
| `/dsar/{id}/review` | PUT | dsar:edit | 审核（REVIEWING→FULFILLED/REJECTED，reject_reason 必填当 REJECTED） |
| `/dsar/stats` | GET | dsar:view | 四态计数 + 超期计数（deadline_at < now() 且 status ∉ FULFILLED/REJECTED） |

### 2.3 四类型执行逻辑

| 类型 | 执行动作 | 完成判定 |
|---|---|---|
| ACCESS | 导出客户全量个人信息（复用 ai_privacy_export 导出能力） | 导出文件生成后附在工单详情供下载 |
| DELETE | 软删除客户数据（status→DEACTIVATED，保留审计不可删） | customer.status=DEACTIVATED + audit_log 留痕 |
| RECTIFY | 客户档案扩展字段修改（B28 九列） | 字段更新后 FULFILLED |
| PORTABILITY | 导出可携带数据（JSON 格式，含客户基础信息+消费记录+卡项余额） | JSON 文件生成后附在工单详情 |

### 2.4 前端挂载

客户 360 档案页（M0CustomerView 或 M4CustomerView）新增「隐私请求」tab，仿既有投诉工单样式：
- 列表：request_no / type 中文映射 / status 四态色标 / deadline_at 超期红 / 操作
- 新建抽屉：customer_id 预填当前客户 / type CSelect 四选项 / description CTextarea
- 审核抽屉：REVIEWING 态可操作，FULFILLED/REJECTED 显 reviewer + reject_reason

---

## 三、同意生命周期设计

### 3.1 数据模型

customer 表加三列（JPA ddl-auto=update）：

| 列 | 类型 | 说明 |
|---|---|---|
| consent_version | INTEGER DEFAULT 0 | 当前同意版本号（0=未同意） |
| consent_at | TIMESTAMPTZ | 最近一次同意时间 |
| consent_withdrawn_at | TIMESTAMPTZ | 最近一次撤回时间（NULL=未撤回） |

### 3.2 四场景同意记录

| 场景 | 触发时机 | consent_version 递增 |
|---|---|---|
| 会员注册 | POST /api/customer/register 成功 | 0→1 |
| 预约确认 | POST /api/txn/appointments 勾选「同意个人信息处理」 | +1 |
| 处方确认 | treat-done 治疗完成勾选 | +1 |
| 营销推送 | PUT /api/customer/consent（单独授权营销推送） | +1 |

每次同意/撤回均在 audit_log 留痕（bizType=CONSENT，action=GRANT/WITHDRAW，payload 含 version+scene）。

### 3.3 撤回同意联动

- 撤回同意后，marketing-service 推送前检查 `consent_withdrawn_at`：非 NULL 且 > consent_at → 跳过推送，DeliveryResult.skipped("客户已撤回同意")
- 前端客户档案「隐私请求」tab 旁显示同意状态徽章（已同意/已撤回/未授权）

---

## 四、合规巡检告警设计

### 4.1 定时任务

新建 `ComplianceInspectionJob`（customer-service 或独立 scheduled，参照 B25 LevelMonthlyJob 范式）：

- **cron**：每日 02:00（北京时区）
- **三项巡检**：

| 巡检项 | 条件 | 告警级别 |
|---|---|---|
| DSAR 超期预警 | deadline_at < now()+7d 且 status ∉ FULFILLED/REJECTED | WARN |
| DSAR 超期未响应 | deadline_at < now() 且 status ∉ FULFILLED/REJECTED | CRITICAL |
| 同意过期 | consent_at < now()-3y 且 consent_withdrawn_at IS NULL | INFO |

### 4.2 告警通道

复用既有 notification 通知中心：
- WARN：门店店长（STORE_MGR）站内信
- CRITICAL：区域经理（REGION_MGR）+ 超管站内信
- INFO：仅落 audit_log，不推送

---

## 五、实施优先级与批次建议

### 5.1 推荐实施顺序

| 优先级 | 模块 | 理由 |
|---|---|---|
| P0 | DSAR 工单流 | PIPL 第 44-49 条刚性要求，30 日响应是法定时限 |
| P1 | 同意生命周期 | PIPL 第 14-16 条同意要件，撤回权是第 15 条刚性要求 |
| P2 | 合规巡检告警 | 小型处理者简化措施下的最低合规保障 |

### 5.2 批次建议

建议独立为 **P5-B58 合规专项批**，不并入当前 B57 批次：
- B57 已有卡1 数据治理 + 卡2 外部配置窗口两项实施，批次负荷已满
- 合规专项涉及 customer 表 schema 变更（加列）、新实体 dsar_request、新权限码播种、前端新 tab，工作量约 4-6 卡
- B57 卡2 实施闭合后先批末落账，B58 独立开工

### 5.3 工作量估算

| 卡 | 内容 | 涉及服务 |
|---|---|---|
| 卡1 | dsar_request 实体 + Repo + 五端点 + 权限播种 | customer-service |
| 卡2 | 四类型执行逻辑（ACCESS 复用导出/DELETE 软删/RECTIFY 改字段/PORTABILITY JSON） | customer-service + ai-service |
| 卡3 | customer 表加 consent 三列 + 四场景同意记录 + 撤回联动 | customer-service + marketing-service |
| 卡4 | ComplianceInspectionJob 定时任务 + 通知联动 | customer-service |
| 卡5 | 前端「隐私请求」tab + 同意状态徽章 | frontend |
| 卡6 | 三轨真验 + 批末落账 | 全栈 |

---

## 六、不做什么（诚实边界）

1. **不采购 OneTrust/DataGrail 等国际工具**：价格 $9K-$500K+/年，面向 GDPR/CCPA，PIPL 适配差
2. **不建独立隐私管理中心页面**：小型处理者简化措施下无需，既有 M1 合规中心可承载
3. **不做 DPIA（数据保护影响评估）**：大型处理者（1000 万人以上）才强制，美研云暂不适用
4. **不做跨境传输影响评估**：美研云数据全部境内存储，无跨境场景
5. **不做 Cookie 同意管理（CMP）**：SaaS B 端产品无 Cookie 横幅需求（非 C 端网站）
6. **不做第三方 SDK 清单管理**：医美 SaaS 不涉及移动端 SDK 嵌入场景

---

## 七、与既有隐私合规三表的关系

B47 卡8 已建的 ai-service 隐私合规三表（脱敏规则/等保台账/导出报告）与本方案的关系：

| 既有 | 本方案 | 关系 |
|---|---|---|
| ai_desensitization_rule（脱敏字段规则） | DSAR DELETE 执行 | DSAR 删除时复用脱敏规则判定哪些字段需脱敏 vs 物理删除 |
| ai_compliance_checklist（等保 2.0 台账） | 合规巡检 Job | 巡检 Job 可追加引用等保台账项作为巡检维度 |
| ai_privacy_export（审计哈希导出） | DSAR ACCESS/PORTABILITY 执行 | ACCESS 请求直接复用导出能力生成个人信息副本 |

**不改动既有三表结构**，本方案新增实体均在 customer-service 域内。
