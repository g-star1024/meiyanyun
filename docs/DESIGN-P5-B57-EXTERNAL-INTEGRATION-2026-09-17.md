# P5-B57 卡2 方案设计：外部依赖统一「配置窗口」（external_integration）

> 时间：2026-09-17
> 状态：**方案定案（用户总授权「按你推荐的来 + 竞品调研后推荐」，本文件给出落地细节，P0 随本批实施）**
> 用户决策原文（2026-09-17）：
> 「所有依赖外部的，都统一调整好配置窗口，确保只要外部信息接入，就可以正常运行；其他项目按你推荐的来，若有涉及决策的，也可以先调研竞品，根据竞品信息，推荐最适合我们的，开工吧」
> 事实基础：2026-09-17 全量代码侦察（10 处外部依赖 @Value 精确行号、B26 回传骨架、A1 加密范式、T3 mock 页、org Flyway/权限/内部端点基线，文件行号均实证）+ 竞品调研（有赞 / 微盟 / 巨量引擎 / 美团 / 抖音公开文档）。
> 铁律遵循：meiyun-dev-rules——写接口四件套（校验/幂等/全动作审计/中文错误）；跨服务取数 RestTemplate 内部端点 + X-Internal-Token（禁直读别域表）；业务表 JPA ddl-auto=update、系统表 Flyway 且已执行迁移禁改；单库共享 flyway_schema_history 全局版本号（当前最高 V33，本批 V34）；种子库/正式库同脚本 CREATE IF NOT EXISTS；样式零改动（复用既有组件与页内 tab，不重排页面）；UI 模型≠后端能力诚实降级不伪造；密钥明文不落库、不进日志、不下发前端。

---

## 〇、设计总原则

1. **一个窗口管全部外部接入点，外部信息一录入即可运行**：通知网关 URL、广告渠道密钥、免签开关等当前散落在 yml/env 的外部依赖，统一收口到 org-service 的 `external_integration` 配置表 + `/integrations` 页真实维护入口；运维/店长在页面录入即落库，消费服务短 TTL 拉取生效，**无需改 yml、无需重启**。
2. **未配置保持诚实降级，绝不伪造成功**：窗口建立前后，未配置的外部通道行为完全不变——通知 `SKIPPED`（不伪造发送成功）、广告回传密钥缺失 `503`（fail-closed，不放行伪造回传）。配置页对每个接入点明示「未配置影响」文案。
3. **env/yml 作为兜底回退而非首选**：消费侧取值优先级 = 库配置（enabled）→ 既有 @Value/env → 空（既有降级）。保证：① 既有 env 部署不破坏；② org-service 短暂故障不拉垮通知/验签链路；③ fail-closed 语义不被任何故障模式意外翻转。
4. **单点持钥，消费方不持主密钥**：AES-GCM 主密钥只注入 org-service；txn/marketing 等消费服务经 X-Internal-Token 内部端点取解密值，不接触主密钥、不持加解密能力。
5. **只建窗口，不伪造对接**：本批交付的是「配置窗口 + 既有真实链路的配置化」，不是支付出站、广告 OAuth/花费拉单、ERP/医保/税控等 T3 能力本身。P2 项只在目录中建模并给诚实空态，绝不假装已连通（与 T3 mock 页红线一致）。
6. **权限/路由/网关零新增**：`integration:view/create/edit/sync/reconcile` 五码已在权限矩阵注册并授权（PermissionMatrix L125/L276-279）；`/integrations` 路由与 T3IntegrationView 已存在；新端点挂 org-service 既有 `/api/org` 前缀，`/internal/` 路径网关对外 404（ScheduleController/OrgController 既有内部端点同构先例）。

---

## 一、现状盘点（实证事实，设计的事实地基）

### 1.1 外部依赖配置散落点（全量 10 处 @Value，yml 均无键、全走代码默认值）

| # | 文件:行 | 配置键 | 默认 | 现状行为 |
|---|---|---|---|---|
| 1 | [SmsChannelAdapter.java:22](../backend/txn-service/src/main/java/com/meiyun/txn/SmsChannelAdapter.java) | `meiyun.notify.gateway.sms-url` | 空 | 空→`DeliveryResult.skipped("dev 网关未配置…")`（L38-40）；4xx→dead，异常→failed |
| 2 | [WechatChannelAdapter.java:22](../backend/txn-service/src/main/java/com/meiyun/txn/WechatChannelAdapter.java) | `meiyun.notify.gateway.wechat-url` | 空 | 同上（企微通道） |
| 3 | [EmailChannelAdapter.java:22](../backend/txn-service/src/main/java/com/meiyun/txn/EmailChannelAdapter.java) | `meiyun.notify.gateway.email-url` | 空 | 同上（邮件通道） |
| 4 | [NotificationQuietConfig.java:20-26](../backend/txn-service/src/main/java/com/meiyun/txn/NotificationQuietConfig.java) | `meiyun.notify.quiet.enabled/start/end` | false/22:00/08:00 | **既有完整可用功能**（免打扰时段），非外部凭证，本批不纳管（P1） |
| 5 | [ExternalChannelController.java:71](../backend/marketing-service/src/main/java/com/meiyun/marketing/ExternalChannelController.java) | `meiyun.channel.dev-no-auth` | false | true 免签并启动告警（L88-93）；默认 fail-closed |
| 6 | 同上 L74 | `meiyun.channel.secret.douyin` | 空 | 空→验签 503「渠道密钥未配置，回传暂不可用」（L244-247） |
| 7 | 同上 L76 | `meiyun.channel.secret.red` | 空 | 同上（RED） |
| 8 | 同上 L78 | `meiyun.channel.secret.meituan` | 空 | 同上（MEITUAN） |

**P0 纳管 7 个值**：3 个通知 webhook URL（#1-3）＋3 个广告 secret（#6-8）＋1 个免签开关（#5）。#4 免打扰时段属内部偏好而非外部接入，列 P1。

### 1.2 B26 广告回传骨架（已存在，本批只换密钥来源，不动安全模型）

[ExternalChannelController.java](../backend/marketing-service/src/main/java/com/meiyun/marketing/ExternalChannelController.java) 已实现完整 fail-closed 安全模型（L28-46 注释即规格）：
- HMAC-SHA256 签名：`hex(secret, channelCode + "\n" + ts + "\n" + nonce + "\n" + rawBody)`，`MessageDigest.isEqual` 常量时间比较（L248-253）；
- 时间戳 ±300s 防重放、nonce≤64、body≤64KB、单 IP 60 次/分限流（L60-63/L104/L239-242）；
- `(channel_code, biz_ref)` 业务幂等，重复回调 dedup=true 返回既有记录（L133-143）；
- 密钥缺失一律 503，渠道不存在一律 401（不暴露渠道存在性）；
- **每次回调验签都即时消费 secret（L243）→ 配置化后消费侧必须短 TTL 拉取，不能只做启动快照**；
- 另有 GET `/sample` 联调样例端点（L190-222），可直接作为「密钥已配置后如何联调」的页面指引。

### 1.3 三套既有「配置窗口」范式（本批的拼装件，不发明新模式）

1. **A1 模型供应商（最成熟，加密/掩码/测试范式）**：[ApiKeyCipher.java](../backend/ai-service/src/main/java/com/meiyun/ai/security/ApiKeyCipher.java) AES-GCM——主密钥 `meiyun.ai.config-secret`（env `AI_CONFIG_SECRET`）SHA-256 派生，密文 `Base64(IV(12B)‖密文+tag128)`，明文不落库不进日志；掩码 `前4****后4`（L75-83）；供应商/模型 CRUD + 真实连通性测试 + 全动作审计（02 册 L149）。
2. **支付渠道配置（upsert/toggle/hasApiKey 范式）**：[payChannel.ts](../frontend/src/api/payChannel.ts)——列表只回 `hasApiKey` 不回明文；保存时密钥留空=不修改；停用不抹配置、不影响历史账。
3. **T3 集成中心页（纯前端 mock，本批的页面落点）**：[T3IntegrationView.vue](../frontend/src/views/T3IntegrationView.vue) + [t3Integration.ts](../frontend/src/stores/t3Integration.ts)——无 axios，9 个连接器/4 tab（连接器/Outbox/对账批次/调用日志）全部 `Math.random()` 造数（L179/L195/L261 等），属 T3-04 远期演示；路由 `/integrations`、菜单、`integration:*` 按钮权限均已真实存在。

### 1.4 org-service 基线（配置窗口平台属主）

- [pom.xml](../backend/org-service/pom.xml)：**无 flyway-core 依赖** → 本批须新增（版本由 spring-boot-starter-parent 管理）；
- [application.yml](../backend/org-service)：无 `spring.flyway` 块；JPA ddl-auto=update；已有 `meiyun.security.internal-token`（L32）；
- [OrgApplication.java](../backend/org-service/src/main/java/com/meiyun/org/OrgApplication.java)：已声明自有 RestTemplate Bean（连接 3s/读取 5s，服务间调用软隔离）→ 管理面测试外呼直接复用；
- 内部端点先例：`/api/org/internal/staff/{id}`、`/internal/staff/by-role`（OrgController L232/L263）、`/internal/schedule/resolve`（ScheduleController L252），统一 `@RequirePerm("internal:name-map")` + X-Internal-Token 系统身份，网关对外 404；
- Flyway 共享链配置范本：[ai-service application.yml L21-36](../backend/ai-service/src/main/resources/application.yml)（enabled / classpath:db/migration / baseline-on-migrate / baseline-version 0 / 共享 `flyway_schema_history` / validate-on-migrate / ignore `*:missing`+`*:future`）；org 为第 9 个接入服务，照抄即可，**改 pom 后必须 `mvn -pl org-service -am package` 重新出 fat-jar 再 compose build**（B38 踩坑，Dockerfile 只 COPY 宿主 jar）；
- 全局版本现状：…V30/V31 audit、V32 marketing、V33 finance（当前最高）→ **本批取 V34**；DDL 一律 CREATE TABLE IF NOT EXISTS，不 ALTER 存量表（V32 仅在实证 drift 时 ADD COLUMN IF NOT EXISTS 为特例）。

---

## 二、竞品调研结论（2026-09-17 检索，映射到本批取舍）

| 竞品（公开文档/帮助中心口径） | 通行做法 | 我们的采纳 |
|---|---|---|
| **有赞**（消息通知/渠道配置后台） | 按通道独立配置网关与签名；**保存即发一条测试消息**验证通路 | 采纳：通知类 URL 行提供「测试连接」，org 侧 RestTemplate 发探测 POST，2xx 即通过并回显结果；审计记录测试动作 |
| **微盟**（第三方应用授权/对接中心） | 集中授权页；未完成授权的能力在业务入口**明示降级后果**（如订单回传延迟约 1 小时），不静默假装可用 | 采纳：目录每行 `remark` 存「未配置影响」诚实文案（如「不配置→短信通知跳过，仅站内信送达」），未配置状态置灰业务说明 |
| **巨量引擎**（转化回传鉴权切换公告） | 新签名验证灰度三段式：**观察期只记日志不拦截 → 测试期失败返回原因但不丢 → 连续无失败后开强校验，开启后失败丢弃** | P0 不做（保持现有强校验 fail-closed，不削弱安全）；表内 `config_json.verifyMode` 预留 ENFORCED/OBSERVE 扩展位，**P1 再做广告验签观察期**，不临时改安全模型 |
| **美团**（开放平台回调验签） | `sig=MD5(URL+字典序参数+secret)`；强制 HTTPS；msgId 去重；回调 URL 需平台审核生效 | 已覆盖：我们 HMAC-SHA256 更强、bizRef 幂等即 msgId 去重；**采纳「强制 HTTPS」**——URL 类配置保存时校验 https://（dev 网关 http 需显式勾选「我确认内网/联调地址」二次确认，与 dev-no-auth 同级别告警） |
| **抖音开放平台/巨量**（回传/事件上报） | ClientKey/Secret 控制台获取；**签名只允许服务器端计算**，密钥禁止下发前端 | 已覆盖：secret 经 internal 端点只在服务间流转；前端列表只见掩码、写后不可读；内部端点网关外 404 |

---

## 三、数据模型（V34，org-service 新建系统表）

### 3.1 表 DDL 草案

迁移文件：`backend/org-service/src/main/resources/db/migration/V34__external_integration.sql`

```sql
-- V34__external_integration.sql
-- 美研云门店中台 - 域⑧平台基建：外部依赖统一配置窗口（P5-B57 卡2）
-- 版本号：全库共享 flyway_schema_history 全局递增，V33（finance 报告验真）之后首个空号 V34。
-- 属主：org-service（第 9 个接入 Flyway 的服务，配置照抄 ai-service 共享链：
--   baseline-version=0 + ignore *:missing/*:future）。
-- 幂等：CREATE TABLE IF NOT EXISTS + 目录行 INSERT ... ON CONFLICT DO NOTHING；
--   不 ALTER 任何存量表；全新库/正式库/种子库同脚本执行。

CREATE TABLE IF NOT EXISTS external_integration (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    integration_code  VARCHAR(64)  NOT NULL,
    category          VARCHAR(32)  NOT NULL,            -- NOTIFY_GATEWAY / AD_CHANNEL
    integration_name  VARCHAR(128) NOT NULL,            -- 中文展示名
    value_kind        VARCHAR(16)  NOT NULL,            -- URL / SECRET / SWITCH
    base_url          VARCHAR(512),                     -- URL 类：网关 webhook
    secret_cipher     TEXT,                             -- SECRET 类：AES-GCM 密文（org 单点持钥）
    secret_mask       VARCHAR(80),                      -- 掩码回显（前4****后4），明文绝不下发
    bool_value        BOOLEAN,                          -- SWITCH 类：开关实际值
    enabled           BOOLEAN      NOT NULL DEFAULT FALSE, -- 录入并启用后才被消费侧取用
    config_json       JSONB,                            -- 协议扩展位（P1 verifyMode 等），P0 可空
    remark            VARCHAR(256),                     -- 「未配置影响」诚实文案
    last_test_at      TIMESTAMPTZ,
    last_test_ok      BOOLEAN,
    last_test_msg     VARCHAR(256),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        VARCHAR(64),
    CONSTRAINT uq_external_integration_code UNIQUE (integration_code),
    CONSTRAINT ck_external_integration_kind CHECK (value_kind IN ('URL','SECRET','SWITCH'))
);
COMMENT ON TABLE external_integration IS '外部依赖统一配置窗口（P5-B57）：通知网关/广告渠道密钥/安全开关，org-service 平台属主';

-- P0 目录行（固定 7 项，ON CONFLICT 幂等；代码侧 IntegrationCatalog 同口径枚举，禁止前端自由建项）
INSERT INTO external_integration
    (integration_code, category, integration_name, value_kind, enabled, bool_value, remark)
VALUES
    ('NOTIFY_SMS_GATEWAY',   'NOTIFY_GATEWAY', '短信通知网关 webhook', 'URL', FALSE, NULL,
     '未配置：短信通道跳过（SKIPPED），通知仅站内信送达，不产生真实短信'),
    ('NOTIFY_WECHAT_GATEWAY','NOTIFY_GATEWAY', '企业微信通知 webhook', 'URL', FALSE, NULL,
     '未配置：企微通道跳过（SKIPPED），通知仅站内信送达'),
    ('NOTIFY_EMAIL_GATEWAY', 'NOTIFY_GATEWAY', '邮件通知网关 webhook', 'URL', FALSE, NULL,
     '未配置：邮件通道跳过（SKIPPED），通知仅站内信送达'),
    ('AD_SECRET_DOUYIN',     'AD_CHANNEL',     '抖音/巨量回传签名密钥', 'SECRET', FALSE, NULL,
     '未配置：抖音回传一律 503 拒绝（fail-closed，杜绝伪造转化）'),
    ('AD_SECRET_RED',        'AD_CHANNEL',     '小红书回传签名密钥', 'SECRET', FALSE, NULL,
     '未配置：小红书回传一律 503 拒绝'),
    ('AD_SECRET_MEITUAN',    'AD_CHANNEL',     '美团回传签名密钥', 'SECRET', FALSE, NULL,
     '未配置：美团回传一律 503 拒绝'),
    ('AD_DEV_NO_AUTH',       'AD_CHANNEL',     '广告回传免签（仅联调）', 'SWITCH', FALSE, FALSE,
     '关闭（默认）：回传必须验签；开启仅限联调环境，生产开启视同安全事故，启动与保存均告警')
ON CONFLICT (integration_code) DO NOTHING;
```

JPA 实体 `ExternalIntegration`（ddl-auto=update 与 V34 共存，表已存在 update 为 no-op）；`config_json` 映射 String（JDBC URL 已带 `stringtype=unspecified`）。

### 3.2 目录口径

- P0 目录 7 行由迁移幂等播种，**P0 不开放自由新增接入点**（避免 P2 能力被提前假装「已接入」）；代码侧 `IntegrationCatalog` 枚举为目录第二真源，与 SQL 同码同名。
- P1/P2 新接入点（免打扰时段、支付出站、广告 OAuth、ERP 等）后续批次以「新迁移 + 枚举扩充」追加，历史迁移禁改。

---

## 四、后端设计（org-service 平台属主，新包 `com.meiyun.org.integration`）

### 4.1 组件清单

| 组件 | 职责 |
|---|---|
| `ExternalIntegration`（domain） | JPA 实体 |
| `ExternalIntegrationRepository` | `findByIntegrationCode` / `findByCategoryOrderByCategoryAscIdAsc` / `findByEnabledTrue` |
| `IntegrationCatalog`（枚举） | 7 项目录：code/category/name/valueKind/影响文案；校验入参 code 合法且 kind 匹配 |
| `IntegrationSecretCipher`（security） | 拷贝 ApiKeyCipher 同算法（AES-GCM/IV12B/tag128/Base64），主密钥 `meiyun.integration.config-secret`（env `INTEGRATION_CONFIG_SECRET`，dev 默认值仅本地联调）；静态 `mask()` 同款 |
| `IntegrationService` | 列表视图装配（不回显明文/密文字段，给 hasSecret+mask）、upsert（密钥留空/含 `*` 不覆盖，仿 A1）、toggle、测试连接、快照装配（解密） |
| `IntegrationAdminController` | 真人管理面 API（§4.2） |
| `InternalIntegrationController` | 服务间分发 API（§4.3） |

审计：复用 org 既有 `AuditRecorder`（RestAuditRecorder 落 audit-service），动作码 `integration.update` / `integration.test`，记 code/操作人/结果，**绝不记明文密钥与完整 URL 查询串**。

### 4.2 管理面 API（真人，挂 /api/org 前缀，网关正常鉴权）

| 方法/路径 | 权限 | 说明 |
|---|---|---|
| GET `/api/org/integrations` | `integration:view` | 目录全量视图：code/category/name/valueKind/baseUrl/hasSecret/secretMask/boolValue/enabled/remark/lastTest*；无密文无明文 |
| POST `/api/org/integrations/{code}` | `integration:edit` | upsert 入参 `{baseUrl, secret, boolValue, enabled}`：①code 必须在目录且 kind 匹配，否则 400 中文错误；②URL 类保存校验 http(s) URL 格式，**非 https 须入参 `insecureHttpConfirmed=true`**（仅联调/内网，二次确认）；③secret 为 null/空白/含掩码（含 `****`）= 不修改原密钥；④SWITCH 行只认 boolValue+enabled；⑤保存即更新 updated_at/updated_by，写审计 |
| POST `/api/org/integrations/{code}/test` | `integration:edit` | 见 §4.4；回写 last_test_* 并返回 `{ok, message}` |

不新增权限码（目录固定，无自由建项，`integration:create` 留给 P1/P2 开放扩项时启用）。

### 4.3 服务间分发 API（系统身份，网关外 404）

`GET /api/org/internal/integrations/snapshot`
- `@RequirePerm("internal:integration-read")`——**新内部权限码，不注册给任何真人角色**（系统 LoginUser perms=["*"] 可过；与 internal:name-map 同款纯文档化防御）；
- 无参，返回全量 7 项快照（含禁用项，消费方自行按 enabled 决策）：

```json
[
  {"code":"NOTIFY_SMS_GATEWAY","enabled":false,"baseUrl":null,"secret":null,"boolValue":null,"updatedAt":null},
  {"code":"AD_SECRET_DOUYIN","enabled":true,"baseUrl":null,"secret":"<明文，仅服务间内网+令牌>","boolValue":null,"updatedAt":"2026-09-17T20:00:00+08:00"}
]
```

- 明文 secret 只出现在此端点；审计记「系统快照拉取」调用方（X-Internal-Token 系统身份），不记值。

### 4.4 测试连接语义（有赞「保存即测试」的诚实版，分类处理）

- **URL 类（3 个通知网关）**：org 复用既有 RestTemplate（3s/5s）向 baseUrl POST `{"test":true,"scene":"integration-probe","ts":...}`；2xx→`{ok:true}`；4xx→`{ok:false, "网关确定性拒绝(4xx)"}`（对应投递 DEAD 语义，不重试）；超时/5xx→`{ok:false, "网关不可达/异常"}`；不篡改任何通知数据。
- **SECRET 类（3 个广告密钥）**：**入站签名密钥无法通过出站调用测试**（诚实，不做假握手）：测试 = 非空/长度≥16/字符集校验通过即 `{ok:true,"密钥格式已校验；请用渠道 /sample 样例发起真实联调回调"}`，并在页面附 marketing `/api/marketing/channels/{code}/sample` 指引链接。
- **SWITCH 类（免签）**：无测试动作；开启保存时返回强告警文案并落 warn 日志（沿用 ExternalChannelController L88-93 告警语义）。

### 4.5 配置与部署变更

- org pom 增 `flyway-core`；org application.yml 增 `spring.flyway` 块（照抄 ai yml L21-36）+ `meiyun.integration.config-secret: ${INTEGRATION_CONFIG_SECRET:meiyun-dev-integration-config-secret-change-in-prod-0123}`；
- docker-compose.app.yml / docker-compose.seed.yml：org-service 增环境变量 `INTEGRATION_CONFIG_SECRET`；txn-service / marketing-service 增 `ORG_SERVICE_URL: "http://org-service:8086"`（实施时先核对两服务现有 env，已有则不重复）；
- 主密钥轮换影响：密钥变更后旧密文解密抛「主密钥可能已变更」（ApiKeyCipher L70 同款异常）→ 管理面对应行提示重新录入，不影响其他行；P0 不做双密钥灰度轮换（P1 候选）。

---

## 五、消费侧改造（txn-service / marketing-service）

### 5.1 取值客户端（两服务各一个，同构，约 80 行/个）

`com.meiyun.<svc>.IntegrationConfigClient`：
- 注入既有 RestTemplate Bean + `@Value("${org.service.url:http://127.0.0.1:8086}")` + `@Value("${meiyun.security.internal-token:...}")`（marketing 需补 internal-token 配置，实施时核对）；
- **60 秒 TTL 内存快照**（volatile 字段 + 拉取时间戳，不引新依赖、不用 @Cacheable 避免 TTL 不可控）；拉取失败：宽限 10 分钟内沿用上一份 good 快照并 log.warn；超过宽限或从无快照→回退 env/@Value；
- 全程 try/catch 软降级（与 OrgStaffClient/StoreCatalogClient 既有客户端同构），**任何异常不抛给业务链路**。

解析优先级（三方法 `resolveUrl/resolveSecret/resolveSwitch(code, envFallback)`）：

```
1. 快照中该行 enabled=true 且对应字段非空 → 用库值
2. envFallback（既有 @Value 默认/环境变量）非空 → 用 env 值（向后兼容）
3. 空/null → 交给调用方既有降级（通知 skipped / 广告 503）
SWITCH：库 enabled=true 且 bool_value=true → true；否则 env=true → true（保留启动告警）；否则 false（fail-closed）
```

### 5.2 txn 三适配器改造

[SmsChannelAdapter](../backend/txn-service/src/main/java/com/meiyun/txn/SmsChannelAdapter.java) L22/L38-40：`smsUrl` 改为构造器注入的 env 兜底值，send() 首部改 `String url = integrationConfig.resolveUrl("NOTIFY_SMS_GATEWAY", envUrl);` 空则原 skipped 文案保留（文案补「或配置窗口未启用」）；Wechat/Email 同构。投递结果契约 sent/dead/failed/skipped 与 4xx→dead 语义一字不动。

### 5.3 marketing ExternalChannelController 改造

- L71 `devNoAuth` 保留为 env 兜底；L117 判断改 `boolean effectiveNoAuth = integrationConfig.resolveSwitch("AD_DEV_NO_AUTH", devNoAuth);`，`@PostConstruct` 告警改按有效值；
- L256-263 `secretOf(code)` 改读客户端：`DOUYIN→resolveSecret("AD_SECRET_DOUYIN", secretDouyin)`，RED/MEITUAN 同理；
- 验签/限流/时间窗/幂等/客户校验全部不动；密钥空→503 文案不动（L244-247）。

### 5.4 不做的事

- 不在消费侧缓存密钥到磁盘/DB；不把内部快照 URL 暴露给任何真人端点；
- 不改 B26 签名算法与状态码；不改 NotificationFanoutJob 重试/死信链路；
- 不让 org-service 反向依赖 txn/marketing（属主只被拉取，不主动推送）。

---

## 六、前端落点（样式零改动）

- 在既有 [T3IntegrationView.vue](../frontend/src/views/T3IntegrationView.vue) 的 CSegmented tab 上**追加首个 tab「接入配置」**（位置在「连接器」之前），现有 4 tab（连接器/Outbox/对账批次/调用日志）布局、组件、视觉零改动；
- 新 tab 全部复用页面既有组件：CCard 列表 + CStatusPill 状态（已启用 success / 未配置 disabled / 免签开启 warning）+ CDrawer+CInput 编辑抽屉 + CButton 测试按钮；不新增任何视觉元素类型；
- 新建 [api/integration.ts](../frontend/src/api/integration.ts)（仿 payChannel.ts：列表/upsert/test 三函数，TS 接口对齐后端视图，secret 只写不读、掩码展示、留空不改）；不新建 store（页内局部状态即可，与页面规模匹配）；
- 行内诚实信息：remark「未配置影响」常驻；SECRET 行密钥框 placeholder 显示掩码或「未配置（保存后不可读回）」；URL 行非 https 弹二次确认；测试结果内联回显（ok/失败原因 + 时间）；SWITCH 开启显示红色告警条；
- 现有 4 个 mock tab **原样保留**：它们是 T3-04 远期演示（Outbox/T+1 对账/资金红线），本批不真实化、也不删除；新 tab 标题区标注「真实配置」与 mock 演示区语义区分，避免运营误读。

---

## 七、切片红线（P0 本批实施，P1/P2 仅登记不提前造能力）

| 切片 | 内容 | 本批 |
|---|---|---|
| **P0** | 7 配置值窗口化（V34 表+目录+加密+管理 API+内部快照+审计）；txn 3 适配器/marketing 验签接配置拉取（env 兜底+60s TTL+宽限）；前端新 tab 真实 CRUD+测试；通知 URL 探测测试；广告密钥格式校验+联调指引；免签开关告警 | ✅ |
| **P1** | 免打扰时段（#4 三值）入窗口；广告验签 OBSERVE 观察期（巨量三段式，用 config_json.verifyMode）；pay_channel/AI 供应商在窗口做只读状态聚合+跳转原管理页（不迁库不重建）；主密钥双密钥灰度轮换 | ⬠ 登记 backlog |
| **P2** | 支付真实出站/账单 API 拉取、广告 OAuth 授权与 spend 拉单、三方归因平台、小红书/大众点评/新氧/美团 OAuth 类接入、MinIO/MQ、ERP/医保/税控连接器、T3 页 Outbox/T+1 对账真实化 | ⬜ 仅保留目录位与诚实空态，**不写任何假对接、不留 CONNECTED 假象** |

---

## 八、台账口径论证（数字不变，纵深闭合 Backlog）

- 02 册 **L117 消息通知中心（✅）**：本批把备注中「真实运营商网关接入留 Backlog」推进为「**网关地址已可在配置窗口录入、录入即生效（无需重启）**；真实运营商账号开通/网关侧「工号→手机号」映射仍为外部前置，留 Backlog」——行状态仍 ✅（既有通知体系早已完成，本批是其 Backlog 纵深）。
- 02 册 **L81 渠道业绩（✅）/L80 营销 ROI（✅）**：B26 回传骨架的密钥来源由 env 变窗口，**回传接收链路已真实闭环，外部平台真实回传流量仍依赖对方平台配置，留 Backlog**；行状态不变。
- 02 册 **L147「T3 外部集成 ⬜ 远期」不跃迁**：T3 行的完成定义是连接器/Outbox 单向镜像/T+1 三方对账整页真实化；本批只建「配置窗口」，未建任何 T3 连接器能力，mock 四 tab 仍在。该行保持 ⬜，备注可注明「配置窗口已由 B57 前置建成（external_integration），连接器本体仍 T3」。
- 04 册 **L52 外部广告渠道 / L64 真实运营商网关**：勾销「配置散落 yml/env、无录入窗口」子项，真实外部账号/网关接入子项保留。
- 仪表盘数字：**109✅ / 1🔧 / 55⬜ 不变**（无新完成模块行）；域⑦域⑧计数不变。纯设计批次与实施批次闭合时均按此口径回写，禁止「校准」历史计数差。

---

## 九、真实验证计划（铁律 7，三轨）

1. **编译轨**：`mvn -pl org-service,txn-service,marketing-service -am package`（必须 package 出 fat-jar，B38 踩坑）；前端 `pnpm vue-tsc`（无新增类型错误）+ `pnpm build`。
2. **PG 轨**（core 与 seed 双栈各执行一次 V34）：表与 7 目录行存在、CHECK/唯一约束生效；upsert 后 secret_cipher 为密文、列内无明文凭据；掩码正确；留空保存密文不变；重新 upsert 覆盖后 updated_at 变化。
3. **运行轨**：
   - org 容器启动日志见 V34 migrate success；双栈重启各一次验证共享 flyway_schema_history 无 missing/future 报错；
   - 网关登录（E005）后 curl 管理面：列表无明文；无 integration:view 权限 403；
   - 网关外 curl `/api/org/internal/integrations/snapshot` 无令牌 → 404；容器内网 curl 带 X-Internal-Token → 200；
   - 通知：窗口启用一个本地探测 URL（one-line http server）→ 触发真实通知 → 收到探测外的真实通知 POST；禁用窗口、env 留空 → skipped 文案正确；
   - 广告：窗口录入密钥后用 `/sample` 同算法签名真实回调 → 200 落库；窗口清空（禁用）+env 空 → 503；故意错签 → 401；免签开关开启→免签成功且 warn 日志在位、关闭→恢复 401；
   - Chrome 轨：/integrations 新 tab 七行渲染、编辑抽屉、留空不改、测试结果、诚实影响文案、权限按钮置灰与既有视觉无错乱。

## 十、风险与回滚

- **org 故障**：60s TTL + 10 分钟 good 快照宽限 + env 兜底三重缓冲；宽限外通知 skipped、广告回退 env，env 也空则 503（fail-closed 不翻转）。
- **主密钥丢失/变更**：受影响行重新录入密钥即可；表内不含不可重建的业务数据（仅配置），必要时可整行重录。
- **回滚**：代码回退后消费侧回到纯 @Value/env 路径（env 键全部保留）；V34 表为独立新表不影响任何存量表，回滚不需 DROP（保留无害）；窗口内录入的密钥随服务回滚停止被消费。
- **防误开免签**：SWITCH 行 UI 红色告警 + 服务启动 warn 日志 + 审计留痕三重可见；生产 env 不设置兜底 true（代码默认 false）。
