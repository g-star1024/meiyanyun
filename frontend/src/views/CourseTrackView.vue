<script setup lang="ts">
/* ============================================================
 * 疗程跟踪（/course-track）
 * 消费 asset store 的 TimesAsset：疗程进度、剩余/总次数、有效期、核销记录、状态。
 * 按状态筛选（全部/进行中/即将到期/已用完）+ 客户搜索。
 * 权限：course:track（查看）；操作（预约下次）复用 appointment:create。
 * ============================================================ */
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAssetStore } from '@/stores/asset'
import { useCustomerStore } from '@/stores/customer'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import CIcon from '@/components/CIcon.vue'
import type { TimesAsset } from '@/types/domain'

const asset = useAssetStore()
const customer = useCustomerStore()
const router = useRouter()

onMounted(() => asset.seed())

type Filter = 'ALL' | 'ACTIVE' | 'EXPIRING' | 'FINISHED'
const filter = ref<Filter>('ALL')
const keyword = ref('')

/** 即将到期阈值：30 天内 */
const EXPIRING_DAYS = 30
function daysLeft(iso?: string): number | null {
  if (!iso) return null
  return Math.ceil((new Date(iso).getTime() - Date.now()) / 86400000)
}

interface TrackRow extends TimesAsset {
  customerName: string
  customerPhone: string
  usedTimes: number
  daysLeft: number | null
  trackStatus: 'ACTIVE' | 'EXPIRING' | 'FINISHED' | 'FROZEN'
  lastConsumeAt?: string
}

const rows = computed<TrackRow[]>(() => {
  const kw = keyword.value.trim()
  return asset.timesAssets
    .map((a) => {
      const c = customer.get(a.customerId)
      const consumeTxns = asset.txns
        .filter((t) => t.assetId === a.id && t.kind === 'CONSUME')
        .sort((x, y) => +new Date(y.at) - +new Date(x.at))
      const trackStatus: TrackRow['trackStatus'] =
        a.status === 'FINISHED' ? 'FINISHED'
        : a.status === 'FROZEN' ? 'FROZEN'
        : (() => {
            const d = daysLeft(a.expiresAt)
            return d !== null && d <= EXPIRING_DAYS ? 'EXPIRING' : 'ACTIVE'
          })()
      return {
        ...a,
        customerName: c?.name || a.customerId,
        customerPhone: c?.phoneMask || '',
        usedTimes: a.totalTimes - a.remainingTimes,
        daysLeft: daysLeft(a.expiresAt),
        trackStatus,
        lastConsumeAt: consumeTxns[0]?.at,
      }
    })
    .filter((r) => {
      if (filter.value === 'ACTIVE') return r.trackStatus === 'ACTIVE'
      if (filter.value === 'EXPIRING') return r.trackStatus === 'EXPIRING'
      if (filter.value === 'FINISHED') return r.trackStatus === 'FINISHED'
      return true
    })
    .filter((r) => !kw || r.customerName.includes(kw) || r.itemName.includes(kw) || r.customerPhone.includes(kw))
    .sort((a, b) => {
      // 即将到期优先，再按剩余次数升序（快用完的在前）
      const order = { EXPIRING: 0, ACTIVE: 1, FROZEN: 2, FINISHED: 3 }
      if (order[a.trackStatus] !== order[b.trackStatus]) return order[a.trackStatus] - order[b.trackStatus]
      return a.remainingTimes - b.remainingTimes
    })
})

const kpi = computed(() => {
  const all = asset.timesAssets
  return {
    active: all.filter((a) => a.status === 'ACTIVE').length,
    expiring: all.filter((a) => {
      if (a.status !== 'ACTIVE') return false
      const d = daysLeft(a.expiresAt)
      return d !== null && d <= EXPIRING_DAYS
    }).length,
    finished: all.filter((a) => a.status === 'FINISHED').length,
    totalRemaining: all.reduce((s, a) => s + (a.status === 'ACTIVE' ? a.remainingTimes : 0), 0),
  }
})

const FILTERS: { k: Filter; label: string }[] = [
  { k: 'ALL', label: '全部' },
  { k: 'ACTIVE', label: '进行中' },
  { k: 'EXPIRING', label: `即将到期（${EXPIRING_DAYS}天）` },
  { k: 'FINISHED', label: '已用完' },
]

function fmtDate(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`
}

const selected = ref<TrackRow | null>(null)
function viewDetail(r: TrackRow) { selected.value = r }
function closeDetail() { selected.value = null }
function goAppointment() {
  if (!selected.value) return
  router.push(`/appointment/new?customerId=${selected.value.customerId}`)
}
</script>

<template>
  <div class="ct-page">
    <div class="ct-kpis">
      <CKpi label="进行中疗程" :value="String(kpi.active)" tone="brand" icon="card" />
      <CKpi :label="`即将到期（${EXPIRING_DAYS}天内）`" :value="String(kpi.expiring)" :tone="kpi.expiring ? 'warning' : 'text'" icon="clock" />
      <CKpi label="已用完疗程" :value="String(kpi.finished)" tone="text" icon="card" />
      <CKpi label="剩余总次数" :value="String(kpi.totalRemaining)" tone="teal" icon="card" />
    </div>

    <CCard class="ct-list-card">
      <div class="ct-toolbar">
        <div class="ct-filters">
          <button
            v-for="f in FILTERS"
            :key="f.k"
            class="ct-tab"
            :class="{ 'is-on': filter === f.k }"
            @click="filter = f.k"
          >{{ f.label }}</button>
        </div>
        <div class="ct-search">
          <CInput v-model="keyword" placeholder="搜客户 / 项目 / 手机号" clearable />
        </div>
      </div>

      <div class="ct-grid">
        <div v-for="r in rows" :key="r.id" class="ct-row" @click="viewDetail(r)">
          <div class="ct-row__main">
            <div class="ct-row__title">
              <span class="ct-row__name">{{ r.customerName }}</span>
              <span class="ct-row__proj">{{ r.itemName }}</span>
            </div>
            <div class="ct-row__meta">
              <span>{{ r.customerPhone }}</span>
              <span class="ct-dot">·</span>
              <span>到期 {{ fmtDate(r.expiresAt) }}</span>
              <template v-if="r.trackStatus === 'EXPIRING' && r.daysLeft !== null">
                <span class="ct-expiring">（剩 {{ r.daysLeft }} 天）</span>
              </template>
            </div>
          </div>

          <div class="ct-row__prog">
            <CProgressBar
              :value="r.usedTimes"
              :max="r.totalTimes"
              :label="`${r.usedTimes}/${r.totalTimes}`"
              :color="r.trackStatus === 'EXPIRING' ? 'var(--c-warning-fg)' : 'var(--c-brand)'"
            />
            <div class="ct-row__times">
              剩余 <strong :class="{ 'ct-times--warn': r.trackStatus === 'EXPIRING' }">{{ r.remainingTimes }}</strong> 次
            </div>
          </div>

          <div class="ct-row__status">
            <CStatusPill
              v-if="r.trackStatus === 'ACTIVE'"
              status="primary"
            >进行中</CStatusPill>
            <CStatusPill
              v-else-if="r.trackStatus === 'EXPIRING'"
              status="warning"
            >即将到期</CStatusPill>
            <CStatusPill
              v-else-if="r.trackStatus === 'FROZEN'"
              status="default"
            >已冻结</CStatusPill>
            <CStatusPill v-else status="default">已用完</CStatusPill>
          </div>
        </div>

        <div v-if="!rows.length" class="ct-empty">
          <CIcon name="card" :size="40" class="ct-empty__icon" />
          <p>没有符合条件的疗程记录</p>
        </div>
      </div>
    </CCard>

    <!-- 疗程详情弹层 -->
    <div v-if="selected" class="mask" @click.self="closeDetail">
      <CCard class="dlg" :title="selected.itemName">
        <div class="dlg__body">
          <div class="dlg__cust">
            <div class="dlg__avatar">{{ selected.customerName.charAt(0) }}</div>
            <div>
              <div class="dlg__name">{{ selected.customerName }}</div>
              <div class="dlg__sub">{{ selected.customerPhone }}</div>
            </div>
            <CStatusPill
              v-if="selected.trackStatus === 'ACTIVE'"
              status="primary"
            >进行中</CStatusPill>
            <CStatusPill v-else-if="selected.trackStatus === 'EXPIRING'" status="warning">即将到期</CStatusPill>
            <CStatusPill v-else-if="selected.trackStatus === 'FROZEN'" status="default">已冻结</CStatusPill>
            <CStatusPill v-else status="default">已用完</CStatusPill>
          </div>

          <div class="dlg__prog">
            <CProgressBar
              :value="selected.usedTimes"
              :max="selected.totalTimes"
              :label="`已用 ${selected.usedTimes} / 共 ${selected.totalTimes} 次`"
              :color="selected.trackStatus === 'EXPIRING' ? 'var(--c-warning-fg)' : 'var(--c-brand)'"
              :height="10"
            />
            <div class="dlg__times">
              <span>剩余 <strong>{{ selected.remainingTimes }}</strong> 次</span>
              <span>有效期至 {{ fmtDate(selected.expiresAt) }}</span>
            </div>
          </div>

          <div class="dlg__section-title">核销记录</div>
          <div class="dlg__txns">
            <div
              v-for="t in asset.txnsOf(selected.id).filter(x => x.kind === 'CONSUME')"
              :key="t.id"
              class="dlg__txn"
            >
              <span class="dlg__txn-time">{{ fmtDate(t.at) }}</span>
              <span class="dlg__txn-remark">{{ t.remark || '核销' }}</span>
              <span class="dlg__txn-op">{{ t.operatorName }}</span>
              <span class="dlg__txn-times">-{{ Math.abs(t.times || 1) }} 次</span>
            </div>
            <div v-if="!asset.txnsOf(selected.id).some(x => x.kind === 'CONSUME')" class="dlg__txn-empty">
              暂无核销记录
            </div>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="closeDetail">关闭</CButton>
          <CButton
            variant="primary"
            v-perm.disable="'appointment:create'"
            @click="goAppointment"
          >
            <CIcon name="calendar" :size="14" /> 预约下次到店
          </CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ct-page { display: flex; flex-direction: column; gap: var(--s-lg); }
.ct-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }

.ct-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); margin-bottom: var(--s-md); flex-wrap: wrap; }
.ct-filters { display: flex; gap: var(--s-xs); }
.ct-tab {
  padding: 6px 14px; border-radius: 999px; border: 1px solid var(--c-border);
  background: var(--c-surface); color: var(--c-text-2); font-size: var(--t-sm);
  cursor: pointer; transition: all 0.15s;
}
.ct-tab:hover { border-color: var(--c-brand); color: var(--c-brand); }
.ct-tab.is-on { background: var(--c-brand); border-color: var(--c-brand); color: #fff; }
.ct-search { width: 260px; }

.ct-grid { display: flex; flex-direction: column; gap: 1px; background: var(--c-border-light); border-radius: var(--r-md); overflow: hidden; }
.ct-row {
  display: grid; grid-template-columns: 1fr 280px 110px; gap: var(--s-lg);
  align-items: center; padding: var(--s-md) var(--s-lg);
  background: var(--c-surface); cursor: pointer; transition: background 0.12s;
}
.ct-row:hover { background: var(--c-brand-soft); }
.ct-row__title { display: flex; align-items: center; gap: var(--s-sm); margin-bottom: 4px; }
.ct-row__name { font-size: var(--t-base); font-weight: 600; color: var(--c-text); }
.ct-row__proj { font-size: var(--t-sm); color: var(--c-brand); background: var(--c-brand-soft); padding: 2px 8px; border-radius: var(--r-sm); }
.ct-row__meta { font-size: var(--t-xs); color: var(--c-text-3); display: flex; align-items: center; gap: 6px; }
.ct-dot { color: var(--c-text-4); }
.ct-expiring { color: var(--c-warning-fg); font-weight: 600; }
.ct-row__prog { display: flex; flex-direction: column; gap: 6px; }
.ct-row__times { font-size: var(--t-xs); color: var(--c-text-3); text-align: right; }
.ct-row__times strong { font-size: var(--t-base); color: var(--c-text); font-variant-numeric: tabular-nums; }
.ct-times--warn { color: var(--c-warning-fg) !important; }
.ct-row__status { display: flex; justify-content: flex-end; }

.ct-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: var(--s-xxl); color: var(--c-text-3); gap: var(--s-sm); background: var(--c-surface); }
.ct-empty__icon { color: var(--c-text-4); }
.ct-empty p { margin: 0; font-size: var(--t-sm); }

/* 弹层 */
.mask { position: fixed; inset: 0; background: rgba(0,0,0,0.32); display: flex; align-items: center; justify-content: center; z-index: 100; }
.dlg { width: 520px; }
.dlg__body { display: flex; flex-direction: column; gap: var(--s-md); padding: var(--s-sm) 0; }
.dlg__cust { display: flex; align-items: center; gap: var(--s-md); }
.dlg__avatar {
  width: 40px; height: 40px; border-radius: 50%;
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center;
  font-weight: 600; flex-shrink: 0;
}
.dlg__name { font-size: var(--t-base); font-weight: 600; color: var(--c-text); }
.dlg__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.dlg__cust > :last-child { margin-left: auto; }

.dlg__times { display: flex; justify-content: space-between; font-size: var(--t-sm); color: var(--c-text-2); margin-top: var(--s-xs); }
.dlg__times strong { font-size: var(--t-lg); color: var(--c-brand); font-variant-numeric: tabular-nums; }

.dlg__section-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); padding-top: var(--s-sm); border-top: 1px solid var(--c-border-light); }
.dlg__txns { display: flex; flex-direction: column; gap: 1px; background: var(--c-border-light); border-radius: var(--r-sm); overflow: hidden; max-height: 200px; overflow-y: auto; }
.dlg__txn {
  display: grid; grid-template-columns: 90px 1fr 100px 70px; gap: var(--s-sm);
  align-items: center; padding: var(--s-sm) var(--s-md); background: var(--c-surface);
  font-size: var(--t-sm);
}
.dlg__txn-time { color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.dlg__txn-remark { color: var(--c-text-2); }
.dlg__txn-op { color: var(--c-text-3); font-size: var(--t-xs); text-align: right; }
.dlg__txn-times { color: var(--c-danger-fg); font-weight: 600; text-align: right; font-variant-numeric: tabular-nums; }
.dlg__txn-empty { padding: var(--s-lg); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); background: var(--c-surface); }

/* Pad 堆叠 */
@media (max-width: 834px) {
  .ct-kpis { grid-template-columns: repeat(2, 1fr); }
  .ct-row { grid-template-columns: 1fr; gap: var(--s-sm); }
  .ct-row__status { justify-content: flex-start; }
  .ct-search { width: 100%; }
}
</style>
