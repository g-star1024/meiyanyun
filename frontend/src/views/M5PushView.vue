<script setup lang="ts">
/* M5-03 客户触达 /m5-push — 选客 → 文案违禁词预检（后端 /check）→ 周频余量（/quota）→ 发送（/push）→ 历史（/history） */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import { listCustomers, type CustomerDTO } from '@/api/customer'
import {
  checkCopy, sendPush, getPushQuota, getPushHistory,
  type PushQuota, type PushRecordDTO, type PushTypeCode,
} from '@/api/marketing'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { useAuthStore } from '@/stores/auth'
import { quotaUsagePct, quotaTone, checkPushReady } from '@/composables/usePushCompliance'

const auth = useAuthStore()
const toast = useToast()

// ---------------- 客户选择（真实 customer-service） ----------------
const customers = ref<CustomerDTO[]>([])
const customersTotal = ref(0)
const keyword = ref('')
const loadingCustomers = ref(false)
const selectedId = ref<string | null>(null)
const selected = computed(() => customers.value.find((c) => c.customerId === selectedId.value) ?? null)

// ---------------- 选中客户的频控 / 历史 ----------------
const quota = ref<PushQuota | null>(null)
const history = ref<PushRecordDTO[]>([])
const loadingDetail = ref(false)

// ---------------- 发送表单 ----------------
const pushType = ref<PushTypeCode>('WECOM')
const content = ref('')
const wordHits = ref<string[]>([])
const checking = ref(false)
const sending = ref(false)
const formError = ref('')

// 后端 push:create 为发送接口唯一权限码（与 @RequirePerm 一致）
const canPush = computed(() => auth.can('push:create'))

const PUSH_TYPE_LABEL: Record<string, string> = {
  SMS: '短信', WECOM: '企业微信', WECHAT_MP: '微信公众号',
  // 兼容种子旧数据的中文渠道值（历史记录展示用，新发送一律走英文码）
  短信: '短信', 小程序: '微信公众号', App: 'App推送',
}
function pushTypeLabel(t: string) {
  return PUSH_TYPE_LABEL[t] ?? t
}

const kpis = computed(() => [
  { label: '可触达客户', icon: 'marketing', value: String(customersTotal.value || customers.value.length), tone: 'brand' as const },
  { label: '当前客户近7天已触达', icon: 'bell', value: String(quota.value?.sentLast7Days ?? 0), tone: 'teal' as const },
  { label: '本周剩余配额', icon: 'clock',
    value: quota.value ? `${quota.value.remaining} 条` : '—',
    tone: (quota.value ? quota.value.remaining > 0 : true) ? ('success' as const) : ('danger' as const) },
  { label: '当前客户累计触达', icon: 'trend-up', value: String(history.value.length), tone: 'orange' as const },
])

const quotaPct = computed(() => quotaUsagePct(quota.value))
const quotaBarTone = computed(() => quotaTone(quotaPct.value))

function fmtTime(iso: string) {
  if (!iso) return '—'
  return iso.replace('T', ' ').slice(0, 16)
}

async function loadCustomers() {
  loadingCustomers.value = true
  try {
    const page = (await listCustomers({ page: 0, size: 50, keyword: keyword.value.trim() || undefined })).data
    customers.value = page.content
    customersTotal.value = page.totalElements
    if (selectedId.value && !customers.value.some((c) => c.customerId === selectedId.value)) {
      selectedId.value = null
    }
  } catch (e) {
    toast.error('客户列表加载失败：' + errMsg(e))
  } finally {
    loadingCustomers.value = false
  }
}

let searchTimer: ReturnType<typeof setTimeout> | undefined
function onSearchInput() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(loadCustomers, 300)
}

async function selectCustomer(c: CustomerDTO) {
  selectedId.value = c.customerId
  content.value = ''
  wordHits.value = []
  formError.value = ''
  loadingDetail.value = true
  try {
    const [q, h] = await Promise.all([
      getPushQuota(c.customerId),
      getPushHistory(c.customerId),
    ])
    quota.value = q.data
    history.value = h.data
  } catch (e) {
    toast.error('触达数据加载失败：' + errMsg(e))
    quota.value = null
    history.value = []
  } finally {
    loadingDetail.value = false
  }
}

// 文案预检：实时调后端 /forbidden-words/check（DB 词库 + 缓存，管理端维护即时生效）
let checkTimer: ReturnType<typeof setTimeout> | undefined
async function previewWords() {
  formError.value = ''
  const text = content.value.trim()
  if (!text) { wordHits.value = []; return }
  checking.value = true
  clearTimeout(checkTimer)
  checkTimer = setTimeout(async () => {
    try {
      const r = (await checkCopy(text)).data
      wordHits.value = r.hits
    } catch (e) {
      // 预检失败不阻断输入，发送时后端仍会强校验
      wordHits.value = []
    } finally {
      checking.value = false
    }
  }, 300)
}

async function doSend() {
  formError.value = ''
  const gate = checkPushReady({
    hasCustomer: !!selected.value,
    content: content.value,
    wordHits: wordHits.value,
    quota: quota.value,
  })
  if (!gate.ok) { formError.value = gate.error; return }
  const customer = selected.value
  if (!customer) { formError.value = '请先选择客户'; return }
  sending.value = true
  try {
    await sendPush({ customerId: customer.customerId, pushType: pushType.value, content: content.value.trim() })
    toast.success(`已向 ${customer.name} 发送${pushTypeLabel(pushType.value)}触达`)
    content.value = ''
    wordHits.value = []
    await selectCustomer(customer)
  } catch (e) {
    formError.value = errMsg(e)
  } finally {
    sending.value = false
  }
}

onMounted(loadCustomers)
</script>

<template>
  <div class="ps">
    <div class="ps__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard class="quota" padding="md">
      <div class="quota__left">
        <CIcon name="shield" :size="16" />
        <span>周频合规：同一客户 7 天内触达 ≤ <strong>{{ quota?.weeklyLimit ?? 3 }}</strong> 条</span>
      </div>
      <div class="quota__bar">
        <div class="quota__track"><div class="quota__fill" :class="{ warn: quotaBarTone === 'warn', danger: quotaBarTone === 'danger' }" :style="{ width: quotaPct + '%' }"></div></div>
        <span class="quota__num">
          <template v-if="quota">{{ quota.sentLast7Days }} / {{ quota.weeklyLimit }} 条</template>
          <template v-else>选择客户后展示</template>
        </span>
      </div>
    </CCard>

    <div class="ps__body">
      <CCard class="ps__list" padding="none">
        <div class="list-head">
          <div class="list-head__left">
            <span class="list-head__title">选择客户</span>
            <span class="list-head__hint">{{ customersTotal || customers.length }} 人</span>
          </div>
        </div>
        <div class="ps__tools">
          <CInput v-model="keyword" placeholder="搜索姓名 / 手机号 / 客户编号" @input="onSearchInput" />
        </div>
        <div class="p-list">
          <div v-if="loadingCustomers" class="p-empty">加载中…</div>
          <div v-else-if="!customers.length" class="p-empty">未找到匹配客户</div>
          <button v-for="c in customers" :key="c.customerId" class="p-row"
            :class="{ 'p-row--active': c.customerId === selectedId }"
            @click="selectCustomer(c)">
            <div class="p-row__top">
              <span class="p-row__name">{{ c.name }}</span>
              <CStatusPill status="default" dot>{{ c.level }}</CStatusPill>
            </div>
            <div class="p-row__sub">{{ c.customerId }} · {{ c.phone }} · {{ c.storeName || c.storeCode || '—' }}</div>
            <div class="p-row__nums">{{ c.status || '—' }} · 累计消费 ¥{{ c.totalSpend ?? 0 }} · 到店 {{ c.visitCount ?? 0 }} 次</div>
          </button>
        </div>
      </CCard>

      <CCard v-if="selected" class="ps__detail" padding="lg">
        <div class="det-head">
          <div>
            <h3 class="det-head__name">{{ selected.name }}（{{ selected.customerId }}）</h3>
            <div class="det-head__sub">{{ selected.phone }} · {{ selected.level }} · {{ selected.storeName || selected.storeCode || '—' }} · 归属 {{ selected.ownerStaffName || selected.ownerStaffId || '—' }}</div>
          </div>
          <CStatusPill status="primary" dot>{{ selected.status || '—' }}</CStatusPill>
        </div>

        <CCard class="tpl-card" padding="md">
          <div class="form-grid">
            <div class="form-row">
              <label class="form-label">推送渠道</label>
              <CSelect v-model="pushType" :options="[
                { value: 'SMS', label: '短信' },
                { value: 'WECOM', label: '企业微信' },
                { value: 'WECHAT_MP', label: '微信公众号' },
              ]" />
            </div>
            <div class="form-row">
              <label class="form-label">周频余量</label>
              <div class="quota-inline">
                <span v-if="quota" :class="quota.remaining > 0 ? 'quota-ok' : 'quota-zero'">
                  近 7 天已发 {{ quota.sentLast7Days }} 条，剩余 {{ quota.remaining }} 条
                </span>
                <span v-else>加载中…</span>
              </div>
            </div>
          </div>
          <div class="form-row">
            <label class="form-label">推送内容</label>
            <CTextarea v-model="content" :rows="4" placeholder="请输入推送文案..." @input="previewWords" />
            <div v-if="wordHits.length" class="word-hint">
              <CIcon name="alert" :size="12" />命中违禁词：<strong>{{ wordHits.join('、') }}</strong>，请修改
            </div>
            <div v-else-if="content.trim() && !checking" class="word-ok">
              <CIcon name="check-square" :size="12" />文案预检通过，未命中违禁词
            </div>
          </div>
          <div class="compliance-note">
            <CIcon name="shield" :size="13" />发送前后端强校验：① 周频 ≤ {{ quota?.weeklyLimit ?? 3 }} 条/客户/7天 ② 违禁词库（DB 实时）。命中任一将被拦截。
          </div>
          <div v-if="formError" class="form-error"><CIcon name="alert" :size="13" />{{ formError }}</div>
          <div class="modal-foot">
            <CButton v-if="canPush" variant="primary" size="sm" :disabled="sending || checking" @click="doSend">
              <CIcon name="bell" :size="14" />{{ sending ? '发送中…' : '立即发送' }}
            </CButton>
            <span v-else class="ops-hint">无触达发送权限（push:create）</span>
          </div>
        </CCard>

        <div class="effect">
          <div class="effect__title">触达历史（{{ history.length }}）</div>
          <div v-if="loadingDetail" class="p-empty">加载中…</div>
          <div v-else-if="!history.length" class="p-empty">该客户暂无触达记录</div>
          <div v-else class="hist-list">
            <CCard v-for="r in history" :key="r.pushId" class="tpl-card hist-item" padding="md">
              <div class="hist-top">
                <CStatusPill status="info" dot>{{ pushTypeLabel(r.pushType) }}</CStatusPill>
                <span class="hist-time">{{ fmtTime(r.sentAt) }}</span>
              </div>
              <div class="tpl-body">{{ r.content }}</div>
            </CCard>
          </div>
        </div>
      </CCard>

      <CCard v-else class="ps__detail" padding="lg">
        <div class="det-head">
          <div>
            <h3 class="det-head__name">客户触达</h3>
            <div class="det-head__sub">请从左侧选择一位客户，查看频控余量、发送触达与历史记录</div>
          </div>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ps { display: flex; flex-direction: column; gap: var(--s-lg); }
.ps__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ps__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.quota { display: flex; align-items: center; gap: var(--s-lg); background: var(--c-brand-soft); border: 1px solid var(--c-brand-light, rgba(94,114,228,.2)); }
.quota__left { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-text-2); white-space: nowrap; }
.quota__left strong { color: var(--c-brand); }
.quota__bar { flex: 1; display: flex; align-items: center; gap: var(--s-md); min-width: 200px; }
.quota__track { flex: 1; height: 8px; background: var(--c-disabled-bg); border-radius: 4px; overflow: hidden; }
.quota__fill { height: 100%; background: var(--c-success-fg); border-radius: 4px; transition: width .3s; }
.quota__fill.warn { background: var(--c-warning-fg); }
.quota__fill.danger { background: var(--c-danger-fg); }
.quota__num { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; white-space: nowrap; }

.ps__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ps__list { min-width: 0; }
.list-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.list-head__left { display: flex; align-items: center; gap: var(--s-sm); }
.list-head__right { display: flex; align-items: center; gap: var(--s-sm); }
.list-head__title { font-size: var(--t-sm); font-weight: 700; }
.list-head__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.ps__tools { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); border-top: 1px solid var(--c-border-light); background: var(--c-surface); flex-wrap: nowrap; overflow-x: auto; }
.ps__tools > * { flex-shrink: 0; width: 100%; }
.ps__tools-btn { margin-left: auto; white-space: nowrap; }
.p-list { max-height: 600px; overflow-y: auto; }
.p-empty { padding: var(--s-lg); text-align: center; font-size: var(--t-xs); color: var(--c-text-3); }
.p-row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; border-left: 3px solid transparent; }
.p-row:hover { background: var(--c-brand-soft); }
.p-row--active { background: var(--c-brand-soft); border-left-color: var(--c-brand); }
.p-row--blocked { border-left-color: var(--c-danger-fg); }
.p-row__top { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); margin-bottom: 6px; }
.p-row__name { font-size: var(--t-sm); font-weight: 600; }
.p-row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.p-row__nums { font-size: var(--t-xs); color: var(--c-text-2); font-variant-numeric: tabular-nums; }

.ps__detail :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); min-width: 0; }
.det-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); }
.det-head > div { min-width: 0; flex: 1; }
.det-head__name { margin: 0; font-size: var(--t-lg); font-weight: 700; word-break: break-word; }
.det-head__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.tpl-card { background: var(--c-bg-right); }
:deep(.tpl-card .card__body) { display: flex; flex-direction: column; gap: var(--s-sm); }
.tpl-title { font-size: var(--t-base); font-weight: 700; color: var(--c-text); }
.tpl-body { font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; word-break: break-word; }
.tpl-foot { font-size: var(--t-xs); color: var(--c-text-4); }

.block-box { display: flex; gap: var(--s-sm); padding: var(--s-md); background: rgba(229,57,53,.08); border-radius: var(--r-md); color: var(--c-danger-fg); }
.block-box__title { font-weight: 700; font-size: var(--t-sm); }
.block-box__hint { font-size: var(--t-xs); margin-top: 2px; }
.det-ops { display: flex; align-items: center; gap: var(--s-sm); }
.ops-hint { font-size: var(--t-xs); color: var(--c-text-3); }

.quota-inline { font-size: var(--t-sm); padding: 10px 0; }
.quota-ok { color: var(--c-success-fg); font-weight: 600; }
.quota-zero { color: var(--c-danger-fg); font-weight: 600; }
.word-ok { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-success-fg); }

.effect { border-top: 1px solid var(--c-border-light); padding-top: var(--s-md); }
.effect__title { font-size: var(--t-sm); font-weight: 700; margin-bottom: var(--s-sm); }
.effect__grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-sm); margin-bottom: var(--s-md); }
.effect-item { display: flex; flex-direction: column; align-items: center; gap: 2px; background: var(--c-bg-right); border-radius: var(--r-sm); padding: var(--s-sm); }
.effect-item__n { font-size: var(--t-lg); font-weight: 700; font-variant-numeric: tabular-nums; }
.effect-item__l { font-size: var(--t-xs); color: var(--c-text-3); }
.funnel { display: flex; flex-direction: column; gap: var(--s-sm); }
.funnel__row { display: grid; grid-template-columns: 60px 1fr 40px; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-2); }
.funnel__bar { height: 8px; background: var(--c-disabled-bg); border-radius: 4px; overflow: hidden; }
.funnel__bar > div { height: 100%; background: var(--c-brand); border-radius: 4px; }
.funnel__bar div.conv { background: var(--c-success-fg); }

.hist-list { display: flex; flex-direction: column; gap: var(--s-sm); max-height: 360px; overflow-y: auto; }
.hist-item { background: var(--c-bg-right); }
.hist-top { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); }
.hist-time { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 540px; max-width: 100%; box-shadow: var(--shadow-pop); }
:deep(.modal .card__body) { display: flex; flex-direction: column; gap: var(--s-md); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form-row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form-label { font-size: var(--t-xs); color: var(--c-text-3); }
.native-input { width: 100%; padding: 10px; border: 1px solid #D1D1D9; border-radius: var(--r-sm); background: var(--c-surface); font-size: 13px; color: var(--c-text); font-family: inherit; }
.native-input:focus { outline: none; border-color: #4D5AD9; box-shadow: 0 0 0 2px rgba(77,90,217,.12); }
.word-hint { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-danger-fg); }
.compliance-note { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-bg-right); padding: var(--s-sm) var(--s-md); border-radius: var(--r-sm); }
.form-error { display: flex; align-items: center; gap: 4px; color: var(--c-danger-fg); font-size: var(--t-xs); background: rgba(229,57,53,.08); padding: var(--s-sm) var(--s-md); border-radius: var(--r-sm); }
.modal-foot { display: flex; justify-content: flex-end; align-items: center; gap: var(--s-sm); }

@media (max-width: 1200px) { .ps__body { grid-template-columns: 1fr; } }
@media (max-width: 1024px) {
  .ps__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .form-grid { grid-template-columns: 1fr; }
  .quota { flex-direction: column; align-items: stretch; }
}
</style>
