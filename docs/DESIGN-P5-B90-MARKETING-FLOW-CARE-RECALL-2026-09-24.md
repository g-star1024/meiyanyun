# DESIGN-P5-B90 营销侧随访 Flow（关怀 / 召回）设计定案

> 批次：P5-B90（B88✅→B89✅→B90 三批路线图收官批）
> 落点：docs/roadmap/02-modules.md L85「随访 / SOP / 关怀 / 召回 ⬜」（实测行号 L85，台账引述口径 L86 为同一行）
> 选型依据：DESIGN-P5-B69 §0.3.4/§3.3（2026-09-20 定案：内建轻量 Trigger-Condition-Action，不采购外部 SaaS）
> 定案时间：2026-09-24（夜间自主决策窗 22:00-06:00，用户授权「按推荐来」）
> 关联勾销：02 L50（域③ 复诊召回「依赖营销自动化，远期」）批末联动评估；04-backlog L52（私域自动化 Flow）、L83（沉睡客户唤醒 / 复诊召回自动化）

---

## 0. 卡0 侦察结论（已闭环）

### 0.1 现状定位

| 对象 | 现状 | 位置 |
|---|---|---|
| 关怀 Care | 纯前端 mock store，零 api 调用 | frontend/src/stores/care.ts（206 行）→ CareView.vue（374 行，/m3-care，router L126） |
| 复诊召回 Recall | 纯前端 mock store，零 api 调用 | frontend/src/stores/recall.ts（346 行）→ RecallView.vue（590 行，/recall，router L62） |
| 跟进任务 FollowTask | mock（M3-08），**不属本批** | followtask.ts（02 L85 落点行不含此页） |
| 诊疗侧术后 SOP | ✅ B30/B31 已闭合（txn-service） | com.meiyun.txn Followup* 18 类，本批零改动 |
| marketing-service | 扁平包 com.meiyun.marketing，无 Care/Recall/Flow 资产 | 既存可复用：AutoGrantJob / PushService / ForbiddenWordService / TxnInternalClient / AuditRecorder |
| Flyway | V11/V12/V13/V14/V32/V46 → **下一版 V47** | 表命名惯例 snake_case 无前缀 |

### 0.2 前端 mock 活规格（切真契约）

**care.ts（M3-09 关怀）**：四型 BIRTHDAY/HOLIDAY/REPURCHASE/REACTIVATE；三渠道 SMS/WECHAT/PHONE；三态 PENDING/SENT/REACHED；5 模板 8 种子；动作 create/send/markReached/markConverted；KPI 本月待关怀/已发送/触达率/带来预约；权限 care:view/edit/send。

**recall.ts（复诊召回）**：状态机 PENDING→NOTIFIED→CONFIRMED/BOOKED/SKIPPED（TRANSITIONS 含 NOTIFIED→PENDING 改期回退，BOOKED/SKIPPED 终态）；四来源 DOCTOR_ADVICE/COURSE_FOLLOW/SYSTEM_AUTO/MANUAL；四方式 PHONE/WECHAT/SMS/IN_STORE；动作 schedule/notify/confirm/markBooked/skip/reschedule；timeline 内嵌 {at,by,action,detail}；KPI 超期/今日待提醒/转化率/3 日到期；权限 recall:view/create/edit。视图层 submitRecall 以 `customerId:'C-NEW'` 占位——切真时改客户检索（仿 RepurchaseView 的 searchCustomers）。

### 0.3 权限码（零新码实证）

org PermissionMatrix.java 已播种：recall:view(L37)/followuptask:view(L80)/care:view(L81) 默认组；recall:edit/create(L173-174)/followuptask:edit(L221)/care:edit/send(L222-223) 编辑组；marketing:view(L45)/marketing:edit(L158) 既有。**本批零新增权限码**。

### 0.4 可复用范式（施工直接照搬）

| 范式 | 出处 | 复用点 |
|---|---|---|
| 5 分钟轮询 Job | marketing AutoGrantJob（B37） | @Scheduled fixedDelay、异常兜底不中断、汇总审计 SYSTEM |
| 限批 50 单条 try-catch | txn FollowupSopDueJob（B30） | 不加方法级 @Transactional、BIZ_TZ=+8 |
| 通知幂等+免打扰 | txn FollowupSopEscalator（B31） | idemKey 幂等、软降级不阻断主链路 |
| 触发幂等+REQUIRES_NEW | txn FollowupScheduler（B30，fa549d5 教训） | 唯一约束兜底+exists 查库防重；afterCommit 无活动事务→新物理事务 |
| consent 门控推送 | marketing PushService | CustomerConsentClient requireConsent/isWithdrawn/isGranted；撤回/未授权跳过不落 record |
| 违禁词校验 | marketing ForbiddenWordService | 关怀内容送审复用 |
| 审计 | marketing AuditRecorder | action 落审计，actor=SYSTEM |

---

## 1. 五拍板定案（D1-D5）

### D1 范围口径
本批三件套：①marketing-service 营销侧 Flow 引擎（automation_rule/automation_log 双表）＋②关怀 Care 切真（/m3-care）＋③复诊召回 Recall 切真（/recall）。
- FollowTasksView（M3-08）**不纳入**（非 L85 落点行内容）。
- Flow 规则管理 **v1 不做前端页**（无 mock 页面对应、铁律-1-B 样式零改动）；规则经 V47 播种默认三条＋REST API 管理，前端 Care/Recall 页仅展示来源徽标（SYSTEM_AUTO/规则名）。
- 诊疗侧术后 SOP（txn followup）零改动、零重复建设。

### D2 引擎形态
按 B69 蓝图落地双表：
- `automation_rule`：trigger_type + trigger_config(JSONB) + condition_config(JSONB) + action_type + action_config(JSONB) + enabled + store_code（NULL=全部门店）。
- `automation_log`：rule_no + customer_id + trigger_date + action_type + action_ref（生成的 care_no/recall_no）+ status(SUCCESS/SKIPPED/FAILED) + message + **idem_key 唯一约束**（幂等防重核心）。
- 扫描器 `MarketingFlowJob`：仿 AutoGrantJob 每 5 分钟一轮（initialDelay 60s），逐规则扫描→候选客户解析→条件匹配→动作执行→落 log；单客户 try-catch 不中断；idem_key=`{ruleNo}:{customerId}:{triggerDate}` 查重即跳。

### D3 权限码
复用已播种码，零新增：
- Care 端点：care:view（查询/KPI/模板）/care:edit（create/markReached/markConverted）/care:send（send）。
- Recall 端点：recall:view（查询/KPI）/recall:create（schedule）/recall:edit（notify/confirm/book/reschedule/skip）。
- Flow 规则/日志端点：marketing:view（查）/marketing:edit（增改/启停）。

### D4 Trigger 首期子集（三条默认规则 V47 播种）
| trigger_type | 语义 | 默认配置 | 动作 | 关怀/召回落型 |
|---|---|---|---|---|
| BIRTHDAY | 生日关怀 | daysBefore=0 | CREATE_CARE_TASK | type=BIRTHDAY，channel=WECHAT |
| DORMANT_DAYS | 沉睡唤醒 | dormantDays=90（末次消费满 N 天未回店） | CREATE_CARE_TASK | type=REACTIVATE，channel=SMS |
| VISIT_GAP_DAYS | 复诊召回自动化 | gapDays=30（末次治疗/到店满 N 天） | CREATE_RECALL | source=SYSTEM_AUTO，status=PENDING |

客户数据来源：customer 域（生日）＋txn 域（末次消费/治疗日期）经既有 internal client 解析；任一域不可用→该规则本轮软降级跳过（落 FAILED log，下轮自愈），不阻断其他规则。
Condition v1：store_code 门店过滤＋condition_config 透传（客户等级/标签预留，引擎做空值安全的基本匹配）。
NOTIFY 动作 v1 不独立落地——通知由 Care send 动作经 PushService consent 门控承载。

### D5 诊疗边界
- 营销侧**不直连 txn/customer 库**，只经既有 internal client（TxnInternalClient/CustomerConsentClient 范式）。
- recall 表与 txn followup 表零共享；DOCTOR_ADVICE/COURSE_FOLLOW 来源 v1 由人工创建承载（医生诊疗建议已由 B30/B31 SOP 覆盖，营销召回定位＝疗程后复诊提醒/沉睡召回）。
- 术后 N 天 Trigger 不做（属诊疗 SOP 域，B69 蓝图该 Trigger 由 txn 侧已闭环）。

---

## 2. 数据模型（V47__marketing_flow_care_recall_baseline.sql）

全部 CREATE TABLE IF NOT EXISTS（沿 V46 幂等可重入惯例；存量库缺列由 Hibernate update 补齐）。

### 2.1 automation_rule
| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | |
| rule_no | VARCHAR(32) UNIQUE NOT NULL | AR + yyyyMMdd + 4 位序号 |
| name | VARCHAR(64) NOT NULL | 规则名（中文） |
| trigger_type | VARCHAR(32) NOT NULL | BIRTHDAY/DORMANT_DAYS/VISIT_GAP_DAYS |
| trigger_config | JSONB | {daysBefore}/{dormantDays}/{gapDays} |
| condition_config | JSONB | {levels:[],tags:[]} 预留 |
| action_type | VARCHAR(32) NOT NULL | CREATE_CARE_TASK/CREATE_RECALL |
| action_config | JSONB | {careType,channel,contentTemplate}/{method,reason} |
| enabled | BOOLEAN NOT NULL DEFAULT true | |
| store_code | VARCHAR(32) | NULL=全部门店（铁律-1-D 多门店隔离：按客户归属门店过滤） |
| created_by | VARCHAR(32) | |
| created_at / updated_at | TIMESTAMPTZ | |

### 2.2 automation_log
| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | |
| rule_no | VARCHAR(32) NOT NULL | |
| customer_id | VARCHAR(16) NOT NULL | |
| trigger_date | DATE NOT NULL | 业务时区 +8 |
| action_type | VARCHAR(32) NOT NULL | |
| action_ref | VARCHAR(64) | 生成的 care_no/recall_no |
| status | VARCHAR(16) NOT NULL | SUCCESS/SKIPPED/FAILED |
| message | VARCHAR(500) | 跳过/失败原因 |
| idem_key | VARCHAR(128) UNIQUE NOT NULL | {ruleNo}:{customerId}:{triggerDate} |
| created_at | TIMESTAMPTZ | |
索引：(rule_no, trigger_date)、(customer_id)。

### 2.3 care_task
| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | |
| care_no | VARCHAR(32) UNIQUE NOT NULL | CARE + yyyyMMdd + 4 位序号 |
| customer_id / customer_name | VARCHAR(16)/VARCHAR(64) | |
| type | VARCHAR(16) NOT NULL | BIRTHDAY/HOLIDAY/REPURCHASE/REACTIVATE |
| channel | VARCHAR(16) NOT NULL | SMS/WECHAT/PHONE |
| content | VARCHAR(500) | 关怀文案（经 ForbiddenWordService 校验） |
| plan_date | DATE NOT NULL | 计划关怀日（KPI「本月待关怀」锚） |
| status | VARCHAR(16) NOT NULL DEFAULT 'PENDING' | PENDING/SENT/REACHED |
| reached | BOOLEAN NOT NULL DEFAULT false | 触达标记 |
| converted_booking | BOOLEAN NOT NULL DEFAULT false | 带来预约 |
| sent_at / reached_at | TIMESTAMPTZ | |
| rule_no | VARCHAR(32) | 来源规则，NULL=人工创建 |
| store_code | VARCHAR(32) | |
| created_by / created_at / updated_at | | |
索引：(status, plan_date)、(customer_id)。

### 2.4 recall
| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | |
| recall_no | VARCHAR(32) UNIQUE NOT NULL | RC + yyyyMMdd + 4 位序号 |
| customer_id / customer_name | | |
| source | VARCHAR(16) NOT NULL | DOCTOR_ADVICE/COURSE_FOLLOW/SYSTEM_AUTO/MANUAL |
| reason | VARCHAR(200) NOT NULL | 召回事由 |
| related_emr_no / related_order_no | VARCHAR(32) | 可空 |
| last_visit_date | DATE | 末次到店/治疗日 |
| due_date | DATE NOT NULL | 应召回日（超期/今日/3 日 KPI 锚） |
| method | VARCHAR(16) NOT NULL | PHONE/WECHAT/SMS/IN_STORE |
| status | VARCHAR(16) NOT NULL DEFAULT 'PENDING' | 五态状态机 |
| customer_reply | VARCHAR(500) | 客户回复 |
| confirmed_date | DATE | 确认复诊日 |
| note | VARCHAR(500) | |
| timeline | JSONB | [{at,by,action,detail}] 内嵌时间线 |
| rule_no | VARCHAR(32) | SYSTEM_AUTO 来源规则 |
| store_code | VARCHAR(32) | |
| created_by / created_at / updated_at | | |
索引：(status, due_date)、(customer_id)。

### 2.5 V47 播种（默认三规则 + 关怀模板）
- 三规则：生日关怀(BIRTHDAY, daysBefore=0)、沉睡唤醒(DORMANT_DAYS, dormantDays=90)、复诊召回(VISIT_GAP_DAYS, gapDays=30)，enabled=true、store_code=NULL。
- 关怀模板 5 条（对齐 mock tpl-s03/tpl-t12/tpl-p01/tpl-r02/tpl-w01 语义）落 marketing_cfg 或独立 care_template 段——施工时以最小改动定（倾向 marketing_cfg KV 承载，不新建表）。

## 3. 端点契约（/api/marketing/**，三方契约沿链上惯例）

### Flow 规则（marketing:view/edit）
- GET /flow/rules（enabled/store 过滤）、POST /flow/rules、PUT /flow/rules/{id}、POST /flow/rules/{id}/toggle
- GET /flow/logs?ruleNo=&date= （分页）

### Care（care:view/edit/send）
- GET /care/tasks?status=&month=&storeCode= → 列表+KPI 同响应（pendingThisMonth/sent/reachRate/converted 四键）
- POST /care/tasks（create，care:edit）
- POST /care/tasks/{id}/send（care:send；channel=SMS/WECHAT→PushService consent 门控落 push_record，PHONE→仅登记 sent_at；撤回同意→返回 skipped 原因不落 record）
- POST /care/tasks/{id}/reach、POST /care/tasks/{id}/convert（care:edit）
- GET /care/templates（care:view）

### Recall（recall:view/create/edit）
- GET /recall?status=&kw=&storeCode= → 列表+KPI（overdue/todayPending/upcoming/conversionRate）
- POST /recall（schedule，recall:create；服务端状态机校验 TRANSITIONS）
- POST /recall/{id}/notify|confirm|book|reschedule|skip（recall:edit；每次转移 append timeline；非法转移 409）
- 状态机服务端唯一权威：PENDING→[NOTIFIED,SKIPPED]；NOTIFIED→[CONFIRMED,BOOKED,SKIPPED,PENDING(改期)]；CONFIRMED→[BOOKED,SKIPPED]；BOOKED/SKIPPED 终态。

### 前端切真（铁律-1-B 样式零改动）
- 新增 src/api/care.ts、src/api/recall.ts 薄封装（axios client 拦截器透传 .data，B86 教训）。
- care.ts/recall.ts store 去 mock 切真：seed()→load() 拉真实列表；KPI getters 由响应计算；动作→API 后本地刷新。
- RecallView submitRecall 的 customerId:'C-NEW' 占位→searchCustomers 检索绑定（仿 RepurchaseView）。
- 来源徽标：rule_no 非空/SYSTEM_AUTO 展示「自动」，其余按 source 标签。

## 4. 引擎执行流（MarketingFlowJob 每 5 分钟）

```
扫 enabled 规则（store_code 过滤）
 └─ 按 trigger_type 解析候选客户：
 │    BIRTHDAY      → customer 域生日=今日（+8）
 │    DORMANT_DAYS  → txn 末次消费满 N 天且此后无回店
 │    VISIT_GAP_DAYS→ txn 末次治疗满 N 天
 ├─ idem_key 查重（存在即跳）
 ├─ condition 匹配（门店/预留条件）
 ├─ 执行动作：
 │    CREATE_CARE_TASK → 落 care_task（rule_no 回溯，plan_date=今日，content 经违禁词校验）
 │    CREATE_RECALL    → 落 recall（source=SYSTEM_AUTO，due_date=今日，timeline 首条系统创建）
 ├─ 落 automation_log（SUCCESS/SKIPPED/FAILED + action_ref）
 └─ 单客户 try-catch 不中断；域故障→该规则本轮 FAILED 跳下轮自愈
```

## 5. 三轨验证预案（卡1 后端）

1. **构建**：backend/ 目录 mvn package 重打 fat-jar → 重建 marketing-service 镜像（B88/B40 P0 教训：push 后必须重包再建镜像）。
2. **迁移**：psql 实证 V47 四表+三规则种子（docker exec meiyun-pg，5433）。
3. **curl 实证**（token /tmp/tok_E*.txt，curl -k，双栈 8080/8443）：
   - Flow：三规则可查、toggle 生效、logs 空→跑批后有记录；
   - Care：create→send（consent 门控正/反例）→reach→convert→KPI 联动；
   - Recall：schedule→notify→confirm→book 全链路＋非法转移 409＋reschedule 回退 PENDING＋skip；
   - 权限反例：无 care:send 角色调 send → 403。
4. **引擎实证**：临时将某客户生日置今日/末次消费置 91 天前 → 单轮手动触发或等一轮 → care_task/recall 自动生成 + automation_log idem 防重（同轮重扫不重复生成）。

## 6. 批末落账预案

- 02 L85 ⬜→✅（主落点）；02 L50（域③ 复诊召回「依赖营销自动化，远期」）联动评估：Recall 全链路真实＋SYSTEM_AUTO 自动生成落地即满足→预期同勾。**数字跃迁预案：✅116→118/128≈92.2%，⬜11→9，🔧1 不动；域⑤ 13✅/0/1⬜→14✅/0/0⬜（域⑤ ⬜ 清零）；域③ 复诊唤回类 ⬜ 联动清零**（批末按实际交付定夺）。
- 04 L52（私域自动化 Flow）勾销；L83（沉睡客户唤醒/复诊召回自动化）全行勾销（DORMANT_DAYS＋VISIT_GAP_DAYS 双双落地）。
- 01/00/03 沿数字跃迁批口径回写；哨兵翻 DORMANT 五键轮换存档（八要素格式）。

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| afterCommit 事务静默丢失（B30 fa549d5） | 引擎为独立 @Scheduled，非 afterCommit 场景；若后续接事件触发，写库体一律 REQUIRES_NEW |
| consent 撤回客户被自动关怀骚扰 | Care send 强制 PushService consent 门控；撤回→skipped 不落 record，合规优先 |
| 生日/沉睡扫描全表性能 | 候选解析走 internal client 分页；v1 门店级过滤＋5 分钟低频；idem_key 唯一约束兜底并发重复 |
| 双前端 8080/18080 会话假象（B83） | 浏览器实证统一 18080 口径 |
| 规则误配产生大量任务 | V47 仅播种三条保守规则；toggle 端点可随时停用；automation_log 全量可追溯 |
