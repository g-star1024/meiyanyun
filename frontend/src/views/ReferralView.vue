<script setup lang="ts">
/* ============================================================
 * M3-11 转介绍管理 /m3-referral
 * 左列表（tab 筛选）+ 右详情（介绍人/被介绍人/关系链时间线）+ 奖励配置卡片。
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
import {
  useReferralStore,
  type Referral,
  type RewardType,
} from '@/stores/referral'
import { REFERRAL_STATUS, REWARD_STATUS, dictPill } from '@/config/dictionary'
import { useAuthStore } from '@/stores/auth'

const store = useReferralStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<Referral | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '转介绍总数', icon: 'marketing', value: `${store.total} 条`, tone: 'teal' as const },
  { label: '本月新增', icon: 'customer', value: String(store.newThisMonth), tone: 'brand' as const },
  { label: '已成交', icon: 'finance', value: `${store.dealt.length} 人`, tone: 'success' as const },
  { label: '待发奖励', icon: 'finance', value: `${store.pendingReward.length} 笔`, tone: 'warning' as const },
])

const tabs = computed(() => [
  { key: 'ALL' as const, label: `全部 (${store.total})` },
  { key: 'PENDING' as const, label: `待确认 (${store.referrals.filter(r => r.status === 'PENDING').length})` },
  { key: 'VISITED' as const, label: `已到店 (${store.referrals.filter(r => r.status === 'VISITED' || r.status === 'DEAL').length})` },
  { key: 'DEAL' as const, label: `已成交 (${store.dealt.length})` },
  { key: 'REWARD' as const, label: `待发奖 (${store.pendingReward.length})` },
])

const rewardTypeIcon: Record<RewardType, string> = {
  POINTS: 'sun',
  COUPON: 'card',
  CASH: 'finance',
}

function fmtDate(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function fmtMoney(n: number) {
  return `¥${n.toLocaleString()}`
}

// 新建绑定弹层
const showForm = ref(false)
const form = ref({
  referrerName: '',
  referrerLevel: '金卡',
  introducedName: '',
  rewardType: 'CASH' as RewardType,
  rewardAmount: 200,
})
const canSubmit = computed(() => form.value.referrerName.trim() && form.value.introducedName.trim())
function openForm() {
  form.value = { referrerName: '', referrerLevel: '金卡', introducedName: '', rewardType: 'CASH', rewardAmount: 200 }
  showForm.value = true
}
function submitForm() {
  if (!canSubmit.value) return
  const r = store.create({ ...form.value })
  if (r) {
    showForm.value = false
    selectedId.value = r.id
  }
}

// 成交弹层
const showDeal = ref(false)
const dealAmount = ref(2000)
function openDeal() {
  dealAmount.value = selected.value?.dealAmount || 2000
  showDeal.value = true
}
function submitDeal() {
  if (!selected.value) return
  store.markDeal(selected.value.id, dealAmount.value)
  showDeal.value = false
}

function doConfirm() { if (selected.value) store.confirm(selected.value.id) }
function doVisited() { if (selected.value) store.markVisited(selected.value.id) }
function doPay() { if (selected.value) store.payReward(selected.value.id) }
</script>

<template>
  <div class="rf">
    <div class="rf__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="rf__body">
      <CCard class="rf__list" padding="none">
        <div class="tabs">
          <button
            v-for="t in tabs" :key="t.key"
            class="tab" :class="{ 'tab--active': store.filterTab === t.key }"
            @click="store.filterTab = t.key"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon :name="('customer' as any)" :size="28" class="empty__icon" />
            <div>暂无转介绍记录</div>
          </div>
          <button
            v-for="r in store.filtered" :key="r.id"
            class="row" :class="{ 'row--active': selected?.id === r.id }"
            @click="selectedId = r.id"
          >
            <div class="row__chain">
              <span class="row__name">{{ r.referrerName }}</span>
              <CIcon name="chevron-right" :size="14" class="row__arrow" />
              <span class="row__name row__name--new">{{ r.introducedName }}</span>
            </div>
            <div class="row__meta">
              <CStatusPill :status="dictPill(REFERRAL_STATUS[r.status]).status">{{ dictPill(REFERRAL_STATUS[r.status]).text }}</CStatusPill>
              <CStatusPill :status="dictPill(REWARD_STATUS[r.rewardStatus]).status">{{ dictPill(REWARD_STATUS[r.rewardStatus]).text }}</CStatusPill>
            </div>
            <div class="row__sub">
              <span><CIcon name="calendar" :size="12" /> {{ fmtDate(r.boundAt) }}</span>
              <span class="row__reward">
                <CIcon :name="(rewardTypeIcon[r.rewardType] as any)" :size="12" />
                {{ fmtMoney(r.rewardAmount) }}
              </span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '绑定关系', disabled: !auth.can('referral:edit'), onClick: openForm }]"
          />
        </div>
      </CCard>

      <div class="rf__right">
        <CCard v-if="selected" class="rf__detail" :title="`${selected.referrerName} → ${selected.introducedName}`">
          <template #header>
            <h3 class="rf__detail-title">
              <CIcon name="customer" :size="18" />
              {{ selected.referrerName }}
              <CIcon name="chevron-right" :size="14" class="title-arrow" />
              {{ selected.introducedName }}
            </h3>
            <CStatusPill :status="dictPill(REFERRAL_STATUS[selected.status]).status">{{ dictPill(REFERRAL_STATUS[selected.status]).text }}</CStatusPill>
          </template>

          <div class="people">
            <div class="person">
              <div class="person__role">介绍人</div>
              <div class="person__name">{{ selected.referrerName }} · {{ selected.referrerLevel }}</div>
              <div class="person__meta">{{ selected.referrerPhone }}</div>
              <div class="person__stat">累计介绍 <strong>{{ selected.referrerTotal }}</strong> 人</div>
            </div>
            <div class="people__divider"><CIcon name="chevron-right" :size="20" /></div>
            <div class="person person--new">
              <div class="person__role">被介绍人（新客）</div>
              <div class="person__name">{{ selected.introducedName }}</div>
              <div class="person__meta">{{ selected.introducedPhone }}</div>
              <div class="person__stat">成交金额 <strong>{{ selected.dealAmount ? fmtMoney(selected.dealAmount) : '—' }}</strong></div>
            </div>
          </div>

          <div class="reward-bar">
            <div class="reward-bar__label">
              <CIcon :name="(rewardTypeIcon[selected.rewardType] as any)" :size="16" />
              奖励：{{ store.REWARD_TYPE_LABEL[selected.rewardType] }} {{ fmtMoney(selected.rewardAmount) }}
            </div>
            <CStatusPill :status="dictPill(REWARD_STATUS[selected.rewardStatus]).status">{{ dictPill(REWARD_STATUS[selected.rewardStatus]).text }}</CStatusPill>
          </div>

          <div class="detail__timeline">
            <div class="detail__sec-title">关系链时间线</div>
            <div class="timeline">
              <div v-for="(t, i) in selected.timeline" :key="i" class="tl-item">
                <div class="tl-item__dot" :class="{ 'tl-item__dot--done': true }" />
                <div class="tl-item__body">
                  <div class="tl-item__head">
                    <span class="tl-item__action">{{ t.action }}</span>
                    <span class="tl-item__time">{{ fmtDate(t.at) }}</span>
                  </div>
                  <div class="tl-item__by">操作人：{{ t.by }}</div>
                </div>
              </div>
            </div>
          </div>

          <div class="detail__ops">
            <template v-if="selected.status === 'PENDING'">
              <CButton variant="ghost" v-perm.disable="'referral:edit'" @click="doConfirm">
                <CIcon name="check" :size="16" />确认归属
              </CButton>
            </template>
            <template v-else-if="selected.status === 'CONFIRMED'">
              <CButton variant="primary" v-perm.disable="'referral:edit'" @click="doVisited">
                <CIcon name="store" :size="16" />标记到店
              </CButton>
            </template>
            <template v-else-if="selected.status === 'VISITED'">
              <CButton variant="primary" v-perm.disable="'referral:edit'" @click="openDeal">
                <CIcon name="check-square" :size="16" />标记成交
              </CButton>
            </template>
            <template v-else-if="selected.status === 'DEAL' && selected.rewardStatus === 'PENDING'">
              <CButton variant="primary" v-perm.disable="'referral:approve'" @click="doPay">
                <CIcon name="finance" :size="16" />发放奖励 {{ fmtMoney(selected.rewardAmount) }}
              </CButton>
            </template>
            <div v-else-if="selected.rewardStatus === 'PAID'" class="ops__done">
              <CIcon name="check" :size="16" />奖励已于 {{ fmtDate(selected.paidAt) }} 发放
            </div>
          </div>
        </CCard>

        <CCard v-else class="rf__detail rf__detail--empty" title="转介绍详情">
          <div class="detail-empty">
            <CIcon :name="('customer' as any)" :size="40" class="detail-empty__icon" />
            <p>请选择一条转介绍记录</p>
          </div>
        </CCard>

        <!-- 奖励配置卡片 -->
        <CCard class="rf__rules" title="奖励规则配置" padding="lg">
          <div class="rules">
            <div v-for="rule in store.rules" :key="rule.type" class="rule">
              <div class="rule__head">
                <div class="rule__icon" :class="`rule__icon--${rule.type.toLowerCase()}`">
                  <CIcon :name="(rewardTypeIcon[rule.type] as any)" :size="18" />
                </div>
                <div>
                  <div class="rule__name">{{ rule.name }}</div>
                  <div class="rule__threshold">{{ rule.threshold }}</div>
                </div>
                <div class="rule__amount">{{ fmtMoney(rule.amount) }}</div>
              </div>
              <div class="rule__desc">{{ rule.desc }}</div>
            </div>
          </div>
          <div class="rules__audit">
            <CIcon name="shield" :size="12" />
            绑定关系经服务端循环校验 · 奖励发放走 T3-01 审核 · 写 T1-04 审计
          </div>
        </CCard>
      </div>
    </div>

    <!-- 新建绑定弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="绑定转介绍关系" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">介绍人姓名</label>
              <CInput v-model="form.referrerName" placeholder="如：林晚" />
            </div>
            <div>
              <label class="form__label">介绍人等级</label>
              <CSelect v-model="form.referrerLevel" :options="[
                { value: '普通', label: '普通' },
                { value: '银卡', label: '银卡' },
                { value: '金卡', label: '金卡' },
                { value: '白金', label: '白金' },
                { value: '黑卡', label: '黑卡' },
              ]" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">被介绍人姓名（新客）</label>
            <CInput v-model="form.introducedName" placeholder="如：苏晴" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">奖励类型</label>
              <CSelect v-model="form.rewardType" :options="[
                { value: 'CASH', label: '现金' },
                { value: 'COUPON', label: '项目券' },
                { value: 'POINTS', label: '积分' },
              ]" />
            </div>
            <div>
              <label class="form__label">奖励金额</label>
              <input v-model.number="form.rewardAmount" type="number" placeholder="200" class="native-input" />
            </div>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">绑定</CButton>
        </template>
      </CCard>
    </div>

    <!-- 成交弹层 -->
    <div v-if="showDeal" class="modal-mask" @click.self="showDeal = false">
      <CCard class="modal modal--sm" title="标记成交" padding="lg">
        <label class="form__label">成交金额</label>
        <input v-model.number="dealAmount" type="number" placeholder="2000" class="native-input" />
        <template #footer>
          <CButton variant="ghost" @click="showDeal = false">取消</CButton>
          <CButton variant="primary" @click="submitDeal">确认成交</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.rf { display: flex; flex-direction: column; gap: var(--s-lg); }
.rf__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .rf__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.rf__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.rf__list { min-width: 0; position: relative; display: flex; flex-direction: column; }
.rf__right { display: flex; flex-direction: column; gap: var(--s-lg); min-width: 0; }

.tabs { display: flex; gap: var(--s-xs); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; align-items: center; }
.tab { padding: 6px 14px; border-radius: var(--r-md); border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text-2); font-size: var(--t-sm); cursor: pointer; white-space: nowrap; flex-shrink: 0; }
.tab:hover { color: var(--c-brand); border-color: var(--c-brand); }
.tab--active { background: var(--c-brand-soft); border-color: var(--c-brand); color: var(--c-brand); font-weight: 600; }

.list { max-height: 620px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg) 64px; color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__chain { display: flex; align-items: center; gap: var(--s-xs); margin-bottom: var(--s-xs); }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__name--new { color: var(--c-brand); }
.row__arrow { color: var(--c-text-3); }
.row__meta { display: flex; gap: var(--s-xs); margin-bottom: var(--s-xs); }
.row__sub { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.row__sub span { display: inline-flex; align-items: center; gap: 3px; }
.row__reward { color: var(--c-warning-fg); font-weight: 600; }

.rf__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; display: inline-flex; align-items: center; gap: var(--s-xs); }
.title-arrow { color: var(--c-text-3); }

.people { display: grid; grid-template-columns: 1fr auto 1fr; gap: var(--s-md); align-items: center; padding: var(--s-lg) 0; border-bottom: 1px solid var(--c-border-light); }
.person { padding: var(--s-md); background: var(--c-surface-muted); border-radius: var(--r-md); }
.person--new { background: var(--c-brand-soft); }
.person__role { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.person__name { font-size: var(--t-base); font-weight: 700; color: var(--c-text); margin-bottom: 4px; }
.person--new .person__name { color: var(--c-brand); }
.person__meta { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.person__stat { font-size: var(--t-sm); color: var(--c-text-2); }
.person__stat strong { color: var(--c-text); font-size: var(--t-md); }
.people__divider { color: var(--c-text-3); display: flex; align-items: center; justify-content: center; }

.reward-bar { display: flex; justify-content: space-between; align-items: center; padding: var(--s-md); background: var(--c-warning-bg); border-radius: var(--r-md); margin: var(--s-md) 0; }
.reward-bar__label { display: inline-flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-warning-fg); }

.detail__timeline { padding: var(--s-md) 0; }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.timeline { display: flex; flex-direction: column; gap: var(--s-sm); }
.tl-item { display: flex; gap: var(--s-sm); position: relative; padding-left: var(--s-sm); }
.tl-item__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-brand); margin-top: 6px; flex-shrink: 0; }
.tl-item__body { flex: 1; }
.tl-item__head { display: flex; justify-content: space-between; align-items: baseline; font-size: var(--t-sm); gap: var(--s-sm); }
.tl-item__action { font-weight: 600; color: var(--c-text); }
.tl-item__time { font-size: var(--t-xs); color: var(--c-text-3); }
.tl-item__by { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__done { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-success-fg); font-weight: 600; margin-left: auto; }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

/* 奖励规则 */
.rf__rules { box-shadow: var(--shadow-card); }
.rules { display: flex; flex-direction: column; gap: var(--s-md); }
.rule { padding: var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-md); }
.rule__head { display: flex; align-items: center; gap: var(--s-md); }
.rule__icon { width: 40px; height: 40px; border-radius: var(--r-md); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.rule__icon--points { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.rule__icon--coupon { background: var(--c-brand-soft); color: var(--c-brand); }
.rule__icon--cash { background: var(--c-success-bg, #e6f9ed); color: var(--c-success-fg); }
.rule__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.rule__threshold { font-size: var(--t-xs); color: var(--c-text-3); }
.rule__amount { margin-left: auto; font-size: var(--t-lg); font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }
.rule__desc { font-size: var(--t-xs); color: var(--c-text-2); margin-top: var(--s-sm); padding-left: 56px; }
.rules__audit { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-md); padding-top: var(--s-md); border-top: 1px dashed var(--c-border-light); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--sm { width: 400px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.native-input {
  width: 100%;
  padding: var(--s-sm) var(--s-md);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  font-size: var(--t-sm);
  color: var(--c-text);
}
.native-input:focus { outline: none; border-color: var(--c-brand); }

@media (max-width: 1024px) {
  .rf__body { grid-template-columns: 1fr; }
  .rf__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .people { grid-template-columns: 1fr; }
  .people__divider { transform: rotate(90deg); padding: var(--s-xs) 0; }
  .list { max-height: 320px; }
}
</style>
