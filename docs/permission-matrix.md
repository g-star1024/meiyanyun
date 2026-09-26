# 美研云 · 角色-权限矩阵（基线草稿 v1）

> 状态：**草稿 / 待评审**。产品架构基线第三份宪法文件。
> 目的：定义"谁（角色）能对什么（资源）做什么（操作），以及数据能看到多大范围（scope）"。
> 这是当前最大缺口之一（报告 §1.3：无 beforeEach 守卫、App.vue 菜单硬编码、M106 仅视觉页）。
> 配套：`domain-model.md`、`business-flows.md`。前端运行时方案见 §6。

---

## 1. 权限模型（RBAC + 数据域）

- **角色（Role）**：角色是可授予员工的权限集合，一人可多角色（权限取并集）。
- **资源（Resource）**：聚合根或功能模块（见 §4 列表）。
- **操作（Action）**：`view / create / edit / delete / export / approve / sign`。
- **权限码（Permission Code）**：`resource:action`，如 `customer:view`、`cashier:approve`。
- **数据域（DataScope）**：`SELF`（仅本人客户）/ `STORE`（本门店）/ `BRAND`（本品牌旗下门店）/ `REGION`（本区域）/ `GROUP`（全集团）。多角色时取最大范围。

---

## 2. 角色定义（8 角色）

| 角色 key | 名称 | 典型岗位 | 数据域默认 |
|----------|------|---------|-----------|
| `SUPER_ADMIN` | 集团管理员 | 总部运营/老板 | GROUP |
| `REGION_MGR` | 区域经理 | 大区负责人 | REGION |
| `STORE_MGR` | 门店店长 | 单店负责人 | STORE |
| `CONSULTANT` | 咨询顾问 | 咨询师 | SELF（客户归属） |
| `DOCTOR` | 医生 | 执业医师 | STORE |
| `FRONT_DESK` | 前台 / 收银 | 导医/收银员 | STORE |
| `OPERATOR` | 运营 | 私域/营销/客服 | STORE |
| `FINANCE` | 财务 | 对账/退款审核 | GROUP/REGION |

---

## 3. 资源 × 操作矩阵

图例：`●` 拥有该操作　`○` 仅查看　`—` 无权限

| 资源 | view | create | edit | delete | export | approve | sign |
|------|:----:|:------:|:----:|:------:|:------:|:------:|:----:|
| customer 客户 | ● | ● | ● | — | ● | — | — |
| appointment 预约 | ● | ● | ● | ● | ○ | — | — |
| queue 排队 | ● | ● | ● | ● | — | — | — |
| reception 接待/分诊 | ● | ● | ● | — | — | — | — |
| consult 咨询 | ● | ● | ● | — | ○ | — | — |
| prescription 开方 | ● | ● | ● | — | ○ | — | — |
| cashier 收银 | ● | ● | — | — | ○ | ● | ● |
| writeoff 划扣 | ● | ● | — | — | — | — | ● |
| course 卡项/疗程 | ● | ● | ● | — | ● | — | — |
| emr 病历 | ● | ● | ● | — | ○ | — | ● |
| followup 回访 | ● | ● | ● | — | ○ | — | — |
| complaint 投诉 | ● | ● | ● | — | ○ | ● | — |
| refund 退款 | ● | ● | — | — | ○ | ● | ● |
| cardcancel 退卡 | ● | ● | — | — | ○ | ● | ● |
| contract 合同 | ● | ● | ● | — | ● | — | — |
| transfer 资产转移 | ● | ● | — | — | — | ● | — |
| marketing 营销 | ● | ● | ● | ● | ○ | ● | — |
| inventory 供应链 | ● | ● | ● | — | ● | ● | — |
| finance 财务 | ● | — | — | — | ● | ● | — |
| report 报表 | ● | — | — | — | ● | — | — |
| tenant 门店主数据 | ● | ● | ● | — | — | ● | — |
| org 组织 | ● | ● | ● | ● | — | ● | — |
| rbac 字段级RBAC | ● | ● | ● | — | — | ● | — |
| compliance 合规 | ● | ● | ● | — | ● | ● | — |
| audit 审计日志 | ● | — | — | — | ● | — | — |
| target 目标 | ● | ● | ● | — | ● | ● | — |
| sop SOP | ● | ● | ● | — | — | ● | — |
| proc 采购 | ● | ● | ● | — | ● | ● | — |
| brand 品牌 | ● | ● | ● | — | — | ● | — |
| dispatch 调度 | ● | ● | ● | — | — | ● | — |
| screen 数据大屏 | ● | — | — | — | — | — | — |
| health 健康度 | ● | — | — | — | ● | — | — |
| recall 复诊提醒 | ● | ● | ● | — | ○ | — | — |
| handover 交接班 | ● | ● | — | — | — | ● | — |
| settings 系统设置 | ○ | — | ● | — | — | ● | — |

---

## 4. 角色 → 权限集（速查）

- **SUPER_ADMIN**：全部资源全部操作。
- **REGION_MGR**：除 `finance` 写、`audit` 写外全有；报表/合规/调度/门店主数据齐全；无 `delete` 高危。
- **STORE_MGR**：本店内全部 `view/create/edit`，`approve` 退款/投诉/资产转移/调度，`sign` 收银/退卡（L2）。
- **CONSULTANT**：`customer`(本人) `consult` `prescription` `appointment` `emr`(建) `followup` `marketing`(查看)；**无** 收银/退款/退卡/财务。
- **DOCTOR**：`emr` `prescription` `consult`(查看) `writeoff`(执行+签) `appointment`(查看)；无收银/财务。
- **FRONT_DESK**：`reception` `queue` `appointment` `customer`(查看/登记) `cashier` `handover`；无开方/病历写/退款审批。
- **OPERATOR**：`marketing` `followup` `complaint`(查看) `course`(查看) `customer`(查看)；无交易写。
- **FINANCE**：`finance` `refund` `cardcancel` `report` `cashier`(查看/审批) `audit`(查看)；无客户写/咨询。

### 4.1 特殊/高危权限（已决议）
| 权限码 | 含义 | 授予角色 | 约束 |
|--------|------|---------|------|
| `customer:merge` | 撞单合并执行 | SUPER_ADMIN / REGION_MGR / STORE_MGR | 需审批 + 留痕、可回滚；系统不自动合并 |
| `customer:transfer:owner` | 变更客户归属（转介绍确认/申诉） | STORE_MGR 及以上 | 改 `ownerStaffId`，影响 SELF 数据域 |
| `impersonate:start` | 超管切换门店/员工视角 | SUPER_ADMIN | 二次认证、默认只读、全程水印+审计 |
| `impersonate:write` | impersonate 态下执行写操作 | SUPER_ADMIN（单独申请） | 每次写操作单独留痕、通知被代操作者 |
| `transfer:approve` | 资产转移审批 | STORE_MGR（店内）/ REGION_MGR（跨店） | AssetTransfer 后置实现，权限码已保留 |

> **SELF 数据域范围（已决议）**：咨询师可见 = 本人 `ownerStaffId` 名下客户 **+ 经审核确认、在有效期内的转介绍客户**（`referralById` 指向本人客户、`referralExpiresAt` 未到期）。

---

## 5. 字段级 RBAC（对接 M106，**本期已落地**）

除"能不能进页面"，还要控制"字段能不能看/改"：
- **敏感字段**：客户真实手机号(`phoneEnc`)、成交金额、医生排班、成本毛利。
- **规则示例**：`CONSULTANT`/`DOCTOR` 持 `customer:phone:decrypt` 可看本人客户明文手机号，其余角色脱敏；`FINANCE` 持 `finance:margin:view` 看成本/毛利，`CONSULTANT`/`STORE_MGR` 不可见。
- **运行时**：`v-perm` 指令 + 字段级 permission 码（资源:字段:操作）。`v-perm.disable` 用于按钮禁用，默认 `v-perm` 无权移除节点；字段显示用 `auth.can('xxx:field:op')` 控制。
- **已验证**：闭环样板页演示了手机号明文/脱敏、毛利可见/`****` 两类字段级门控（Playwright PASS）。

---

## 6. 前端运行时方案（本期落地）

```
src/
 ├─ config/nav.ts          # 导航配置（permission 可选，省略=公共页）
 ├─ stores/auth.ts         # currentRoles[]多角色 + permissions 并集 + can()/scope取最大 + registerCustomRole()
 ├─ router/index.ts        # permissionForPath + beforeEach 守卫
 ├─ App.vue                # 菜单由 auth.can(perm) 过滤生成（去掉硬编码）
 └─ directives/vPerm.ts    # v-perm 按钮/字段级（已注册全局）
```

**守卫逻辑**：
```ts
router.beforeEach((to) => {
  const auth = useAuthStore()
  const need = permissionForPath(to.path) // 与菜单同源；undefined=公共页放行
  if (need && !auth.can(need)) return { path: '/no-auth', query: { from: to.fullPath, need } }
  return true
})
```

**菜单生成**：`config/nav.ts` 每项含可选 `permission`；`buildNav(p => auth.can(p))` 过滤后传给 `CShellDesktop`；无 `permission` 的项（如闭环样板）对所有登录者可见。

**多角色 / 自定义角色**：auth 用 `currentRoles[]`，权限取并集、数据域取最大；`registerCustomRole(key,{label,permissions,scope})` 支持后续"角色管理"页自定义。

**开发期角色切换**：URL `?as=CONSULTANT` 单角色，`?as=STORE_MGR,CONSULTANT` 多角色叠加；闭环页顶部 chip 可直接点选叠加。

---

## 7. 路由 → 权限映射（来自本期 nav 配置）

| 路由 | 所需权限 | 路由 | 所需权限 |
|------|---------|------|---------|
| /appointment | appointment:view | /m1 | report:view |
| /queue | queue:view | /m1-rbac | rbac:view |
| /reception | reception:view | /m1-compliance | compliance:view |
| /guest-reg | customer:create | /m1-audit-log | audit:view |
| /customer-graph | customer:view | /m1-procurement | inventory:view |
| /consultation | consult:view | /m1-marketing | marketing:view |
| /prescription | prescription:view | /m1-dispatch | dispatch:view |
| /order | cashier:view | /m1-tenant | tenant:view |
| /writeoff | writeoff:view | /m1-org | org:view |
| /refund | refund:view | /m1-report | report:view |
| /card-cancel | cardcancel:view | /m1-screen | screen:view |
| /customers | customer:view | /m1-target | target:view |
| /card-course | course:view | /m1-sop | sop:view |
| /course-track | course:track | /m1-health | health:view |
| /emr | emr:view | /m1-region | tenant:view |
| /followup | followup:view | /m1-brand | brand:view |
| /complaint | complaint:view | /m1-settings | settings:view |
| /recall | recall:view | /handover | handover:view |
| /asset-transfer | transfer:view | /contract | contract:view |

---

## 8. 待你确认 / 开放问题

1. **~~角色粒度~~（已决议 2026-08-24）**：8 个内置角色为起步模板，后续支持**自定义角色及权限**；auth 已预留 `registerCustomRole`，待做"角色管理"页。
2. **~~数据域继承（SELF 含转介绍）~~（已决议 2026-08-24）**：咨询师 `SELF` **包含经审核确认、在有效期内的转介绍客户**；需凭证+被介绍人确认，防抢客；归属可到期/申诉重分配（见 §4.1）。
3. **~~字段级优先级~~（已决议 2026-08-24）**：字段级 RBAC **本轮一并落地**，`v-perm` 指令 + 字段级权限码（手机号、毛利已在闭环页演示验证）。
4. **~~超级管理员 impersonate~~（已决议 2026-08-24，受控允许）**：`SUPER_ADMIN` 可 impersonate 任意门店/员工，但**二次认证、默认只读、全程水印+审计**，写操作需 `impersonate:write` 单独授权并通知被代操作者（见 §4.1）。
5. **默认登录角色**：开发期默认用哪个角色进入系统？（当前默认 `STORE_MGR`，最接近真实店长视角）

> 评审后据此落地：`src/stores/auth.ts` + `router` 守卫 + `App.vue` 权限菜单（本期任务 3）。
