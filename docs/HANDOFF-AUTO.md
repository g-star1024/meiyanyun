# 美研云门店中台 · 自动开发接力哨兵（HANDOFF-AUTO）

> 用途：铁律 11 自主续跑的唯一事实源。任何接续会话/定时任务先读本文件，再按「开发前 checklist」执行。
> 维护规则：每批开工改 ACTIVE+心跳；批末（或中断前）改 DORMANT+存档。心跳格式 `YYYY-MM-DD HH:mm CST`。

<!-- MACHINE:STATUS=ACTIVE -->
<!-- MACHINE:HEARTBEAT=2026-09-14 11:19 CST -->
<!-- MACHINE:BATCH=P5-B49 -->
<!-- MACHINE:CARD=B49 卡1 M1 集团屏侦察（队列：B49 M1 集团屏 14 页批；用户 09-14 白天拍板「先 A 技术债批 → 再 C M1」，A 批 B48 已闭合） -->

## 当前状态（人读区）

- **批次**：P5-B49（M1 集团管控 14 页批，C 方向）
- **阶段**：卡1 侦察开工——盘点 14 页现状（真实/ mock 清单）+ nav/router 视图清单，再定分组闭合序列
- **上一批**：P5-B48 技术债纯还债批四卡全部闭合入库（见文末「P5-B48 闭合存档」）

## 批概览（B49）

- 入口台账：02-modules L131「| M1 集团其余 14 页 | ⬜ | — | — | 远期 M1 |」
- 已知真实 3 页：brand / procurement / marketing（B33-B47 历代已闭合）；其余 11 页 mock/占位待侦察确权
- 集团多店对比后端聚合：走**跨店例外域（铁律 -1-D）**，禁止在单店域内泄漏跨店查询
- 完成度影响：B49 起每闭合一页/一组，94/166 完成度递增（B48 纯还债批数字不变系正常）

## 开工基线

- 代码 HEAD=`14cdd8f`（B48 卡4 已推送，工作区干净 = origin/main）
- docs HEAD=`c0ae715`（B48 批末落账已推送）
- 后端 19 服务 + 网关全部在线（`bash /tmp/meiyun-health.sh` 复核）；前端 dev http://localhost:8080
- 登录：curl 通道 POST `https://127.0.0.1:8443/api/org/auth/login`（curl -k，E011/meiyun123=REGION_MGR，token 存 /tmp/meiyun_token.txt，验证前重新登录）；Chrome 通道 http://localhost:8080 手工填表（快捷登录已关闭）

## 下一步动作（B49 序列）

1. ⬜ 卡1 侦察：02-modules L131 + nav/router 视图清单全读，产出 14 页「真实/mock/缺口」三清单（写 DELIVERY-P5-B49 侦察章）
2. ⬜ 卡2 起：按侦察分组逐页闭合——集团多店对比聚合接口走铁律 -1-D 跨店例外域，前端逐页接真实 API 去 mock
3. ⬜ 每卡：铁律 4 三轨真验（curl+Chrome+构建）→ 铁律 6 构建 → 铁律 8 一卡一 feat commit+push
4. ⬜ 批中：02-modules L131 逐页翻 ✅、01-dashboard 数字递增、03-timeline 逐卡加行
5. ⬜ 批末：DELIVERY-P5-B49 + 五分册回写 + docs 原子提交 + 铁律 9 汇报 + 哨兵改 B50

## 中断恢复指引

- 心跳 <15 分钟：另一实例活跃，**只读不接管**，直接退出
- 心跳 ≥15 分钟且 STATUS=ACTIVE：前实例中断，从「下一步动作」第一个 ⬜ 续跑
- 429/模型上限：刷新本文件心跳后退出，等每小时定时任务重试
- 每卡开工前必须重读本文件 + 铁律 10 全读五分册

## P5-B48 闭合存档（2026-09-14）

- 卡1 审计写失败补偿/对账监测 feat `4bfb658`（14 文件：outbox+中继+对账监测三道防线，KB-DOC-7 幻影审计观察项复核登记，历史断链 id=380 append-only 不可篡改存量保留）
- 卡2 ES reindex 对账治理 feat `9c1fd6a`（4 文件：文档级 diff 三清单+孤儿文档清理+6h 定时巡检，M001–M003 漂移归零）
- 卡3 marketing ddl-auto update→validate feat `188c90b`（1 文件：18 表零 drift，现网+scratch 全新库双场景一次通过）
- 卡4 推理模型 max_tokens 遵从度+前端日期统一锚定 Asia/Shanghai feat `14cdd8f`（40 文件 +146/-70：finish_reason=length WARN/空回答异常双分支，lenient ping 实证 128>8 仍 SUCCESS；时区实际 56 处 slice，登记 21 处系低估本批更正）
- 纯还债批：完成度 **94/166=约57%、🔧2、⬜70 均不变**；勾销 04-backlog 四行（L121 审计静默监测、L76 尾 ES 对账、L97 ddl-auto validate、L117 中段时区推广）
- 交付文档：DELIVERY-P5-B48-2026-09-14.md

## P5-B47 闭合存档（2026-09-14）

- 卡8 AI 知识库检索闭环 feat `b26d52e`（检索事件处置台+zhparser/pg_trgm 分词+附件版本审批流）
- 完成度 94/166=约57%、🔧2、⬜70
- 交付文档：DELIVERY-P5-B47-8-2026-09-14.md
