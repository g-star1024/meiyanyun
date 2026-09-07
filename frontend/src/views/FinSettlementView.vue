<script setup lang="ts">
/* ============================================================
 * B7-2 日结/月结封账工作台 /m6-settlement
 * 封账 = 期间锁：封账后该「期间 × 门店」任何新分录（含经营域补录）一律 422 拒绝，
 * 差错只能走差异调平 ADJUST（落当前期间）。封账永久、无解封（资金安全红线）。
 * 期间口径与 fund_entry 一致：occurredAt 按 UTC 归一（日结 UTC 半开区间）。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CInput from '@/components/CInput.vue'
import { useAuthStore } from '@/stores/auth'
import { useStoreContext } from '@/stores/storeContext'
import { useFinCarryStore } from '@/stores/finCarry'
import { getSettlements, postSettlement, exportSettlementCsv, type SettlementPeriod } from '@/api/finance'

const auth = useAuthStore()
const storeCtx = useStoreContext()
const carry = useFinCarryStore()
const router = useRouter()
const canEdit = computed(() => auth.can('finance:settlement:edit'))

const loading = ref(false)
const error = ref('')
const rows = ref<SettlementPeriod[]>([])

const filterType = ref<'ALL' | 'DAY' | 'MONTH'>('ALL')
const filterStore = ref('')

const storeOptions = computed(() => [
  { label: '全部门店（有权限）', value: '' },
  ...storeCtx.stores.map((s) => ({ label: s.storeName || s.storeCode, value: s.storeCode })),
])

function todayShanghai(): string {
  const now = new Date()
  const utc = now.getTime() + now.getTimezoneOffset() * 60000
  return new Date(utc + 8 * 3600000).toISOString().slice(0, 10)
}
function monthShanghai(): string {
  return todayShanghai().slice(0, 7)
}

function yuan(fen: number | null | undefined): string {
  if (fen == null) return '—'
  return `¥${(fen / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

async function load() {
  if (loading.value) return
  if (!storeCtx.loaded) {
    try { await storeCtx.loadStores() } catch { /* 门店名回落编码 */ }
  }
  loading.value = true
  error.value = ''
  try {
    const params: { periodType?: 'DAY' | 'MONTH'; storeCode?: string } = {}
    if (filterType.value !== 'ALL') params.periodType = filterType.value
    if (filterStore.value) params.storeCode = filterStore.value
    const { data } = await getSettlements(params)
    rows.value = data
  } catch (e) {
    rows.value = []
    error.value = e instanceof Error ? e.message : '封账台账查询失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)

// ==================== B11 本月待结转提示（DESIGN §4.4） ====================
// 封账前应先执行月结成本结转；此提示只读查询 carry/pending，失败诚实降级不阻断封账页。
const carryPending = ref<{ count: number; fen: number } | null>(null)
async function loadCarryPending() {
  try {
    const r = await carry.pending(`${monthShanghai()}-01`, filterStore.value || undefined)
    carryPending.value = { count: r.pendingCount, fen: r.pendingFen }
  } catch {
    carryPending.value = null
  }
}
watch(filterStore, () => { void loadCarryPending() })
onMounted(loadCarryPending)

const exporting = ref(false)
async function doExport() {
  if (exporting.value) return
  exporting.value = true
  try {
    const params: { periodType?: 'DAY' | 'MONTH'; storeCode?: string } = {}
    if (filterType.value !== 'ALL') params.periodType = filterType.value
    if (filterStore.value) params.storeCode = filterStore.value
    await exportSettlementCsv(params)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '封账台账导出失败'
  } finally {
    exporting.value = false
  }
}

const kpis = computed(() => [
  { label: '已封账期间数', icon: 'shield', value: `${rows.value.length} 个`, tone: 'brand' as const },
  { label: '日结（DAY）', icon: 'finance', value: `${rows.value.filter((r) => r.periodType === 'DAY').length} 天`, tone: 'teal' as const },
  { label: '月结（MONTH）', icon: 'finance', value: `${rows.value.filter((r) => r.periodType === 'MONTH').length} 月`, tone: 'orange' as const },
  { label: '封账时点分录合计', icon: 'check', value: `${rows.value.reduce((s, r) => s + r.entryCount, 0)} 笔`, tone: 'text' as const },
])

// ==================== 封账操作 ====================
const showClose = ref(false)
const closeForm = ref({ periodType: 'DAY' as 'DAY' | 'MONTH', date: todayShanghai(), month: monthShanghai(), storeCode: '', memo: '' })
const closeAck = ref(false)
const closing = ref(false)
const closeError = ref('')

function openClose() {
  closeForm.value = { periodType: 'DAY', date: todayShanghai(), month: monthShanghai(), storeCode: '', memo: '' }
  closeAck.value = false
  closeError.value = ''
  showClose.value = true
}

const periodLabel = computed(() => {
  const f = closeForm.value
  if (f.periodType === 'DAY') return f.date || '—'
  return f.month || '—'
})
const canSubmitClose = computed(() => {
  const f = closeForm.value
  if (closing.value) return false
  if (!f.storeCode) return false
  if (f.periodType === 'DAY' ? !f.date : !f.month) return false
  return closeAck.value
})

async function submitClose() {
  if (!canSubmitClose.value) return
  closing.value = true
  closeError.value = ''
  try {
    const { data } = await postSettlement({
      periodType: closeForm.value.periodType,
      periodKey: closeForm.value.periodType === 'DAY' ? closeForm.value.date : closeForm.value.month,
      storeCode: closeForm.value.storeCode,
      memo: closeForm.value.memo.trim() || undefined,
    })
    if (data.duplicated) {
      error.value = data.message || '该期间已封账，幂等返回'
    } else {
      error.value = ''
    }
    showClose.value = false
    await load()
  } catch (e) {
    closeError.value = e instanceof Error ? e.message : '封账失败'
  } finally {
    closing.value = false
  }
}

function fmtTime(iso: string): string {
  if (!iso) return '—'
  return iso.slice(0, 16).replace('T', ' ')
}
</script>

<template>
  <div class="st">
    <div class="st__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- 红线说明卡 -->
    <CCard class="st__rule" padding="lg">
      <div class="rule">
        <div class="rule__head">
          <div class="rule__title"><CIcon name="shield" :size="16" /> 日结 / 月结封账（期间锁）</div>
        </div>
        <p class="rule__note">
          封账后，该「期间 × 门店」任何发生时间落在期间内的资金分录将被系统 <b>一律拒绝（含经营域补录/重投）</b>；
          封账为<b>永久操作、不可解封、不可篡改</b>。对账发现的差错请走「差异调平 ADJUST」，调平分录计入当前期间，不影响已封账数据。
        </p>
        <p class="rule__note">期间口径与资金分录一致，按 UTC 归一：日结为 UTC 当日 0 点至次日 0 点半开区间，月结为 UTC 当月。</p>
      </div>
    </CCard>

    <!-- 错误提示 -->
    <CCard v-if="error" class="st__err" padding="md">
      <div class="err"><CIcon name="alert" :size="15" /> {{ error }}</div>
    </CCard>

    <CCard class="st__list" padding="none">
      <div class="filters">
        <CSelect
          v-model="filterType"
          :options="[
            { value: 'ALL', label: '全部类型' },
            { value: 'DAY', label: '日结（DAY）' },
            { value: 'MONTH', label: '月结（MONTH）' },
          ]"
        />
        <CSelect v-model="filterStore" :options="storeOptions" />
        <div class="filters__right">
          <button
            v-if="carryPending && carryPending.count > 0"
            type="button" class="cp-warn" @click="router.push('/m6-cost')"
            title="点击前往成本分析页执行期末结转"
          >
            <CIcon name="alert" :size="14" />
            <span>本月 {{ carryPending.count }} 项成本结转未执行（约 {{ yuan(carryPending.fen) }}），封账前请先结转 →</span>
          </button>
          <CButton variant="secondary" size="sm" @click="load">
            <CIcon name="refresh" :size="14" />刷新
          </CButton>
          <CButton variant="secondary" size="sm" :disabled="exporting" @click="doExport">
            <CIcon name="export" :size="14" />导出 CSV
          </CButton>
          <CButton v-if="canEdit" variant="primary" size="sm" @click="openClose">
            <CIcon name="shield" :size="14" />发起封账
          </CButton>
        </div>
      </div>

      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th>类型</th>
              <th>期间</th>
              <th>门店</th>
              <th class="ta-r">封账时点分录</th>
              <th class="ta-r">净额快照（分，IN正/OUT负）</th>
              <th>状态</th>
              <th>封账人</th>
              <th>封账时间</th>
              <th>备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="9" class="ta-c muted">加载中…</td>
            </tr>
            <tr v-else-if="rows.length === 0">
              <td colspan="9" class="ta-c muted">
                <CIcon name="finance" :size="22" class="empty__icon" />
                <div>暂无封账记录；确认当日/当月对账一致后，点击右上角「发起封账」</div>
              </td>
            </tr>
            <tr v-for="r in rows" :key="r.settlementId">
              <td>
                <CStatusPill :status="r.periodType === 'DAY' ? 'info' : 'primary'" dot>
                  {{ r.periodType === 'DAY' ? '日结' : '月结' }}
                </CStatusPill>
              </td>
              <td class="mono">{{ r.periodKey }}</td>
              <td>{{ r.storeName }}<span class="sub">（{{ r.storeCode }}）</span></td>
              <td class="ta-r mono">{{ r.entryCount }} 笔</td>
              <td class="ta-r mono" :class="{ 'amt--neg': r.netAmountFen < 0 }">{{ yuan(r.netAmountFen) }}</td>
              <td><CStatusPill status="success" dot>已封账</CStatusPill></td>
              <td>{{ r.closedBy }}</td>
              <td class="mono">{{ fmtTime(r.closedAt) }}</td>
              <td class="muted">{{ r.memo || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </CCard>

    <!-- 封账强确认弹层 -->
    <div v-if="showClose" class="modal-mask" @click.self="showClose = false">
      <CCard class="modal" title="发起封账（日结 / 月结）" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">封账类型 <span class="req">*</span></label>
            <CSelect
              v-model="closeForm.periodType"
              :options="[
                { value: 'DAY', label: '日结（锁定某一天）' },
                { value: 'MONTH', label: '月结（锁定整月）' },
              ]"
            />
          </div>
          <div class="form__row">
            <label class="form__label">封账期间 <span class="req">*</span></label>
            <input
              v-if="closeForm.periodType === 'DAY'"
              v-model="closeForm.date"
              type="date"
              class="date-input"
            />
            <input
              v-else
              v-model="closeForm.month"
              type="month"
              class="date-input"
            />
          </div>
          <div class="form__row">
            <label class="form__label">封账门店 <span class="req">*</span></label>
            <CSelect
              v-model="closeForm.storeCode"
              :options="storeOptions.filter((o) => o.value !== '')"
            />
          </div>
          <div class="form__row">
            <label class="form__label">备注（可选）</label>
            <CInput v-model="closeForm.memo" placeholder="如：当日三方对账一致，现金已双签交接" />
          </div>

          <div class="warn-box">
            <div class="warn-box__title"><CIcon name="alert" :size="16" /> 封账红线（不可逆）</div>
            <div class="warn-box__text">
              您正在封账：<b>{{ closeForm.periodType === 'DAY' ? '日结' : '月结' }} {{ periodLabel }}</b>
              ，门店 <b>{{ closeForm.storeCode || '（未选择）' }}</b>。
            </div>
            <div class="warn-box__text">
              封账后该期间 <b>不可补记、不可改账、不可解封</b>；后续任何落账将被拒绝。
              如有差错，只能走「差异调平 ADJUST」计入当前期间。
            </div>
            <label class="ack">
              <input v-model="closeAck" type="checkbox" />
              <span>我已确认该期间对账一致，知悉封账不可逆</span>
            </label>
          </div>

          <p v-if="closeError" class="form__tip form__tip--err">{{ closeError }}</p>
        </div>
        <template #footer>
          <CButton variant="ghost" :disabled="closing" @click="showClose = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmitClose" @click="submitClose">
            {{ closing ? '封账中…' : '确认封账' }}
          </CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.st { display: flex; flex-direction: column; gap: var(--s-lg); }
.st__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .st__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.st__rule { width: 100%; }
.rule { display: flex; flex-direction: column; gap: var(--s-xs); }
.rule__head { display: flex; align-items: center; gap: var(--s-xs); }
.rule__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-md); font-weight: 600; color: var(--c-text); }
.rule__note { font-size: var(--t-xs); color: var(--c-text-3); margin: 0; line-height: 1.7; }
.rule__note b { color: var(--c-danger-fg); }

.st__err { border-left: 3px solid var(--c-danger); }
.err { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-danger-fg); }

.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.filters__right { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; }

.table-wrap { overflow-x: auto; }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.tbl th { text-align: left; font-weight: 600; color: var(--c-text-3); font-size: var(--t-xs); padding: var(--s-sm) var(--s-md); border-bottom: 1px solid var(--c-border-light); white-space: nowrap; }
.tbl td { padding: var(--s-sm) var(--s-md); border-bottom: 1px solid var(--c-border-light); color: var(--c-text-2); vertical-align: middle; }
.tbl tbody tr:hover { background: var(--c-brand-soft); }
.ta-r { text-align: right; }
.ta-c { text-align: center; }
.mono { font-variant-numeric: tabular-nums; white-space: nowrap; }
.muted { color: var(--c-text-3); }
.sub { color: var(--c-text-3); font-size: var(--t-xs); }
.amt--neg { color: var(--c-danger-fg); }
.empty__icon { color: var(--c-text-4); display: block; margin: 0 auto var(--s-sm); }

.date-input { height: 34px; padding: 0 var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-sm); font-size: var(--t-sm); color: var(--c-text); background: var(--c-bg); min-width: 180px; }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.form__tip { font-size: var(--t-xs); border-radius: var(--r-sm); padding: var(--s-sm); margin: 0; }
.form__tip--err { background: var(--c-danger-bg); color: var(--c-danger-fg); }

.warn-box { background: var(--c-warning-bg); border: 1px solid var(--c-border-light); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: 6px; }
.warn-box__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-warning-fg); }
.warn-box__text { font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.6; }
.warn-box__text b { color: var(--c-warning-fg); }
.ack { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-2); margin-top: 4px; cursor: pointer; }
.ack input { width: 15px; height: 15px; accent-color: var(--c-warning); }

.cp-warn {
  display: inline-flex; align-items: center; gap: 6px;
  border: 1px solid var(--c-border-light); border-radius: var(--r-md);
  background: var(--c-warning-bg); color: var(--c-warning-fg);
  font-size: var(--t-xs); line-height: 1.5;
  padding: 6px var(--s-sm); cursor: pointer; white-space: nowrap;
}
.cp-warn:hover { border-color: var(--c-warning); }
</style>
