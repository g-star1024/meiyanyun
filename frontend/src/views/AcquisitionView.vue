<script setup lang="ts">
/* ============================================================
 * 拓客活动 /m2-acquisition（M2-16）
 * 体验价/拼团/老带新，左活动列表 + 右详情含转化漏斗（纯 div 条形）。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CFab from '@/components/CFab.vue'
import { useAuthStore } from '@/stores/auth'
import { useAcquisitionStore, type AcquisitionCampaign, type AcqType } from '@/stores/acquisition'
import { ACQUISITION_STATUS, dictPill } from '@/config/dictionary'
import { ALL_STAFF } from '@/config/staff'

const auth = useAuthStore()
const store = useAcquisitionStore()
onMounted(() => store.seed())

// 投放渠道多选项（选中后以「+」拼接存入 channel 字段，便于渠道归因）
const CHANNEL_OPTS = ['小红书', '抖音', '大众点评', '美团', '私域社群', '微信朋友圈']
const ownerOptions = ALL_STAFF.map((s) => ({ value: s.name, label: `${s.name}（${s.title}）` }))

const selectedId = ref<string | null>(null)
const selected = computed<AcquisitionCampaign | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '进行中活动', icon: 'marketing', value: String(store.ongoing.length), tone: 'brand' as const },
  { label: '本月引流', icon: 'marketing', value: String(store.monthLeads), tone: 'teal' as const },
  { label: '转化数', icon: 'trend-up', value: String(store.monthDeals), tone: 'success' as const },
  { label: '平均转化率', icon: 'trend-up', value: `${store.avgConversion}%`, tone: 'orange' as const },
])

const typeOptions = [
  { value: 'ALL', label: '全部类型' },
  { value: 'TRIAL', label: '体验价' },
  { value: 'GROUP', label: '拼团' },
  { value: 'REFERRAL', label: '老带新' },
]
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'ONGOING', label: '进行中' },
  { value: 'ENDED', label: '已结束' },
  { value: 'DRAFT', label: '草稿' },
]

// 漏斗条形宽度（相对曝光最大值）
const funnel = computed(() => {
  if (!selected.value) return []
  const c = selected.value
  const max = Math.max(c.exposure, 1)
  return [
    { label: '曝光', value: c.exposure, width: 100, color: 'var(--c-brand)' },
    { label: '到店', value: c.arrival, width: Math.round((c.arrival / max) * 100), color: 'var(--c-teal-dark, #0ea5a4)' },
    { label: '成交', value: c.deal, width: Math.round((c.deal / max) * 100), color: 'var(--c-success-fg)' },
  ]
})
function pct(n: number, d: number) {
  if (!d) return '0%'
  return `${Math.round((n / d) * 1000) / 10}%`
}

function fmtDate(iso: string) {
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// 新建弹层
const showForm = ref(false)
function todayStr(offsetDays = 0) {
  const d = new Date(Date.now() + offsetDays * 86400_000)
  return d.toISOString().slice(0, 10)
}
const form = ref({
  name: '',
  type: 'TRIAL' as AcqType,
  budget: '',
  channels: ['私域社群'] as string[],
  owner: '',
  startDate: todayStr(),
  endDate: todayStr(30),
})
const canSubmit = computed(() => form.value.name.trim())
function toggleChannel(ch: string) {
  const i = form.value.channels.indexOf(ch)
  if (i >= 0) form.value.channels.splice(i, 1)
  else form.value.channels.push(ch)
}
function openForm() {
  form.value = {
    name: '', type: 'TRIAL', budget: '', channels: ['私域社群'],
    owner: '', startDate: todayStr(), endDate: todayStr(30),
  }
  showForm.value = true
}
function submitForm() {
  if (!canSubmit.value) return
  const c = store.create({
    name: form.value.name,
    type: form.value.type,
    budget: Number(form.value.budget) || 0,
    channel: form.value.channels.join('+'),
    startDate: form.value.startDate ? new Date(form.value.startDate).toISOString() : undefined,
    endDate: form.value.endDate ? new Date(form.value.endDate).toISOString() : undefined,
    owner: form.value.owner || undefined,
  })
  if (c) {
    showForm.value = false
    selectedId.value = c.id
  }
}
</script>

<template>
  <div class="aq">
    <div class="aq__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="aq__body">
      <!-- 左：活动列表 -->
      <CCard class="aq__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterType" :options="typeOptions" width="120px" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="120px" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="marketing" :size="28" class="empty__icon" />
            <div>暂无活动</div>
          </div>
          <button
            v-for="c in store.filtered" :key="c.id"
            class="row" :class="{ 'row--active': selected?.id === c.id }"
            @click="selectedId = c.id"
          >
            <div class="row__top">
              <span class="row__type"><CIcon :name="(store.TYPE_ICON[c.type]) as any" :size="12" /> {{ store.TYPE_LABEL[c.type] }}</span>
              <CStatusPill :status="dictPill(ACQUISITION_STATUS[c.status]).status">{{ dictPill(ACQUISITION_STATUS[c.status]).text }}</CStatusPill>
            </div>
            <div class="row__title">{{ c.name }}</div>
            <div class="row__meta">
              <span><CIcon name="trend-up" :size="12" /> 转化 {{ store.conversionRate(c) }}%</span>
              <span><CIcon name="user" :size="12" /> {{ c.arrival }}人到店</span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '新建活动', disabled: !auth.can('acquisition:edit'), onClick: openForm }]"
          />
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="aq__detail" :title="selected.name">
        <template #header>
          <h3 class="aq__detail-title">{{ selected.name }}</h3>
          <CStatusPill :status="dictPill(ACQUISITION_STATUS[selected.status]).status">{{ dictPill(ACQUISITION_STATUS[selected.status]).text }}</CStatusPill>
        </template>

        <div class="detail__head">
          <div class="detail__sub">
            <span class="tag tag--type"><CIcon :name="(store.TYPE_ICON[selected.type]) as any" :size="12" /> {{ store.TYPE_LABEL[selected.type] }}</span>
            <span class="tag"><CIcon name="marketing" :size="12" /> {{ selected.channel }}</span>
            <span class="tag"><CIcon name="calendar" :size="12" /> {{ fmtDate(selected.startDate) }} ~ {{ fmtDate(selected.endDate) }}</span>
          </div>
          <div class="detail__assign">
            <div class="detail__assign-label">负责人</div>
            <div class="detail__assign-name">{{ selected.owner }}</div>
          </div>
        </div>

        <div class="detail__grid">
          <div class="field"><span class="field__label">活动预算</span><span class="field__val">¥{{ selected.budget.toLocaleString() }}</span></div>
          <div class="field"><span class="field__label">已花费</span><span class="field__val">¥{{ selected.spent.toLocaleString() }}</span></div>
          <div class="field"><span class="field__label">到店人数</span><span class="field__val">{{ selected.arrival }}</span></div>
          <div class="field"><span class="field__label">成交人数</span><span class="field__val is-brand">{{ selected.deal }}</span></div>
        </div>

        <!-- 转化漏斗 -->
        <div class="funnel">
          <div class="detail__sec-title">转化漏斗</div>
          <div class="funnel__bars">
            <div v-for="(f, i) in funnel" :key="f.label" class="funnel__row">
              <div class="funnel__label">
                <span class="funnel__dot" :style="{ background: f.color }"></span>
                {{ f.label }}
              </div>
              <div class="funnel__track">
                <div class="funnel__fill" :style="{ width: f.width + '%', background: f.color }"></div>
              </div>
              <div class="funnel__val">{{ f.value.toLocaleString() }}</div>
              <div v-if="i > 0" class="funnel__rate">{{ pct(f.value, funnel[i - 1].value) }}</div>
            </div>
          </div>
          <div class="funnel__summary">
            整体到店转化率 <strong>{{ pct(selected.arrival, selected.exposure) }}</strong>
            ，到店成交率 <strong class="is-brand">{{ store.conversionRate(selected) }}%</strong>
          </div>
        </div>

        <div class="detail__ops">
          <template v-if="selected.status === 'DRAFT'">
            <CButton variant="primary" v-perm.disable="'acquisition:edit'" @click="store.launch(selected.id)">
              <CIcon name="trend-up" :size="16" />启用活动
            </CButton>
          </template>
          <template v-else-if="selected.status === 'ONGOING'">
            <CButton variant="ghost" v-perm.disable="'acquisition:edit'" @click="store.end(selected.id)">
              <CIcon name="check" :size="16" />结束活动
            </CButton>
            <span class="ops__hint">活动进行中</span>
          </template>
          <div v-else class="ops__done">
            <CIcon name="check" :size="16" />活动已于 {{ fmtDate(selected.endDate) }} 结束
          </div>
        </div>
      </CCard>

      <CCard v-else class="aq__detail aq__detail--empty" title="活动详情">
        <div class="detail-empty">
          <CIcon name="marketing" :size="40" class="detail-empty__icon" />
          <p>请选择一个活动</p>
        </div>
      </CCard>
    </div>

    <!-- 新建活动弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建拓客活动" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">活动名称 <span class="req">*</span></label>
            <CInput v-model="form.name" placeholder="如：99元水光体验日" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">活动类型</label>
              <CSelect v-model="form.type" :options="[
                {value:'TRIAL',label:'体验价'},{value:'GROUP',label:'拼团'},{value:'REFERRAL',label:'老带新'}
              ]" width="100%" />
            </div>
            <div>
              <label class="form__label">预算（元）</label>
              <CInput v-model="form.budget" type="number" placeholder="如：30000" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">开始日期</label>
              <input type="date" v-model="form.startDate" class="date-input" />
            </div>
            <div>
              <label class="form__label">结束日期</label>
              <input type="date" v-model="form.endDate" class="date-input" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">负责人</label>
            <CSelect v-model="form.owner" :options="ownerOptions" width="100%" placeholder="默认为当前操作人" />
          </div>
          <div class="form__row">
            <label class="form__label">投放渠道（可多选，用于渠道归因）</label>
            <div class="chips">
              <button
                v-for="ch in CHANNEL_OPTS" :key="ch" type="button"
                class="chip" :class="{ 'chip--on': form.channels.includes(ch) }"
                @click="toggleChannel(ch)"
              >{{ ch }}</button>
            </div>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">创建（草稿）</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.aq { display: flex; flex-direction: column; gap: var(--s-lg); }
.aq__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .aq__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.aq__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.aq__list { min-width: 0; display: flex; flex-direction: column; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.filters > * { flex-shrink: 0; }
.list { max-height: 560px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__type { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); }
.row__title { font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.row__meta span { display: inline-flex; align-items: center; gap: 3px; }

.aq__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.detail__head { display: flex; justify-content: space-between; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); align-items: center; }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-disabled-bg); color: var(--c-text-2); display: inline-flex; align-items: center; gap: 3px; }
.tag--type { background: var(--c-brand-soft); color: var(--c-brand); }
.detail__assign { text-align: right; flex-shrink: 0; }
.detail__assign-label { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__assign-name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }

.detail__grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); font-variant-numeric: tabular-nums; }
.is-brand { color: var(--c-brand); font-weight: 700; }

.funnel { background: var(--c-disabled-bg); border-radius: var(--r-md); padding: var(--s-lg); margin-bottom: var(--s-lg); }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-md); }
.funnel__bars { display: flex; flex-direction: column; gap: var(--s-md); }
.funnel__row { display: grid; grid-template-columns: 64px 1fr 72px 56px; align-items: center; gap: var(--s-sm); }
.funnel__label { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); color: var(--c-text-2); }
.funnel__dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.funnel__track { height: 24px; background: var(--c-surface); border-radius: var(--r-sm); overflow: hidden; }
.funnel__fill { height: 100%; border-radius: var(--r-sm); transition: width 0.3s; min-width: 2px; }
.funnel__val { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); text-align: right; font-variant-numeric: tabular-nums; }
.funnel__rate { font-size: var(--t-xs); color: var(--c-text-3); text-align: right; }
.funnel__summary { margin-top: var(--s-md); padding-top: var(--s-md); border-top: 1px solid var(--c-border-light); font-size: var(--t-sm); color: var(--c-text-2); }
.funnel__summary strong { color: var(--c-text); font-weight: 700; }
.funnel__summary .is-brand { color: var(--c-brand); }

.detail__ops { display: flex; justify-content: flex-end; align-items: center; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__done { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-success-fg); font-weight: 600; margin-left: auto; }
.ops__hint { font-size: var(--t-sm); color: var(--c-text-3); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.date-input { padding: 10px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); font-family: inherit; width: 100%; box-sizing: border-box; }
.date-input:focus { outline: none; border-color: var(--c-brand); }
.chips { display: flex; flex-wrap: wrap; gap: var(--s-xs); }
.chip { padding: 6px 14px; border-radius: var(--r-pill); border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text-2); font-size: var(--t-xs); cursor: pointer; transition: all .15s; }
.chip:hover { border-color: var(--c-brand); color: var(--c-brand); }
.chip--on { background: var(--c-brand); border-color: var(--c-brand); color: #fff; }
.req { color: var(--c-danger-fg); }

@media (max-width: 1024px) {
  .aq__body { grid-template-columns: 1fr; }
  .aq__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .detail__head { flex-direction: column; gap: var(--s-sm); }
  .detail__assign { text-align: left; }
  .detail__grid { grid-template-columns: 1fr 1fr; }
  .list { max-height: 320px; }
  .funnel__row { grid-template-columns: 56px 1fr 60px 44px; }
}
</style>
