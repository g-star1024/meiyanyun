<script setup lang="ts">
/* ============================================================
 * M5-07 渠道管理 /m5-channel
 * 4 KPI（已接入渠道/本月线索/本月成交/渠道佣金支出）
 * 渠道卡片列表 + 编辑弹层 + 业绩归集 + 对账状态
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CInput from '@/components/CInput.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import {
  useM5ChannelStore,
  CHANNEL_TYPE_PILL,
  RECONCILE_PILL,
  type ChannelRow,
} from '@/stores/m5Channel'
import type { ChannelKey } from '@/stores/m5Core'

const store = useM5ChannelStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '已接入渠道', icon: 'marketing', value: `${store.connectedCount}/7`, tone: 'brand' as const },
  { label: '本月线索', icon: 'customer', value: store.monthlyLeads.toLocaleString('zh-CN'), tone: 'text' as const },
  { label: '本月成交', icon: 'finance', value: store.monthlyDeals.toLocaleString('zh-CN'), tone: 'teal' as const },
  { label: '渠道佣金支出', icon: 'marketing', value: `¥${(store.commissionTotal / 10000).toFixed(1)}万`, tone: 'orange' as const },
])

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN')}`
}

// 编辑弹层
const showEdit = ref(false)
const editing = reactive<{
  key: ChannelKey | null
  name: string
  commissionRate: number
  settlementDay: number
  apiKey: string
}>({
  key: null,
  name: '',
  commissionRate: 0,
  settlementDay: 1,
  apiKey: '',
})
const editError = ref('')

function openEdit(row: ChannelRow) {
  editing.key = row.key
  editing.name = row.name
  editing.commissionRate = Math.round(row.commissionRate * 100)
  editing.settlementDay = row.settlementDay
  editing.apiKey = row.apiKey && row.apiKey !== '—' ? row.apiKey : ''
  editError.value = ''
  showEdit.value = true
}

function saveEdit() {
  if (!editing.key) return
  if (editing.commissionRate < 0 || editing.commissionRate > 100) {
    editError.value = '佣金费率需在 0~100 之间'
    return
  }
  if (editing.settlementDay < 1 || editing.settlementDay > 28) {
    editError.value = '对账日需在 1~28 之间'
    return
  }
  store.updateChannelConfig(editing.key, {
    name: editing.name.trim() || undefined,
    commissionRate: editing.commissionRate / 100,
    settlementDay: editing.settlementDay,
    apiKey: editing.apiKey.trim(),
  })
  showEdit.value = false
}

function connect(row: ChannelRow) {
  store.connectChannel(row.key)
}
</script>

<template>
  <div class="ch">
    <div class="ch__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- 渠道卡片网格 -->
    <div class="ch__bar">
      <span class="ch__bar-title">渠道接入</span>
      <CButton variant="primary" size="sm" v-perm.disable="'channel:edit'">
        <CIcon name="plus" :size="14" />接入渠道
      </CButton>
    </div>
    <div class="ch__grid">
      <CCard
        v-for="r in store.rows"
        :key="r.key"
        class="ch-card"
        padding="lg"
      >
        <div class="ch-card__head">
          <div class="ch-card__title">
            <span class="ch-card__dot" :class="`ch-card__dot--${r.key}`" />
            <span class="ch-card__name">{{ r.name }}</span>
          </div>
          <CStatusPill :status="r.connected ? 'success' : 'disabled'" dot>
            {{ r.connected ? '已接入' : '未接入' }}
          </CStatusPill>
        </div>

        <div class="ch-card__tags">
          <CStatusPill :status="CHANNEL_TYPE_PILL[r.type]">{{ r.typeLabel }}</CStatusPill>
          <CStatusPill v-if="r.connected" :status="RECONCILE_PILL[r.reconcile]" dot>
            {{ r.reconcileLabel }}
          </CStatusPill>
        </div>

        <div class="ch-card__stats">
          <div class="ch-stat">
            <span class="ch-stat__label">广告成本</span>
            <span class="ch-stat__value">{{ r.adCost ? money(r.adCost) : '—' }}</span>
          </div>
          <div class="ch-stat">
            <span class="ch-stat__label">线索 / 成交</span>
            <span class="ch-stat__value">{{ r.leads }} / {{ r.deals }}</span>
          </div>
          <div class="ch-stat">
            <span class="ch-stat__label">营收</span>
            <span class="ch-stat__value ch-stat__value--brand">{{ money(r.revenue) }}</span>
          </div>
          <div class="ch-stat">
            <span class="ch-stat__label">佣金费率</span>
            <span class="ch-stat__value">{{ (r.commissionRate * 100).toFixed(0) }}%</span>
          </div>
        </div>

        <div v-if="r.connected" class="ch-card__conv">
          <div class="ch-card__conv-head">
            <span>线索→成交转化</span>
            <span class="ch-card__conv-pct">{{ r.conversion }}%</span>
          </div>
          <CProgressBar
            :value="r.conversion"
            :max="60"
            :height="6"
            :show-label="false"
            color="var(--c-teal)"
          />
        </div>

        <div class="ch-card__foot">
          <span class="ch-card__meta">每月 {{ r.settlementDay }} 日对账</span>
          <div class="ch-card__actions">
            <CButton
              v-if="!r.connected"
              variant="primary"
              size="sm"
              v-perm.disable="'channel:edit'"
              @click="connect(r)"
            >
              <CIcon name="plus" :size="14" />去接入
            </CButton>
            <CButton
              v-else
              variant="secondary"
              size="sm"
              v-perm.disable="'channel:edit'"
              @click="openEdit(r)"
            >
              <CIcon name="settings" :size="14" />编辑
            </CButton>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 业绩归集 + 对账状态 -->
    <div class="ch__bottom">
      <CCard class="ch__collect" title="业绩归集（线索→成交转化）" padding="lg">
        <div class="ch-collect">
          <div class="ch-collect__head">
            <span class="ch-collect__c">渠道</span>
            <span class="ch-collect__c">线索</span>
            <span class="ch-collect__c">成交</span>
            <span class="ch-collect__c">转化率</span>
            <span class="ch-collect__c">营收</span>
          </div>
          <div v-for="r in store.collectionRows" :key="r.key" class="ch-collect__row">
            <span class="ch-collect__c ch-collect__name">
              <span class="ch-card__dot" :class="`ch-card__dot--${r.key}`" />
              {{ r.name }}
            </span>
            <span class="ch-collect__c">{{ r.leads }}</span>
            <span class="ch-collect__c">{{ r.deals }}</span>
            <span class="ch-collect__c ch-collect__pct">{{ r.conversion }}%</span>
            <span class="ch-collect__c ch-collect__amt">{{ money(r.revenue) }}</span>
          </div>
        </div>
      </CCard>

      <CCard class="ch__reconcile" title="对账状态（与 M6-03 财务对账联动）" padding="lg">
        <div class="ch-rec">
          <div class="ch-rec__summary">
            <div class="ch-rec__item ch-rec__item--ok">
              <div class="ch-rec__num">{{ store.syncedCount }}</div>
              <div class="ch-rec__label">已同步</div>
            </div>
            <div class="ch-rec__item ch-rec__item--warn">
              <div class="ch-rec__num">{{ store.pendingCount }}</div>
              <div class="ch-rec__label">待对账</div>
            </div>
            <div class="ch-rec__item">
              <div class="ch-rec__num">{{ 7 - store.connectedCount }}</div>
              <div class="ch-rec__label">未接入</div>
            </div>
          </div>
          <div class="ch-rec__list">
            <div v-for="r in store.rows.filter(x => x.connected)" :key="r.key" class="ch-rec__row">
              <span class="ch-rec__name">{{ r.name }}</span>
              <CStatusPill :status="RECONCILE_PILL[r.reconcile]" dot>{{ r.reconcileLabel }}</CStatusPill>
              <span class="ch-rec__day">每月 {{ r.settlementDay }} 日</span>
            </div>
          </div>
          <div class="ch-rec__hint">
            <CIcon name="alert" :size="14" />
            <span>待对账渠道将在 M6-03 结算台生成对账任务，请财务确认后核销。</span>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 编辑弹层 -->
    <div v-if="showEdit" class="modal-mask" @click.self="showEdit = false">
      <CCard class="modal modal--md" title="编辑渠道" padding="lg">
        <div class="ch-form">
          <CInput
            label="渠道名称"
            :model-value="editing.name"
            @update:model-value="editing.name = $event"
            placeholder="如：美团"
          />
          <CInput
            label="佣金费率（%）"
            type="number"
            :model-value="String(editing.commissionRate)"
            @update:model-value="editing.commissionRate = Number($event) || 0"
            placeholder="0~100"
          />
          <CInput
            label="对账周期（每月几日，1~28）"
            type="number"
            :model-value="String(editing.settlementDay)"
            @update:model-value="editing.settlementDay = Number($event) || 1"
            placeholder="如：5"
          />
          <CInput
            label="接入密钥（演示）"
            :model-value="editing.apiKey"
            @update:model-value="editing.apiKey = $event"
            placeholder="留空则自动生成"
          />
          <div v-if="editError" class="ch-form__err">
            <CIcon name="alert" :size="14" />{{ editError }}
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showEdit = false">取消</CButton>
          <CButton variant="primary" v-perm.disable="'channel:edit'" @click="saveEdit">保存</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ch { display: flex; flex-direction: column; gap: var(--s-lg); }
.ch__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ch__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.ch__bar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); flex-wrap: wrap; }
.ch__bar-title { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
:deep(.ckpi) { min-width: 0; }

/* 卡片网格 */
.ch__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--s-md);
}
.ch-card { min-width: 0; display: flex; flex-direction: column; }
.ch-card :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); }
.ch-card__head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); }
.ch-card__title { display: flex; align-items: center; gap: var(--s-xs); min-width: 0; }
.ch-card__dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.ch-card__dot--meituan { background: var(--c-series-4); }
.ch-card__dot--douyin { background: var(--c-series-1); }
.ch-card__dot--xiaohongshu { background: var(--c-series-6); }
.ch-card__dot--dianping { background: var(--c-series-3); }
.ch-card__dot--xinyang { background: var(--c-series-5); }
.ch-card__dot--referral { background: var(--c-series-7); }
.ch-card__dot--wecom { background: var(--c-series-8); }
.ch-card__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.ch-card__tags { display: flex; gap: var(--s-xs); flex-wrap: wrap; }

.ch-card__stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-sm);
  background: var(--c-bg-right);
  border-radius: var(--r-md);
  padding: var(--s-sm) var(--s-md);
}
.ch-stat { display: flex; flex-direction: column; gap: 2px; }
.ch-stat__label { font-size: var(--t-xs); color: var(--c-text-3); }
.ch-stat__value { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.ch-stat__value--brand { color: var(--c-brand); }

.ch-card__conv { display: flex; flex-direction: column; gap: var(--s-xs); }
.ch-card__conv-head { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.ch-card__conv-pct { color: var(--c-teal-dark); font-weight: 700; font-variant-numeric: tabular-nums; }

.ch-card__foot {
  display: flex; align-items: center; justify-content: space-between;
  gap: var(--s-sm); padding-top: var(--s-xs); border-top: 1px solid var(--c-border-light);
}
.ch-card__meta { font-size: var(--t-xs); color: var(--c-text-3); }
.ch-card__actions { display: flex; gap: var(--s-xs); }

/* 底部 */
.ch__bottom { display: grid; grid-template-columns: 1.3fr 1fr; gap: var(--s-lg); align-items: start; }
.ch__collect, .ch__reconcile { min-width: 0; }

.ch-collect { display: flex; flex-direction: column; gap: 0; }
.ch-collect__head, .ch-collect__row {
  display: grid;
  grid-template-columns: 1.4fr 0.8fr 0.8fr 1fr 1.2fr;
  align-items: center;
  padding: var(--s-sm) var(--s-sm);
  gap: var(--s-sm);
  font-size: var(--t-sm);
}
.ch-collect__head {
  background: var(--c-bg-page);
  color: var(--c-text-3);
  font-weight: 600;
  font-size: var(--t-xs);
  border-radius: var(--r-md);
  margin-bottom: var(--s-xs);
}
.ch-collect__row { border-bottom: 1px solid var(--c-border-light); }
.ch-collect__row:last-child { border-bottom: none; }
.ch-collect__c { text-align: right; color: var(--c-text-2); font-variant-numeric: tabular-nums; }
.ch-collect__name { display: flex; align-items: center; gap: var(--s-xs); text-align: left !important; color: var(--c-text); font-weight: 600; }
.ch-collect__pct { color: var(--c-teal-dark); font-weight: 700; }
.ch-collect__amt { color: var(--c-text); font-weight: 600; }

.ch-rec { display: flex; flex-direction: column; gap: var(--s-md); }
.ch-rec__summary { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-sm); }
.ch-rec__item {
  background: var(--c-bg-right); border-radius: var(--r-md);
  padding: var(--s-md); text-align: center;
}
.ch-rec__num { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.ch-rec__label { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.ch-rec__item--ok .ch-rec__num { color: var(--c-success-fg); }
.ch-rec__item--warn .ch-rec__num { color: var(--c-warning-fg); }

.ch-rec__list { display: flex; flex-direction: column; gap: var(--s-xs); }
.ch-rec__row {
  display: grid; grid-template-columns: 1fr auto auto;
  align-items: center; gap: var(--s-sm);
  padding: var(--s-xs) 0; border-bottom: 1px solid var(--c-border-light);
  font-size: var(--t-sm);
}
.ch-rec__row:last-child { border-bottom: none; }
.ch-rec__name { color: var(--c-text); }
.ch-rec__day { font-size: var(--t-xs); color: var(--c-text-3); }

.ch-rec__hint {
  display: flex; align-items: flex-start; gap: var(--s-xs);
  background: var(--c-warn-soft-bg); border-radius: var(--r-md);
  padding: var(--s-sm) var(--s-md); font-size: var(--t-xs); color: var(--c-text-2); line-height: var(--lh-sm);
}
.ch-rec__hint :deep(svg) { color: var(--c-warning-fg); margin-top: 2px; flex-shrink: 0; }

/* modal */
.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 440px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal--md { width: 460px; }
.ch-form { display: flex; flex-direction: column; gap: var(--s-md); }
.ch-form__err {
  display: flex; align-items: center; gap: var(--s-xs);
  color: var(--c-danger-fg); font-size: var(--t-xs);
}

@media (max-width: 1024px) {
  .ch__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .ch__bottom { grid-template-columns: 1fr; }
}
</style>
