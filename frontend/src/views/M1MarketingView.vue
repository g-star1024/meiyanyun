<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import {
  useM1MarketingStore, type Campaign, type CampaignStatus, type CampaignType,
} from '@/stores/m1Marketing'
import { errMsg } from '@/stores/m5Coupon'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'

const mk = useM1MarketingStore()
const auth = useAuthStore()
const toast = useToast()
onMounted(async () => {
  try { await mk.seed() } catch (e) { toast.error('活动数据加载失败：' + errMsg(e)) }
})

const canEdit = computed(() => auth.can('marketing:edit'))

const statusFilter = ref<CampaignStatus | ''>('')
const typeFilter = ref<CampaignType | ''>('')
const keyword = ref('')

const filtered = computed(() => {
  return mk.campaigns.filter((c) => {
    if (statusFilter.value && c.status !== statusFilter.value) return false
    if (typeFilter.value && c.type !== typeFilter.value) return false
    if (keyword.value && !`${c.name} ${c.owner}`.includes(keyword.value.trim())) return false
    return true
  })
})

const selectedId = ref('')
const showDetail = ref(false)
const selected = computed<Campaign | undefined>(() => {
  if (selectedId.value) { const c = mk.campaign(selectedId.value); if (c) return c }
  return filtered.value[0]
})
function select(c: Campaign) { selectedId.value = c.id; showDetail.value = true }
function closeDetail() { showDetail.value = false }

function statusTone(s: CampaignStatus) {
  return ({ DRAFT: 'disabled', SCHEDULED: 'info', RUNNING: 'success', ENDED: 'default', CANCELLED: 'danger' } as const)[s]
}
function typeTone(t: CampaignType) {
  return ({ FULL_REDUCE: 'primary', DISCOUNT: 'warning', COUPON_PACK: 'info', GIFT: 'success', NEWBIE: 'draft', VIP_DAY: 'danger' } as const)[t]
}
function fmtMoney(n: number) { return '¥' + (n >= 10000 ? (n / 10000).toFixed(1) + '万' : n.toLocaleString('zh-CN')) }

const statusChips: { key: CampaignStatus | ''; label: string }[] = [
  { key: '', label: '全部' }, { key: 'RUNNING', label: '进行中' }, { key: 'SCHEDULED', label: '待开始' },
  { key: 'DRAFT', label: '草稿' }, { key: 'ENDED', label: '已结束' }, { key: 'CANCELLED', label: '已取消' },
]

// 操作
async function doTransit(to: CampaignStatus) {
  if (!selected.value) return
  try {
    await mk.transit(selected.value.id, to)
    toast.success('活动状态已更新')
  } catch (e) {
    toast.error('操作失败：' + errMsg(e))
  }
}

// 新建活动
const showModal = ref(false)
const formErr = ref('')
const form = reactive({
  name: '', type: 'DISCOUNT' as CampaignType, channels: [] as string[],
  startDate: '', endDate: '', budget: '', targetAmount: '', storeScope: '全部门店', owner: auth.user.name, remark: '',
})
function openCreate() {
  Object.assign(form, { name: '', type: 'DISCOUNT', channels: [], startDate: '', endDate: '', budget: '', targetAmount: '', storeScope: '全部门店', owner: auth.user.name, remark: '' })
  formErr.value = ''; showModal.value = true
}
function toggleChannel(ch: string) {
  const i = form.channels.indexOf(ch)
  if (i >= 0) form.channels.splice(i, 1); else form.channels.push(ch)
}
async function submit() {
  if (!form.name.trim()) { formErr.value = '请填写活动名称'; return }
  if (!form.startDate || !form.endDate) { formErr.value = '请选择活动起止日期'; return }
  if (form.channels.length === 0) { formErr.value = '至少选择一个投放渠道'; return }
  try {
    const c = await mk.createCampaign({
      name: form.name.trim(), type: form.type, channels: [...form.channels],
      startDate: form.startDate, endDate: form.endDate, storeScope: form.storeScope,
      budget: Number(form.budget) || 0, targetAmount: Number(form.targetAmount) || 0,
      owner: form.owner || auth.user.name, remark: form.remark.trim() || undefined,
    })
    showModal.value = false
    selectedId.value = c.id
  } catch (e) { formErr.value = errMsg(e) }
}

const selectedCoupons = computed(() => selected.value ? mk.couponsOf(selected.value.id) : [])
</script>

<template>
  <div class="mkt-page">
    <div class="mkt-kpis">
      <div class="kpi kpi--success"><div class="kpi__icon"><CIcon name="trend-up" :size="20" /></div><div class="kpi__body"><div class="kpi__label">进行中活动</div><div class="kpi__value">{{ mk.stats.running }}</div></div></div>
      <div class="kpi kpi--brand"><div class="kpi__icon"><CIcon name="pos" :size="20" /></div><div class="kpi__body"><div class="kpi__label">累计成交额</div><div class="kpi__value">{{ fmtMoney(mk.stats.amount) }}</div></div></div>
      <div class="kpi kpi--warning"><div class="kpi__icon"><CIcon name="customer" :size="20" /></div><div class="kpi__body"><div class="kpi__label">引流新客</div><div class="kpi__value">{{ mk.stats.newCustomers }}</div></div></div>
      <div class="kpi kpi--info"><div class="kpi__icon"><CIcon name="finance" :size="20" /></div><div class="kpi__body"><div class="kpi__label">整体 ROI</div><div class="kpi__value">{{ mk.stats.roi }}<span class="kpi__sub">花费 {{ fmtMoney(mk.stats.spent) }}</span></div></div></div>
    </div>

    <CCard padding="md" class="mkt-toolbar">
      <div class="filters">
        <CInput v-model="keyword" placeholder="搜索活动名称/负责人" />
        <select v-model="typeFilter" class="sel">
          <option value="">全部类型</option>
          <option v-for="(label, key) in mk.CAMPAIGN_TYPE_LABEL" :key="key" :value="key">{{ label }}</option>
        </select>
      </div>
      <CButton variant="primary" :disabled="!canEdit" v-perm="'marketing:edit'" @click="openCreate"><CIcon name="plus" :size="15" /> 新建活动</CButton>
    </CCard>

    <div class="chips-row">
      <button v-for="s in statusChips" :key="s.key" class="chip" :class="{ 'chip--on': statusFilter === s.key }" @click="statusFilter = s.key">{{ s.label }}</button>
    </div>

    <div class="mkt-grid">
      <CCard v-for="c in filtered" :key="c.id" padding="none" class="camp" :class="{ 'camp--active': selected?.id === c.id, 'camp--off': c.status === 'CANCELLED' || c.status === 'ENDED' }" @click="select(c)">
        <div class="camp__head">
          <div class="camp__title">
            <CStatusPill :status="typeTone(c.type)">{{ mk.CAMPAIGN_TYPE_LABEL[c.type] }}</CStatusPill>
            <span class="camp__name">{{ c.name }}</span>
          </div>
          <CStatusPill :status="statusTone(c.status)" dot>{{ mk.CAMPAIGN_STATUS_LABEL[c.status] }}</CStatusPill>
        </div>
        <div class="camp__meta">
          <span><CIcon name="calendar" :size="13" /> {{ c.startDate }} ~ {{ c.endDate }}</span>
          <span><CIcon name="user" :size="13" /> {{ c.owner }}</span>
        </div>
        <div class="camp__ch"><span v-for="ch in c.channels" :key="ch" class="ch-chip">{{ ch }}</span></div>

        <div class="camp__metrics">
          <div class="m"><div class="m__label">成交额 / 目标</div><div class="m__val">{{ fmtMoney(c.actualAmount) }} / {{ fmtMoney(c.targetAmount) }}</div>
            <div class="bar"><div class="bar__fill" :style="{ width: Math.min(100, mk.achieveRate(c)) + '%' }"></div></div>
          </div>
          <div class="m m--split">
            <div><div class="m__label">ROI</div><div class="m__val" :class="{ 'roi-hi': mk.roi(c) >= 5 }">{{ c.spent ? mk.roi(c) : '—' }}</div></div>
            <div><div class="m__label">新客</div><div class="m__val">{{ c.newCustomers }}</div></div>
            <div><div class="m__label">花费</div><div class="m__val">{{ fmtMoney(c.spent) }}</div></div>
          </div>
        </div>
      </CCard>
      <div v-if="filtered.length === 0" class="empty">暂无符合条件的活动</div>
    </div>

    <!-- 详情抽屉 -->
    <div v-if="selected && showDetail" class="drawer-mask" @click.self="closeDetail">
      <div class="drawer">
        <div class="drawer__head">
          <div>
            <div class="drawer__title-row">
              <CStatusPill :status="typeTone(selected.type)">{{ mk.CAMPAIGN_TYPE_LABEL[selected.type] }}</CStatusPill>
              <h3>{{ selected.name }}</h3>
            </div>
            <div class="drawer__meta"><span class="code">{{ selected.id.slice(-6) }}</span><span>负责人 {{ selected.owner }}</span><span>{{ selected.startDate }} ~ {{ selected.endDate }}</span></div>
          </div>
          <button class="drawer__close" @click="closeDetail"><CIcon name="close" :size="18" /></button>
        </div>
        <div class="drawer__body">
          <div class="d-stats">
            <div class="ds"><div class="ds__label">预算</div><div class="ds__val">{{ fmtMoney(selected.budget) }}</div></div>
            <div class="ds"><div class="ds__label">已花费</div><div class="ds__val">{{ fmtMoney(selected.spent) }}</div></div>
            <div class="ds"><div class="ds__label">成交额</div><div class="ds__val ds__val--brand">{{ fmtMoney(selected.actualAmount) }}</div></div>
            <div class="ds"><div class="ds__label">目标达成</div><div class="ds__val">{{ mk.achieveRate(selected) }}%</div></div>
            <div class="ds"><div class="ds__label">ROI</div><div class="ds__val" :class="{ 'roi-hi': mk.roi(selected) >= 5 }">{{ selected.spent ? mk.roi(selected) : '—' }}</div></div>
            <div class="ds"><div class="ds__label">引流新客</div><div class="ds__val">{{ selected.newCustomers }}</div></div>
          </div>

          <section class="d-sec">
            <h4>投放渠道</h4>
            <div class="ch-list"><span v-for="ch in selected.channels" :key="ch" class="ch-chip ch-chip--lg">{{ ch }}</span></div>
          </section>

          <section class="d-sec" v-if="selectedCoupons.length">
            <h4>关联优惠券（{{ selectedCoupons.length }}）</h4>
            <div class="table-wrap">
              <table class="dt">
                <thead><tr><th>券码</th><th>名称</th><th class="num">面值</th><th class="num">门槛</th><th class="num">已领/总量</th><th class="num">已核销</th></tr></thead>
                <tbody>
                  <tr v-for="cp in selectedCoupons" :key="cp.id">
                    <td class="mono">{{ cp.code }}</td><td class="cell-name">{{ cp.name }}</td>
                    <td class="num">{{ cp.type === 'AMOUNT' ? '¥' + cp.value : cp.value + '折' }}</td>
                    <td class="num">{{ cp.threshold ? '¥' + cp.threshold : '无门槛' }}</td>
                    <td class="num">{{ cp.received }}/{{ cp.total }}</td>
                    <td class="num"><b class="brand-txt">{{ cp.used }}</b></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <p v-if="selected.remark" class="d-remark">{{ selected.remark }}</p>
        </div>
        <div class="drawer__foot">
          <template v-if="selected.status === 'DRAFT'">
            <CButton variant="secondary" :disabled="!canEdit" v-perm="'marketing:edit'" @click="doTransit('CANCELLED')">作废</CButton>
            <CButton variant="primary" :disabled="!canEdit" v-perm="'marketing:edit'" @click="doTransit('SCHEDULED')">提交上线</CButton>
          </template>
          <template v-else-if="selected.status === 'SCHEDULED'">
            <CButton variant="secondary" :disabled="!canEdit" v-perm="'marketing:edit'" @click="doTransit('DRAFT')">撤回草稿</CButton>
            <CButton variant="primary" :disabled="!canEdit" v-perm="'marketing:edit'" @click="doTransit('RUNNING')">立即开始</CButton>
          </template>
          <template v-else-if="selected.status === 'RUNNING'">
            <CButton variant="secondary" :disabled="!canEdit" v-perm="'marketing:edit'" @click="doTransit('CANCELLED')">中止活动</CButton>
            <CButton variant="primary" :disabled="!canEdit" v-perm="'marketing:edit'" @click="doTransit('ENDED')">结束活动</CButton>
          </template>
          <CButton v-else variant="secondary" disabled>{{ mk.CAMPAIGN_STATUS_LABEL[selected.status] }}</CButton>
        </div>
      </div>
    </div>

    <!-- 新建活动弹层 -->
    <div v-if="showModal" class="modal-mask" @click.self="showModal = false">
      <div class="modal modal--lg">
        <div class="modal__head"><h3>新建营销活动</h3><button class="modal__close" @click="showModal = false"><CIcon name="close" :size="18" /></button></div>
        <div class="modal__body">
          <div class="form-grid">
            <label class="field field--full"><span class="field__label">活动名称 <i>*</i></span><CInput v-model="form.name" placeholder="如 9月新客体验礼" /></label>
            <label class="field"><span class="field__label">活动类型 <i>*</i></span>
              <select v-model="form.type" class="sel"><option v-for="(label, key) in mk.CAMPAIGN_TYPE_LABEL" :key="key" :value="key">{{ label }}</option></select>
            </label>
            <label class="field"><span class="field__label">适用范围</span><CInput v-model="form.storeScope" placeholder="全部门店 / 指定门店" /></label>
            <label class="field"><span class="field__label">开始日期 <i>*</i></span><input v-model="form.startDate" type="date" class="sel" /></label>
            <label class="field"><span class="field__label">结束日期 <i>*</i></span><input v-model="form.endDate" type="date" class="sel" /></label>
            <label class="field"><span class="field__label">预算（元）</span><CInput v-model="form.budget" placeholder="80000" /></label>
            <label class="field"><span class="field__label">目标成交额（元）</span><CInput v-model="form.targetAmount" placeholder="300000" /></label>
            <label class="field field--full"><span class="field__label">投放渠道 <i>*</i></span>
              <div class="ch-pick">
                <label v-for="ch in mk.CHANNELS" :key="ch" class="ch-opt" :class="{ 'is-on': form.channels.includes(ch) }">
                  <input type="checkbox" :checked="form.channels.includes(ch)" @change="toggleChannel(ch)" /> {{ ch }}
                </label>
              </div>
            </label>
            <label class="field field--full"><span class="field__label">活动说明</span><CTextarea v-model="form.remark" :rows="2" /></label>
          </div>
          <div v-if="formErr" class="form-err">{{ formErr }}</div>
        </div>
        <div class="modal__foot"><CButton variant="secondary" @click="showModal = false">取消</CButton><CButton variant="primary" @click="submit">创建（草稿）</CButton></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mkt-page { display: flex; flex-direction: column; gap: var(--s-md); }
.mkt-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--success .kpi__icon { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi--warning .kpi__icon { background: var(--c-warning-bg, #FFF5E6); color: var(--c-warning-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; display: flex; align-items: baseline; gap: 6px; }
.kpi__sub { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); }

.mkt-toolbar { /* flex 作用在 CCard 根 .card 上影响不到 .card__body 内的子项，故下沉到 body */ }
.mkt-toolbar :deep(.card__body) {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-md);
}
.filters { display: flex; gap: var(--s-sm); flex: 1; min-width: 0; }
.filters > :deep(.cinput) { flex: 1; min-width: 0; max-width: 360px; }
.sel { flex-shrink: 0; height: 36px; padding: 0 12px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); }
.mkt-toolbar :deep(.cbtn) { flex-shrink: 0; }
@media (max-width: 1024px) {
  .mkt-toolbar :deep(.card__body) { flex-wrap: wrap; }
  .filters { width: 100%; }
  .filters > :deep(.cinput) { max-width: none; }
}

.chips-row { display: flex; gap: 6px; flex-wrap: wrap; }
.chip { border: 1px solid var(--c-border-light); background: var(--c-surface); padding: 5px 14px; border-radius: var(--r-capsule); font-size: var(--t-xs); color: var(--c-text-2); cursor: pointer; }
.chip:hover { border-color: var(--c-brand); color: var(--c-brand); }
.chip--on { background: var(--c-brand); color: #fff; border-color: var(--c-brand); }

.mkt-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--s-md); }
.camp { padding: var(--s-lg); cursor: pointer; transition: box-shadow .15s, border-color .15s; border: 1px solid var(--c-border-light); }
.camp:hover { box-shadow: var(--shadow-pop, 0 8px 24px rgba(0,0,0,.08)); }
.camp--active { border-color: var(--c-brand); box-shadow: 0 0 0 2px var(--c-brand-soft); }
.camp--off { opacity: .7; }
.camp__head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-sm); }
.camp__title { display: flex; align-items: center; gap: var(--s-sm); flex: 1; min-width: 0; }
.camp__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.camp__meta { display: flex; gap: var(--s-md); margin-top: 8px; font-size: var(--t-xs); color: var(--c-text-3); }
.camp__meta span { display: inline-flex; align-items: center; gap: 4px; }
.camp__ch { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 10px; }
.ch-chip { font-size: 11px; padding: 2px 10px; background: var(--c-surface, #f7f8fa); color: var(--c-text-2); border-radius: var(--r-capsule); border: 1px solid var(--c-border-light); }
.ch-chip--lg { font-size: var(--t-xs); padding: 4px 14px; }
.camp__metrics { display: flex; flex-direction: column; gap: var(--s-sm); margin-top: var(--s-md); padding-top: var(--s-md); border-top: 1px solid var(--c-border-light); }
.m__label { font-size: 11px; color: var(--c-text-3); }
.m__val { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); font-family: var(--t-number, monospace); }
.bar { height: 6px; background: var(--c-surface, #f7f8fa); border-radius: 3px; margin-top: 4px; overflow: hidden; }
.bar__fill { height: 100%; background: linear-gradient(90deg, var(--c-brand), var(--c-brand, #FF8FB5)); border-radius: 3px; transition: width .3s; }
.m--split { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-sm); padding-top: var(--s-sm); border-top: 1px dashed var(--c-border-light); }
.roi-hi { color: var(--c-success-fg); }
.empty { grid-column: 1 / -1; text-align: center; padding: var(--s-xl); color: var(--c-text-3); }

.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); z-index: 100; display: flex; justify-content: flex-end; }
.drawer { width: 560px; max-width: 100vw; background: var(--c-surface); height: 100%; display: flex; flex-direction: column; box-shadow: -8px 0 32px rgba(0,0,0,.12); }
.drawer__head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-md); padding: var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.drawer__title-row { display: flex; align-items: center; gap: var(--s-sm); }
.drawer__title-row h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.drawer__meta { display: flex; flex-wrap: wrap; gap: var(--s-md); margin-top: 6px; font-size: var(--t-xs); color: var(--c-text-3); }
.code { font-family: var(--t-number, monospace); background: var(--c-surface, #f7f8fa); padding: 1px 8px; border-radius: var(--r-sm); }
.drawer__close { border: none; background: none; cursor: pointer; color: var(--c-text-3); padding: 4px; }
.drawer__body { flex: 1; overflow-y: auto; padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }
.d-stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1px; background: var(--c-border-light); border: 1px solid var(--c-border-light); border-radius: var(--r-lg); overflow: hidden; }
.ds { background: var(--c-surface); padding: var(--s-md); }
.ds__label { font-size: 11px; color: var(--c-text-3); }
.ds__val { font-size: var(--t-md); font-weight: 700; color: var(--c-text); margin-top: 4px; font-family: var(--t-number, monospace); }
.ds__val--brand { color: var(--c-brand); }
.d-sec h4 { margin: 0 0 var(--s-sm); font-size: var(--t-sm); font-weight: 700; }
.ch-list { display: flex; flex-wrap: wrap; gap: 6px; }
.d-remark { margin: 0; padding: var(--s-sm) var(--s-md); background: var(--c-surface, #f7f8fa); border-left: 3px solid var(--c-brand); border-radius: var(--r-sm); font-size: var(--t-xs); color: var(--c-text-2); }
.table-wrap { border: 1px solid var(--c-border-light); border-radius: var(--r-md); overflow: auto; }
.dt { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.dt th { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); font-weight: 600; text-align: left; padding: 8px 12px; font-size: var(--t-xs); white-space: nowrap; border-bottom: 1px solid var(--c-border-light); }
.dt td { padding: 8px 12px; border-bottom: 1px solid var(--c-border-light); }
.dt tr:last-child td { border-bottom: none; }
.num { text-align: right; font-family: var(--t-number, monospace); }
.mono { font-family: var(--t-number, monospace); font-size: var(--t-xs); color: var(--c-text-2); }
.cell-name { font-weight: 600; }
.brand-txt { color: var(--c-brand); }
.drawer__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-top: 1px solid var(--c-border-light); }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 110; }
.modal { background: var(--c-surface); border-radius: var(--r-xl); width: 640px; max-width: calc(100vw - 48px); max-height: 86vh; display: flex; flex-direction: column; }
.modal--lg { width: 640px; }
.modal__head { display: flex; align-items: center; justify-content: space-between; padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.modal__head h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.modal__close { border: none; background: none; cursor: pointer; color: var(--c-text-3); padding: 4px; }
.modal__body { padding: var(--s-lg); overflow-y: auto; }
.modal__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-top: 1px solid var(--c-border-light); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.field { display: flex; flex-direction: column; gap: 6px; }
.field--full { grid-column: 1 / -1; }
.field__label { font-size: var(--t-xs); color: var(--c-text-2); font-weight: 500; }
.field__label i { color: var(--c-danger-fg); font-style: normal; }
.ch-pick { display: flex; flex-wrap: wrap; gap: var(--s-sm); }
.ch-opt { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); padding: 6px 14px; border: 1px solid var(--c-border); border-radius: var(--r-capsule); cursor: pointer; color: var(--c-text-2); }
.ch-opt input { accent-color: var(--c-brand); }
.ch-opt.is-on { border-color: var(--c-brand); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }
.form-err { margin-top: var(--s-sm); color: var(--c-danger-fg); font-size: var(--t-xs); }

@media (max-width: 1024px) {
  .mkt-kpis { grid-template-columns: repeat(2, 1fr); }
  .mkt-grid { grid-template-columns: 1fr; }
  .drawer { width: 100%; }
}
</style>
