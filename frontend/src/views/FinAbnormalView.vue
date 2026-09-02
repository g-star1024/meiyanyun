<script setup lang="ts">
/* ============================================================
 * M6 异常账务 /m6-abnormal
 * 4 KPI（异常笔数/长款 outboxLong/短款 outboxShort/待处置）
 * 左：异常清单（LONG/SHORT/REVERSED/PENDING）；右：三方金额对比 + 人工处置双签
 * 红线：从 financeCore.outbox 只读镜像；处置仅登记记录，不反向动账。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useFinAbnormalStore, type AbnormalItem, type DisposeMethod } from '@/stores/finReports'

const store = useFinAbnormalStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<AbnormalItem | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '异常笔数', icon: 'alert', value: String(store.totalCount), tone: store.totalCount ? ('danger' as const) : ('text' as const) },
  { label: '长款金额', icon: 'alert', value: money(store.longAmount), tone: 'success' as const },
  { label: '短款金额', icon: 'alert', value: money(store.shortAmount), tone: 'danger' as const },
  { label: '待处置', icon: 'check-square', value: String(store.openCount), tone: store.openCount ? ('warning' as const) : ('text' as const) },
])

const typeOptions = [
  { value: 'ALL', label: '全部类型' },
  { value: 'LONG', label: '长款' },
  { value: 'SHORT', label: '短款' },
  { value: 'REVERSED', label: '冲正' },
  { value: 'PENDING', label: '待对账' },
]

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}
function diff(it: AbnormalItem) {
  return Math.abs(it.bankAck - it.cashier)
}

// 处置双签
const showDispose = ref(false)
const form = ref({ method: 'ADJUST' as DisposeMethod, reviewer: '', remark: '' })
const methodOptions = [
  { value: 'ADJUST', label: '调平入账' },
  { value: 'LOSS', label: '报损核销' },
  { value: 'ACCOUNTABILITY', label: '追责赔偿' },
  { value: 'PENDING', label: '挂账待查' },
]
function openDispose() {
  form.value = { method: 'ADJUST', reviewer: '', remark: '' }
  showDispose.value = true
}
const canSubmit = computed(() => form.value.reviewer.trim().length >= 2 && form.value.remark.trim().length > 0)
function submitDispose() {
  if (!selected.value || !canSubmit.value) return
  store.dispose(selected.value.id, form.value.method, form.value.reviewer, form.value.remark)
  showDispose.value = false
}
</script>

<template>
  <div class="ab">
    <div class="ab__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="ab__body">
      <!-- 左：异常清单 -->
      <CCard class="ab__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterType" width="130px" :options="typeOptions" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="check-square" :size="28" class="empty__icon" />
            <div>暂无异常账务</div>
          </div>
          <button
            v-for="it in store.filtered" :key="it.id"
            class="row" :class="{ 'row--active': selected?.id === it.id, 'row--resolved': it.status === 'RESOLVED' }"
            @click="selectedId = it.id"
          >
            <div class="row__top">
              <span class="row__no">{{ it.txnNo }}</span>
              <CStatusPill :status="store.ABNORMAL_TYPE_PILL[it.type]">{{ store.ABNORMAL_TYPE_LABEL[it.type] }}</CStatusPill>
            </div>
            <div class="row__sub">{{ it.channel }} · {{ it.occurredAt }}</div>
            <div class="row__bottom">
              <span class="row__amt" :class="{ 'row__amt--long': it.type === 'LONG', 'row__amt--short': it.type === 'SHORT' }">
                {{ it.type === 'LONG' ? '+' : it.type === 'SHORT' ? '−' : '' }}{{ money(diff(it)) }}
              </span>
              <CStatusPill :status="store.ABNORMAL_STATUS_PILL[it.status]">{{ store.ABNORMAL_STATUS_LABEL[it.status] }}</CStatusPill>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="ab__detail" padding="none">
        <template #header>
          <div class="ab__detail-head">
            <div>
              <h3 class="ab__no">{{ selected.txnNo }}</h3>
              <div class="ab__sub">{{ selected.channel }} · {{ selected.occurredAt }}</div>
            </div>
            <div class="ab__detail-ops">
              <CStatusPill :status="store.ABNORMAL_STATUS_PILL[selected.status]" dot>{{ store.ABNORMAL_STATUS_LABEL[selected.status] }}</CStatusPill>
              <CButton variant="secondary" size="sm" @click="store.syncFromCore()">
                <CIcon name="loading" :size="14" />同步
              </CButton>
              <CButton variant="secondary" size="sm" v-perm.disable="'finance:export'">
                <CIcon name="export" :size="14" />导出
              </CButton>
            </div>
          </div>
        </template>

        <div class="detail-body">
          <!-- 三方金额对比 -->
          <div class="block">
            <div class="block__title"><span>三方金额对比（Outbox 对账）</span></div>
            <div class="tri">
              <div class="tri__col">
                <div class="tri__label">收银记账</div>
                <div class="tri__value">{{ money(selected.cashier) }}</div>
                <div class="tri__tag tri__tag--ok">已记录</div>
              </div>
              <div class="tri__op">
                <CIcon name="chevron-right" :size="16" />
              </div>
              <div class="tri__col">
                <div class="tri__label">渠道回单</div>
                <div class="tri__value" :class="{ 'tri__value--diff': selected.channelAck !== selected.cashier }">{{ money(selected.channelAck) }}</div>
                <div class="tri__tag" :class="selected.channelAck === selected.cashier ? 'tri__tag--ok' : 'tri__tag--warn'">
                  {{ selected.channelAck === selected.cashier ? '一致' : '差异' }}
                </div>
              </div>
              <div class="tri__op">
                <CIcon name="chevron-right" :size="16" />
              </div>
              <div class="tri__col">
                <div class="tri__label">银行到账</div>
                <div class="tri__value" :class="{ 'tri__value--diff': selected.bankAck !== selected.cashier }">{{ money(selected.bankAck) }}</div>
                <div class="tri__tag" :class="selected.bankAck === selected.cashier ? 'tri__tag--ok' : 'tri__tag--danger'">
                  {{ selected.bankAck === selected.cashier ? '一致' : (selected.type === 'LONG' ? '长款' : '短款') }}
                </div>
              </div>
            </div>
            <div class="diff-bar">
              <span>差异金额</span>
              <b :class="selected.type === 'LONG' ? 'is-long' : 'is-short'">
                {{ selected.type === 'LONG' ? '+' : '−' }}{{ money(diff(selected)) }}
              </b>
            </div>
          </div>

          <!-- 处置信息 -->
          <div v-if="selected.status === 'RESOLVED'" class="resolved">
            <div class="resolved__head">
              <CIcon name="check-square" :size="16" />
              <span>已于 {{ selected.disposedAt?.slice(0, 16).replace('T', ' ') }} 处置</span>
            </div>
            <div class="resolved__row"><span>处置方式</span><b>{{ store.DISPOSE_LABEL[selected.disposeMethod!] }}</b></div>
            <div class="resolved__row"><span>复核人</span><b>{{ selected.reviewer }}</b></div>
            <div class="resolved__row"><span>处置说明</span><b>{{ selected.remark }}</b></div>
          </div>

          <!-- 操作 -->
          <div v-if="selected.status !== 'RESOLVED'" class="ops">
            <CButton variant="primary" size="sm" v-perm.disable="'finance:abnormal:dispose'" @click="openDispose">
              <CIcon name="shield" :size="14" />人工处置（双签）
            </CButton>
            <span class="ops__hint">需 finance:abnormal:dispose 权限 + 复核人双签</span>
          </div>

          <p class="redline">
            <CIcon name="shield" :size="14" />
            异常数据从 Outbox 三方对账镜像读取；人工处置仅登记调平/报损/追责记录，不反向修改资金流水。
          </p>
        </div>
      </CCard>

      <CCard v-else class="ab__detail ab__detail--empty" title="异常详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="alert" :size="40" class="detail-empty__icon" />
          <p>请选择一条异常记录</p>
        </div>
      </CCard>
    </div>

    <!-- 处置双签弹层 -->
    <div v-if="showDispose" class="modal-mask" @click.self="showDispose = false">
      <CCard class="modal" title="异常人工处置（双签）" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="shield" :size="16" /> 双签确认</div>
            <div class="sign-box__text">异常单：{{ selected?.txnNo }}（{{ selected ? store.ABNORMAL_TYPE_LABEL[selected.type] : '' }}）</div>
            <div class="sign-box__text">差异金额：<b>{{ selected ? money(diff(selected)) : '' }}</b></div>
          </div>
          <label class="form__label">处置方式</label>
          <CSelect v-model="form.method" width="100%" :options="methodOptions" />
          <label class="form__label">复核人 <span class="req">*</span></label>
          <CInput v-model="form.reviewer" placeholder="请输入复核人姓名，如：陈雅琳（财务主管）" />
          <label class="form__label">处置说明 <span class="req">*</span></label>
          <CInput v-model="form.remark" placeholder="说明差异原因与处置依据" />
          <p class="form__tip form__tip--warn">处置仅登记记录，不直接修改资金；提交后状态置为「已处置」。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showDispose = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" v-perm.disable="'finance:abnormal:dispose'" @click="submitDispose">确认处置</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ab { display: flex; flex-direction: column; gap: var(--s-lg); }
.ab__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ab__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.ab__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ab__list { min-width: 0; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.filters__right { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }
.list { max-height: 640px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-success-fg); }

.row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; }
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--resolved { opacity: 0.65; }
.row__top { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); margin-bottom: 4px; }
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); font-variant-numeric: tabular-nums; }
.row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 6px; }
.row__bottom { display: flex; align-items: center; justify-content: space-between; }
.row__amt { font-size: var(--t-md); font-weight: 700; font-variant-numeric: tabular-nums; }
.row__amt--long { color: var(--c-success-fg); }
.row__amt--short { color: var(--c-danger-fg); }

.ab__detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-md); width: 100%; }
.ab__detail-ops { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end; }
.ab__no { font-size: var(--t-lg); font-weight: 700; margin: 0; }
.ab__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }
.block { display: flex; flex-direction: column; gap: var(--s-sm); }
.block__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }

.tri { display: grid; grid-template-columns: 1fr auto 1fr auto 1fr; align-items: center; gap: var(--s-sm); }
.tri__col { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); text-align: center; display: flex; flex-direction: column; gap: 4px; align-items: center; }
.tri__label { font-size: var(--t-xs); color: var(--c-text-3); }
.tri__value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.tri__value--diff { color: var(--c-danger-fg); }
.tri__tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); }
.tri__tag--ok { background: var(--c-success-bg); color: var(--c-success-fg); }
.tri__tag--warn { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.tri__tag--danger { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.tri__op { color: var(--c-text-4); display: flex; }
.diff-bar { display: flex; align-items: center; justify-content: space-between; padding: var(--s-sm) var(--s-md); background: var(--c-warn-soft-bg); border-radius: var(--r-sm); margin-top: var(--s-xs); }
.diff-bar span { font-size: var(--t-xs); color: var(--c-text-3); }
.diff-bar b { font-size: var(--t-md); font-variant-numeric: tabular-nums; }
.diff-bar b.is-long { color: var(--c-success-fg); }
.diff-bar b.is-short { color: var(--c-danger-fg); }

.resolved { background: var(--c-success-bg); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: var(--s-xs); }
.resolved__head { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); font-weight: 600; color: var(--c-success-fg); margin-bottom: var(--s-xs); }
.resolved__row { display: flex; justify-content: space-between; font-size: var(--t-sm); }
.resolved__row span { color: var(--c-text-3); }
.resolved__row b { color: var(--c-text-2); font-weight: 500; text-align: right; }

.ops { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.ops__hint { font-size: var(--t-xs); color: var(--c-text-3); }

.redline { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-warning-fg); background: var(--c-warn-soft-bg); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); margin: 0; }
.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 460px; max-width: 100%; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-sm); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.form__tip { font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-bg-right); border-radius: var(--r-sm); padding: var(--s-sm); margin: 0; }
.form__tip--warn { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.sign-box { background: var(--c-warning-bg); border: 1px solid var(--c-border-light); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: 4px; }
.sign-box__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-warning-fg); }
.sign-box__text { font-size: var(--t-sm); color: var(--c-text-2); }

@media (max-width: 1024px) {
  .ab__body { grid-template-columns: 1fr; }
  .tri { grid-template-columns: 1fr; }
  .tri__op { transform: rotate(90deg); justify-self: center; }
  .list { max-height: 320px; }
}
</style>
