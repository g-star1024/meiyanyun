# 美研云门店中台 · 客户域联调开发总结与待决策报告

> 时间：2026-09-01 凌晨
> 范围：客户 360 详情页接真实 API（客户域 customer-service）+ 全站中文名解析
> 验证栈：独立种子库 `meiyun_seed`（100 客户富画像）+ seed 联调栈（网关 18443 / 前端 dev 5174）
> 每步均按 `meiyun-dev-rules` 铁律自检，真实验证（curl + 浏览器截图）。

---

## 一、本轮已完成并验证（无需决策，可直接继续）

### 1. 客户 360 详情页真实接入（CustomerProfileView.vue 重写）
- **移除全部 mock store**（customer/order/emr/consultation 4 个 Pinia mock store 与 `store.seedProfile()`），改为 `onMounted` 用 `route.params.id` 拉真实接口。
- **已接真实并经浏览器验证**（SC001 方梦洁）：
  - 顶部信息条：真实姓名、手机（按 `customer:phone:decrypt` 权限脱敏 `139****0001`）、中文等级胶囊（黑卡会员）、客户状态胶囊（活跃）、会员号（customerId）、注册时间、来源（WECHAT 经字典转「微信」）、真实标签名（高净值客户/高频到店/抗衰偏好/油性肌肤）。
  - 4 KPI：累计消费 ¥26,752（totalSpend 元）、到店频次 18 次（visitCount）、积分余额 186,905、卡余额 ¥600（member_card.balance 60000 分 ÷100 换算）。
  - 档案 Tab：客户基础档案 10 项 + 会员卡列表（敏感肌修复8次卡 / 在用 / 剩余 1/8 次 / ¥600）+ 积分流水（开卡赠积分 +8000、消费累积 +178905，余额/原因中文）。
  - 智能提醒：由真实字段派生（沉睡/流失唤回、卡剩余次数 ≤2 提示续卡），非编造。
- **无后端字段的模块显式中文占位**，严禁造假数据：
  - 价值画像（RFM 五维雷达 / 生命周期）→「价值画像待接入」占位。
  - 消费订单 / 病历随访 / 对比照面诊 → 各自说明数据来源服务（txn-service / EMR 域 / 图床）的「待接入」占位。
  - 会员权益/服务偏好 → 「规则待配置接入」占位。
- **健壮性**：加载态、404 客户不存在态（不再 fallback 到假人 C-201）、错误重试态。

### 2. 后端 customer-service 扩展
- **新增 `RefNameResolver`**：用 `JdbcTemplate` **只读**批量解析 `staff.staff_name` / `store.store_name`（同库只读，不写表、不跨服务，避免 N+1）。
- **新增 `CustomerDetailDTO`**：详情接口 `GET /api/customer/{id}` 由直接返回实体改为返回 DTO，冗余 `ownerStaffName` / `storeName`。
- **`CustomerRowDTO` 扩展**：列表接口同步冗余 `storeName` / `ownerStaffName`。
- **API 契约**：路径前缀 `/api/customer` 未变（铁律 1 三处一致），仅新增响应字段，向后兼容。

### 3. 前端 API 层类型对齐真实响应（src/api/customer.ts）
- `CustomerDTO` 补 `birthDate/createdAt/storeName/ownerStaffName`。
- 修正 `PointsLedgerDTO`（ledgerId/changeAmt/balanceAfter/reason，原为错误的 id/delta）、新增 `MemberCardDTO`（balance 注明单位分）、`CustomerTagDTO`、`CustomerTagRelDTO`。
- 新增 `listAllTags()` / `listCustomerTagRels()`；标签两段 join（关系 tagId → 全量标签取名）。

### 4. 全站中文名（铁律 3「零技术码外露」+ 铁律 7「同类全站扫」）
- 列表页归属列 `ownerStaffId(SE006)` → `ownerStaffName(沈咨询)`；详情页归属咨询师 / 所属门店 → 中文名。
- 验证：seed 网关 `GET /api/customer/SC001` 返回 `SE006→沈咨询`、`SST02→上海浦东店`；列表前 3 条均返回中文名。

### 5. 工具链增强（向后兼容）
- `vite.config.ts`：dev proxy 网关目标支持 `VITE_GATEWAY_TARGET` 环境变量（默认仍 8443 正式栈），联调种子库 `VITE_GATEWAY_TARGET=https://localhost:18443 pnpm dev` 即指向 seed。

### 验证证据
- 前端 `vue-tsc --noEmit` **0 error**；`vite build` **成功**。
- 后端 `mvn package` **成功**，fat jar 48MB；seed 容器 `meiyun-seed-customer-service` healthy。
- 正式栈 `meiyun-customer-service` 全程 healthy **未受影响**（seed 栈独立项目名隔离）。
- 浏览器真实渲染截图 3 张（hero / 档案 / 中文名修正后），全部中文、无技术码外露、占位无假数据。

---

## 二、需你拍板的决策点（横跨架构边界或业务口径，我不擅自定）

### 决策 1｜跨服务读名字的架构边界（当前用了权宜方案，需确认长期方向）
**现状**：customer-service 用 `JdbcTemplate` 直接只读 `staff`(属 org-service) / `store`(属 store-service) 的表来取中文名。
- 之所以可行：当前 7 服务共单库 `meiyun_core/meiyun_seed`，MVP 联调阶段。
- 潜在问题：微服务边界上，customer-service 越权读了 org/store 的表；未来若拆库/拆数据源会失效。

**选项**：
- **A（推荐，暂维持）**：联调阶段维持同库只读解析（最小可逆、已工作），在代码注释与铁律中标注「拆库时改走服务调用」；等做服务拆分时统一改造。
- B：现在就改——customer-service 通过 HTTP 调 org-service（staff）/ store-service（store）聚合名字。更"正确"但要加跨服务调用、容错、缓存，当前 store-service 还**没有 staff 真名端点**（只有门店 3 个端点），需先补 org-service staff 查询接口，工作量明显更大。

> 我的建议：A。单库未拆前，同库只读是务实选择；拆库是独立里程碑，届时连同其他跨域聚合一起改。

### 决策 2｜消费订单 Tab：缺「按客户列订单」端点 + 订单数据结构缺口
**核证事实**：
- txn-service 订单端点只有 `GET /api/txn/order/{no}`（按**单号**查单个），**没有「按客户列出全部订单」**的端点。
- `txn_order` 表只有单字段 `project`（一个中文项目名）、`amount`(bigint 分)、`status`(中文 待签核/待收款/已收款/已取消)，**没有订单子项 items 表**。
- 而前端 360 订单 Tab / mock order store 用的是 `o.items[].name` 多项目结构 —— 与真实表结构不符。

**需决策**：
- 2a. 是否在 txn-service **新增** `GET /api/txn/order?customerId=xxx`（按客户分页列订单）端点？（属新增 API，建议做）
- 2b. 订单明细以什么为准：真实表只有单一 `project`，前端「多个商品项」展示是改成显示单个 project，还是后续要建订单子项表（order_item）？这关系到下单流程的数据模型，影响 M4 开单链路。

### 决策 3｜价值画像（RFM 五维评分 / 生命周期）评分口径
**现状**：后端客户域**零 RFM 字段**，前端雷达图 5 维（最近R/频次F/金额M/忠诚/活跃）与生命周期阶段（生美体验→首诊→升单→复购→维持）全是 mock。
种子数据已含 totalSpend/visitCount/最近消费时间等**可计算 RFM 的原料**。

**需决策**（业务算法定口径，不宜我拍）：
- 3a. RFM 各维评分规则：R/F/M 分别按什么阈值打分（如最近消费天数区间、到店次数区间、累计金额区间）？忠诚/活跃两维如何定义？
- 3b. 评分在哪算：customer-service 实时聚合计算，还是离线/定时任务算好落库（如 customer_rfm 表）？
- 3c. 生命周期阶段判定规则（升单链路 5 阶段的进入/流转条件）。

> 我的建议：3b 用「customer-service 读时实时计算 + 结果可选缓存」起步（数据量小、无需新表），口径由你/运营给阈值表后我落地。

### 决策 4｜病历随访 / 对比照面诊 Tab
- **面诊咨询**：`GET /api/txn/consultation/{customerId}` 端点**现成**，可直接接（含方案金额/项目）。
- **预约**：`GET /api/txn/appointment/cross-check/{customerId}` 现成（近 30 天），档案页「近期预约」可接。
- **EMR 病历 / 随访 / 对比照**：涉及 EMR 只读双签合规、字段级 RBAC、MinIO/S3 图床，**当前无图床、无 EMR 查询端点**，属医疗文书域，合规要求高（不可篡改、全程审计）。

**需决策**：
- 4a. 下一步是否先接「面诊咨询 + 预约」（txn-service 现成端点，低风险）进病历随访 Tab / 档案近期预约？
- 4b. EMR 病历与对比照是否本阶段暂缓（等图床与 EMR 服务合规接口就绪）？

### 决策 5｜CCustomer360.vue 组件处置
详情页重写后，旧组件 `CCustomer360.vue`（深度耦合 6 个 mock store）已**无任何页面引用**。
- 选项：保留（订单/病历 tab 接入时可参考其布局）/ 删除（避免 mock 误用）。
> 我的建议：暂保留到订单 Tab 接入完成，确认无复用价值后再删（删组件是破坏性操作，等你确认）。

---

## 三、建议的下一步顺序（待你确认后执行）

1. **接订单/面诊/预约 Tab**（依赖决策 2、4a）：先在 txn-service 补 `按客户列订单` 端点 → 前端订单 Tab 接真实（单 project 结构）；面诊 `consultation/{id}` + 预约 `cross-check/{id}` 接进病历随访/档案。
2. **RFM 价值画像**（依赖决策 3 阈值口径）：拿到评分规则后 customer-service 实时计算，前端雷达图接真实。
3. EMR/对比照（决策 4b）随图床与合规接口另排。

---

## 四、运行方式（留档）
```bash
# 种子库（已灌 100 客户富画像，开发完可 reset 清空）
bash scripts/setup-seed-db.sh                 # 建库灌数（幂等）
bash scripts/reset-seed-db.sh                 # 全部开发完后 DROP 清空

# seed 联调栈（独立项目名，不碰正式栈）
docker compose -f docker-compose.seed.yml up -d customer-service txn-service gateway

# 前端 dev 指向 seed 网关（富数据验证）
cd frontend && VITE_GATEWAY_TARGET=https://localhost:18443 ./node_modules/.bin/vite --port 5174
# 访问 http://127.0.0.1:5174/customers/SC001

# 正式栈（core 库，10 个 M00x 客户）
docker compose -f docker-compose.app.yml up -d   # 前端 8080 / 网关 8443
```

---

## 五、进展更新（2026-09-01 续）：订单 / 面诊 / 预约已按建议接通

在你拍板前，对**纯只读、端点现成或仅需补只读端点、不碰数据模型**的两块先行实施（决策 2a、4a），均已端到端验证：

### 已完成
- **消费订单 tab 接通真实数据**：
  - 后端 txn-service 新增只读端点 `GET /api/txn/customer/{id}/orders`（补 `TxnOrderRepository.findByCustomerIdOrderByCreatedAtDesc`）。
  - **采用真实单 project 结构**（决策 2b 的现实落点）：订单表只有一个项目名 `project`，前端按「单项目 + 金额 + 状态」展示，**未臆造 items 子表**；订单明细多商品的数据模型（是否建 order_item 表）仍列为待决策（见下）。
  - 验证：SC001 返回 22 条订单，金额分→元（¥1,531），状态中文胶囊（待签核/已收款），咨询师工号 SE007→中文名「古医生」。
- **病历随访 tab 接通面诊 + 预约**：
  - 新增 `GET /api/txn/customer/{id}/consultations`（面诊概要，不下发过敏史明细等隐私）、`GET /api/txn/customer/{id}/appointments`（预约）。
  - 档案 tab 同步加「近期预约」小节。
  - 验证：SC009 面诊「偏油，毛孔粗大」咨询师曹咨询、已脱敏、需求中文；预约 3 条（射频紧致/热玛吉/小气泡，来源 C端App/B端登记，医生严医生，状态胶囊）。
- **后端新增**：`TxnStaffNameResolver`（同库只读解析 staff 名，模式与 customer-service 一致）、`CustomerViewController`（三个只读 View DTO，敏感签核/豁免/过敏字段不下发）。
- **前端新增**：`src/api/customerView.ts`（订单/面诊/预约读模型类型，金额标注单位分）；详情页订单/病历随访 tab 改真实渲染，txn 域用 `Promise.allSettled` 容错（txn-service 未就绪时对应 tab 显空态，不拖垮客户域）。
- **对比照/面诊报告 tab 仍占位**（图床未接入）；**EMR 电子病历/随访**仍占位（合规接口未就绪）。
- 验证：vue-tsc 0 error、vite build 成功、txn-service fat jar 47MB、seed txn-service healthy、浏览器截图 2 张（订单 / 病历随访）。

### 待你拍板（收敛后剩余 4 项）
- **决策 1（架构备案）**：customer-service / txn-service 均同库只读 staff 表取中文名（联调权宜）。长期拆库时改走 org-service 调用——建议联调期维持，拆库里程碑统一改。**仅需你知悉/认可此权宜**。
- **决策 2b（订单明细数据模型）**：真实订单只有单 `project`，无多商品子项。若业务需要「一单多项目/分项金额」，需建 `order_item` 子表并改开单链路（影响 M4 开单）；若医美订单以「单方案单项目」为主，维持现状即可。**需你确认业务上单笔订单是否会含多个收费项目**。
- **决策 3（RFM 评分口径）**：客户域后端零 RFM 字段，种子已有 totalSpend/visitCount/最近消费时间等原料。**需你/运营给 R/F/M/忠诚/活跃的打分阈值**（如最近消费天数区间、到店次数区间、累计金额区间）与生命周期阶段流转规则；计算位置我建议「读时实时计算起步，不新建表」。拿到阈值即可接通价值画像雷达图。
- **决策 4b / 5**：EMR 病历与对比照（图床 + 双签 RBAC）本阶段暂缓是否认可；旧组件 `CCustomer360.vue` 已无引用，删除还是保留待你确认。

### 建议下一步
拿到**决策 3 的 RFM 阈值**后，我即接通价值画像五维雷达 + 生命周期（最后一块客户域富画像）；决策 2b 视你对订单子项的业务确认决定是否建 order_item。其余跨域（财务/营销等）按业务优先级另排。

---

## 六、进展更新（2026-09-01 晨）：五项决策全部拍板并落地、端到端验证通过

你对上文 5 项待决策逐条拍板，全部实施完成：

### 决策落地
1. **RFM 计算规则（决策 3）——已调研竞品 + 定自有阈值 + 接通价值画像**
   - 调研医美/零售/会员 CRM 惯例：**活跃 90 天 / 沉睡 180 天 / 流失 365 天；年消费 ≥4 次为高频；五分位打分（5 最优）；R/F/M 二分八象限中文分层**。
   - 结合 seed 库 100 客户真实分位数标定阈值（近 365 天 F 中位 3/p75 6、M 中位 ¥4160/p75 ¥8448、R 中位 112 天），产出 **`docs/RFM-RULES.md`**（五维 = R/F/M + 忠诚 L + 活跃 A，含八象限运营策略、技术实现约定）。
   - 后端 txn-service 新增 `RfmCalculator`（读时实时计算、不建表）+ 端点 `GET /api/txn/customer/{id}/rfm`；前端价值画像 tab 由占位改为真实渲染（分层横幅 + R/F/M 评分条 + 关键指标）。
   - **关键修复**：八象限初版用「F/M ≤2 判低」漏判了 3 分中间态（70% 客户全落「一般挽留」），改为标准 RFM 二分（≥4 为高、否则低，2³=8 全覆盖）。实测分布合理：一般挽留 50 / 重要价值 18 / 一般发展 15 / 重要保持 6 / 一般价值 5 / 重要发展 2 / 重要挽留 1。
2. **订单子项模型（决策 2b）——医美一单多项目，已落地**
   - 新增 `order_item` 表 + JPA 实体 `OrderItem`/`OrderItemRepository`（item_id 自增、order_no 逻辑外键、line_no/item_name/qty/unit_price/amount，金额单位分）。
   - 种子生成器改为每单 1–3 个收费子项（黑卡/钻石更易多项目联单），订单总额 = 子项小计之和；seed 库 **599 订单 / 1108 子项**。
   - 订单视图批量带子项（`findByOrderNoIn` 防 N+1），前端订单卡显示「皮秒祛斑等 2 项」并展开子项明细（项目 × 数量 × 小计）。金额自洽校验 **0 不一致**。
3. **staff/store 名解析服务化（决策 1）——一劳永逸，已改服务间调用**
   - org-service 新增批量员工名解析、store-service 新增批量门店名解析端点；customer-service `RefNameResolver`、txn-service `TxnStaffNameResolver` **方法签名不变，内部从 JdbcTemplate 同库直读改为 RestTemplate 服务调用**（调用方零改动，拆库后无需再动），try/catch 降级。
   - 顺带修复既有隐患：txn 容器此前无 `AUDIT_SERVICE_URL`，审计追加一直静默降级。compose 锚点统一注入 ORG/STORE/AUDIT 服务名寻址 URL；RestTemplate 加 3s/5s 超时。
   - 验证：客户列表门店名/归属咨询师**全中文非空、0 缺失**（customer→org/store 服务间真正调通）。
4. **EMR/对比照（决策 4b）——本阶段暂缓**，已在价值画像/对比照 tab 保留显式中文占位，不造假。
5. **旧组件（决策 5）——二次确认零引用后删除**：`CCustomer360.vue`、`customer360.ts` 经全站 grep 确认无 import（`openCustomer360` 仅路由跳转），移入 `.trash-0901/` 备份（可回溯），全仓零残留。

### 顺带修复的基础设施问题
- **PG 连接池打满**：seed 栈与正式栈共用同一 PG 实例（max_connections=100），14 个后端服务 × HikariCP 默认池 10 ≈ 140 连接，启动时 `too many clients already`。两个 compose 公共 env 加 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5 / MINIMUM_IDLE=2`（14×5=70），连接数回落 **57/100**。
- **order_item 新表**：setup-seed-db.sh 在灌种子前幂等 `CREATE TABLE IF NOT EXISTS order_item`（克隆源库尚无此表），不依赖服务启动顺序。
- 构建坑（记录）：zsh 中 `$svc:latest` 的 `:l` 被当成小写修饰符，导致镜像标签误打成 `<svc>atest`；镜像 tag 参数需加引号。

### 端到端验证（seed 栈 18443 网关，全 healthy）
- RFM：SC001（方梦洁·黑卡）= **重要价值客户**，R 4/5（50 天）、F 5/5（17 单）、M 5/5（¥59,514），活跃·高忠诚；未成交客户 SC073 评分 null、`transacted:false`、分层「未成交客户」（边界正确）。
- 订单子项：SC001 22 单 / 18 单多子项，样例「皮秒祛斑 ¥2,178 + 玻尿酸填充 ¥2,550 = ¥4,728」金额一致，咨询师「古医生」中文。
- 客户列表：门店名（北京国贸店等）、归属咨询师（曹咨询/沈咨询等）全中文、0 缺失。
- 前端 vue-tsc 0 error；浏览器快照确认价值画像评分条/胶囊、订单「等 N 项」+ 子项明细行真实渲染，全中文零技术码。

### 现状与说明
- seed 库 `meiyun_seed` 保留供联调；**全部开发完后跑 `bash scripts/reset-seed-db.sh` 清空**。
- RFM 忠诚度（L）当前按「累计成交单量」判定，会员存续月数（客户域）暂留空，后续如需可让 customer-service 提供建档时间再并入。
- 阈值基于 seed 100 客户标定，正式上线后建议用真实生产数据分位数复核一次（调参改 `RfmCalculator` 常量 + RFM-RULES.md 版本号）。

### ⚠️ 重要：多栈隔离漏洞修复（网络第四要素）
- **现象**：正式栈（8443，连 core 库 10 个 M00x 客户）一度返回 seed 库的 100 个 SC 客户。
- **根因**：seed 栈与正式栈**共用同一 Docker 网络 `10-_meiyun`**。compose 会在网络上为每个容器注册「服务名别名」（`customer-service`/`txn-service`…），两栈服务名相同 → 共享网络上同名别名冲突，正式栈网关 `http://customer-service:8082` 被 **DNS 轮询路由到 seed 容器**。此前隔离只做了项目名/容器名/端口三要素，**漏了网络**。
- **修复**：seed compose 改用独占自建网络 `meiyun-seed_seed-net`（`networks: seed-net: driver: bridge`），seed 网关/服务 env 用 `meiyun-seed-*` 容器名寻址；中间件容器（pg/redis/es/minio/rocketmq）经新增脚本 `scripts/seed-network-connect.sh` 幂等接入（`compose down` 会删自建网络，每次 seed up 后需重跑）。已补进 meiyun-dev-rules 铁律「多栈隔离四要素」。
- **验证**：8443 现正确返回 core 的 M00x 客户（M002 刘女士…），18443 返回 seed 的 SC 客户；两栈 8 后端全 healthy；seed 独立网络下 RFM/订单子项/名解析均正常；空 order_item 的 core 库订单返回 `items:[]` 边界安全；两栈满负荷 PG 连接 41/100。


