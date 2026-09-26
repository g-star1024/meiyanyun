# 批量页面开发规范与检查清单

> 沉淀自 42 个真实业务页开发经验，尤其针对上一轮 8 页批量开发出现的"内容堆挤"事故。
> 每开发一个页面，**必须逐条过一遍本清单**，再提交。

---

## 一、铁律：上轮事故根因（最高优先级）

### 事故复盘
M1 八页开发后，用户反馈"间距、边距、格式全没了，全堆在一起"。根因是 CSS 使用了
**`--sp-2/--sp-3/--sp-4/--sp-6/--sp-8` 间距变量，但项目从未定义 `--sp-*` 系列**，
正确命名是 `--s-*`。未定义的 CSS 自定义属性**静默失效、零报错**，导致所有 gap/padding/margin 失效。

### 强制规则
1. **写任何 CSS 变量前，先确认它在 `src/styles/tokens.css` 中真实存在**，禁止凭直觉命名（如 `--sp-*`、`--t-xxl`、`--c-info-fg` 等可能不存在）。
2. 每页写完后必须用 Playwright 实际截图，**用眼睛确认间距渲染正常**——`vue-tsc` 和 `build` 无法发现 CSS 变量静默失效。
3. 新增页面的 `<style>` 第一行前，先 grep 确认所有 `var(--xxx)` 都有定义：
   ```bash
   grep -ohE 'var\(--[a-z0-9-]+' YourView.vue | sort -u
   # 逐一对照 tokens.css
   ```

---

## 二、Token 速查（只允许使用这些）

### 间距 `--s-*`（4px 网格）
| token | 值 | 用途 |
|---|---|---|
| `--s-xxs` | 4px | 图标与文字间距 |
| `--s-xs` | 8px | 紧凑元素间距、徽章内边距 |
| `--s-sm` | 12px | 表单项间距、小卡片 padding |
| `--s-md` | 16px | **卡片内 padding 基准**、元素常规间距 |
| `--s-lg` | 24px | Desktop 内容区 padding、区块间距、KPI 卡 padding |
| `--s-xl` | 32px | 区块大分隔 |
| `--s-xxl` | 48px | 页面级留白 |

> **没有 `--sp-*`，永远不要写。**

### 字号 `--t-*`
`--t-xs`(12) / `--t-sm`(13) / `--t-md`(16) / `--t-lg`(18) / `--t-xl`(20)。
**最大 `--t-xl`，没有 `--t-xxl`。** 配套行高 `--lh-xs/sm/md/lg/xl`。
英雄数字可用裸值 26-40px（设计强调），但须加注释。

### 圆角 `--r-*`
`--r-sm`(6 输入框) / `--r-md`(8 表格卡片) / `--r-lg`(10 导航芯片) / `--r-xl`(12 卡片弹窗)。

### 语义色
- 主色：`--c-brand`(粉 #ff6b9e) / `--c-brand-secondary`(蓝紫 #6b8aff) / `--c-brand-soft`(浅粉底) / `--c-brand-press` / `--c-brand-border`
- 状态：`--c-success-fg`+`--c-success-bg` / `--c-warning-fg`+`--c-warning-bg` / `--c-danger-fg`+`--c-danger-bg`
- 文字：`--c-text`(主) / `--c-text-2` / `--c-text-3`
- 表面/描边：`--c-surface` / `--c-border` / `--c-border-light` / `--c-surface-muted`(若用裸值兜底用 `#f7f8fa`)
- 系列色：`--c-series-1..n`、`--c-teal`/`--c-orange` 等（用前 grep 确认）

> 状态色文字用带 `-fg` 的，背景用带 `-bg` 的，不要用 `--c-success`（不存在）。

---

## 三、组件正确用法（踩过的坑）

| 组件 | 正确 | 易错点 |
|---|---|---|
| **CKpi** | `<CKpi value="123" label="标题" tone="brand" trend="2%" trend-up />` | 用 `tone` 不是 `type/variant`；tone 取值 text/brand/teal/orange/warning/danger/success；value 必须 string |
| **CButton** | `variant` primary/secondary/danger/ghost/text；`size` sm/md/lg；图标放默认 slot | **没有 `left-icon`/`icon` prop**；没有 loading prop（自行处理） |
| **CCard** | `padding` sm/md/lg/**none**；`title`；`headerBorder`；头部用 `<template #header>` | **没有 `no-padding`/`header-right` prop**；插槽名是 `header` 不是 `header-right` |
| **CDrawer** | `v-model:show="open"` `title` `size` sm/md/lg | 不要用 `v-if`/`width`；用 size 不是 width |
| **CStatusPill** | `<CStatusPill status="success">文本</CStatusPill>` 走默认 slot | **没有 `label` prop**；status 取值 default/primary/success/warning/danger/info/disabled/draft；有 `dot` boolean |
| **CProgressBar** | `:value="n" :color="'var(--c-brand)'" :show-label="false"` | 用 `color` 不是 `tone`；color 传 CSS 变量字符串；height 默认 8 |
| **CSegmented** | `v-model="x" :options="[{label,value}]"` | 已有 disabled prop；size sm/md |
| **CTable** | `:columns` 中 `align` 必须 `as const`；rows 是 Record<string,any>[] | columns 的 align 不写 as const 会被推断为 string 报错 |
| **CInput/CTextarea** | `v-model` `label` `placeholder` `:disabled` `:error` | type 仅 text/password/number；textarea 用 rows |
| **CSelect** | `v-model` `:options="[{label,value}]"` `width` | 没有多选 |
| **CIcon** | `name` 必须是下表白名单；`:size="16"`；传 name 给变量时用 `as any` | **不存在的 name 会渲染成空心方框**，用前核对 |

### CIcon 白名单（48 个，新增需改组件）
calendar chat order scan refund sign customer card handover store org finance
marketing mall search plus check export upload edit delete chevron-down/left/right
home bell settings shield dashboard profile check-square pos box sun scissors
package volume trend-up trend-down clock phone menu user user-check alert loading close

> 业务需要的图标若不在表中，优先选近义图标；确实没有再给 CIcon.vue 增补 `<template v-else-if>`。

---

## 四、Pinia setup store 约定

```ts
export const useXxxStore = defineStore('xxx', () => {
  const list = ref<Xxx[]>([...seed])
  const selectedId = ref<string | null>(null)
  const selected = computed(() => list.value.find(x => x.id === selectedId.value))
  function add() { ... }
  return { list, selectedId, selected, add }   // 必须 return
})
```
- **组件中访问 store 的 ref/computed 不加 `.value`**（Pinia setup store 自动解包）。
  错误：`store.list.value`；正确：`store.list`。
- 深拷贝不要用 `structuredClone(reactiveProxy)`——会抛 DataCloneError。
  用 `JSON.parse(JSON.stringify(toRaw(v)))`。
- seed 数据里的可选数组字段（如 `completedSteps?`）要在初始化/创建动作里赋默认 `[]`，避免 undefined.map。

---

## 五、角色与权限

- 角色枚举：`STORE_MGR / REGION_MGR / SUPER_ADMIN / FINANCE / OPERATOR / DOCTOR / CONSULTANT / FRONT_DESK`
  （**没有 STORE_MANAGER**，写错会静默回退到默认角色，权限测试假通过）。
- 权限判断：`auth.can('xxx:edit')`；模板用 `v-perm` 指令（无权限移除 DOM）。
- 权限测试最稳方式：选"有 view 无 edit"的角色，断言编辑按钮 `count() === 0`。
- **Playwright 多角色测试必须用 `browser.newContext()`**，不能用 `browser.newPage()`——
  newPage 共享同一 context 的 localStorage，角色会残留串味。

---

## 六、页面布局规范（对齐 design-fidelity）

1. **桌面端 1280 优先**，平板断点 834（本项目用 `@media (max-width: 900px)` 统一）。
2. **禁止双头**：CShellDesktop 全局顶栏已显示页面标题，页面内不要再放 `<h2>页面标题</h2>`。
   页面顶部只放说明文案 + 操作按钮。
3. 标准页结构：
   ```
   .page (display:flex; flex-direction:column; gap: var(--s-lg))
     ├── .page__kpis (display:flex; flex-wrap:wrap; gap:var(--s-md))
   │     └── :deep(.ckpi){ flex:1 1 0; min-width:168px }
   └── .page__body (grid 左栏固定 280-360px + 1fr; gap:var(--s-lg); align-items:start)
         ├── 左 CCard padding="none"（列表）
         └── 右 CCard padding="lg"（详情/表单）
   ```
4. **KPI 一律用 `<CKpi>`**，不要手写 `.kpi` div（上轮手写 KPI 数字顶格/标签溢出是错位重灾区）。
   仅 hero 渐变卡可手写，但必须设 padding、font-size、line-height、nowrap。
5. 列表选中态用 `box-shadow: inset 3px 0 0 var(--c-brand)`，**不要用 border-left**（会撑动布局导致跳动）。
6. 数字加 `font-variant-numeric: tabular-nums`；KPI 数值长文本加 `white-space: nowrap`。
7. 所有间距/字号/圆角/颜色用 token；裸值仅允许：hero 大数字、深色大屏（如数据大屏径向渐变）、
   且需注释说明"设计强调/有意为之"。

---

## 七、每页交付标准（Definition of Done）

每个页面必须完成以下全部步骤才算交付：

- [ ] **store**：`src/stores/xxx.ts`，含类型定义、seed 数据、computed、动作方法，return 完整
- [ ] **view**：`src/views/XxxView.vue`，复用现有组件，全部 token，无裸值（除允许项）
- [ ] **router**：在 `src/router/index.ts` 注册真实组件，**删除任何 `uc()` 占位**
- [ ] **nav**：如属新菜单项，在 `src/config/nav.ts` 注册（带权限 key）
- [ ] **vue-tsc**：`npx vue-tsc --noEmit` 0 报错
- [ ] **build**：`npm run build` 通过
- [ ] **verify 脚本**：`/tmp/verify-xxx.mjs`，Playwright 用 `newContext()`，
      断言关键元素存在、0 console error、至少一条权限断言
- [ ] **双端截图**：1280×900 桌面 + 834×1112 平板，**肉眼确认间距/边距/无重叠/无方框图标**
- [ ] **冒烟**：核心交互链路（选中→编辑→保存/审批/提交）跑通
- [ ] **CSS 变量复核**：grep `var(--` 确认全部已定义

### 截图验证脚本模板
```js
import { chromium } from 'playwright'
const b = await chromium.launch()
for (const [name,w,h] of [['desktop',1280,900],['tablet',834,1112]]) {
  const ctx = await b.newContext({ viewport:{width:w,height:h} })
  const p = await ctx.newPage()
  const errs = []
  p.on('console', m => { if(m.type()==='error') errs.push(m.text()) })
  await p.goto('http://127.0.0.1:5173/xxx?as=SUPER_ADMIN', { waitUntil:'networkidle' })
  await p.waitForTimeout(700)
  await p.screenshot({ path:`/tmp/xxx-${name}.png`, fullPage:true })
  console.log(name, 'errors:', errs.length, errs.slice(0,3))
  await ctx.close()
}
await b.close()
```

---

## 八、批量开发节奏建议

1. **一次不要写超过 4 个页面不验证**。每写完 2-3 页就跑一次 tsc + 截图，早发现 CSS/类型问题。
2. 先写一个"样板页"跑通全链路（store→view→路由→截图），确认模式无问题，再复制结构批量推进。
3. 同类页面（列表+详情两栏）复制结构时，**重点检查：CSS 变量名、CIcon name、CCard/CDrawer prop 名、
   角色枚举名、store 访问是否多加 .value**——这五个是 90% bug 来源。
4. dev server 用 `npm run dev` 配合 `run_in_background` 常驻，不要用 nohup（非交互 shell 会被杀）。
