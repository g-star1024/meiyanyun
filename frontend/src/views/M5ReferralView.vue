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
import { useM5ReferralCampaignStore } from '@/stores/m5ReferralCampaign'
import { useReferralStore, type RewardType } from '@/stores/referral'
import { useAuthStore } from '@/stores/auth'
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

const STATUS_PILL: Record<string, 'default' | 'primary' | 'info' | 'success'> = {
  PENDING: 'default', CONFIRMED: 'primary', VISITED: 'info', DEAL: 'success',
}
const REWARD_PILL: Record<string, 'warning' | 'success'> = { PENDING: 'warning', PAID: 'success' }

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
function confirmApprove() {
  if (approveTarget.value) {
    const ok = store.approveReward(approveTarget.value.id)
    if (ok) showApprove.value = false
  }
}
</script>

<template>
  <div class="mr">
    <div class="mr__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

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
          <CButton v-if="canEdit" variant="primary" size="sm" class="filters__btn" @click="openConfig">
            <CIcon name="settings" :size="14" />配置邀请机制
          </CButton>
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

@media (max-width: 1024px) {
  .mr__body { grid-template-columns: 1fr; }
  .mr__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .two-col { grid-template-columns: 1fr; }
  .ladders { grid-template-columns: 1fr; }
  .list { max-height: 360px; }
}
</style>
