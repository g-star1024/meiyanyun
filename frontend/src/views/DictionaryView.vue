<script setup lang="ts">
/* ============================================================
 * 全站数据字典库查看页 /admin/dictionary
 * 只读展示所有字典枚举的定义，方便开发和运维快速查阅。
 * ============================================================ */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CInput from '@/components/CInput.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import * as dict from '@/config/dictionary'

const router = useRouter()

// 枚举分类元信息（顺序 + 中文名）
const DICT_SECTIONS: { key: string; label: string; domain: string }[] = [
  { key: 'CUSTOMER_STATUS', label: '客户状态', domain: '客户' },
  { key: 'CUSTOMER_LEVEL', label: '客户等级', domain: '客户' },
  { key: 'CUSTOMER_SOURCE', label: '客户来源', domain: '客户' },
  { key: 'CUSTOMER_TIER', label: '客户分层(NEW/C/B/A/KA)', domain: '客户' },
  { key: 'APPOINTMENT_STATUS', label: '预约状态', domain: '预约' },
  { key: 'ORDER_STATUS', label: '订单状态', domain: '订单' },
  { key: 'PAY_METHOD', label: '支付方式', domain: '订单' },
  { key: 'CONSULTATION_STATUS', label: '面诊/咨询状态', domain: '咨询' },
  { key: 'EMR_STATUS', label: '病历状态', domain: '病历' },
  { key: 'FOLLOWUP_STATUS', label: '回访状态', domain: '回访' },
  { key: 'FOLLOWUP_METHOD', label: '回访方式', domain: '回访' },
  { key: 'RECOVERY_STATUS', label: '恢复状态', domain: '回访' },
  { key: 'RECALL_STATUS', label: '复诊提醒状态', domain: '回访' },
  { key: 'FOLLOW_TASK_STATUS', label: '跟进任务状态', domain: '跟进' },
  { key: 'CARE_STATUS', label: '关怀状态', domain: '跟进' },
  { key: 'ACQUISITION_STATUS', label: '拓客活动状态', domain: '跟进' },
  { key: 'REACTIVATE_STATUS', label: '沉睡唤醒状态', domain: '跟进' },
  { key: 'REFERRAL_STATUS', label: '转介绍状态', domain: '跟进' },
  { key: 'REWARD_STATUS', label: '奖励状态', domain: '跟进' },
  { key: 'CHURN_STATUS', label: '流失预警状态', domain: '运营' },
  { key: 'RISK_LEVEL', label: '风险等级', domain: '运营' },
  { key: 'EXCEPTION_STATUS', label: '异常状态', domain: '运营' },
  { key: 'INSPECTION_STATUS', label: '巡店检查状态', domain: '运营' },
  { key: 'RECTIFY_STATUS', label: '整改状态', domain: '运营' },
  { key: 'CONTRACT_STATUS', label: '合同状态', domain: '合同' },
  { key: 'TRANSFER_STATUS', label: '资产转移状态', domain: '合同' },
  { key: 'TRANSFER_ASSET_TYPE', label: '资产类型', domain: '合同' },
  { key: 'REFUND_STATUS', label: '退款状态', domain: '财务' },
  { key: 'REFUND_CHANNEL', label: '退款渠道', domain: '财务' },
  { key: 'SETTLEMENT_STATUS', label: '结算状态', domain: '财务' },
  { key: 'RECONCILE_STATUS', label: '对账状态', domain: '财务' },
  { key: 'WRITEOFF_RECORD_STATUS', label: '核销记录状态', domain: '财务' },
  { key: 'WRITEOFF_DESK_STATUS', label: '划扣执行台状态', domain: '财务' },
  { key: 'CHECKIN_STATUS', label: '到店核销状态', domain: '财务' },
  { key: 'COMPLAINT_STATUS', label: '投诉状态', domain: '客诉' },
  { key: 'COMPLAINT_SEVERITY', label: '投诉严重度', domain: '客诉' },
  { key: 'COMPLAINT_SOURCE', label: '投诉来源', domain: '客诉' },
  { key: 'COMPLAINT_CATEGORY', label: '投诉类别', domain: '客诉' },
  { key: 'APPROVAL_STATUS', label: '审批状态', domain: '审批' },
  { key: 'PRODUCT_STATUS', label: '商品状态', domain: '积分' },
  { key: 'REDEMPTION_STATUS', label: '兑换状态', domain: '积分' },
  { key: 'WORK_ORDER_STATUS', label: '工单状态', domain: '工单' },
  { key: 'WORK_ORDER_PRIORITY', label: '工单优先级', domain: '工单' },
  { key: 'INVENTORY_STATUS', label: '库存状态', domain: '库存' },
  { key: 'COUPON_STATUS', label: '优惠券状态', domain: '营销' },
  { key: 'PUSH_CHANNEL', label: '推送渠道', domain: '营销' },
  { key: 'NOTIFICATION_CATEGORY', label: '通知分类', domain: '通知' },
  { key: 'NOTIFICATION_LEVEL', label: '通知级别', domain: '通知' },
  { key: 'HELP_TYPE', label: '帮助类型', domain: '帮助' },
  { key: 'HANDOVER_STATUS', label: '交接班状态', domain: '交接' },
  { key: 'AI_CAPABILITY_STATUS', label: 'AI 能力状态', domain: 'AI' },
]

type DictEntry = { value: string; label: string; color?: string; icon?: string }

const keyword = ref('')

const sections = computed(() => {
  const kw = keyword.value.trim()
  return DICT_SECTIONS
    .map((s) => {
      const raw = (dict as any)[s.key] as Record<string, DictEntry> | undefined
      if (!raw) return null
      const entries = Object.values(raw)
      const filtered = kw
        ? entries.filter((e) =>
            e.label.includes(kw) || e.value.toLowerCase().includes(kw.toLowerCase()) ||
            (e.color && e.color.includes(kw)),
          )
        : entries
      if (kw && filtered.length === 0) return null
      return { ...s, entries: kw ? filtered : entries }
    })
    .filter(Boolean) as (typeof DICT_SECTIONS[number] & { entries: DictEntry[] })[]
})

const totalCount = computed(() => sections.value.reduce((sum, s) => sum + s.entries.length, 0))
const domainCount = computed(() => new Set(sections.value.map((s) => s.domain)).size)

const colorMap: Record<string, string> = {
  success: '#52c41a', warning: '#faad14', danger: '#ff4d4f',
  info: '#1890ff', primary: '#4c7dff', default: '#8c8c8c',
  brand: '#ff6b9d', teal: '#13c2c2', purple: '#8c5cf5',
  disabled: '#bfbfbf', draft: '#d9d9d9',
}

function pillStyle(entry: DictEntry) {
  const c = entry.color ? colorMap[entry.color] || '#8c8c8c' : '#8c8c8c'
  return {
    bg: c + '18',
    fg: c,
    border: c + '40',
  }
}
</script>

<template>
  <div class="dict-page">
    <!-- 页头 -->
    <div class="dict-head">
      <div class="dict-head__text">
        <h2 class="dict-title">
          <CIcon name="layers" :size="22" />
          全站数据字典库
        </h2>
        <p class="dict-subtitle">
          {{ sections.length }} 类枚举 · {{ totalCount }} 条定义 · {{ domainCount }} 个业务域
        </p>
      </div>
      <div class="dict-head__action">
        <CInput v-model="keyword" placeholder="搜索枚举名 / 值 / 颜色" width="260px" />
        <CButton variant="primary" @click="router.push('/admin/dictionary/manage')">
          <CIcon name="settings" :size="16" />
          字典管理
        </CButton>
      </div>
    </div>

    <!-- 分类列表 -->
    <div class="dict-grid">
      <CCard v-for="sec in sections" :key="sec.key" padding="lg" class="dict-card">
        <template #header>
          <div class="dict-card__head">
            <div>
              <h3 class="dict-card__title">{{ sec.label }}</h3>
              <span class="dict-card__meta">{{ sec.key }} · {{ sec.domain }}域</span>
            </div>
            <span class="dict-card__count">{{ sec.entries.length }}</span>
          </div>
        </template>
        <table class="dict-table">
          <thead>
            <tr>
              <th class="col-val">枚举值 (value)</th>
              <th class="col-label">显示名 (label)</th>
              <th class="col-color">颜色 (color)</th>
              <th class="col-preview">预览</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="entry in sec.entries" :key="entry.value">
              <td class="col-val"><code class="mono">{{ entry.value }}</code></td>
              <td class="col-label">{{ entry.label }}</td>
              <td class="col-color">
                <span v-if="entry.color" class="color-chip" :style="{ background: colorMap[entry.color] || '#8c8c8c' }" />
                <code v-if="entry.color" class="mono">{{ entry.color }}</code>
                <span v-else-if="entry.icon" class="icon-chip"><CIcon :name="(entry.icon as any)" :size="14" /> {{ entry.icon }}</span>
                <span v-else class="muted">—</span>
              </td>
              <td class="col-preview">
                <span
                  v-if="entry.color"
                  class="pill-preview"
                  :style="{ background: pillStyle(entry).bg, color: pillStyle(entry).fg, borderColor: pillStyle(entry).border }"
                >{{ entry.label }}</span>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.dict-page { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }

.dict-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-lg);
}
.dict-head__action {
  display: flex; align-items: center; gap: var(--s-sm);
  flex-shrink: 0;
}
.dict-title {
  display: flex; align-items: center; gap: var(--s-sm);
  font-size: var(--t-lg); font-weight: 700; margin: 0;
}
.dict-subtitle { font-size: var(--t-xs); color: var(--c-text-3); margin: var(--s-xs) 0 0 calc(22px + var(--s-sm)); }

.dict-grid { display: flex; flex-direction: column; gap: var(--s-md); }

.dict-card__head { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.dict-card__title { font-size: var(--t-md); font-weight: 600; margin: 0; }
.dict-card__meta { font-size: var(--t-xs); color: var(--c-text-3); }
.dict-card__count {
  font-size: 11px; font-weight: 600; color: var(--c-brand);
  background: var(--c-brand-soft); padding: 2px 8px; border-radius: 10px;
}

.dict-table { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.dict-table th {
  text-align: left; padding: var(--s-sm) var(--s-xs);
  font-weight: 500; color: var(--c-text-3); font-size: var(--t-xs);
  border-bottom: 1px solid var(--c-border);
}
.dict-table td {
  padding: var(--s-sm) var(--s-xs); border-bottom: 1px solid var(--c-border-light);
  vertical-align: middle;
}
.dict-table tr:last-child td { border-bottom: none; }
.dict-table tr:hover td { background: var(--c-bg-hover, rgba(0,0,0,.02)); }

.col-val { width: 180px; }
.col-label { width: 140px; }
.col-color { width: 140px; }
.col-preview { width: 100px; }

.mono {
  font-family: 'SF Mono', 'Fira Code', monospace; font-size: 12px;
  background: var(--c-bg-right, #f5f5f5); padding: 1px 6px; border-radius: 4px;
}

.color-chip {
  display: inline-block; width: 14px; height: 14px; border-radius: 3px;
  vertical-align: middle; margin-right: 6px;
}
.icon-chip { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--c-text-2); }

.pill-preview {
  display: inline-block; padding: 1px 10px; border-radius: 10px;
  font-size: 12px; font-weight: 500; border: 1px solid;
  line-height: 1.6;
}

.muted { color: var(--c-text-3); }
</style>
