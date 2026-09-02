<script setup lang="ts">
/* ============================================================
 * 全院客户流水牌 /board
 * 一线首页：按客户在院状态分列（候诊→咨询→审核→收费→治疗→回访），
 * 数据聚合 arrival / consultation / order / followup 现有 store，不建新状态机。
 * 卡片点击进客户 360（唯一操作锚点）；列头「去处理」跳对应作业页。
 * ============================================================ */
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import { useArrivalStore } from '@/stores/arrival'
import { useConsultationStore } from '@/stores/consultation'
import { useFollowupStore } from '@/stores/followup'
import { useCustomerStore } from '@/stores/customer'
import { useOrderStore } from '@/stores/order'

const router = useRouter()
const arrival = useArrivalStore()
const consultation = useConsultationStore()
const followup = useFollowupStore()
const customer = useCustomerStore()
const order = useOrderStore()

onMounted(() => {
  arrival.seed()
  consultation.seed()
  followup.seed()
  customer.seedProfile()
  order.seed()
})

type IconName = 'home' | 'chat' | 'shield' | 'pos' | 'check-square' | 'tool' | 'phone' | 'dashboard' | 'chevron-right'
const CHANNEL_LABEL: Record<string, string> = { ONLINE_APPT: '线上预约', REFERRAL: '转介绍', MARKETING: '营销到店', WALK_IN: 'walk-in 到店' }

interface BoardCard {
  key: string
  customerId: string
  name: string
  sub: string
  to: string
}
interface BoardCol {
  key: string
  title: string
  icon: IconName
  tone: 'brand' | 'warning' | 'danger' | 'success' | 'info' | 'purple' | 'teal'
  hint: string
  actionTo: string
  actionLabel: string
  cards: BoardCard[]
}

const nameOf = (id?: string) => (id ? customer.nameOf(id) : '—')

const columns = computed<BoardCol[]>(() => [
  {
    key: 'waiting',
    title: '候诊 / 已到店',
    icon: 'home',
    tone: 'info',
    hint: '前台分诊后进入咨询',
    actionTo: '/reception',
    actionLabel: '接待台',
    cards: arrival.waiting.map((a) => ({
      key: a.id,
      customerId: a.customerId,
      name: nameOf(a.customerId),
      sub: `排队号 ${a.queueNo} · ${CHANNEL_LABEL[a.channel] ?? a.channel}`,
      to: `/customers/${a.customerId}`,
    })),
  },
  {
    key: 'consulting',
    title: '咨询中',
    icon: 'chat',
    tone: 'brand',
    hint: '咨询师面诊 / 开方案',
    actionTo: '/consultation',
    actionLabel: '咨询工作台',
    cards: [...consultation.pending, ...consultation.active.filter((c) => c.status === 'ACTIVE')].map((c) => ({
      key: c.id,
      customerId: c.customerId,
      name: nameOf(c.customerId),
      sub: c.planItems?.length ? `${c.planItems[0].name} 等 ${c.planItems.length} 项` : '面诊进行中',
      to: `/customers/${c.customerId}`,
    })),
  },
  {
    key: 'reviewing',
    title: '待医生审核',
    icon: 'shield',
    tone: 'warning',
    hint: '方案待医生审核 / 签病历',
    actionTo: '/doctor',
    actionLabel: '医师工作台',
    cards: consultation.reviewing.map((c) => ({
      key: c.id,
      customerId: c.customerId,
      name: nameOf(c.customerId),
      sub: c.planAmount ? `方案 ¥${c.planAmount.toLocaleString()}` : '方案待审',
      to: `/doctor?fromConsult=${c.id}`,
    })),
  },
  {
    key: 'paying',
    title: '待收费',
    icon: 'pos',
    tone: 'danger',
    hint: '病历已签 · 缴费单待支付',
    actionTo: '/order',
    actionLabel: '收款收银',
    cards: consultation.readyPay.map((c) => {
      // 诊疗动线口径：方案单经 approveAndSignEmr 已自动生成真实缴费单（c.orderId），
      // 显示财务真值（订单号 + 订单 amount）；纯零售应收单不进诊疗流水牌，由收银台/我的工作台兜住。
      const o = c.orderId ? order.get(c.orderId) : undefined
      return {
        key: c.id,
        customerId: c.customerId,
        name: nameOf(c.customerId),
        sub: o
          ? `${o.orderNo} · 待收 ¥${o.amount.toLocaleString()}`
          : c.planAmount
            ? `待收 ¥${c.planAmount.toLocaleString()}`
            : '待生成缴费单',
        to: '/order',
      }
    }),
  },
  {
    key: 'paid',
    title: '待治疗',
    icon: 'check-square',
    tone: 'purple',
    hint: '已缴费 · 术前核对排期',
    actionTo: '/doctor',
    actionLabel: '医师工作台',
    cards: consultation.paid.map((c) => ({
      key: c.id,
      customerId: c.customerId,
      name: nameOf(c.customerId),
      sub: c.planItems?.length ? `${c.planItems[0].name}` : '待排期治疗',
      to: `/doctor?fromConsult=${c.id}`,
    })),
  },
  {
    key: 'treating',
    title: '治疗中',
    icon: 'tool',
    tone: 'teal',
    hint: '治疗进行 · 完成后归档',
    actionTo: '/doctor',
    actionLabel: '医师工作台',
    cards: consultation.treating.map((c) => ({
      key: c.id,
      customerId: c.customerId,
      name: nameOf(c.customerId),
      sub: c.planItems?.length ? `${c.planItems[0].name} 进行中` : '治疗中',
      to: `/doctor?fromConsult=${c.id}`,
    })),
  },
  {
    key: 'followup',
    title: '待回访',
    icon: 'phone',
    tone: 'success',
    hint: '术后 SOP 随访 / 满意度',
    actionTo: '/followup',
    actionLabel: '术后回访',
    cards: followup.sopPending.map((f) => ({
      key: f.id,
      customerId: f.customerId,
      name: f.customerName || nameOf(f.customerId),
      sub: `${f.sopLabel || f.project} · 计划 ${f.planDate.slice(0, 10)}`,
      to: `/customers/${f.customerId}`,
    })),
  },
])

const totalInStore = computed(() => columns.value.reduce((s, c) => s + c.cards.length, 0))
/** 拥堵预警：某环节积压超过阈值提示（参考睿美云拥堵预警） */
function busy(col: BoardCol) {
  return col.cards.length >= 3
}
function open(to: string) {
  router.push(to)
}
</script>

<template>
  <div class="board">
    <!-- 顶部说明条 -->
    <div class="board__intro">
      <div class="board__intro-l">
        <CIcon name="dashboard" :size="18" />
        <div>
          <div class="board__intro-title">全院在院客户 <strong>{{ totalInStore }}</strong> 人</div>
          <div class="board__intro-desc">客户随动线自动流转，点击卡片查看客户 360 档案，列头「去处理」进入对应岗位工作台。</div>
        </div>
      </div>
      <CButton variant="secondary" size="sm" @click="open('/my-workbench')">
        <CIcon name="home" :size="14" />我的工作台
      </CButton>
    </div>

    <!-- 流水列 -->
    <div class="board__cols">
      <div v-for="col in columns" :key="col.key" class="bcol" :class="`bcol--${col.tone}`">
        <div class="bcol__head" :title="col.hint">
          <div class="bcol__head-l">
            <span class="bcol__icon"><CIcon :name="col.icon" :size="15" /></span>
            <span class="bcol__title">{{ col.title }}</span>
          </div>
          <span class="bcol__count" :class="{ 'is-busy': busy(col) }">{{ col.cards.length }}</span>
        </div>
        <button class="bcol__action" @click="open(col.actionTo)">
          {{ col.actionLabel }} <CIcon name="chevron-right" :size="12" />
        </button>

        <div class="bcol__body">
          <button
            v-for="card in col.cards"
            :key="card.key"
            class="bcard"
            @click="open(card.to)"
          >
            <div class="bcard__name">{{ card.name }}</div>
            <div class="bcard__sub">{{ card.sub }}</div>
            <CIcon name="chevron-right" :size="13" class="bcard__arrow" />
          </button>
          <div v-if="!col.cards.length" class="bcol__empty">
            <CIcon name="check-square" :size="16" />
            <span>暂无客户</span>
          </div>
          <div v-if="busy(col)" class="bcol__busy">该环节积压 {{ col.cards.length }} 人，建议优先处理</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.board { display: flex; flex-direction: column; gap: var(--s-md); }

.board__intro {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-md);
  background: var(--c-surface); border: 1px solid var(--c-border); border-radius: var(--r-lg);
  padding: var(--s-md) var(--s-lg);
}
.board__intro-l { display: flex; align-items: center; gap: var(--s-sm); color: var(--c-brand); }
.board__intro-title { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.board__intro-title strong { color: var(--c-brand); font-size: var(--t-lg); margin: 0 2px; }
.board__intro-desc { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.board__cols {
  display: grid; grid-template-columns: repeat(7, minmax(172px, 1fr)); gap: var(--s-sm);
  align-items: stretch; overflow-x: auto; padding-bottom: var(--s-xs);
}

/* —— 流水列：每列一个阶段色（浅底分区）—— */
.bcol {
  border-radius: var(--r-lg); padding: var(--s-sm); min-width: 172px;
  display: flex; flex-direction: column; gap: var(--s-xs);
  border: 1px solid var(--c-border-light);
}
/* 阶段色浅底：蓝→粉→橙→红→紫→青→绿，形成流水线色阶 */
.bcol--info    { background: var(--c-info-bg);    --tone: var(--c-info-fg); }
.bcol--brand   { background: var(--c-brand-soft); --tone: var(--c-brand); }
.bcol--warning { background: var(--c-warning-bg); --tone: var(--c-warning-fg); }
.bcol--danger  { background: var(--c-danger-bg);  --tone: var(--c-danger-fg); }
.bcol--purple  { background: var(--c-purple-soft); --tone: var(--c-purple); }
.bcol--teal    { background: var(--c-teal-bg);    --tone: var(--c-teal-fg); }
.bcol--success { background: var(--c-success-bg); --tone: var(--c-success-fg); }

.bcol__head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-xs); }
.bcol__head-l { display: flex; align-items: center; gap: 6px; min-width: 0; }
.bcol__icon {
  width: 26px; height: 26px; border-radius: var(--r-md); flex-shrink: 0;
  background: var(--c-surface); color: var(--tone);
  display: inline-flex; align-items: center; justify-content: center;
}
.bcol__title { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 阶段计数：实心阶段色大徽标 */
.bcol__count {
  min-width: 24px; height: 22px; padding: 0 8px; border-radius: var(--r-pill); flex-shrink: 0;
  background: var(--tone); color: #fff; font-size: var(--t-xs); font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center;
}
.bcol__count.is-busy { background: var(--c-danger-fg); animation: bcol-pulse 1.6s ease-in-out infinite; }
@keyframes bcol-pulse { 0%,100% { box-shadow: 0 0 0 0 rgba(255,77,79,.35); } 50% { box-shadow: 0 0 0 4px rgba(255,77,79,0); } }

.bcol__action {
  align-self: flex-start; display: inline-flex; align-items: center; gap: 2px;
  border: none; background: none; cursor: pointer; padding: 0;
  font-size: var(--t-xs); font-weight: 600; color: var(--tone);
}
.bcol__action:hover { text-decoration: underline; }

/* 列体等高 + 超长独立滚动 */
.bcol__body {
  flex: 1; min-height: 0; display: flex; flex-direction: column; gap: 6px;
  max-height: calc(100vh - 300px); overflow-y: auto; padding-top: 2px;
}

.bcard {
  position: relative; text-align: left; width: 100%; cursor: pointer;
  background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--r-md);
  border-left: 3px solid var(--tone);
  padding: var(--s-xs) var(--s-sm); transition: border-color .15s, box-shadow .15s, transform .15s;
}
.bcard:hover { border-color: var(--tone); box-shadow: var(--shadow-card); transform: translateY(-1px); }
.bcard__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); padding-right: var(--s-md); }
.bcard__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; line-height: 1.4; }
.bcard__arrow { position: absolute; top: 50%; right: var(--s-xs); transform: translateY(-50%); color: var(--c-text-4); }

.bcol__empty {
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  font-size: var(--t-xs); color: var(--c-text-4); text-align: center; padding: var(--s-lg) 0;
  border: 1px dashed var(--c-border); border-radius: var(--r-md); background: rgba(255,255,255,.5);
}
.bcol__busy {
  margin-top: 2px; font-size: 10px; line-height: 1.4; text-align: center;
  color: var(--c-danger-fg); background: var(--c-danger-bg);
  border-radius: var(--r-sm); padding: 3px 6px; font-weight: 600;
}

@media (max-width: 1100px) {
  .board__cols { grid-template-columns: repeat(2, 1fr); }
  .bcol__body { max-height: none; overflow: visible; }
}
@media (max-width: 720px) {
  .board__cols { grid-template-columns: 1fr; }
  .board__intro { flex-direction: column; align-items: flex-start; }
}
</style>
