<script setup lang="ts">
/* ============================================================
 * 私域运营 /m3-private（M3-13）
 * 三 tab：企微客户（左列表右详情）、社群（卡片网格）、SOP 编排（规则列表+弹层）。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import { usePrivateStore, type PrivateCustomer, type SopChannel, type SopTrigger } from '@/stores/private'

const store = usePrivateStore()
onMounted(() => store.seed())

type Tab = 'CUSTOMER' | 'GROUP' | 'SOP'
const tab = ref<Tab>('CUSTOMER')

const kpis = computed(() => [
  { label: '企微好友数', icon: 'customer', value: String(store.friendCount), tone: 'brand' as const },
  { label: '活跃社群', icon: 'customer', value: String(store.activeGroups), tone: 'teal' as const },
  { label: 'SOP 执行中', icon: 'check-square', value: String(store.runningSops), tone: 'success' as const },
  { label: '平均转化率', icon: 'trend-up', value: `${store.avgConversion}%`, tone: 'orange' as const },
])

// ============ 客户 ============
const selectedId = ref<string | null>(null)
const selected = computed<PrivateCustomer | null>(() => {
  if (selectedId.value) return store.getCustomer(selectedId.value) ?? null
  return store.filteredCustomers[0] ?? null
})

const staffOptions = computed(() => store.staffList.map((s) => ({ value: s, label: s === 'ALL' ? '全部员工' : s })))

function friendStatus(s: PrivateCustomer['friendStatus']) {
  if (s === 'FRIEND') return { text: '已添加', status: 'success' as const }
  if (s === 'PENDING') return { text: '待通过', status: 'warning' as const }
  return { text: '未添加', status: 'draft' as const }
}
function fmtAgo(iso: string) {
  const d = Date.now() - new Date(iso).getTime()
  const days = Math.floor(d / 86400_000)
  if (days >= 1) return `${days} 天前`
  const h = Math.floor(d / 3600_000)
  if (h >= 1) return `${h} 小时前`
  return '刚刚'
}

// ============ SOP ============
const showSopForm = ref(false)
const sopForm = ref({
  name: '',
  trigger: 'NEW_FRIEND' as SopTrigger,
  steps: [{ channel: 'WECHAT' as SopChannel, content: '', delay: '立即' }],
})
const canSubmitSop = computed(() => sopForm.value.name.trim() && sopForm.value.steps.every((s) => s.content.trim()))
function addStep() {
  sopForm.value.steps.push({ channel: 'WECHAT', content: '', delay: '次日' })
}
function removeStep(i: number) {
  sopForm.value.steps.splice(i, 1)
}
function submitSop() {
  if (!canSubmitSop.value) return
  store.createSop({ ...sopForm.value })
  showSopForm.value = false
  sopForm.value = { name: '', trigger: 'NEW_FRIEND', steps: [{ channel: 'WECHAT', content: '', delay: '立即' }] }
}

const triggerOptions = [
  { value: 'NEW_FRIEND', label: '新好友添加' },
  { value: 'BIRTHDAY', label: '客户生日' },
  { value: 'DORMANT_30D', label: '沉睡 30 天' },
  { value: 'AFTER_SERVICE', label: '到店后' },
  { value: 'MANUAL', label: '手动触发' },
]
const channelOptions = [
  { value: 'WECHAT', label: '企微单聊' },
  { value: 'GROUP', label: '群发' },
  { value: 'MOMENTS', label: '朋友圈' },
  { value: 'SMS', label: '短信' },
]
</script>

<template>
  <div class="pv">
    <div class="pv__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard padding="none">
      <div class="main-tabs">
        <button class="mtab" :class="{ 'mtab--active': tab === 'CUSTOMER' }" @click="tab = 'CUSTOMER'">
          <CIcon name="customer" :size="14" />企微客户
        </button>
        <button class="mtab" :class="{ 'mtab--active': tab === 'GROUP' }" @click="tab = 'GROUP'">
          <CIcon name="marketing" :size="14" />社群
        </button>
        <button class="mtab" :class="{ 'mtab--active': tab === 'SOP' }" @click="tab = 'SOP'">
          <CIcon name="scan" :size="14" />SOP 编排
        </button>
        <div class="main-tabs__right">
          <CButton v-if="tab === 'SOP'" variant="primary" size="sm" v-perm.disable="'private:edit'" @click="showSopForm = true">
            <CIcon name="plus" :size="14" />新建 SOP
          </CButton>
        </div>
      </div>
    </CCard>

    <!-- 企微客户 -->
    <div v-if="tab === 'CUSTOMER'" class="pv__body">
      <CCard class="pv__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterStaff" :options="staffOptions" width="100%" />
        </div>
        <div class="list">
          <button
            v-for="c in store.filteredCustomers" :key="c.id"
            class="row" :class="{ 'row--active': selected?.id === c.id }"
            @click="selectedId = c.id"
          >
            <div class="row__avatar">{{ c.name.slice(0, 1) }}</div>
            <div class="row__main">
              <div class="row__top">
                <span class="row__name">{{ c.name }}</span>
                <CStatusPill :status="friendStatus(c.friendStatus).status">{{ friendStatus(c.friendStatus).text }}</CStatusPill>
              </div>
              <div class="row__meta">
                <span><CIcon name="customer" :size="11" /> {{ c.staff }}</span>
                <span>{{ c.groupCount }} 群</span>
                <span>{{ fmtAgo(c.lastInteraction) }}</span>
              </div>
            </div>
          </button>
        </div>
      </CCard>

      <CCard v-if="selected" class="pv__detail" padding="lg">
        <template #header>
          <h3 class="card-title">{{ selected.name }}</h3>
          <CStatusPill :status="friendStatus(selected.friendStatus).status">{{ friendStatus(selected.friendStatus).text }}</CStatusPill>
        </template>

        <div class="detail__hero">
          <div class="detail__avatar">{{ selected.name.slice(0, 1) }}</div>
          <div>
            <div class="detail__name">{{ selected.name }}</div>
            <div class="detail__sub">归属：{{ selected.staff }} · 最近互动 {{ fmtAgo(selected.lastInteraction) }}</div>
          </div>
        </div>

        <div class="detail__grid">
          <div class="field"><span class="field__label">所在群聊</span><span class="field__val">{{ selected.groupCount }} 个</span></div>
          <div class="field"><span class="field__label">待执行 SOP</span><span class="field__val">{{ selected.sopTasks }} 条</span></div>
          <div class="field"><span class="field__label">转化率</span><span class="field__val is-brand">{{ selected.conversion }}%</span></div>
          <div class="field"><span class="field__label">好友状态</span><span class="field__val">{{ store.FRIEND_LABEL[selected.friendStatus] }}</span></div>
        </div>

        <div class="detail__tags">
          <span v-for="t in selected.tags" :key="t" class="tag">{{ t }}</span>
        </div>

        <div class="detail__ops">
          <CButton variant="ghost" v-perm.disable="'private:edit'">
            <CIcon name="chat" :size="16" />发起企微
          </CButton>
          <CButton variant="primary" v-perm.disable="'private:edit'">
            <CIcon name="plus" :size="16" />添加跟进
          </CButton>
        </div>
      </CCard>

      <CCard v-else class="pv__detail" padding="lg">
        <div class="detail-empty">
          <CIcon :name="('customer' as any)" :size="40" class="detail-empty__icon" />
          <p>请选择客户</p>
        </div>
      </CCard>
    </div>

    <!-- 社群 -->
    <div v-else-if="tab === 'GROUP'" class="groups">
      <div v-for="g in store.communities" :key="g.id" class="grp-card">
        <div class="grp-card__head">
          <div class="grp-card__icon"><CIcon name="marketing" :size="20" /></div>
          <div class="grp-card__name">{{ g.name }}</div>
        </div>
        <div class="grp-card__stats">
          <div class="grp-stat">
            <div class="grp-stat__val">{{ g.members }}</div>
            <div class="grp-stat__lbl">成员</div>
          </div>
          <div class="grp-stat">
            <div class="grp-stat__val" :class="{ 'is-warn': g.activePct < 40 }">{{ g.activePct }}%</div>
            <div class="grp-stat__lbl">活跃度</div>
          </div>
          <div class="grp-stat">
            <div class="grp-stat__val">{{ g.tags.length }}</div>
            <div class="grp-stat__lbl">标签</div>
          </div>
        </div>
        <CProgressBar :value="g.activePct" :color="g.activePct >= 60 ? 'var(--c-success-fg)' : g.activePct >= 40 ? 'var(--c-warning-fg)' : 'var(--c-danger-fg)'" :show-label="false" :height="6" />
        <div class="grp-card__foot">
          <span class="grp-card__op">运营：{{ g.operator }}</span>
          <button class="grp-card__btn">
            <CIcon name="chat" :size="12" />群发
          </button>
        </div>
      </div>
    </div>

    <!-- SOP -->
    <div v-else class="sops">
      <CCard v-for="s in store.sops" :key="s.id" class="sop" padding="lg">
        <div class="sop__head">
          <div>
            <div class="sop__name">{{ s.name }}</div>
            <div class="sop__trigger">
              <CIcon name="calendar" :size="12" />
              触发：{{ store.TRIGGER_LABEL[s.trigger] }}
            </div>
          </div>
          <label class="switch" :class="{ 'switch--on': s.enabled }">
            <input type="checkbox" :checked="s.enabled" v-perm.disable="'private:edit'" @change="store.toggleSop(s.id)" />
            <span class="switch__slider"></span>
          </label>
        </div>

        <div class="sop__steps">
          <div v-for="step in s.steps" :key="step.id" class="step">
            <div class="step__order">{{ step.order }}</div>
            <div class="step__body">
              <div class="step__meta">
                <CStatusPill status="primary">{{ store.CHANNEL_LABEL[step.channel] }}</CStatusPill>
                <span class="step__delay">{{ step.delay }}</span>
              </div>
              <div class="step__content">{{ step.content }}</div>
            </div>
          </div>
        </div>

        <div class="sop__foot">
          <span>目标 {{ s.targetCount }} 人 · 已执行 {{ s.executed }} 人</span>
          <span class="sop__rate">执行率 {{ s.targetCount ? Math.round(s.executed / s.targetCount * 100) : 0 }}%</span>
        </div>
      </CCard>
    </div>

    <!-- 新建 SOP 弹层 -->
    <div v-if="showSopForm" class="modal-mask" @click.self="showSopForm = false">
      <CCard class="modal modal--lg" title="新建 SOP" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">SOP 名称 <span class="req">*</span></label>
            <CInput v-model="sopForm.name" placeholder="如：新客 7 天欢迎 SOP" />
          </div>
          <div class="form__row">
            <label class="form__label">触发事件</label>
            <CSelect v-model="sopForm.trigger" :options="triggerOptions" width="100%" />
          </div>
          <div class="form__row">
            <label class="form__label">执行步骤</label>
            <div class="steps-edit">
              <div v-for="(step, i) in sopForm.steps" :key="i" class="step-edit">
                <div class="step-edit__head">
                  <span class="step-edit__no">步骤 {{ i + 1 }}</span>
                  <button v-if="sopForm.steps.length > 1" class="step-edit__del" @click="removeStep(i)">
                    <CIcon name="alert" :size="12" />删除
                  </button>
                </div>
                <div class="step-edit__row">
                  <CSelect v-model="step.channel" :options="channelOptions" width="140px" />
                  <CInput v-model="step.delay" placeholder="如：立即 / 次日 10:00" />
                </div>
                <CInput v-model="step.content" placeholder="消息内容" />
              </div>
              <button class="step-add" @click="addStep">
                <CIcon name="plus" :size="14" />添加步骤
              </button>
            </div>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showSopForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmitSop" @click="submitSop">创建（默认停用）</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.pv { display: flex; flex-direction: column; gap: var(--s-lg); }
.pv__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .pv__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.card-title { font-size: var(--t-md); font-weight: 700; margin: 0; }

/* tabs */
.main-tabs { display: flex; align-items: center; gap: 0; padding: 0 var(--s-sm); }
.main-tabs__right { margin-left: auto; padding: var(--s-xs) var(--s-sm); flex-shrink: 0; display: flex; align-items: center; }
.mtab { display: inline-flex; align-items: center; gap: 6px; padding: var(--s-md) var(--s-lg); font-size: var(--t-sm); color: var(--c-text-2); background: none; border: none; border-bottom: 2px solid transparent; cursor: pointer; }
.mtab:hover { color: var(--c-text); }
.mtab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

/* 客户双栏 */
.pv__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.pv__list { min-width: 0; }
.filters { padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.list { max-height: 560px; overflow-y: auto; }
.row { display: flex; gap: var(--s-md); width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; }
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__avatar { width: 40px; height: 40px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand); display: flex; align-items: center; justify-content: center; font-weight: 700; flex-shrink: 0; }
.row__main { flex: 1; min-width: 0; }
.row__top { display: flex; justify-content: space-between; align-items: center; }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__meta { display: flex; gap: var(--s-sm); margin-top: 4px; font-size: var(--t-xs); color: var(--c-text-3); align-items: center; flex-wrap: wrap; }
.row__meta span { display: inline-flex; align-items: center; gap: 3px; }

/* 详情 */
.detail__hero { display: flex; gap: var(--s-md); align-items: center; padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); margin-bottom: var(--s-md); }
.detail__avatar { width: 56px; height: 56px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand); display: flex; align-items: center; justify-content: center; font-size: var(--t-lg); font-weight: 700; }
.detail__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.detail__grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md) var(--s-lg); margin-bottom: var(--s-md); }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.is-brand { color: var(--c-brand); font-weight: 700; }
.detail__tags { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-bottom: var(--s-md); }
.tag { font-size: var(--t-xs); padding: 3px 10px; border-radius: var(--r-sm); background: var(--c-brand-soft); color: var(--c-brand); }
.detail__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); padding-top: var(--s-md); border-top: 1px solid var(--c-border-light); }
.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

/* 社群卡片 */
.groups { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: var(--s-md); }
.grp-card { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--r-xl); padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-md); }
.grp-card__head { display: flex; gap: var(--s-sm); align-items: center; }
.grp-card__icon { width: 40px; height: 40px; border-radius: var(--r-md); background: var(--c-teal-soft, rgba(14, 165, 164, 0.12)); color: var(--c-teal-dark); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.grp-card__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); line-height: var(--lh-md); }
.grp-card__stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-sm); }
.grp-stat { text-align: center; }
.grp-stat__val { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.grp-stat__val.is-warn { color: var(--c-warning-fg); }
.grp-stat__lbl { font-size: var(--t-xs); color: var(--c-text-3); }
.grp-card__foot { display: flex; justify-content: space-between; align-items: center; padding-top: var(--s-sm); border-top: 1px solid var(--c-border-light); }
.grp-card__op { font-size: var(--t-xs); color: var(--c-text-3); }
.grp-card__btn { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-brand); background: none; border: none; cursor: pointer; padding: 2px 6px; border-radius: var(--r-sm); }
.grp-card__btn:hover { background: var(--c-brand-soft); }

/* SOP */
.sops { display: flex; flex-direction: column; gap: var(--s-md); }
.sop__head { display: flex; justify-content: space-between; align-items: flex-start; padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); margin-bottom: var(--s-md); }
.sop__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.sop__trigger { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.sop__steps { display: flex; flex-direction: column; gap: var(--s-sm); }
.step { display: flex; gap: var(--s-md); padding: var(--s-sm) 0; }
.step__order { width: 24px; height: 24px; border-radius: 50%; background: var(--c-brand); color: #fff; display: flex; align-items: center; justify-content: center; font-size: var(--t-xs); font-weight: 700; flex-shrink: 0; }
.step__body { flex: 1; min-width: 0; }
.step__meta { display: flex; gap: var(--s-sm); align-items: center; margin-bottom: 4px; }
.step__delay { font-size: var(--t-xs); color: var(--c-text-3); }
.step__content { font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); }
.sop__foot { display: flex; justify-content: space-between; padding-top: var(--s-md); margin-top: var(--s-sm); border-top: 1px solid var(--c-border-light); font-size: var(--t-xs); color: var(--c-text-3); }
.sop__rate { color: var(--c-brand); font-weight: 600; }

/* switch */
.switch { position: relative; display: inline-block; width: 40px; height: 22px; flex-shrink: 0; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch__slider { position: absolute; cursor: pointer; inset: 0; background: var(--c-disabled-bg); border-radius: 999px; transition: 0.2s; }
.switch__slider::before { content: ''; position: absolute; width: 18px; height: 18px; left: 2px; top: 2px; background: #fff; border-radius: 50%; transition: 0.2s; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2); }
.switch--on .switch__slider { background: var(--c-brand); }
.switch--on .switch__slider::before { transform: translateX(18px); }
.switch input:disabled + .switch__slider { opacity: 0.5; cursor: not-allowed; }

/* modal */
.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--lg { width: 640px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.steps-edit { display: flex; flex-direction: column; gap: var(--s-sm); }
.step-edit { background: var(--c-disabled-bg); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.step-edit__head { display: flex; justify-content: space-between; align-items: center; }
.step-edit__no { font-size: var(--t-xs); font-weight: 600; color: var(--c-text); }
.step-edit__del { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-danger-fg); background: none; border: none; cursor: pointer; }
.step-edit__row { display: grid; grid-template-columns: 140px 1fr; gap: var(--s-sm); }
.step-add { align-self: flex-start; font-size: var(--t-sm); color: var(--c-brand); background: none; border: 1px dashed var(--c-border); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); cursor: pointer; display: inline-flex; align-items: center; gap: 4px; }
.step-add:hover { background: var(--c-brand-soft); border-color: var(--c-brand); }

@media (max-width: 1024px) {
  .pv__body { grid-template-columns: 1fr; }
  .detail__grid { grid-template-columns: 1fr 1fr; }
  .list { max-height: 320px; }
}
</style>
