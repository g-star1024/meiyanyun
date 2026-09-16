# 美研云门店中台 · 自动开发接力哨兵（HANDOFF-AUTO）

> 用途：铁律 11 自主续跑的唯一事实源。任何接续会话/定时任务先读本文件，再按「开发前 checklist」执行。
> 维护规则：每批开工改 ACTIVE+心跳；批末（或中断前）改 DORMANT+存档。心跳格式 `YYYY-MM-DD HH:mm CST`。

<!-- MACHINE:STATUS=ACTIVE -->
<!-- MACHINE:HEARTBEAT=2026-09-16 12:13 CST -->
<!-- MACHINE:BATCH=P5-B54 排班真实化大批（6 卡，B=L144 医生/房间班次表）；其后 P5-B55 impersonate → P5-B56 报告哈希验真 -->
<!-- MACHINE:CARD=卡1 编码+编译+双栈换载+双栈三轨真验全绿（core 17 项/seed 12 项），待 commit+push；StaffShift/StaffShiftRepository/ScheduleController 三新文件未跟踪 -->
<!-- MACHINE:PREV=P5-B53 全闭合（style fb16b53+docs 50103cc 已 push，L141 A3 保留登记不勾销，数字不变） -->

## 当前状态（人读区）

- **批次**：P5-B54 排班真实化大批（B=L144，**2026-09-16 11:43 开工，6 卡**）——用户 B 方向拍板：建实体+维护界面+后端接线、全员排班、周例（维度照线上截图 6 员工×7 天网格，早/中/全改名 上午/下午/全天，加 休息/请假 共 5 态）、前端时间格以后端为准。**卡0 只读侦察已完成**（11:43）：org-service 无任何既有排班实现（仅 PermissionMatrix schedule:view/edit/approve 三码已预置——view 八角色全有、edit/approve 仅 REGION_MGR/STORE_MGR，无需新播种）；业务表一律 JPA ddl-auto=update（org 无 Flyway、V1 仅 sys_ 表）；实体审计字段惯例仅 createdAt 一个、操作人/动作走 AuditRecorder；范式=Controller 直注 Repository、无 Service、裸 Map 出参、中文 ResponseStatusException、DataScope 门店越权 404、internal 走 X-Internal-Token（internal:name-map，网关外 404）。**卡1 已落地待提交**：com.meiyun.org 新建 StaffShift（staff_id+shift_date 唯一、shift_code 5 态、source TEMPLATE/OVERRIDE、createdAt）+ StaffShiftRepository + ScheduleController（GET 周视图/GET 字典/PUT 改班/POST 周例铺底），编译 0 错误、双栈双库建表、core 17 项+seed 12 项三轨真验全绿。完成度数字本批随模块落地而动，L144 全行勾销留待真源贯通（卡1 仅后端表+端点，不动数字）
- **用户拍板（B54 B 方向原文照录）**：「B. L144 医生/房间班次表（资源真实可用时间）1、建实体+维护界面+后端接线；2、全员排班；3、周例，排班维度你看看我给的线上截图，按这种维度就行；早中全，改为上午、下午、全天、休息、请假；4、前端时间格以后端为准；其他的问题你可以先做竞品调研，然后根据调研结果，推荐一个最合适我们的」；同批另三方向：A=L141 选 A3（不提前造字段+诚实空态，已在 B53 闭合）、C=L133 真实 impersonate（P5-B55，超管→店长→员工、总部跨店门店不可跨店）、D=L123 报告哈希验真 UI（P5-B56，按推荐口径）；「剩下的我没有决定的你按照推荐来就行」
- **阶段（P5-B54）**：卡0 只读侦察完成（11:43）；**卡1 编码+编译+双栈换载+双栈三轨真验全绿（12:13），待 commit+push**——三新文件 StaffShift.java / StaffShiftRepository.java / ScheduleController.java 未跟踪。编译：`mvn -pl org-service package` 二次通过（首编译 Specification<Object> 泛型退化，抽 activeStaffInScope()+显式 witness `DataScope.<Staff>storeSpec` 修复），fat jar 47,180,905 字节；双栈 docker cp /app/app.jar+restart 均 healthy；staff_shift 由 ddl-auto 在 meiyun_core/meiyun_seed 双库建成（id IDENTITY PK、uk_staff_shift_date UNIQUE(staff_id,shift_date) 已 \d 核对）。**core 栈 17 项真验全绿**（经 8443）：字典五态中文+assignable；周视图数据域（店长本店 8 人/区域 stores 6 店在编 10 人/财务空 stores 全量 19 人）；PUT 新建 OVERRIDE+同码幂等不重复审计+改码；非法码/非法日期双 400；同区他店/跨大区 404；财务/医生写 403；无 token 401；周例铺底 created=55/skipped=1、再铺 0/56；财务铺底 403；铺底结构周一至六 FULL/TEMPLATE、周日 OFF/TEMPLATE、手工 MID/OVERRIDE 不被覆盖；PG 计数 47/1/8；重复插 23505；audit_log 598-601 四条 SCHEDULE（SET×2 幂等重复未记、GENERATE_WEEK×2，actor E005，合法 JSON 含中文 staffName）。**seed 栈 12 项抽验全绿**（经 18443，SE001 许店长 SST01/E012 财务/E001 医生）：字典+空态 4 人+PUT 手工 SE003 周二 MID+铺底 27/1+再铺 0/28+财务/医生写双 403+财务铺底 403+无 token 401+非法码 400+越权改 SST02 SE005 404+财务读全量 43 人；PG seed 23 FULL/TEMPLATE+1 MID/OVERRIDE+4 OFF/TEMPLATE；audit_log 246-248（actor SE001 江医生中文）。**数据处置**：core.staff_shift 真验 56 行已 DELETE 清空恢复纯净（审计 append-only 保留 598-601 留痕）；seed.staff_shift 28 行保留作卡3 前端联调样例（本周 2026-09-14~20，SST01 4 人，SE003 周二手工 MID）
- **本批 6 卡范围**：卡1 org staff_shift 实体+Repo+浏览器周视图/改班/周例端点（**本卡，待提交**）→卡2 org internal schedule/resolve（X-Internal-Token、网关外 404、软降级）+txn DispatchService 三处固定窗（WORK_START/END 09:00/20:00、resources DOCTOR、派单/改期 422）改排班真源→卡3 M2 /m2-schedule 接真（frontend/src/api/schedule.ts 走 api/client.ts baseURL '/api' 勿用 request.ts、stores/schedule.ts 换真源、SHIFTS 早/中/全→上午/下午/全天、ScheduleView.vue 模板样式零改、seed 对齐）→卡4 M1 m1Dispatch SLOTS/时间轴硬编码以后端排班动态化→卡5 周例复制上周+请假批准写 OVERRIDE/LEAVE 联动（评估 leave_id）→卡6 批末落账（DELIVERY-P5-B54+台账六册 L144 勾销+数字重算+哨兵 DONE，原子 docs 提交 push）。docker CLI 在 `/Applications/Docker.app/Contents/Resources/bin/docker`（本会话 PATH 无系统命令须绝对路径）
- **保留登记（不在 B51 范围，随后续批次评估）**：L133 impersonate＝大（JWT act/realSub claim+meiyun-security 全服务回归+前端换 token，与 L46 合并）、L123 报告哈希验真 UI＝中（content_hash+规范化字节口径，DSAR/consent 远期）。非本批：L89 setup-seed-db 保留 audit_log、L128 ai-service seed Flyway V17-V29 悬置。
- **记账口径**：纵深缺口批以「缺口闭合即勾 04-backlog 行」为记账单位——完成度数字（✅108/166≈65%、⬜56、🔧1、域⑧ 34✅2🔧31⬜）仅当缺口对应功能真实落地才动；侦察无源的行保持登记不动、诚实说明。
- **上一批**：P5-B50 平台治理/还债批九卡全闭合（B49 四项观察项清零，见文末存档）

## 开工基线

- 代码 HEAD=`50103cc`（P5-B53 批末落账，已 push origin/main）；工作区=B54 卡1 三新文件未跟踪（backend/org-service/src/main/java/com/meiyun/org/ 下 StaffShift.java、StaffShiftRepository.java、ScheduleController.java）+本哨兵修改；target/org-service-1.0.0-SNAPSHOT.jar 已构建（fat 47,180,905 字节）
- 双栈 org-service 均已换载本卡 jar（docker cp /app/app.jar+restart，12:0x 起双 healthy）；后端 19 服务+网关在线；正式前端 http://localhost:8080 / 网关 https://127.0.0.1:8443；seed 前端 127.0.0.1:18080 / 网关 https://127.0.0.1:18443
- 登录：POST `/api/org/auth/login` body {loginName,password=meiyun123}，curl -k；core 19 名在职（E001-E014+SE101-105；E005 李店长 ST-SH-001/E011 冯区域 stores 6 店/E012 褚财务 REGION 空 stores 全量/E009 吴店长 ST-BJ-001 华北/E001 刘治疗师 STORE）；seed 43 名在职（E001-E014 无门店+SE001 起 SST01/02/03；SE001 许店长 SST01 4 人在编）。token 存 /tmp/tok_E*.txt、/tmp/seed_tok_*.json（permissions 字段名）
- 数据库：docker exec meiyun-pg psql -U meiyun -d meiyun_core|meiyun_seed；staff_shift 双库已建；core 已清空 0 行（audit_log 598-601 留痕保留）、seed 28 行联调样例（本周 2026-09-14~20）
- 铁律 10 开工读数：索引+00～04 五分册本批开工前已全部整读
- docs 提交坑：`docs/` 被 .gitignore 整体忽略；已跟踪文档用 `git add -u docs/`，仅新增 docs 文件用 `git add -f`；Grep 工具对 docs 超长中文行失效，用 shell `grep -rn` 或 Read

## 下一步动作（B54 卡序列）

0. ✅ 卡0 只读侦察（11:43）：org-service 无既有排班实现；PermissionMatrix schedule:view/edit/approve 三码已预置（view 八角色、edit/approve 仅 REGION_MGR/STORE_MGR/SUPER_ADMIN）；业务表 JPA ddl-auto=update（org 无 Flyway）；实体审计惯例仅 createdAt+AuditRecorder；Controller 直注 Repository 无 Service、裸 Map、中文 ResponseStatusException、DataScope 越权 404、internal 走 X-Internal-Token
1. ⏳ 卡1 后端端点（**编码/编译/双栈换载/双栈三轨真验全绿，仅剩 commit+push**）：StaffShift（id IDENTITY、staff_id(16)+shift_date date 唯一键 uk_staff_shift_date、shift_code(8)/source(8)、created_at timestamptz）+StaffShiftRepository（区间按日期工号排序、单日 findById 锚点）+ScheduleController 四端点：GET /api/org/schedule?weekStart=（默认本周、任意日期归一化到周一；返回 weekStart/weekEnd/staff[]/shifts[]，DataScope 数据域）、GET /api/org/shift-codes（MORNING 上午/MID 下午/FULL 全天 assignable=true、OFF 休息/LEAVE 请假 assignable=false）、PUT /api/org/schedule/shift（schedule:edit；落 OVERRIDE；同码幂等不重复审计；离职/越权 404；非法码/日期 400；SET 审计 bizType=SCHEDULE）、POST /api/org/schedule/generate-week（周一至六 FULL/周日 OFF TEMPLATE、已存在一律跳过不覆盖手工行、GENERATE_WEEK 一条审计）。**编译坑留证**：`DataScope.storeSpec("storeCode").and(lambda)` 链式推断退化为 Specification<Object>，须显式 witness `DataScope.<Staff>storeSpec(...)`（抽私有静态 activeStaffInScope()）
2. ⬜ 卡2 org internal resolve 端点（/api/org/internal/schedule/resolve，X-Internal-Token=internal: 权限、网关外 404）+txn-service 新增 org RestTemplate client（软降级回固定窗，铁律 6）+DispatchService 三处固定窗（WORK_START/END 09:00/20:00、resources DOCTOR 过滤、派单/改期越窗 422）改 staff_shift 真源（FULL=全天可派/MORNING+MID 时段语义/休息日与请假不可派）
3. ⬜ 卡3 M2 /m2-schedule 接真：新建 frontend/src/api/schedule.ts（仿 api/client.ts baseURL '/api'，勿用 request.ts）、stores/schedule.ts 换真源、SHIFTS label 早/中/全→上午/下午/全天+休息/请假、ScheduleView.vue 模板与样式零改、seed 数据对齐
4. ⬜ 卡4 M1 m1Dispatch SLOTS/时间轴硬编码以后端排班动态化
5. ⬜ 卡5 周例复制上周+请假批准写 OVERRIDE/LEAVE 联动（评估 leave_id 锚点）
6. ⬜ 卡6 批末落账：DELIVERY-P5-B54+台账六册 L144 全行勾销+完成度数字重算+哨兵置 DONE，原子 docs 提交 push

## 中断恢复指引

- 心跳 <15 分钟：另一实例活跃，**只读不接管**，直接退出
- 心跳 ≥15 分钟且 STATUS=ACTIVE：前实例中断，从「下一步动作」第一个 ⬜ 续跑
- 429/模型上限：刷新本文件心跳后退出，等每小时定时任务重试
- 每卡开工前必须重读本文件 + 铁律 10 全读五分册

## P5-B53 闭合存档（2026-09-16）

- **主题**：用户 A/B/C/D 四方向拍板（A=L141 URGENT 加急源、B=L144 班次表、C=L133 合并 L46 impersonate、D=L123 报告哈希验真）后的首个最小卡；A 选 A3「不提前造字段、继续诚实空态、顺手清死 CSS」，卡1 单删一行死码 + 卡2 批末落账，共 1 style commit（`fb16b53`）+1 docs 原子提交，均 push
- **完成度**：纯死码清理数字一律不变——**✅108/166=约65%、🔧1、⬜56 约34%；域⑧ 34✅ 2🔧 31⬜**（同 B44/B45/B50/B51/B52 先例：无新页面、无模块状态跃迁、无 🔧 成因消解）；新增完成模块 **0 个**，勾销 04-backlog **0 行**（L141 按 A3 保留登记仅尾接注记，不打勾不删除线）
- 卡1 删死 CSS `fb16b53`（style，1 file changed 1 deletion）：`frontend/src/views/M1DispatchView.vue` 删除 `.job--urgent { border-left: 3px solid var(--c-danger-fg); }` 孤儿规则一行（B49 卡12 去 mock 已删 urgentJobs 死脚本后的遗留；模板零 job--urgent 绑定）。删除前全站只读核实：`urgent` 共 52 命中，其余 51 处全属 T3 工单/通知/交接/日结待办等独立功能，**均未触碰**；相邻真实在用规则 `.job--active` 保留。模板/脚本/后端/契约/schema/文案零改动
- **三轨真验全绿**：①构建 `pnpm build` exit0（20.24s，0 error），新产物 M1DispatchView-B-BnlAuT.css/-DfV4LoYr.js；②产物 grep：新 dist 内 job--urgent 计数 0、job--active 仍在（精准单删未误伤）；③双栈部署+HTTP：docker cp 叠加残留旧 hash 产物（Cyk7km7L.css）→两容器先 `rm -rf /usr/share/nginx/html/*` 再干净 cp，复测各 2 个新 hash 产物、全 assets grep 零命中，8080/18080 均 200；提交 `17251b7..fb16b53 main -> main`
- 卡2 批末落账（docs）：DELIVERY-P5-B53-2026-09-16.md（7 章）+五分册（00 顶部新简报+B52 降级「上一批 B52」/01 L18 口径流水+L36 域⑧行双尾接/02 M1 调度行尾接/03 表底 P5-B53 六列行/04 L141 尾接 A3 注记**保留登记不勾销**），数字五处勾稽一致
- **保留登记（后续批次主线，均已锁方案）**：B=L144 医生/房间班次表→**P5-B54 排班真实化大批 6 卡**（org `staff_shift` 单表 ddl-auto/meiyun_core：staff_id+shift_date 唯一键、source=TEMPLATE/OVERRIDE、5 态 MORNING 上午/MID 下午/FULL 全天/OFF 休息/LEAVE 请假，全员排班+周例生成+请假 OVERRIDE；浏览器 GET/PUT/POST generate-week + internal resolve 软降级；txn DispatchService 派单/改期三处固定窗改真源+422；M2 /m2-schedule 接真模板样式零改；M1 SLOTS/时间轴以后端为准）；C=L133 合并 L46→**P5-B55**（真实 impersonate：JWT act/realSub 双 claim+meiyun-security 全服务回归、审计 realSub·act、短时效切换 token+版本号回收、前端换 token+强制横幅+一键退出；授权矩阵 超管→店长→员工、总部员工可跨店、门店员工不可跨店 DataScope 403）；D=L123→**P5-B56**（报告哈希验真 UI：先冻结规范化字节 UTF-8 BOM+CRLF/文件名去下载时刻→finance report_job 加 content_hash SHA-256 落库原始字节→验真端点 report:verify+前端验真卡；注意现有 finance/ai 两套体系）
- **下一批 P5-B54**：开工前重读六册+铁律、哨兵置 ACTIVE，卡1 从 org-service staff_shift 实体+JPA 建表+读写端点起步，一卡一 commit 紧跟 push；无用户新指令前不擅自提前开工

## P5-B52 闭合存档（2026-09-16）

- **主题**：seed 栈别名层纵深加固（环境治理）+M1 派单自由时段开放（既有模块纵深）两卡施工+卡0 侦察+卡3 批末落账，共 1 fix+1 feat+1 docs 三 commit 全 push；用户 2026-09-15 晚三项拍板（①DNS 串扰走方案②别名加 seed- 前缀 ②自由时段开放 ③开工 P5-B52）
- **完成度**：纯纵深+环境治理批数字一律不变——**✅108/166=约65%、🔧1、⬜56 约34%；域⑧ 34✅ 2🔧 31⬜**（同 B44/B45/B50/B51 先例：均既有 ✅ 模块纵深挂载/环境治理，无新页面、无模块状态跃迁、无 🔧 成因消解）；本批新增完成模块 **0 个**，勾销 04-backlog **2 行**（L146 自由时段、L147 seed 别名串扰）
- 卡1 seed 栈多栈隔离纵深 `0ea8983`（fix，纯环境治理不挂业务模块行）：docker-compose.seed.yml 十服务键全加 seed- 前缀（八后端+seed-gateway+seed-frontend，container_name/env URL/端口/独立网络/锚点不动），裸名 gateway 在 seed 网内 NXDOMAIN，与既有独立网络（seed-net vs 默认网零跨网附着，卡0 实证）成第二道纵深；前端镜像双栈共享，nginx.conf 4 处 proxy_pass 改 envsubst `${GATEWAY_UPSTREAM}` 模板+Dockerfile COPY templates，app/seed 分别注入 gateway/meiyun-seed-gateway（$host/$remote_addr 非 env 白名单原样保留）；连带修 seed marketing Exited(1) 31h：Flyway out-of-order:true 补 V11-14 乱序 pending（同 B38 修法）+新增 V32 `ALTER ... ADD COLUMN IF NOT EXISTS` 补 writeoff_fallback_store_code schema drift、对正式库幂等 no-op。三轨：10 容器 Up、双向 DNS 隔离、双栈 nginx 上游分流、8080/18080 全 200、V11-14+V32 齐、core 库重放安全。**如实标注**：施工时双网运行态已隔离零跨网附着，系别名层纵深加固（防误接第二道防线）非现网抢修
- 卡2 M1 派单自由时段开放 `cc00948`（feat，5 files +46/-25，挂 02-modules M1 调度中心行纵深）：DispatchCmd record 增可选第四参 start（String HH:mm），Service 正则 `^([01]\d|2[0-3]):[0-5]\d$` 非法→422「派单时段格式应为 HH:mm（如 09:30）」、null/空白（空串/纯空格/不传三态）回落 apptTime、越固定班次窗 09:00-20:00→422「请选择班次内的空闲时段」、同时段/半重叠→409 全量复用；end 仍走 resolveDurationMin（绑 SKU duration_min 真源/未绑 60min）；前端 slotClick(resourceId, slot) 带格子时段、班次内全空闲格可点（原仅 apptTime 格）、data-slot 锚点、dispatch.ts start?:string、m1Dispatch 请求体透传、style 零改。三轨：curl+PG 五分支（9:60→422、08:00/19:30→422、10:00→200 id14、同 10:00+半重叠 10:30 双 409、相邻 11:00→200 id15、空串/空白/不传三态回落落 id16/17/18）+Playwright Chrome（22 格放开/非预约 14:00 派单/释放复位/治疗室设备 tab/FINANCE 守卫/0 console error）+双栈构建部署
- **接力会话独立复验（07:09-07:13，只读三轨）**：seed 18443 E011 登录→GET resources SST01 2026-09-16→200 12 资源（DOCTOR/ROOM/DEVICE，workStart 09:00/workEnd 20:00）、GET jobs→200 2 个 PENDING（000007 16:00/000008 17:30 与 PG 对账一致）；PG dispatch_assignment id14-18 全 RELEASED 留档（id14=自由 10:00 异于 apptTime 16:00 铁证）；audit_log id234-243 DISPATCH/RELEASE payload 含 start/end、actor=E011、哈希链连续；cc00948 完整 diff 逐行复核
- 卡3 批末落账 `6510627`（docs，7 files +98/-13）：DELIVERY-P5-B52-2026-09-16.md（9 章）+五分册（00 顶部新简报+B51 降级上一批/01 L18 口径流水+L36 域⑧行双尾接/02 M1 调度行尾接【卡1 环境治理明示不挂业务模块行】/03 表底 P5-B52 六列行/04 L146+L147 双勾销末列 ✅ B52），通读勾稽数字五处一致；保留登记不动：L141 URGENT/L144 班次表（拍板④诚实空态）、L133 impersonate 大项、L123 报告哈希验真 UI
- **append-only 留档（不可删）**：seed 库 dispatch_assignment id14-18（全 RELEASED，id14=自由 10:00/apptTime 16:00、id15=相邻 11:00、id16/17/18=三态回落 17:30）、audit_log id234-243（DISPATCH/RELEASE 哈希链 actor=E011）；seed marketing Flyway V11-14+V32 迁移记录
- **中断/接力留档**：前实例完成卡2 代码+双栈部署（05:57 dist/05:58 txn 镜像）后中断于三轨真验前（心跳停 05:12→06:11 停滞 ≥15min）；接力会话 06:11 法医鉴识（git show/diff+docker 现场无活跃构建进程，确认非并发）接管，完成卡2 curl+PG 五分支真验+feat 提交 push（cc00948 06:23）、Chrome 轨引用提交内 Playwright 实证、07:09-07:13 二次独立复验、卡3 批末落账全流程
- **下一批候选方向（待用户拍板）**：①04-backlog 存量纵深（L141 URGENT 加急标记加列、L144 医生/房间班次表真实排班源——均需产品决策，拍板④曾保留诚实空态）；②L133 impersonate 平台安全大项（JWT act/realSub claim+meiyun-security 全服务回归+前端换 token，与 L46 合并）；③L123 报告哈希验真 UI（content_hash+规范化字节口径，DSAR/consent 远期）；④其他用户指定方向。哨兵 STATUS=DONE，下一自主周期空跑，直到用户拍板新批次才置 ACTIVE

## P5-B51 闭合存档（2026-09-16）

- **主题**：M1 调度 Backlog 纵深缺口批（04-backlog L140-145 六缺口，用户 2026-09-15 晚拍板「P5-B51开工吧」=顺序②授权落地），卡1 只读侦察+卡2 四项拍板+卡3–卡6 四卡施工全闭合（4 feat+1 fix 共 5 commit 均 push）
- **完成度**：纯纵深批数字一律不变——**✅108/166=约65%、🔧1、⬜56 约34%；域⑧ 34✅ 2🔧 31⬜**（同 B44/B45/B50 先例：均既有 ✅ 模块纵深挂载，无新页面、无模块状态跃迁、无 🔧 成因消解）
- 卡3 L143 assignment DONE 完成态联动 `79a8ae4`（9 files +150/-25，新建 DispatchCompletion AFTER_COMMIT REQUIRES_NEW 同服务同库联动 ConsultPlanService.treatDone——TREATING→DONE+EM 治疗记录+revision+audit+FollowupScheduler 先例；终态 422、isSlotBusy 排除 DONE 不占时段）+顺手 fix `2714287`（预约号 22P02 既有 bug）；三轨真验 8 项全绿
- 卡4 L140 DEVICE 接真+放开派单写 `86d4a65`（8 files +179/-21，store-service 新建 InternalEquipmentController 复刻 InternalRoomController 范式+txn StoreEquipmentClient 软降级；读侧 resources?type=DEVICE 真实 5 台 NORMAL（校准/维修/停用过滤）、写侧派 EQ-L001 成功 id=8/仅 NORMAL 404/同时段 409；「设备档案无源诚实空态」闭合为读+写全闭环）
- 卡5 L145 预约改期联动 `50a7eb7`（2 files +56/-3，reschedule 同事务 followReschedule——SCHEDULED 派单跟随新时段/重叠 409 预约回滚/越班次 422/audit 双轨 RESCHEDULE+RESCHEDULE_FOLLOW；自由时段派单系产品决策未施工，转 04 新登记行）
- 卡6 L142 appointment 加 sku_code 列 `777d456`（7 files +130/-18，schema 变更+创建选 SKU 403 降级+activeSkuDurationMap 60s 缓存+伪 SKU 400+resolveDurationMin 三消费点统一：绑 SKU 按 duration_min 真源 90 分钟实证/未绑回落 60min，替代原固定 end+60min）
- 批末回写：DELIVERY-P5-B51-2026-09-15.md（13 章）+五分册（00 顶部简报+B50 降级上一批/03 表底 P5-B51 行/04 四行勾销 L140/L142/L143/L145+两行保留登记 L141/L144+两行新增登记/02 调度行尾接+新建预约行挂卡6/01 L18+L36 尾接数字不动），同批原子 docs 提交
- **用户四项拍板（2026-09-15 晚，均已照办）**：①按建议序开工（L143→L140→L145→L142）②同时放开 DEVICE 派单写（读+写全闭环）③appointment 加 sku_code 列（schema 变更+durationMin 从 SKU 真源取）④L141 URGENT/L144 班次表都保留诚实空态（appointment 无 priority/urgent 列、库内无 shift/duty/roster 表，04 保留登记）
- **新增登记两项（待用户拍板）**：①M1 调度派单自由时段（start 不锚定 apptTime，产品决策）；②seed 栈与正式栈同 Docker 网络+同名服务别名致 DNS 轮询串扰（环境治理——seed 库客户号 SC 前缀，查 M002 → 200 空 Map，间歇性空数据而非报错；建议①seed 栈迁移独立 Docker 网络或②seed 容器服务别名加 seed- 前缀）
- **append-only 留档（不可删）**：dispatch id=6/7（卡3 DONE 链）、id=8（卡4 DEVICE+卡5 改期跟随）、预约 000007 改期链（卡5）、AP20260915-000001~000005+dispatch id=1/2+audit CREATE×4（卡6）
- **环境排障留档**：name-map 空 Map 之谜=seed 栈同网络别名 DNS 轮询，txn recreate 恢复
- **下一批 P5-B52 候选方向（待用户拍板）**：①04-backlog 存量纵深（L141 URGENT 加列/L144 班次表/派单自由时段——均需产品决策）；②seed 栈网络隔离环境治理；③L133 impersonate 大项（JWT act/realSub claim+meiyun-security 全服务回归+前端换 token）；④其他用户指定方向

## P5-B50 闭合存档（2026-09-15）

- **主题**：平台治理/纯还债批（用户拍板顺序①），卡1 只读侦察+卡2–卡8 共九卡全闭合（8 个 feat commit+卡8b 零代码）；B49 登记四项观察项全部清零
- **完成度**：纯还债批数字一律不变——**✅108/166=约65%、🔧1、⬜56 约34%；域⑧ 34✅ 2🔧 31⬜**
- 卡2 采购列表越权 `da3d812`（3 files +116/-14，DataScope 基座+越权用例，seed E011 6→4/SST03 消失）；卡3 大屏时区 `cbd6692`（2 files +9/-2，shTimeStr 锚 Asia/Shanghai，05:26Z→13:26）
- 卡4 target 行级 DataScope `3a263ab`（6 files +206/-5，canReadTarget 四角真验 7/5/5/10）；卡5 登录四门控 `7f08103`（4 files +52/-23，auth/storeContext/CShellDesktop/notification，无 token 零请求）
- 卡6 哈希链全量断链清单 `aecf97f`（5 files +201/-8，prod breaks=[380,520] 均 B48 修复前历史窗口、seed []/117、卡8c 后 total=124）；卡7 网关真实客户端 IP `5251306`（7 files +364/-7，XFF→X-Real-IP→remoteAddr→unknown，20 单测，合规链路去硬编码 "web"）
- 卡8a 耗材越权+库存 watch `dd4307a`（5 files +179/-39，两 Repository 删 cast null JPQL，seed 档案 36→18/流水 38→18，InventoryView 切店重拉）；卡8b 零代码（SE101 周岚双库 SUPER_ADMIN/scope=GROUP/stores=[]/perms=* 即合格，不新增 SE106；三口径对账差额恰为越界 SST03 ¥999,000,000）；卡8c actor 按通道强制 `43f009b`（1 file +9/-2，五通道真验，P1 伪造 HACK→SE101）
- 批末回写：DELIVERY-P5-B50-2026-09-15.md（14 章 170 行）+五分册（00 顶部简报/03 表底全行/04 九行勾销 L122/L124/L127/L129/L132/L134/L146/L147/L148/02 十处挂载/01 L18+L36 尾接数字不动），同批原子 docs 提交
- **保留登记不动工**：04 L133 impersonate（大，act/realSub+全服务回归+前端换 token，与 L46 合并）、L123 报告哈希验真 UI（中，content_hash+规范化字节口径；DSAR/consent 远期）
- **append-only 留档（不可删）**：prod meiyun_core.compliance_check 手插 id=1 PRIVACY「L134 平台治理验真临时项」现 FAIL；prod 审计 id588/589（卡7）、id590（卡8c P1 伪造）；seed 审计 id180-182（卡7 curl）、id183（Chrome 冯区域 ip=192.168.65.1）、id184/185/186（B50_8C_TEST T1/T2/T4）
- **下一批 P5-B51**：M1 调度六缺口（04 L140-145，用户已拍板顺序②）——DEVICE 设备档案/URGENT 加急源/durationMin 真实时长（现 SKU↔project 名匹配率 0%）/assignment DONE 完成态/医生房间班次表（现固定 09:00-20:00）/派单时段自由度改期（start 现锚 apptTime）；多依赖跨域新数据源，开工逐卡只读侦察，无源继续诚实空态

## P5-B49 闭合存档（2026-09-15）

- **主题**：M1 集团管控屏接真批（C 方向），卡0 落账纠错+卡1 侦察+卡2–卡12 共 12 卡全闭合；M1 集团屏 **17/17 全切真**（3 真实页+14 已切真页+0 mock，M1 批目标达成）
- **完成度**：批初 94/166=约57% → 批末 **✅108/166=约65%、⬜56、🔧1**；域⑧ 平台与基建 34✅ 2🔧 31⬜
- 卡2 审计日志 `43f52ca`（total=553 三轨对账；orphan 只 warn 不删、两轮恒=100 共享索引拓扑；卡0 已纠 f2181aa 失实）
- 卡3 区域+门店主数据 `494c272`（6 前端文件 +248/-653）；卡4 集团聚合三页 `f672a80`（跨店例外域 -1-D GET /finance/group-overview，8 文件 +407/-154）
- 卡5 采购补全 `a108203`（procurement 四表六态状态机/审批阈值/移动均价，15 文件 +1425/-80）；卡6 SOP `d4c6453`（sop 三表八端点，11 文件 +1283/-10）
- 卡7 数据大屏 `815f006`（screen 包 overview+SSE，修复 SSE 认证串扰+nginx 缓冲双缺陷，13 文件 +848/-79）
- 卡8 目标管理 `1f432a2`（biz_target 五指标×三级×四态+idemKey 幂等，8 文件 +820/-54）；卡9 合规中心 `b7baee5`（compliance 域+V31+RECHECK 八键）
- 卡10 健康度巡检 `177a32e`（health 三表、clamp[40,98] 扣分服务端化，12 文件 +808/-90）；卡11 报表中心 `01c7af1`（report 域 BYTEA CSV 异步、R01/R02 真实聚合，13 文件 +1241/-162）
- 卡12 调度中心 `0b3dfb0`（收官，10 文件 +964/-99 跨 txn/store/org 三服务：dispatch_assignment ddl-auto、四端点 resources/jobs/dispatch/release、改派 422/重叠 409/重复 409、权限预置零改动、前端删 urgentJobs 死代码；三轨真验四角全绿）
- 批末回写：DELIVERY-P5-B49-2026-09-14.md 卡12 设计章+交付章（L461-537）+五分册（history/dashboard/modules/timeline/backlog）docs `05f1593`（6 文件 79+/10-，17/17 收官口径全册统一，历史卡 1/17…13/17 记录保留）
- **Backlog 净增**：卡12 新登记 6 纵深缺口（DEVICE 设备档案域/URGENT 加急源/durationMin 真实时长/DONE 完成态/班次表/派单时段自由度，建议远期 M1）+1 观察项（登录页 CShellDesktop 全局壳无 token 四路 401 console 噪音，既有现象非卡12 引入，建议平台治理批修复=壳按 token 门控预拉取）；另历史卡累计 backlog 见 04-backlog
- **下一批 P5-B50 候选方向（待用户拍板）**：①平台治理批（登录页 401 观察项+卡2 历史断链 #380 全量断链清单增强+各卡登记的平台类小项）；②M1 Backlog 纵深缺口（DEVICE/URGENT/durationMin 等，业务纵深但多数依赖跨域新数据源）；③其他用户指定方向

## P5-B48 闭合存档（2026-09-14）

- 卡1 审计写失败补偿/对账监测 feat `4bfb658`（14 文件：outbox+中继+对账监测三道防线，KB-DOC-7 幻影审计观察项复核登记，历史断链 id=380 append-only 不可篡改存量保留）
- 卡2 ES reindex 对账治理 feat `9c1fd6a`（4 文件：文档级 diff 三清单——missing 逐条 upsert 自动补齐、orphan 仅报告+warn 待人工不自动删（两轮恒=100，SC* seed/app 共享索引拓扑预期）+6h 定时巡检；M001–M003 首跑补齐 es110→113、二跑幂等；原「ES110 vs PG100 漂移 10」系 seed-only 口径误判，权威源 seed∪core=113）
- 卡3 marketing ddl-auto update→validate feat `188c90b`（1 文件：18 表零 drift，现网+scratch 全新库双场景一次通过）
- 卡4 推理模型 max_tokens 遵从度+前端日期统一锚定 Asia/Shanghai feat `14cdd8f`（40 文件 +146/-70：finish_reason=length WARN/空回答异常双分支，lenient ping 实证 128>8 仍 SUCCESS；时区实际 56 处 slice，登记 21 处系低估本批更正）
- 纯还债批：完成度 **94/166=约57%、🔧2、⬜70 均不变**；勾销 04-backlog 四行（L121 审计静默监测、L76 尾 ES 对账、L97 ddl-auto validate、L117 中段时区推广）
- 交付文档：DELIVERY-P5-B48-2026-09-14.md

## P5-B47 闭合存档（2026-09-14）

- 卡8 AI 知识库检索闭环 feat `b26d52e`（检索事件处置台+zhparser/pg_trgm 分词+附件版本审批流）
- 完成度 94/166=约57%、🔧2、⬜70
- 交付文档：DELIVERY-P5-B47-8-2026-09-14.md
