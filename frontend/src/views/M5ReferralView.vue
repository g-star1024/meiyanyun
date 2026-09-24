<script setup lang="ts">
/* ============================================================
 * M5-11 老带新 /m5-referral
 * 4 KPI（进行中活动/累计邀请/成功转化/待发奖励）
 * 左：老带新关系链列表（消费 referral store）
 * 右：关系时间线 + 层级奖励规则（可编辑）+ 邀请排行 Top5
 * 主按钮「配置邀请机制」弹层：奖励形式/阶梯/有效期/话术（敏感词）
 * 「审核奖励」弹层：确认发放，需要 referral:approve
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CBarChart from '@/components/CBarChart.vue'
import { useM5ReferralCampaignStore, type InviteCampaign, type CampaignStatus } from '@/stores/m5ReferralCampaign'
import { useReferralStore, type RewardType } from '@/stores/referral'
import { useAuthStore } from '@/stores/auth'
import { searchCustomers, type CustomerDTO } from '@/api/customer'
import { checkSensitive } from '@/composables/useSensitiveWords'

const store = useM5ReferralCampaignStore()
const referral = useReferralStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const canEdit = computed(() => auth.can('referralCampaign:edit'))
const canApprove = computed(() => auth.can('referral:approve'))

const selectedId = ref<string | null>(null)
const selected = computed(() => {
  if (selectedId.value) return referral.get(selectedId.value) ?? null
  return referral.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '进行中邀请活动', icon: 'marketing', value: String(store.ongoingCount), tone: 'brand' as const },
  { label: '累计邀请人数', icon: 'customer', value: String(store.totalInvited), tone: 'teal' as const },
  { label: '成功转化', icon: 'trend-up', value: `${store.convertedCount} 人`, tone: 'orange' as const },
  {
    label: '待发奖励', icon: 'finance',
    value: `${store.pendingRewardCount} 笔 / ¥${store.pendingRewardAmount.toLocaleString('zh-CN')}`,
    tone: store.pendingRewardCount > 0 ? ('warning' as const) : ('success' as const),
  },
])

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN')}`
}

function rewardLabel(t: RewardType, n: number) {
  if (t === 'POINTS') return `${n} 积分`
  if (t === 'COUPON') return `${money(n)} 项目券`
  return money(n)
}

const STATUS_PILL: Record<string, 'default' | 'primary' | 'info' | 'success' | 'danger'> = {
  PENDING: 'default', CONFIRMED: 'primary', VISITED: 'info', DEAL: 'success',
  EXPIRED: 'default', REJECTED: 'danger',
}
const REWARD_PILL: Record<string, 'warning' | 'success' | 'danger'> = { PENDING: 'warning', PAID: 'success', REJECTED: 'danger' }

function fmtTime(iso: string) {
  try { return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) } catch { return iso }
}

const timelineRows = computed(() => {
  if (!selected.value) return []
  // referral.timeline 为倒序（最新在前），这里正序展示
  return [...selected.value.timeline].reverse()
})

const rankItems = computed(() =>
  store.topReferrers.map((r) => ({ label: r.name, values: [r.total] })))

// ---------- 配置邀请机制弹层 ----------
const showConfig = ref(false)
const cfgForm = ref<{ rewardType: RewardType; validDays: number; script: string; ladders: { threshold: number; type: RewardType; amount: number; desc: string }[] }>({
  rewardType: 'CASH', validDays: 30, script: '', ladders: [],
})
const cfgError = ref('')

function openConfig() {
  if (!canEdit.value) return
  cfgForm.value = {
    rewardType: store.rewardType,
    validDays: store.validDays,
    script: store.script,
    ladders: store.ladders.map((l) => ({ ...l })),
  }
  cfgError.value = ''
  showConfig.value = true
}
const rewardTypeOptions = store.REWARD_TYPE_OPTIONS

function saveConfig() {
  cfgError.value = ''
  if (!cfgForm.value.script.trim()) { cfgError.value = '请填写邀请话术'; return }
  const hit = checkSensitive(cfgForm.value.script)
  if (hit.hit) { cfgError.value = hit.message; return }
  for (const l of cfgForm.value.ladders) {
    if (l.threshold <= 0 || l.amount < 0) { cfgError.value = '阶梯门槛与奖励需为非负数'; return }
  }
  store.saveConfig({
    rewardType: cfgForm.value.rewardType,
    validDays: Number(cfgForm.value.validDays) || 30,
    script: cfgForm.value.script.trim(),
    ladders: cfgForm.value.ladders.map((l, i) => ({
      ...l,
      threshold: Number(l.threshold) || i + 1,
      amount: Number(l.amount) || 0,
      desc: `邀请 ${l.threshold} 人${l.threshold >= 3 ? '成交' : '到店'}，奖励 ${l.type === 'POINTS' ? l.amount + ' 积分' : money(l.amount)}`,
    })),
  })
  showConfig.value = false
}

// 层级奖励就地编辑
const editLevel = ref<1 | 2 | null>(null)
const levelDraft = ref(0)
function startEditLevel(lvl: 1 | 2) {
  if (!canEdit.value) return
  editLevel.value = lvl
  levelDraft.value = Math.round((store.levelReward(lvl)?.rate ?? 0) * 100)
}
function saveLevel(lvl: 1 | 2) {
  store.updateLevelRate(lvl, (Number(levelDraft.value) || 0) / 100)
  editLevel.value = null
}

// ---------- 审核奖励弹层 ----------
const showApprove = ref(false)
const approveTarget = ref<typeof selected.value>(null)
function openApprove() {
  if (!selected.value || selected.value.rewardStatus !== 'PENDING' || selected.value.status !== 'DEAL') return
  approveTarget.value = selected.value
  showApprove.value = true
}
async function confirmApprove() {
  if (approveTarget.value) {
    const ok = await store.approveReward(approveTarget.value.id)
    if (ok) showApprove.value = false
  }
}

// ---------- P5-B91 卡2：邀请活动区块（新建/编辑/状态流转） ----------
const showCamp = ref(false)
const campEditId = ref<string | null>(null)
const campForm = ref({ name: '', startAt: '', endAt: '', storeCode: '', remark: '' })
const campError = ref('')
const campSaving = ref(false)

function openCampCreate() {
  if (!canEdit.value) return
  campEditId.value = null
  campForm.value = { name: '', startAt: '', endAt: '', storeCode: '', remark: '' }
  campError.value = ''
  showCamp.value = true
}

function openCampEdit(c: InviteCampaign) {
  if (!canEdit.value || c.status === 'ENDED') return
  campEditId.value = c.id
  campForm.value = {
    name: c.name,
    startAt: c.startAt,
    endAt: c.endAt,
    storeCode: c.storeCode ?? '',
    remark: c.remark ?? '',
  }
  campError.value = ''
  showCamp.value = true
}

async function saveCampaign() {
  campError.value = ''
  const f = campForm.value
  if (!f.name.trim()) { campError.value = '请填写活动名称'; return }
  const hit = checkSensitive(f.name)
  if (hit.hit) { campError.value = hit.message; return }
  if (!f.startAt || !f.endAt) { campError.value = '请选择起止日期'; return }
  if (f.startAt > f.endAt) { campError.value = '开始日期不能晚于结束日期'; return }
  const payload = {
    name: f.name.trim(),
    startAt: f.startAt,
    endAt: f.endAt,
    storeCode: f.storeCode.trim() || undefined,
    remark: f.remark.trim() || undefined,
  }
  campSaving.value = true
  const ok = campEditId.value
    ? await store.updateCampaign(campEditId.value, payload)
    : await store.createCampaign(payload)
  campSaving.value = false
  if (ok) showCamp.value = false
  else campError.value = '保存失败：请检查权限或日期/状态合法性（已结束活动禁止编辑）'
}

async function transitionCampaign(c: InviteCampaign, target: CampaignStatus) {
  await store.transitionCampaignStatus(c.id, target)
}

// ---------- P5-B91 卡2：新建绑定弹层 ----------
const canBind = computed(() => auth.can('referral:edit'))
const showBind = ref(false)
const bindForm = ref<{
  referrer: CustomerDTO | null
  referee: CustomerDTO | null
  campaignId: string
  validDays?: number
  remark: string
}>({ referrer: null, referee: null, campaignId: '', validDays: undefined, remark: '' })
const referrerKw = ref('')
const refereeKw = ref('')
const referrerOptions = ref<CustomerDTO[]>([])
const refereeOptions = ref<CustomerDTO[]>([])
const bindError = ref('')
const bindSaving = ref(false)

const ongoingCampaignOptions = computed(() => [
  { value: '', label: '不挂活动' },
  ...store.campaigns
    .filter((c) => c.status === 'ONGOING')
    .map((c) => ({ value: c.id, label: c.name })),
])

function openBind() {
  if (!canBind.value) return
  bindForm.value = { referrer: null, referee: null, campaignId: '', validDays: undefined, remark: '' }
  referrerKw.value = ''
  refereeKw.value = ''
  referrerOptions.value = []
  refereeOptions.value = []
  bindError.value = ''
  showBind.value = true
}

async function searchReferrer() {
  const q = referrerKw.value.trim()
  if (!q) { referrerOptions.value = []; return }
  try {
    referrerOptions.value = (await searchCustomers(q)).data
      .filter((c) => c.customerId !== bindForm.value.referee?.customerId)
      .slice(0, 8)
  } catch { referrerOptions.value = [] }
}

async function searchReferee() {
  const q = refereeKw.value.trim()
  if (!q) { refereeOptions.value = []; return }
  try {
    refereeOptions.value = (await searchCustomers(q)).data
      .filter((c) => c.customerId !== bindForm.value.referrer?.customerId)
      .slice(0, 8)
  } catch { refereeOptions.value = [] }
}

async function saveBind() {
  bindError.value = ''
  const f = bindForm.value
  if (!f.referrer) { bindError.value = '请选择推荐人'; return }
  if (!f.referee) { bindError.value = '请选择被推荐人'; return }
  if (f.referrer.customerId === f.referee.customerId) { bindError.value = '推荐人与被推荐人不能是同一人'; return }
  if (f.validDays != null && (f.validDays < 1 || f.validDays > 365)) { bindError.value = '有效期需为 1-365 天'; return }
  bindSaving.value = true
  const ok = await referral.createBinding({
    referrerCustomerId: f.referrer.customerId,
    refereeCustomerId: f.referee.customerId,
    campaignId: f.campaignId || undefined,
    validDays: f.validDays,
    remark: f.remark.trim() || undefined,
  })
  bindSaving.value = false
  if (ok) showBind.value = false
  else bindError.value = '绑定失败：可能已存在有效绑定、活动不可用或数据越权'
}
</script>

<template>
  <div class="mr">
    <div class="mr__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- P5-B91 卡2：邀请活动区块（统计列接真 D8 + CRUD D10） -->
    <CCard class="mr__camp" padding="none">
      <div class="filters">
        <div class="filters__left">
          <span class="filters__title">
            <CIcon name="gift" :size="14" />邀请活动
          </span>
          <span class="filters__count">共 {{ store.campaigns.length }} 场</span>
        </div>
        <CButton v-if="canEdit" variant="primary" size="sm" class="filters__btn" @click="openCampCreate">
          <CIcon name="plus" :size="14" />新建活动
        </CButton>
      </div>
      <div class="camp-table">
        <div v-if="store.campaigns.length === 0" class="empty">
          <CIcon name="gift" :size="28" class="empty__icon" />
          <div>暂无邀请活动，点击右上角新建</div>
        </div>
        <div v-for="c in store.campaigns" :key="c.id" class="camp-row">
          <div class="camp-row__main">
            <span class="camp-row__name">{{ c.name }}</span>
            <CStatusPill :status="store.CAMPAIGN_STATUS_PILL[c.status]" dot>
              {{ store.CAMPAIGN_STATUS_LABEL[c.status] }}
            </CStatusPill>
          </div>
          <span class="camp-row__date">{{ c.startAt }} ~ {{ c.endAt }}</span>
          <span class="camp-row__stat">邀请 {{ c.invited }} 人</span>
          <span class="camp-row__stat">转化 {{ c.converted }} 人</span>
          <div class="camp-row__ops">
            <CButton v-if="canEdit && c.status !== 'ENDED'" variant="text" size="sm" @click="openCampEdit(c)">编辑</CButton>
            <CButton v-if="canEdit && c.status === 'DRAFT'" variant="text" size="sm" @click="transitionCampaign(c, 'ONGOING')">开始</CButton>
            <CButton v-if="canEdit && c.status === 'ONGOING'" variant="text" size="sm" @click="transitionCampaign(c, 'ENDED')">结束</CButton>
          </div>
        </div>
      </div>
    </CCard>

    <div class="mr__body">
      <!-- 左：关系链列表 -->
      <CCard class="mr__list" padding="none">
        <div class="filters">
          <div class="filters__left">
            <span class="filters__title">
              <CIcon name="handover" :size="14" />老带新关系链
            </span>
            <span class="filters__count">共 {{ referral.filtered.length }} 条</span>
          </div>
          <div class="filters__right">
            <CButton v-if="canBind" variant="ghost" size="sm" class="filters__btn" @click="openBind">
              <CIcon name="plus" :size="14" />新建绑定
            </CButton>
            <CButton v-if="canEdit" variant="primary" size="sm" class="filters__btn" @click="openConfig">
              <CIcon name="settings" :size="14" />配置邀请机制
            </CButton>
          </div>
        </div>
        <div class="list">
          <div v-if="referral.filtered.length === 0" class="empty">
            <CIcon name="customer" :size="28" class="empty__icon" />
            <div>暂无转介绍关系</div>
          </div>
          <button
            v-for="r in referral.filtered" :key="r.id"
            class="row" :class="{ 'row--active': selected?.id === r.id }"
            @click="selectedId = r.id"
          >
            <div class="row__avatar">{{ r.referrerName.slice(0, 1) }}</div>
            <div class="row__main">
              <div class="row__top">
                <span class="row__name">{{ r.referrerName }}</span>
                <CStatusPill :status="STATUS_PILL[r.status]" dot>
                  {{ referral.STATUS_LABEL[r.status] }}
                </CStatusPill>
              </div>
              <div class="row__sub">
                <CIcon name="chevron-right" :size="12" />
                {{ r.introducedName }} · {{ r.referrerLevel }} · {{ rewardLabel(r.rewardType, r.rewardAmount) }}
              </div>
              <div v-if="r.dealAmount" class="row__amount">成交 {{ money(r.dealAmount) }}</div>
            </div>
            <CStatusPill :status="REWARD_PILL[r.rewardStatus]">
              {{ referral.REWARD_LABEL[r.rewardStatus] }}
            </CStatusPill>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="mr__detail" padding="none">
        <template #header>
          <div class="mr__detail-head">
            <div class="mr__who">
              <div class="mr__avatar">{{ selected.referrerName.slice(0, 1) }}</div>
              <div>
                <h3>{{ selected.referrerName }} → {{ selected.introducedName }}</h3>
                <div class="mr__sub">{{ selected.referrerLevel }} · 累计推荐 {{ selected.referrerTotal }} 人 · 绑定于 {{ selected.boundAt.slice(0, 10) }}</div>
              </div>
            </div>
            <div class="mr__head-pills">
              <CStatusPill :status="STATUS_PILL[selected.status]" dot>{{ referral.STATUS_LABEL[selected.status] }}</CStatusPill>
              <CStatusPill :status="REWARD_PILL[selected.rewardStatus]">{{ referral.REWARD_LABEL[selected.rewardStatus] }}</CStatusPill>
            </div>
          </div>
        </template>

        <div class="detail-body">
          <!-- 时间线 + 奖励规则 -->
          <div class="two-col">
            <div class="block">
              <div class="block__title"><span>关系时间线</span></div>
              <div class="timeline">
                <div v-for="(t, i) in timelineRows" :key="i" class="tl">
                  <div class="tl__dot" :class="{ 'tl__dot--last': i === timelineRows.length - 1 }" />
                  <div class="tl__body">
                    <div class="tl__action">{{ t.action }}</div>
                    <div class="tl__meta">{{ fmtTime(t.at) }} · {{ t.by }}</div>
                  </div>
                </div>
              </div>
            </div>

            <div class="block">
              <div class="block__title">
                <span>层级奖励规则</span>
                <span class="block__hint">按成交额返佣</span>
              </div>
              <div class="levels">
                <div v-for="lv in store.levels" :key="lv.level" class="level">
                  <div class="level__head">
                    <span class="level__tag" :class="`level__tag--${lv.level}`">{{ lv.level }} 级</span>
                    <div v-if="editLevel !== lv.level" class="level__rate">
                      {{ (lv.rate * 100).toFixed(0) }}%
                      <CButton v-if="canEdit" variant="text" size="sm" @click="startEditLevel(lv.level)">
                        <CIcon name="edit" :size="12" />调整
                      </CButton>
                    </div>
                    <div v-else class="level__edit">
                      <CInput
                        :model-value="String(levelDraft)"
                        @update:model-value="levelDraft = Number($event) || 0"
                        type="number"
                      />
                      <span class="level__unit">%</span>
                      <CButton variant="text" size="sm" @click="saveLevel(lv.level)">保存</CButton>
                    </div>
                  </div>
                  <div class="level__desc">{{ lv.desc }}</div>
                </div>
              </div>

              <div class="reward-summary">
                <div>
                  <div class="reward-summary__label">本单奖励金额</div>
                  <div class="reward-summary__value">{{ rewardLabel(selected.rewardType, selected.rewardAmount) }}</div>
                </div>
                <CButton
                  v-if="selected.status === 'DEAL' && selected.rewardStatus === 'PENDING'"
                  variant="primary" size="sm"
                  v-perm.disable="'referral:approve'"
                  @click="openApprove"
                >
                  <CIcon name="check-square" :size="14" />审核奖励
                </CButton>
                <span v-else-if="selected.rewardStatus === 'PAID'" class="reward-summary__done">
                  <CIcon name="check" :size="12" />已于 {{ selected.paidAt?.slice(0, 10) }} 发放
                </span>
                <span v-else class="reward-summary__wait">
                  <CIcon name="clock" :size="12" />成交后可发放奖励
                </span>
              </div>
            </div>
          </div>

          <!-- 阶梯奖励 + 邀请话术 -->
          <div class="block">
            <div class="block__title"><span>阶梯奖励（当前生效）</span></div>
            <div class="ladders">
              <div v-for="(l, i) in store.ladders" :key="i" class="ladder">
                <div class="ladder__badge">邀请 {{ l.threshold }} 人</div>
                <div class="ladder__reward">{{ rewardLabel(l.type, l.amount) }}</div>
                <div class="ladder__desc">{{ l.desc }}</div>
              </div>
            </div>
            <div class="script-box">
              <div class="script-box__title"><CIcon name="chat" :size="14" />邀请话术</div>
              <div class="script-box__text">{{ store.script }}</div>
              <div class="script-box__meta">有效期 {{ store.validDays }} 天 · 奖励形式：{{ store.REWARD_TYPE_OPTIONS.find((o) => o.value === store.rewardType)?.label }}</div>
            </div>
          </div>

          <!-- 邀请排行 -->
          <div class="block">
            <div class="block__title">
              <span>邀请排行 Top 5</span>
              <span class="block__hint">按累计邀请人数</span>
            </div>
            <CBarChart
              :items="rankItems"
              orientation="horizontal"
              :height="220"
              :show-value="true"
              unit=" 人"
            />
          </div>
        </div>
      </CCard>

      <CCard v-else class="mr__detail mr__detail--empty" title="关系详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="customer" :size="40" class="detail-empty__icon" />
          <p>请选择一条转介绍关系</p>
        </div>
      </CCard>
    </div>

    <!-- 配置邀请机制弹层 -->
    <div v-if="showConfig" class="modal-mask" @click.self="showConfig = false">
      <CCard class="modal modal--lg" title="配置邀请机制" padding="lg">
        <div class="form">
          <div class="form-row">
            <div class="form-col">
              <label class="form__label">奖励形式</label>
              <CSelect v-model="cfgForm.rewardType" width="100%" :options="rewardTypeOptions" />
            </div>
            <div class="form-col">
              <label class="form__label">有效期（天）</label>
              <CInput
                :model-value="String(cfgForm.validDays)"
                @update:model-value="cfgForm.validDays = Number($event) || 0"
                type="number"
              />
            </div>
          </div>

          <label class="form__label">阶梯奖励（邀请人数门槛）</label>
          <div class="ladder-form">
            <div v-for="(l, i) in cfgForm.ladders" :key="i" class="ladder-form__row">
              <CInput
                :model-value="String(l.threshold)"
                @update:model-value="l.threshold = Number($event) || 0"
                type="number"
              />
              <span class="ladder-form__unit">人</span>
              <CSelect
                v-model="l.type"
                width="110px"
                :options="rewardTypeOptions"
              />
              <CInput
                :model-value="String(l.amount)"
                @update:model-value="l.amount = Number($event) || 0"
                type="number"
              />
            </div>
          </div>

          <label class="form__label">邀请话术</label>
          <CTextarea v-model="cfgForm.script" :rows="3" placeholder="发给客户的邀请文案，提交前将进行违禁词校验" />

          <div v-if="cfgError" class="form__error">
            <CIcon name="alert" :size="14" />{{ cfgError }}
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showConfig = false">取消</CButton>
          <CButton variant="primary" @click="saveConfig">保存配置</CButton>
        </template>
      </CCard>
    </div>

    <!-- 审核奖励弹层 -->
    <div v-if="showApprove && approveTarget" class="modal-mask" @click.self="showApprove = false">
      <CCard class="modal modal--sm" title="审核奖励发放" padding="lg">
        <div class="approve">
          <div class="approve__icon"><CIcon name="shield" :size="28" /></div>
          <div class="approve__title">确认向 {{ approveTarget.referrerName }} 发放奖励？</div>
          <div class="approve__grid">
            <div><span>被推荐人</span><b>{{ approveTarget.introducedName }}</b></div>
            <div><span>成交金额</span><b>{{ money(approveTarget.dealAmount ?? 0) }}</b></div>
            <div><span>奖励形式</span><b>{{ referral.REWARD_TYPE_LABEL[approveTarget.rewardType] }}</b></div>
            <div><span>奖励金额</span><b class="approve__amount">{{ rewardLabel(approveTarget.rewardType, approveTarget.rewardAmount) }}</b></div>
          </div>
          <div class="approve__tip">
            <CIcon name="alert" :size="12" />发放后将写入活动流水，状态不可撤销。
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showApprove = false">取消</CButton>
          <CButton variant="primary" :disabled="!canApprove" @click="confirmApprove">确认发放</CButton>
        </template>
      </CCard>
    </div>

    <!-- P5-B91 卡2：新建/编辑邀请活动弹层 -->
    <div v-if="showCamp" class="modal-mask" @click.self="showCamp = false">
      <CCard class="modal modal--lg" :title="campEditId ? '编辑邀请活动' : '新建邀请活动'" padding="lg">
        <div class="form">
          <label class="form__label">活动名称</label>
          <CInput v-model="campForm.name" placeholder="如：金秋转介绍加码季" />
          <div class="form-row">
            <div class="form-col">
              <label class="form__label">开始日期</label>
              <CInput v-model="campForm.startAt" type="date" />
            </div>
            <div class="form-col">
              <label class="form__label">结束日期</label>
              <CInput v-model="campForm.endAt" type="date" />
            </div>
          </div>
          <label class="form__label">门店码（可空，空 = 全部门店）</label>
          <CInput v-model="campForm.storeCode" placeholder="如 SST01，留空表示全部门店通用" />
          <label class="form__label">备注（可空）</label>
          <CTextarea v-model="campForm.remark" :rows="2" placeholder="活动说明，仅内部可见" />
          <div v-if="campError" class="form__error">
            <CIcon name="alert" :size="14" />{{ campError }}
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showCamp = false">取消</CButton>
          <CButton variant="primary" :disabled="campSaving" @click="saveCampaign">
            {{ campEditId ? '保存活动' : '创建活动' }}
          </CButton>
        </template>
      </CCard>
    </div>

    <!-- P5-B91 卡2：新建转介绍绑定弹层 -->
    <div v-if="showBind" class="modal-mask" @click.self="showBind = false">
      <CCard class="modal modal--lg" title="新建转介绍绑定" padding="lg">
        <div class="form">
          <label class="form__label">推荐人（老客）</label>
          <div class="pick">
            <CInput
              :model-value="referrerKw"
              placeholder="输入姓名或手机号搜索"
              @update:model-value="referrerKw = $event; searchReferrer()"
            />
            <div v-if="bindForm.referrer" class="pick__chosen">
              <CIcon name="user-check" :size="14" />
              {{ bindForm.referrer.name }} · {{ bindForm.referrer.phone }}
              <CButton variant="text" size="sm" @click="bindForm.referrer = null">重选</CButton>
            </div>
            <div v-else-if="referrerOptions.length" class="pick__list">
              <button
                v-for="c in referrerOptions" :key="c.customerId"
                class="pick__item"
                @click="bindForm.referrer = c; referrerOptions = []"
              >
                <span class="pick__name">{{ c.name }}</span>
                <span class="pick__phone">{{ c.phone }}</span>
                <span class="pick__level">{{ c.level }}</span>
              </button>
            </div>
          </div>

          <label class="form__label">被推荐人（新客）</label>
          <div class="pick">
            <CInput
              :model-value="refereeKw"
              placeholder="输入姓名或手机号搜索"
              @update:model-value="refereeKw = $event; searchReferee()"
            />
            <div v-if="bindForm.referee" class="pick__chosen">
              <CIcon name="user-check" :size="14" />
              {{ bindForm.referee.name }} · {{ bindForm.referee.phone }}
              <CButton variant="text" size="sm" @click="bindForm.referee = null">重选</CButton>
            </div>
            <div v-else-if="refereeOptions.length" class="pick__list">
              <button
                v-for="c in refereeOptions" :key="c.customerId"
                class="pick__item"
                @click="bindForm.referee = c; refereeOptions = []"
              >
                <span class="pick__name">{{ c.name }}</span>
                <span class="pick__phone">{{ c.phone }}</span>
                <span class="pick__level">{{ c.level }}</span>
              </button>
            </div>
          </div>

          <div class="form-row">
            <div class="form-col">
              <label class="form__label">所属活动（可空）</label>
              <CSelect v-model="bindForm.campaignId" width="100%" :options="ongoingCampaignOptions" />
            </div>
            <div class="form-col">
              <label class="form__label">有效期（天，可空）</label>
              <CInput
                :model-value="bindForm.validDays == null ? '' : String(bindForm.validDays)"
                type="number"
                :placeholder="`留空按全局配置 ${store.validDays} 天`"
                @update:model-value="bindForm.validDays = $event === '' ? undefined : Number($event)"
              />
            </div>
          </div>

          <label class="form__label">备注（可空）</label>
          <CTextarea v-model="bindForm.remark" :rows="2" placeholder="绑定来源说明，仅内部可见" />

          <div v-if="bindError" class="form__error">
            <CIcon name="alert" :size="14" />{{ bindError }}
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showBind = false">取消</CButton>
          <CButton variant="primary" :disabled="bindSaving" @click="saveBind">创建绑定</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.mr { display: flex; flex-direction: column; gap: var(--s-lg); }
.mr__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .mr__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.mr__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.mr__list { min-width: 0; }
.filters { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.filters__left { display: flex; align-items: center; gap: var(--s-sm); }
.filters__btn { flex-shrink: 0; }
.filters__title { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.filters__count { font-size: var(--t-xs); color: var(--c-text-3); }
.list { max-height: 640px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: flex; align-items: center; gap: var(--s-sm); width: 100%; text-align: left;
  padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__avatar {
  width: 36px; height: 36px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: var(--t-sm); flex-shrink: 0;
}
.row__main { flex: 1; min-width: 0; }
.row__top { display: flex; align-items: center; gap: var(--s-xs); margin-bottom: 2px; }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__sub { display: flex; align-items: center; gap: 2px; font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 2px; }
.row__amount { font-size: var(--t-xs); color: var(--c-orange-dark); font-weight: 600; }

.mr__detail-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); width: 100%; }
.mr__who { display: flex; align-items: center; gap: var(--s-md); min-width: 0; }
.mr__avatar {
  width: 44px; height: 44px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; font-size: var(--t-lg); font-weight: 700;
}
.mr__who h3 { font-size: var(--t-lg); font-weight: 700; }
.mr__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.mr__head-pills { display: flex; align-items: center; gap: var(--s-xs); flex-shrink: 0; }

.mr__detail :deep(.card__body) { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }

.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-lg); align-items: start; }
.block { display: flex; flex-direction: column; gap: var(--s-sm); min-width: 0; }
.block__title {
  display: flex; justify-content: space-between; align-items: center;
  font-size: var(--t-sm); font-weight: 600; color: var(--c-text);
}
.block__hint { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }

.timeline { display: flex; flex-direction: column; padding-left: var(--s-xs); }
.tl { display: flex; gap: var(--s-sm); padding-bottom: var(--s-md); position: relative; }
.tl:not(:last-child)::before {
  content: ''; position: absolute; left: 5px; top: 14px; bottom: 0;
  width: 1px; background: var(--c-border);
}
.tl__dot { width: 10px; height: 10px; border-radius: 50%; background: var(--c-brand); margin-top: 5px; flex-shrink: 0; z-index: 1; }
.tl__dot--last { background: var(--c-success-fg); }
.tl__body { min-width: 0; }
.tl__action { font-size: var(--t-sm); color: var(--c-text); line-height: var(--lh-sm); }
.tl__meta { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.levels { display: flex; flex-direction: column; gap: var(--s-sm); }
.level { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.level__head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); }
.level__tag {
  display: inline-flex; align-items: center; padding: 2px 10px; border-radius: var(--r-pill);
  font-size: var(--t-xs); font-weight: 700; color: #fff;
}
.level__tag--1 { background: var(--c-brand); }
.level__tag--2 { background: var(--c-brand-secondary); }
.level__rate { display: inline-flex; align-items: center; gap: var(--s-xs); font-size: var(--t-md); font-weight: 700; color: var(--c-brand); }
.level__edit { display: flex; align-items: center; gap: var(--s-xs); }
.level__edit :deep(.cinput) { width: 80px; }
.level__unit { font-size: var(--t-sm); color: var(--c-text-3); }
.level__desc { font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-xs); }

.reward-summary {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-md);
  margin-top: var(--s-sm); padding: var(--s-md); background: var(--c-warning-bg);
  border-radius: var(--r-md);
}
.reward-summary__label { font-size: var(--t-xs); color: var(--c-text-3); }
.reward-summary__value { font-size: var(--t-md); font-weight: 700; color: var(--c-warning-fg); margin-top: 2px; }
.reward-summary__done { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-success-fg); }
.reward-summary__wait { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); }

.ladders { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-sm); }
.ladder {
  background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md);
  display: flex; flex-direction: column; gap: 4px; border: 1px solid var(--c-border-light);
}
.ladder__badge {
  align-self: flex-start; font-size: var(--t-xs); font-weight: 700; color: var(--c-brand);
  background: var(--c-brand-soft); padding: 2px 8px; border-radius: var(--r-pill);
}
.ladder__reward { font-size: var(--t-md); font-weight: 700; color: var(--c-text); margin-top: var(--s-xs); }
.ladder__desc { font-size: var(--t-xs); color: var(--c-text-3); }

.script-box { margin-top: var(--s-sm); background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.script-box__title { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.script-box__text { font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-sm); margin: var(--s-xs) 0; }
.script-box__meta { font-size: var(--t-xs); color: var(--c-text-3); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 480px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal--lg { width: 560px; }
.modal--sm { width: 420px; }
.form { display: flex; flex-direction: column; gap: var(--s-xs); }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-xs); }
.ladder-form { display: flex; flex-direction: column; gap: var(--s-xs); }
.ladder-form__row { display: grid; grid-template-columns: 80px 32px 110px 1fr; align-items: center; gap: var(--s-xs); }
.ladder-form__unit { font-size: var(--t-xs); color: var(--c-text-3); }
.form__error {
  display: flex; align-items: center; gap: 4px; margin-top: var(--s-xs);
  padding: var(--s-sm); background: var(--c-danger-bg); color: var(--c-danger-fg);
  border-radius: var(--r-sm); font-size: var(--t-xs);
}

.approve { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); text-align: center; }
.approve__icon {
  width: 56px; height: 56px; border-radius: 50%; background: var(--c-warning-bg);
  color: var(--c-warning-fg); display: flex; align-items: center; justify-content: center;
}
.approve__title { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.approve__grid {
  width: 100%; display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-sm);
  background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); text-align: left;
}
.approve__grid > div { display: flex; flex-direction: column; gap: 2px; }
.approve__grid span { font-size: var(--t-xs); color: var(--c-text-3); }
.approve__grid b { font-size: var(--t-sm); color: var(--c-text); }
.approve__amount { color: var(--c-warning-fg) !important; }
.approve__tip { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); }

/* P5-B91 卡2：邀请活动区块 */
.camp-table { display: flex; flex-direction: column; }
.camp-row {
  display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(0, 1.1fr) 90px 90px auto;
  align-items: center; gap: var(--s-md); padding: var(--s-md) var(--s-lg);
  border-bottom: 1px solid var(--c-border-light);
}
.camp-row:last-child { border-bottom: none; }
.camp-row__main { display: flex; align-items: center; gap: var(--s-sm); min-width: 0; }
.camp-row__name {
  font-size: var(--t-sm); font-weight: 600; color: var(--c-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.camp-row__date { font-size: var(--t-xs); color: var(--c-text-3); }
.camp-row__stat { font-size: var(--t-xs); color: var(--c-text-2); }
.camp-row__ops { display: flex; align-items: center; gap: var(--s-xs); justify-content: flex-end; }

.filters__right { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; }

/* P5-B91 卡2：绑定弹层搜索选择器 */
.pick { display: flex; flex-direction: column; gap: var(--s-xs); }
.pick__chosen {
  display: flex; align-items: center; gap: var(--s-sm);
  font-size: var(--t-sm); color: var(--c-text);
  background: var(--c-brand-soft); border-radius: var(--r-sm); padding: var(--s-sm) var(--s-md);
}
.pick__list {
  display: flex; flex-direction: column; max-height: 220px; overflow-y: auto;
  border: 1px solid var(--c-border-light); border-radius: var(--r-sm);
}
.pick__item {
  display: flex; align-items: center; gap: var(--s-sm); width: 100%;
  padding: var(--s-sm) var(--s-md); background: none; border: none;
  border-bottom: 1px solid var(--c-border-light); cursor: pointer; text-align: left;
}
.pick__item:last-child { border-bottom: none; }
.pick__item:hover { background: var(--c-brand-soft); }
.pick__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.pick__phone { font-size: var(--t-xs); color: var(--c-text-3); }
.pick__level { font-size: var(--t-xs); color: var(--c-brand); margin-left: auto; }

@media (max-width: 1024px) {
  .mr__body { grid-template-columns: 1fr; }
  .mr__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .two-col { grid-template-columns: 1fr; }
  .ladders { grid-template-columns: 1fr; }
  .list { max-height: 360px; }
  .camp-row { grid-template-columns: minmax(0, 1fr) auto; row-gap: var(--s-xs); }
  .camp-row__date, .camp-row__stat { grid-column: 1; }
  .camp-row__ops { grid-column: 2; grid-row: 1; }
}
</style>
