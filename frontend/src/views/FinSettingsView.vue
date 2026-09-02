<script setup lang="ts">
/* ============================================================
 * 财务设置 /m6-settings（M6-17）
 * 双栏：左侧分组导航（会计科目/税率/结算周期/对账与镜像），右侧配置表单。
 * 保存二次确认 + 影响范围提示；底部变更审计记录。
 * 镜像单向同步，不碰资金池。
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useFinSettingsStore, type FinGroupKey, type FinSettings, type SubjectEnable } from '@/stores/finSettings'
import type { SubjectCode } from '@/stores/financeCore'

const store = useFinSettingsStore()
onMounted(() => store.seed())

const activeGroup = ref<FinGroupKey>('SUBJECT')

const groups: { key: FinGroupKey; label: string; icon: 'sign' | 'profile' | 'check-square' | 'shield'; desc: string }[] = [
  { key: 'SUBJECT', label: '会计科目', icon: 'sign', desc: 'RF/TK 科目表与启用' },
  { key: 'TAX', label: '税率配置', icon: 'profile', desc: '增值税/附加税/所得税' },
  { key: 'SETTLE', label: '结算周期', icon: 'check-square', desc: '结算日/提成日/对账周期' },
  { key: 'RECONCILE', label: '对账与镜像', icon: 'shield', desc: '差异阈值/镜像源/Outbox' },
]

// 本地草稿
const draft = reactive<FinSettings>({ ...store.settings })
const draftSubjects = ref<SubjectEnable[]>([])

function syncFromStore() {
  Object.assign(draft, store.settings)
  draftSubjects.value = store.subjectEnable.map((s) => ({ ...s }))
}
syncFromStore()
onMounted(syncFromStore)

const dirty = computed(() => {
  if (JSON.stringify(draft) !== JSON.stringify(store.settings)) return true
  return draftSubjects.value.some((d) => {
    const cur = store.subjectEnable.find((s) => s.code === d.code)
    return cur && cur.enabled !== d.enabled
  })
})

function pct(n: number) {
  return String(Math.round(n * 1000) / 10)
}
function toPct(v: string) {
  return (Number(v) || 0) / 100
}
function toggleDraftSubject(code: SubjectCode) {
  if (!store.canEdit) return
  const row = draftSubjects.value.find((s) => s.code === code)
  if (row) row.enabled = !row.enabled
}

const kpis = computed(() => [
  { label: '启用科目', icon: 'settings', value: `${store.enabledSubjectCount}`, tone: 'brand' as const },
  { label: '增值税率', icon: 'finance', value: pct(store.settings.vatRate) + '%', tone: 'text' as const },
  { label: '对账周期', icon: 'clock', value: `T+${store.settings.reconcileTn}`, tone: 'teal' as const },
  { label: '镜像源', icon: 'settings', value: String(Number(store.settings.mirrorKingdee) + Number(store.settings.mirrorYonyou)), tone: 'orange' as const },
])

// 保存二次确认
const showConfirm = ref(false)
const toast = ref('')
function requestSave() {
  showConfirm.value = true
}
function confirmSave() {
  if (store.save({ ...draft }, draftSubjects.value.map((s) => ({ ...s })))) {
    syncFromStore()
    showConfirm.value = false
    toast.value = '财务设置已保存'
    setTimeout(() => (toast.value = ''), 2400)
  }
}
function resetDraft() {
  syncFromStore()
}

function fmt(iso: string) {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function subjectKind(code: SubjectCode): 'RF' | 'TK' {
  return code.startsWith('RF') ? 'RF' : 'TK'
}
</script>

<template>
  <div class="fs">
    <div class="fs__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="fs__layout">
      <!-- 左：分组导航 -->
      <CCard class="fs__nav" padding="none">
        <button
          v-for="g in groups" :key="g.key"
          class="nav-item" :class="{ 'is-active': activeGroup === g.key }"
          @click="activeGroup = g.key"
        >
          <CIcon :name="g.icon" :size="18" class="nav-item__icon" />
          <span class="nav-item__text">
            <span class="nav-item__label">{{ g.label }}</span>
            <span class="nav-item__desc">{{ g.desc }}</span>
          </span>
          <CIcon name="chevron-right" :size="14" class="nav-item__arrow" />
        </button>
      </CCard>

      <!-- 右：配置表单 -->
      <CCard class="fs__form" padding="lg">
        <template #header>
          <div class="form-head">
            <h3 class="form-head__title">财务配置</h3>
            <div class="form-head__actions">
              <CButton variant="ghost" size="sm" :disabled="!dirty" @click="resetDraft">撤销修改</CButton>
              <CButton variant="primary" size="sm" v-perm.disable="'finance:settings:edit'" :disabled="!dirty" @click="requestSave">
                <CIcon name="check" :size="14" />保存设置
              </CButton>
            </div>
          </div>
        </template>
        <!-- 会计科目 -->
        <template v-if="activeGroup === 'SUBJECT'">
          <h3 class="group-title"><CIcon name="sign" :size="16" /> 会计科目（RF/TK 科目表）</h3>
          <p class="group-desc">RF 为资金类科目，TK 为库存/成本类科目。科目表由 finance-service 镜像，只读不可新增；可停用不参与本机构核算。</p>
          <div class="subj-table">
            <div class="subj-head">
              <span>科目代码</span>
              <span>科目名称</span>
              <span>类别</span>
              <span class="ta-r">启用</span>
            </div>
            <div v-for="s in draftSubjects" :key="s.code" class="subj-row">
              <span class="mono">{{ s.code }}</span>
              <span>{{ store.SUBJECT_LABEL[s.code] }}</span>
              <span>
                <span class="kind" :class="`kind--${subjectKind(s.code).toLowerCase()}`">{{ subjectKind(s.code) }}</span>
              </span>
              <span class="ta-r">
                <label class="switch">
                  <input type="checkbox" :checked="s.enabled" :disabled="!store.canEdit" @change="toggleDraftSubject(s.code)" />
                  <span class="slider" />
                </label>
              </span>
            </div>
          </div>
        </template>

        <!-- 税率配置 -->
        <template v-else-if="activeGroup === 'TAX'">
          <h3 class="group-title"><CIcon name="profile" :size="16" /> 税率配置</h3>
          <p class="group-desc">用于发票税额估算与经营报表口径，百分比请输入 0–100 的数值。</p>
          <div class="form-grid">
            <div class="fld">
              <label class="fld__label">增值税率（%，服务 6%）</label>
              <CInput type="number" :model-value="pct(draft.vatRate)" :disabled="!store.canEdit"
                @update:model-value="draft.vatRate = toPct($event)" />
            </div>
            <div class="fld">
              <label class="fld__label">附加税率（%）</label>
              <CInput type="number" :model-value="pct(draft.surtaxRate)" :disabled="!store.canEdit"
                @update:model-value="draft.surtaxRate = toPct($event)" />
            </div>
            <div class="fld">
              <label class="fld__label">所得税率（%）</label>
              <CInput type="number" :model-value="pct(draft.incomeTaxRate)" :disabled="!store.canEdit"
                @update:model-value="draft.incomeTaxRate = toPct($event)" />
            </div>
          </div>
        </template>

        <!-- 结算周期 -->
        <template v-else-if="activeGroup === 'SETTLE'">
          <h3 class="group-title"><CIcon name="check-square" :size="16" /> 结算周期</h3>
          <p class="group-desc">设定门店月度结算、专家提成发放与三方对账的时间口径。</p>
          <div class="form-grid">
            <div class="fld">
              <label class="fld__label">门店结算日（每月几号）</label>
              <CInput type="number" :model-value="String(draft.settleDay)" :disabled="!store.canEdit"
                @update:model-value="draft.settleDay = Math.min(28, Math.max(1, Number($event) || 1))" />
            </div>
            <div class="fld">
              <label class="fld__label">专家提成发放日（每月几号）</label>
              <CInput type="number" :model-value="String(draft.commissionPayDay)" :disabled="!store.canEdit"
                @update:model-value="draft.commissionPayDay = Math.min(28, Math.max(1, Number($event) || 1))" />
            </div>
            <div class="fld">
              <label class="fld__label">对账周期 T+N（默认 T+1）</label>
              <CInput type="number" :model-value="String(draft.reconcileTn)" :disabled="!store.canEdit"
                @update:model-value="draft.reconcileTn = Math.max(0, Number($event) || 0)" />
            </div>
          </div>
        </template>

        <!-- 对账与镜像 -->
        <template v-else>
          <h3 class="group-title"><CIcon name="shield" :size="16" /> 对账规则与镜像源</h3>
          <p class="group-desc">Outbox 三方对账参数；镜像源单向同步收银/渠道/银行数据用于核对。</p>
          <div class="form-grid">
            <div class="fld">
              <label class="fld__label">对账差异阈值（元，超过需双签）</label>
              <CInput type="number" :model-value="String(draft.diffThreshold)" :disabled="!store.canEdit"
                @update:model-value="draft.diffThreshold = Math.max(0, Number($event) || 0)" />
            </div>
            <div class="fld">
              <label class="fld__label">Outbox 重试次数</label>
              <CInput type="number" :model-value="String(draft.outboxRetry)" :disabled="!store.canEdit"
                @update:model-value="draft.outboxRetry = Math.max(0, Number($event) || 0)" />
            </div>
          </div>

          <div class="switches">
            <label class="sw">
              <span class="sw__text">
                <span class="sw__title">金蝶镜像源</span>
                <span class="sw__desc">单向镜像金蝶财务凭证，仅用于核对，不反向写入</span>
              </span>
              <label class="switch">
                <input type="checkbox" v-model="draft.mirrorKingdee" :disabled="!store.canEdit" />
                <span class="slider" />
              </label>
            </label>
            <label class="sw">
              <span class="sw__text">
                <span class="sw__title">用友镜像源</span>
                <span class="sw__desc">单向镜像用友 ERP 成本/库存数据，仅用于核对</span>
              </span>
              <label class="switch">
                <input type="checkbox" v-model="draft.mirrorYonyou" :disabled="!store.canEdit" />
                <span class="slider" />
              </label>
            </label>
          </div>

          <div class="redline">
            <CIcon name="shield" :size="16" />
            <span>镜像单向同步，仅做读取与三方对账，不碰资金池。</span>
          </div>
        </template>
      </CCard>
    </div>

    <!-- 变更审计记录 -->
    <CCard class="fs__log" padding="lg">
      <template #header><h3 class="card-title"><CIcon name="check" :size="16" /> 变更审计记录</h3></template>
      <div class="log-list">
        <div v-for="l in store.logs" :key="l.id" class="log-row">
          <span class="log-field">{{ l.field }}</span>
          <span class="log-change">
            <span class="log-old">{{ l.oldValue }}</span>
            <CIcon name="chevron-right" :size="12" class="log-arrow" />
            <span class="log-new">{{ l.newValue }}</span>
          </span>
          <span class="log-by">{{ l.by }}</span>
          <span class="log-at">{{ fmt(l.at) }}</span>
        </div>
      </div>
    </CCard>

    <!-- 保存二次确认 -->
    <div v-if="showConfirm" class="modal-mask" @click.self="showConfirm = false">
      <CCard class="modal modal--sm" title="确认保存财务设置" padding="lg">
        <div class="confirm">
          <div class="confirm__icon"><CIcon name="alert" :size="28" /></div>
          <p class="confirm__text">本次设置将作用于全 M6 财务页（科目口径、税率、结算与对账规则）。</p>
          <p class="confirm__hint">镜像源与对账规则变更会影响后续三方对账口径，请确认无误。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showConfirm = false">再看看</CButton>
          <CButton variant="primary" @click="confirmSave">确认保存</CButton>
        </template>
      </CCard>
    </div>

    <transition name="toast">
      <div v-if="toast" class="toast"><CIcon name="check" :size="16" />{{ toast }}</div>
    </transition>
  </div>
</template>

<style scoped>
.fs { display: flex; flex-direction: column; gap: var(--s-lg); }
.fs__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .fs__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.form-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); width: 100%; }
.form-head__title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.form-head__actions { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; }

.card-title { font-size: var(--t-md); font-weight: 700; margin: 0; display: flex; align-items: center; gap: var(--s-xs); }

.fs__layout { display: grid; grid-template-columns: 280px 1fr; gap: var(--s-lg); align-items: start; }
.fs__nav { overflow: hidden; }
.nav-item {
  display: flex; align-items: center; gap: var(--s-sm); width: 100%; text-align: left;
  padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light);
  cursor: pointer; transition: background 0.15s;
}
.nav-item:last-child { border-bottom: none; }
.nav-item:hover { background: var(--c-brand-soft); }
.nav-item.is-active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.nav-item__icon { color: var(--c-text-3); flex-shrink: 0; }
.nav-item.is-active .nav-item__icon { color: var(--c-brand); }
.nav-item__text { flex: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.nav-item__label { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.nav-item__desc { font-size: var(--t-xs); color: var(--c-text-3); }
.nav-item__arrow { color: var(--c-text-4); }

.group-title { font-size: var(--t-md); font-weight: 700; margin: 0 0 var(--s-xs); display: flex; align-items: center; gap: var(--s-xs); }
.group-desc { font-size: var(--t-xs); color: var(--c-text-3); margin: 0 0 var(--s-lg); line-height: 1.6; }

.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.fld { display: flex; flex-direction: column; gap: var(--s-xs); }
.fld__label { font-size: var(--t-xs); color: var(--c-text-3); }

/* 科目表 */
.subj-table { border: 1px solid var(--c-border-light); border-radius: var(--r-md); overflow: hidden; }
.subj-head, .subj-row {
  display: grid; grid-template-columns: 140px 1fr 80px 80px;
  align-items: center; gap: var(--s-md); padding: var(--s-sm) var(--s-md); font-size: var(--t-sm);
}
.subj-head { background: var(--c-bg-right); font-size: var(--t-xs); font-weight: 600; color: var(--c-text-3); }
.subj-row { border-top: 1px solid var(--c-border-light); color: var(--c-text); }
.ta-r { text-align: right; }
.mono { font-family: var(--t-font-mono, monospace); font-size: var(--t-xs); color: var(--c-text-2); }
.kind { font-size: 10px; font-weight: 700; padding: 2px 6px; border-radius: var(--r-sm); color: #fff; }
.kind--rf { background: var(--c-brand); }
.kind--tk { background: var(--c-orange-dark); }

/* 开关 */
.switch { position: relative; display: inline-block; width: 42px; height: 22px; flex-shrink: 0; }
.switch input { opacity: 0; width: 0; height: 0; }
.slider { position: absolute; cursor: pointer; inset: 0; background: var(--c-border); border-radius: 22px; transition: .2s; }
.slider::before { content: ''; position: absolute; height: 18px; width: 18px; left: 2px; top: 2px; background: #fff; border-radius: 50%; transition: .2s; }
.switch input:checked + .slider { background: var(--c-brand); }
.switch input:checked + .slider::before { transform: translateX(20px); }
.switch input:disabled + .slider { opacity: .5; cursor: not-allowed; }

.switches { display: flex; flex-direction: column; gap: var(--s-sm); margin-top: var(--s-lg); }
.sw { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); padding: var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-md); }
.sw__text { display: flex; flex-direction: column; gap: 2px; }
.sw__title { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.sw__desc { font-size: var(--t-xs); color: var(--c-text-3); }

.redline {
  display: flex; align-items: center; gap: var(--s-xs); margin-top: var(--s-lg);
  padding: var(--s-sm) var(--s-md); background: var(--c-warning-bg); color: var(--c-warning-fg);
  border-radius: var(--r-md); font-size: var(--t-xs); font-weight: 600;
}

/* 审计记录 */
.log-list { display: flex; flex-direction: column; }
.log-row { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); }
.log-row:last-child { border-bottom: none; }
.log-field { flex: 1; color: var(--c-text); font-weight: 500; }
.log-change { display: inline-flex; align-items: center; gap: var(--s-xs); color: var(--c-text-3); }
.log-old { color: var(--c-text-4); text-decoration: line-through; }
.log-arrow { color: var(--c-text-4); }
.log-new { color: var(--c-brand); font-weight: 600; }
.log-by { color: var(--c-text-3); font-size: var(--t-xs); }
.log-at { color: var(--c-text-4); font-size: var(--t-xs); min-width: 120px; text-align: right; }

/* 弹层 */
.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 380px; max-width: 100%; box-shadow: var(--shadow-pop); }
.confirm { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); text-align: center; padding: var(--s-sm) 0; }
.confirm__icon { width: 52px; height: 52px; border-radius: 50%; background: var(--c-warning-bg); color: var(--c-warning-fg); display: flex; align-items: center; justify-content: center; }
.confirm__text { font-size: var(--t-sm); color: var(--c-text-2); margin: 0; line-height: 1.6; }
.confirm__hint { font-size: var(--t-xs); color: var(--c-text-3); margin: 0; line-height: 1.6; }

.toast {
  position: fixed; bottom: var(--s-xl); left: 50%; transform: translateX(-50%);
  display: inline-flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-sm) var(--s-lg); background: var(--c-success-fg); color: #fff;
  border-radius: var(--r-capsule); font-size: var(--t-sm); font-weight: 600;
  box-shadow: var(--shadow-pop); z-index: 300;
}
.toast-enter-active, .toast-leave-active { transition: opacity 0.2s, transform 0.2s; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translate(-50%, 10px); }

@media (max-width: 1024px) {
  .fs__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .fs__layout { grid-template-columns: 1fr; }
  .fs__nav { display: grid; grid-template-columns: 1fr 1fr; }
  .nav-item { border-bottom: 1px solid var(--c-border-light); }
  .nav-item:nth-child(odd) { border-right: 1px solid var(--c-border-light); }
  .nav-item__arrow { display: none; }
  .form-grid { grid-template-columns: 1fr; }
  .subj-head, .subj-row { grid-template-columns: 110px 1fr 60px 70px; gap: var(--s-sm); }
  .log-at { min-width: 0; }
}
</style>
