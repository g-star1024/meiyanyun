<script setup lang="ts">
/* ============================================================
 * 我的工作台 /my-workbench（角色待办首页）
 * 一线个人首页：我的待办（行内计数 → 点击直达对应作业页）+ 高频操作 + 今日概览。
 * 待办项用「权限 + 实时计数」驱动，天然按当前角色/权限过滤，无需硬编码角色分支。
 * 真实计数：审批待办 / 待收款订单 / 今日预约（看板剔除已取消）/ 方案单五态队列（listPlans totalElements）
 *           / 候诊待接待（今日 WAITING）/ 病历草稿待签（DRAFT）/ 术后 SOP 待回访与超期（完成治疗自动排程）。
 * 演示计数：复诊提醒（所属域暂无后端，文案带「演示」后缀、不计入真实总数）。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import { useAuthStore } from '@/stores/auth'
import { useRecallStore } from '@/stores/recall'
import { useStoreContext } from '@/stores/storeContext'
import { listApprovals, type ApprovalTodoDTO } from '@/api/approval'
import { listOrders } from '@/api/order'
import { listPlans } from '@/api/consultPlan'
import { appointmentBoard } from '@/api/appointment'
import { listArrivals } from '@/api/arrival'
import { statsEmr } from '@/api/emr'
import { statsFollowup } from '@/api/followup'
import { staffName } from '@/config/staff'

const router = useRouter()
const auth = useAuthStore()
const recall = useRecallStore()
const storeCtx = useStoreContext()

// ---- 真实计数：审批待办 / 待收款订单 / 今日预约 / 方案单各状态队列（按权限分别拉取，失败静默降级为 0 隐藏） ----
const myTodoCount = ref(0)
const pendingPayCount = ref(0)
const todayApptCount = ref(0)
const planQueueCount = ref(0)
const planReviewCount = ref(0)
const planPaidCount = ref(0)
const planTreatingCount = ref(0)
const waitingCount = ref(0)
const emrDraftCount = ref(0)
const sopPendingCount = ref(0)
const sopOverdueCount = ref(0)

function todayLocal(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const BIZ_PERM_WB: Record<string, string> = {
  REFUND: 'refund:approve', CARD_CANCEL: 'cardcancel:approve', TRANSFER: 'transfer:approve',
  LEAVE: 'schedule:approve', PROCUREMENT: 'inventory:approve', PRICE_CHANGE: 'brand:approve',
  LOSS_REPORT: 'inventory:approve', REQUISITION: 'inventory:approve',
}
// E/SE 真实工号已由 config/staff.ts ROSTER 覆盖；此处仅保留离线 mock 旧 ID 与系统占位
const STAFF_NAME_EXTRA_WB: Record<string, string> = {
  'staff-zhou': '周岚', 'staff-chen': '陈野', 'staff-xia': '夏沫',
  'staff-bai': '白桥', 'staff-qian': '钱进', cashier: '前台收银', system: '系统',
}
function nameOfWb(id?: string | null): string {
  if (!id) return ''
  if (STAFF_NAME_EXTRA_WB[id]) return STAFF_NAME_EXTRA_WB[id]
  const n = staffName(id)
  return n === id ? id : n
}
function stagePermWb(d: ApprovalTodoDTO): string {
  if (d.stage === 'FINANCE') {
    if (d.bizType === 'REFUND') return 'refund:sign'
    if (d.bizType === 'CARD_CANCEL') return 'cardcancel:sign'
    if (d.bizType === 'TRANSFER') return 'transfer:edit'
  }
  return BIZ_PERM_WB[d.bizType] || 'approval:view'
}

async function loadTxnCounts() {
  try {
    if (!storeCtx.loaded) await storeCtx.loadStores()
  } catch {
    return
  }
  const store = storeCtx.currentStoreCode || undefined

  // 审批待办（真实）：PENDING + 当前人有签署权 + 指派人匹配
  if (auth.can('approval:view')) {
    try {
      const apRes = await listApprovals({ tab: 'todo' })
      myTodoCount.value = apRes.data.filter((t) => {
        if (t.status !== 'PENDING') return false
        if (!auth.can(stagePermWb(t))) return false
        const assignee = t.assignee ? nameOfWb(t.assignee) : ''
        return !assignee || assignee === auth.user.name
      }).length
    } catch {
      myTodoCount.value = 0
    }
  }

  // 待收款订单（真实，订单口径：含方案单缴费单 + 零售/药妆应收单）
  if (auth.can('cashier:view')) {
    try {
      const ordRes = await listOrders({ size: 100, status: '待收款', storeCode: store })
      pendingPayCount.value = ordRes.data.totalElements ?? ordRes.data.content.length
    } catch {
      pendingPayCount.value = 0
    }
  }

  // 今日预约（真实）：看板 total 剔除已取消；医生 SELF 数据域只见本人
  if (auth.can('appointment:view')) {
    try {
      const board = await appointmentBoard(store, todayLocal())
      todayApptCount.value = Math.max(0, (board.data.total ?? 0) - (board.data.cancelled ?? 0))
    } catch {
      todayApptCount.value = 0
    }
  }

  // 方案单各队列（真实）：列表端点挂 consult:view，size:1 只取 totalElements
  if (auth.can('consult:view')) {
    try {
      const [pending, active, review, paid, treating] = await Promise.all([
        listPlans({ size: 1, status: 'PENDING', storeCode: store }),
        listPlans({ size: 1, status: 'ACTIVE', storeCode: store }),
        listPlans({ size: 1, status: 'PENDING_REVIEW', storeCode: store }),
        listPlans({ size: 1, status: 'PAID', storeCode: store }),
        listPlans({ size: 1, status: 'TREATING', storeCode: store }),
      ])
      planQueueCount.value = (pending.data.totalElements ?? 0) + (active.data.totalElements ?? 0)
      planReviewCount.value = review.data.totalElements ?? 0
      planPaidCount.value = paid.data.totalElements ?? 0
      planTreatingCount.value = treating.data.totalElements ?? 0
    } catch {
      planQueueCount.value = 0
      planReviewCount.value = 0
      planPaidCount.value = 0
      planTreatingCount.value = 0
    }
  }

  // 候诊待接待（真实）：今日当前门店 WAITING 队列长度
  if (auth.can('reception:view')) {
    try {
      const arrRes = await listArrivals({ date: todayLocal(), storeCode: store, status: 'WAITING' })
      waitingCount.value = (arrRes.data ?? []).length
    } catch {
      waitingCount.value = 0
    }
  }

  // 病历草稿待签（真实）：当前门店 DRAFT 病历数
  if (auth.can('emr:view')) {
    try {
      const emrRes = await statsEmr(store)
      emrDraftCount.value = emrRes.data.draft ?? 0
    } catch {
      emrDraftCount.value = 0
    }
  }

  // 术后 SOP（真实）：当前门店待回访节点数 / 超期未回访节点数（完成治疗自动排程）
  if (auth.can('followup:view')) {
    try {
      const fuRes = await statsFollowup(store)
      sopPendingCount.value = fuRes.data.sopPending ?? 0
      sopOverdueCount.value = fuRes.data.sopOverdue ?? 0
    } catch {
      sopPendingCount.value = 0
      sopOverdueCount.value = 0
    }
  }
}

onMounted(() => {
  // 复诊提醒域暂无后端，计数为演示数据；其余计数均在 loadTxnCounts 真实拉取
  recall.seed()
  loadTxnCounts()
})

type IconName = 'calendar' | 'home' | 'chat' | 'shield' | 'edit' | 'check-square' | 'tool' | 'pos' | 'phone' | 'alert' | 'bell' | 'dashboard' | 'customer' | 'user' | 'box' | 'layers' | 'chevron-right'

interface Todo {
  key: string
  label: string
  count: number
  to: string
  icon: IconName
  tone: 'brand' | 'warning' | 'danger' | 'success'
  perm?: string
  /** 演示数据：所属域暂无后端（复诊提醒），不计入真实待办总数 */
  demo?: boolean
  group: '临床诊疗' | '收银履约' | '术后跟进' | '管理协同'
}

const todos = computed<Todo[]>(() => {
  const all: Todo[] = [
    // 临床诊疗（真实计数）
    { key: 'appt', label: '今日预约', count: todayApptCount.value, to: '/appointment', icon: 'calendar', tone: 'brand', perm: 'appointment:view', group: '临床诊疗' },
    { key: 'waiting', label: '候诊待接待', count: waitingCount.value, to: '/reception', icon: 'home', tone: 'brand', perm: 'reception:view', group: '临床诊疗' },
    { key: 'consult', label: '待咨询 / 面诊', count: planQueueCount.value, to: '/consultation', icon: 'chat', tone: 'brand', perm: 'consult:view', group: '临床诊疗' },
    { key: 'review', label: '待医生审核方案', count: planReviewCount.value, to: '/doctor', icon: 'shield', tone: 'warning', perm: 'consult:review', group: '临床诊疗' },
    { key: 'emr-draft', label: '病历草稿待签', count: emrDraftCount.value, to: '/emr', icon: 'edit', tone: 'warning', perm: 'emr:view', group: '临床诊疗' },
    { key: 'paid', label: '待治疗 / 术前核对', count: planPaidCount.value, to: '/doctor', icon: 'check-square', tone: 'brand', perm: 'consult:review', group: '临床诊疗' },
    { key: 'treating', label: '治疗中待归档', count: planTreatingCount.value, to: '/doctor', icon: 'tool', tone: 'brand', perm: 'consult:review', group: '临床诊疗' },
    // 收银履约：订单口径（含方案单自动生成的缴费单 + 零售/药妆应收单），不遗漏非诊疗单
    { key: 'pay', label: '待收款订单', count: pendingPayCount.value, to: '/order', icon: 'pos', tone: 'danger', perm: 'cashier:view', group: '收银履约' },
    // 术后待回访 / SOP 超期（真实计数，完成治疗 AFTER_COMMIT 自动排程）；复诊提醒域暂无后端→演示数据
    { key: 'fu', label: '术后待回访', count: sopPendingCount.value, to: '/followup', icon: 'phone', tone: 'success', perm: 'followup:view', group: '术后跟进' },
    { key: 'fu-overdue', label: 'SOP 超期未回访', count: sopOverdueCount.value, to: '/sop', icon: 'alert', tone: 'danger', perm: 'followup:view', group: '术后跟进' },
    { key: 'recall', label: '复诊待提醒（演示）', count: recall.pending.length, to: '/recall', icon: 'bell', tone: 'warning', perm: 'recall:view', demo: true, group: '术后跟进' },
    // 管理协同（真实计数）
    { key: 'approval', label: '待我审批', count: myTodoCount.value, to: '/approval', icon: 'check-square', tone: 'warning', perm: 'approval:view', group: '管理协同' },
  ]
  // 按权限过滤 + 仅保留有待办的项
  return all.filter((t) => (!t.perm || auth.can(t.perm)) && t.count > 0)
})

const groups = computed(() => {
  const groupOrder: Todo['group'][] = ['临床诊疗', '收银履约', '术后跟进', '管理协同']
  return groupOrder
    .map((g) => ({ name: g, items: todos.value.filter((t) => t.group === g) }))
    .filter((g) => g.items.length > 0)
})

// 真实待办总数（演示域不计入欢迎条数字，避免把 mock 当真实工作量）
const todoTotal = computed(() => todos.value.filter((t) => !t.demo).reduce((s, t) => s + t.count, 0))

const quickActions = computed(() => {
  const acts: { label: string; icon: IconName; to: string; perm?: string }[] = [
    { label: '全院流水牌', icon: 'dashboard', to: '/board' },
    { label: '客户档案', icon: 'customer', to: '/customers', perm: 'customer:view' },
    { label: '新建预约', icon: 'calendar', to: '/appointment/new', perm: 'appointment:create' },
    { label: '客情登记', icon: 'user', to: '/guest-reg', perm: 'customer:create' },
    { label: '咨询工作台', icon: 'chat', to: '/consultation', perm: 'consult:view' },
    { label: '收款收银', icon: 'pos', to: '/order', perm: 'cashier:view' },
    { label: '电子病历', icon: 'box', to: '/emr', perm: 'emr:view' },
    { label: '术后 SOP', icon: 'layers', to: '/sop', perm: 'followup:view' },
  ]
  return acts.filter((a) => !a.perm || auth.can(a.perm))
})

const today = new Date()
const dateStr = `${today.getFullYear()} 年 ${today.getMonth() + 1} 月 ${today.getDate()} 日`
const weekStr = ['日', '一', '二', '三', '四', '五', '六'][today.getDay()]

function go(to: string) {
  router.push(to)
}
</script>

<template>
  <div class="wb">
    <!-- 欢迎条 -->
    <div class="wb__hero">
      <div class="wb__hero-l">
        <div class="wb__hello">{{ auth.user.name }}，{{ auth.user.roleLabels }}</div>
        <div class="wb__date">{{ dateStr }} · 星期{{ weekStr }} · 你今天有 <strong>{{ todoTotal }}</strong> 项待办</div>
      </div>
      <CButton variant="primary" size="sm" @click="go('/board')">
        <CIcon name="dashboard" :size="14" />查看全院流水牌
      </CButton>
    </div>

    <div class="wb__main">
      <!-- 左：我的待办（按组） -->
      <CCard class="wb__todos" padding="lg">
        <template #header>
          <h3 class="wb__card-title"><CIcon name="bell" :size="16" />我的待办</h3>
          <span class="wb__todo-total">{{ todoTotal }}</span>
        </template>

        <div v-if="!groups.length" class="wb__all-done">
          <CIcon name="check-square" :size="28" />
          <div>今天的待办都处理完啦</div>
        </div>

        <div v-for="g in groups" :key="g.name" class="wb__group">
          <div class="wb__group-name">{{ g.name }}</div>
          <div class="wb__todo-grid">
            <button
              v-for="t in g.items"
              :key="t.key"
              class="todo"
              :class="`todo--${t.tone}`"
              @click="go(t.to)"
            >
              <span class="todo__icon"><CIcon :name="t.icon" :size="16" /></span>
              <span class="todo__label">{{ t.label }}</span>
              <span class="todo__count">{{ t.count }}</span>
              <CIcon name="chevron-right" :size="13" class="todo__arrow" />
            </button>
          </div>
        </div>
      </CCard>

      <!-- 右：高频操作 -->
      <CCard class="wb__quick" padding="lg">
        <template #header>
          <h3 class="wb__card-title"><CIcon name="pos" :size="16" />高频操作</h3>
        </template>
        <div class="wb__quick-grid">
          <button v-for="a in quickActions" :key="a.to" class="qact" @click="go(a.to)">
            <span class="qact__icon"><CIcon :name="a.icon" :size="18" /></span>
            <span class="qact__label">{{ a.label }}</span>
          </button>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.wb { display: flex; flex-direction: column; gap: var(--s-md); }

.wb__hero {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-md);
  background: linear-gradient(135deg, var(--c-brand-soft), var(--c-surface));
  border: 1px solid var(--c-border); border-radius: var(--r-lg);
  padding: var(--s-md) var(--s-lg);
}
.wb__hello { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.wb__date { font-size: var(--t-sm); color: var(--c-text-3); margin-top: 4px; }
.wb__date strong { color: var(--c-brand); font-size: var(--t-md); }

.wb__main { display: grid; grid-template-columns: 1fr 300px; gap: var(--s-md); align-items: start; }

.wb__card-title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-md); font-weight: 700; color: var(--c-text); margin: 0; }
.wb__todo-total {
  min-width: 24px; height: 24px; padding: 0 8px; border-radius: var(--r-pill);
  background: var(--c-brand); color: #fff; font-size: var(--t-xs); font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center;
}

.wb__all-done { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); color: var(--c-success-fg); padding: var(--s-xl) 0; font-size: var(--t-sm); }

.wb__group { margin-top: var(--s-md); }
.wb__group:first-of-type { margin-top: var(--s-sm); }
.wb__group-name { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 700; margin-bottom: var(--s-xs); letter-spacing: .02em; }
.wb__todo-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--s-sm); }

.todo {
  position: relative; display: flex; align-items: center; gap: var(--s-sm);
  background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--r-md);
  padding: var(--s-sm); cursor: pointer; text-align: left; transition: border-color .15s, box-shadow .15s;
}
.todo:hover { border-color: var(--c-brand); box-shadow: var(--shadow-card); }
.todo__icon {
  width: 34px; height: 34px; border-radius: var(--r-md); flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--c-brand-soft); color: var(--c-brand);
}
.todo--warning .todo__icon { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.todo--danger .todo__icon { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.todo--success .todo__icon { background: var(--c-success-bg); color: var(--c-success-fg); }
.todo__label { flex: 1; font-size: var(--t-sm); color: var(--c-text); font-weight: 500; }
.todo__count {
  min-width: 24px; height: 24px; padding: 0 7px; border-radius: var(--r-pill);
  background: var(--c-bg-right); color: var(--c-text-2); font-size: var(--t-sm); font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center;
}
.todo--danger .todo__count { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.todo--warning .todo__count { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.todo__arrow { color: var(--c-text-4); }

.wb__quick-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--s-sm); }
.qact {
  display: flex; flex-direction: column; align-items: center; gap: 6px; cursor: pointer;
  background: var(--c-bg-right); border: 1px solid var(--c-border-light); border-radius: var(--r-md);
  padding: var(--s-md) var(--s-sm); transition: border-color .15s, background .15s;
}
.qact:hover { border-color: var(--c-brand); background: var(--c-brand-soft); }
.qact__icon {
  width: 38px; height: 38px; border-radius: var(--r-md); background: var(--c-surface);
  color: var(--c-brand); display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--c-border-light);
}
.qact__label { font-size: var(--t-xs); color: var(--c-text-2); font-weight: 500; text-align: center; }

@media (max-width: 1024px) {
  .wb__main { grid-template-columns: 1fr; }
}
@media (max-width: 720px) {
  .wb__todo-grid { grid-template-columns: 1fr; }
  .wb__hero { flex-direction: column; align-items: flex-start; }
}
</style>
