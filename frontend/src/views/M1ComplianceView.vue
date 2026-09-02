<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import {
  useM1ComplianceStore, type CheckItem, type CheckCategory, type ComplianceStatus,
} from '@/stores/m1Compliance'
import { useAuthStore } from '@/stores/auth'

const cp = useM1ComplianceStore()
const auth = useAuthStore()
onMounted(() => cp.seed())

const canEdit = computed(() => auth.can('compliance:edit'))

const CATS: CheckCategory[] = ['QUALIFICATION', 'CONSENT', 'DRUG_TRACE', 'PRIVACY', 'AD', 'INFECTION']
const CAT_ICON = {
  QUALIFICATION: 'shield', CONSENT: 'profile', DRUG_TRACE: 'scan',
  PRIVACY: 'sign', AD: 'volume', INFECTION: 'check',
} as const
const tab = ref<'check' | 'audit'>('check')
const catFilter = ref<CheckCategory | ''>('')
const statusFilter = ref<ComplianceStatus | ''>('')

const filtered = computed(() => cp.items.filter((i) => {
  if (catFilter.value && i.category !== catFilter.value) return false
  if (statusFilter.value && i.status !== statusFilter.value) return false
  return true
}))

function statusTone(s: ComplianceStatus) {
  return ({ PASS: 'success', WARN: 'warning', FAIL: 'danger', PENDING: 'disabled' } as const)[s]
}
function riskTone(r: string) {
  return ({ HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' } as const)[r as 'HIGH'] ?? 'disabled'
}
function fmtTime(iso: string) {
  const d = new Date(iso)
  const now = Date.now()
  const diff = (now - d.getTime()) / 3600000
  if (diff < 1) return Math.round(diff * 60) + '分钟前'
  if (diff < 24) return Math.round(diff) + '小时前'
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

// 整改/复检弹层
const recheckTarget = ref<CheckItem | null>(null)
const recheckForm = reactive({ pass: true, remark: '' })
function openRecheck(it: CheckItem) { recheckTarget.value = it; recheckForm.pass = true; recheckForm.remark = '' }
function submitRecheck() {
  if (!recheckTarget.value) return
  cp.recheck(recheckTarget.value.id, recheckForm.pass, auth.user.name, recheckForm.remark.trim() || undefined)
  recheckTarget.value = null
}

// impersonate 弹层
const showImp = ref(false)
const impForm = reactive({ target: '', reason: '' })
const impErr = ref('')
function openImp() { impForm.target = ''; impForm.reason = ''; impErr.value = ''; showImp.value = true }
function startImp() {
  if (!impForm.target.trim()) { impErr.value = '请输入被代操作人'; return }
  if (!impForm.reason.trim()) { impErr.value = '代操作必须填写理由（将记入审计）'; return }
  const ok = cp.startImpersonate(impForm.target.trim(), impForm.reason, auth.user.name)
  if (!ok) { impErr.value = '已有进行中的代操作会话'; return }
  showImp.value = false
}
function endImp() { cp.endImpersonate(auth.user.name) }

const scoreColor = (rate: number) => rate >= 90 ? 'var(--c-success-fg)' : rate >= 70 ? 'var(--c-warning-fg)' : 'var(--c-danger-fg)'
</script>

<template>
  <div class="cx-page">
    <!-- impersonate 会话条 -->
    <div v-if="cp.activeSession" class="imp-bar">
      <CIcon name="alert" :size="16" />
      <span class="imp-bar__txt">代操作中：您正以「<b>{{ cp.activeSession.target }}</b>」身份操作，全程审计留痕 · 理由：{{ cp.activeSession.reason }}</span>
      <CButton size="sm" variant="primary" @click="endImp">结束代操作</CButton>
    </div>

    <div class="cx-kpis">
      <div class="kpi kpi--brand"><div class="kpi__icon"><CIcon name="shield" :size="20" /></div><div class="kpi__body"><div class="kpi__label">整体合规率</div><div class="kpi__value" :style="{ color: scoreColor(cp.stats.passRate) }">{{ cp.stats.passRate }}%</div></div></div>
      <div class="kpi kpi--success"><div class="kpi__icon"><CIcon name="check" :size="20" /></div><div class="kpi__body"><div class="kpi__label">合规</div><div class="kpi__value">{{ cp.stats.pass }}</div></div></div>
      <div class="kpi kpi--warning"><div class="kpi__icon"><CIcon name="clock" :size="20" /></div><div class="kpi__body"><div class="kpi__label">预警/待检</div><div class="kpi__value">{{ cp.stats.warn + cp.stats.pending }}</div></div></div>
      <div class="kpi kpi--danger"><div class="kpi__icon"><CIcon name="alert" :size="20" /></div><div class="kpi__body"><div class="kpi__label">不合规（须整改）</div><div class="kpi__value">{{ cp.stats.fail }}</div></div></div>
    </div>

    <!-- 分类合规分 -->
    <div class="cat-grid">
      <div v-for="c in CATS" :key="c" class="cat-card" :class="{ 'cat-card--off': catFilter === c }" @click="catFilter = catFilter === c ? '' : c">
        <div class="cat-card__icon"><CIcon :name="CAT_ICON[c]" :size="18" /></div>
        <div class="cat-card__body">
          <div class="cat-card__title">{{ cp.CATEGORY_LABEL[c] }}</div>
          <div class="cat-card__score" :style="{ color: scoreColor(cp.categoryScore(c).rate) }">{{ cp.categoryScore(c).rate }}%</div>
        </div>
        <div v-if="cp.categoryScore(c).fail" class="cat-card__bad">{{ cp.categoryScore(c).fail }}项不合规</div>
      </div>
    </div>

    <CCard padding="none">
      <div class="cx-tabs">
        <button class="cx-tab" :class="{ 'is-active': tab === 'check' }" @click="tab = 'check'">合规检查（{{ cp.items.length }}）</button>
        <button class="cx-tab" :class="{ 'is-active': tab === 'audit' }" @click="tab = 'audit'">审计日志（{{ cp.auditLogs.length }}）</button>
        <div class="cx-tabs__ops">
          <CButton v-if="auth.isSuper" variant="secondary" size="sm" @click="openImp"><CIcon name="user-check" :size="14" /> 超管代操作</CButton>
        </div>
      </div>

      <!-- 检查项 -->
      <div v-if="tab === 'check'" class="check-pane">
        <div class="filter-row">
          <button class="chip" :class="{ 'chip--on': statusFilter === '' }" @click="statusFilter = ''">全部</button>
          <button v-for="(label, key) in cp.STATUS_LABEL" :key="key" class="chip" :class="{ 'chip--on': statusFilter === key, ['chip--' + key.toLowerCase()]: true }" @click="statusFilter = key as ComplianceStatus">{{ label }}</button>
        </div>
        <div class="table-wrap">
          <table class="dt">
            <thead><tr><th>分类</th><th>检查项</th><th>门店</th><th>要求</th><th>状态</th><th>最近检查</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="it in filtered" :key="it.id" :class="{ 'row--fail': it.status === 'FAIL', 'row--warn': it.status === 'WARN' }">
                <td><span class="cat-tag">{{ cp.CATEGORY_LABEL[it.category] }}</span></td>
                <td class="cell-name">
                  {{ it.title }}
                  <div v-if="it.evidence" class="sub">证据：{{ it.evidence }}</div>
                  <div v-if="it.remark" class="sub sub--warn">备注：{{ it.remark }}</div>
                </td>
                <td>{{ it.storeName }}</td>
                <td class="req">{{ it.requirement }}</td>
                <td><CStatusPill :status="statusTone(it.status)" dot>{{ cp.STATUS_LABEL[it.status] }}</CStatusPill></td>
                <td class="muted">{{ fmtTime(it.lastCheckAt) }}<div class="sub">{{ it.checker }}</div></td>
                <td class="ops-cell">
                  <CButton v-if="canEdit" variant="text" size="sm" @click="openRecheck(it)">复检/整改</CButton>
                </td>
              </tr>
              <tr v-if="filtered.length === 0"><td colspan="7" class="empty-cell">暂无检查项</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 审计日志 -->
      <div v-else class="audit-pane">
        <div class="audit-tip"><CIcon name="shield" :size="14" /> 以下日志仅超管可见，不可删除、不可篡改；超管代操作（impersonate）会全程强制留痕。</div>
        <div class="timeline">
          <div v-for="log in cp.auditLogs" :key="log.id" class="tl-item" :class="'tl-item--' + log.risk.toLowerCase()">
            <div class="tl-dot"></div>
            <div class="tl-body">
              <div class="tl-head">
                <span class="tl-action">{{ cp.AUDIT_ACTION_LABEL[log.action] }}</span>
                <CStatusPill :status="riskTone(log.risk)" dot>{{ log.risk === 'HIGH' ? '高风险' : log.risk === 'MEDIUM' ? '中风险' : '低风险' }}</CStatusPill>
                <span class="tl-time">{{ fmtTime(log.at) }}</span>
              </div>
              <div class="tl-detail">{{ log.detail }}</div>
              <div class="tl-meta"><span>操作人：<b>{{ log.actor }}</b></span><span v-if="log.target">对象：{{ log.target }}</span><span class="mono">IP {{ log.ip }}</span></div>
            </div>
          </div>
        </div>
      </div>
    </CCard>

    <!-- 复检弹层 -->
    <div v-if="recheckTarget" class="modal-mask" @click.self="recheckTarget = null">
      <div class="modal modal--sm">
        <div class="modal__head"><h3>复检 / 整改记录</h3><button class="modal__close" @click="recheckTarget = null"><CIcon name="close" :size="18" /></button></div>
        <div class="modal__body">
          <p class="confirm-txt">检查项：<b>{{ recheckTarget.title }}</b>（{{ recheckTarget.storeName }}）</p>
          <div class="recheck-opts">
            <label class="ro" :class="{ 'ro--on': recheckForm.pass }"><input type="radio" :checked="recheckForm.pass" @change="recheckForm.pass = true" /> 复检通过（合规）</label>
            <label class="ro ro--bad" :class="{ 'ro--on': !recheckForm.pass }"><input type="radio" :checked="!recheckForm.pass" @change="recheckForm.pass = false" /> 仍不合规</label>
          </div>
          <label class="field field--full" style="margin-top:12px"><span class="field__label">整改/复检说明</span><CTextarea v-model="recheckForm.remark" :rows="3" placeholder="记录整改措施或复检结论" /></label>
        </div>
        <div class="modal__foot"><CButton variant="secondary" @click="recheckTarget = null">取消</CButton><CButton variant="primary" @click="submitRecheck">提交</CButton></div>
      </div>
    </div>

    <!-- impersonate 弹层 -->
    <div v-if="showImp" class="modal-mask" @click.self="showImp = false">
      <div class="modal modal--sm">
        <div class="modal__head"><h3><CIcon name="alert" :size="16" class="danger-ic" /> 超管代操作（受控）</h3><button class="modal__close" @click="showImp = false"><CIcon name="close" :size="18" /></button></div>
        <div class="modal__body">
          <div class="imp-warn">代操作期间您将以指定身份执行操作，所有行为将记录到不可删除的审计日志。请仅在排障/授权场景使用。</div>
          <label class="field field--full"><span class="field__label">被代操作人 <i>*</i></span><input v-model="impForm.target" class="inp" placeholder="如 苏晴（静安店长）" /></label>
          <label class="field field--full" style="margin-top:12px"><span class="field__label">代操作理由（必填，记入审计） <i>*</i></span><CTextarea v-model="impForm.reason" :rows="3" placeholder="如：处理工单#T20260824 退款审批异常" /></label>
          <div v-if="impErr" class="form-err">{{ impErr }}</div>
        </div>
        <div class="modal__foot"><CButton variant="secondary" @click="showImp = false">取消</CButton><CButton variant="primary" @click="startImp">开始代操作</CButton></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cx-page { display: flex; flex-direction: column; gap: var(--s-md); }
.imp-bar { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-lg); background: var(--c-danger-bg, #FFF0F0); border: 1px solid var(--c-danger-fg); border-radius: var(--r-lg); color: var(--c-danger-fg); font-size: var(--t-sm); }
.imp-bar__txt { flex: 1; }
.imp-bar b { color: var(--c-danger-fg); }

.cx-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--success .kpi__icon { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi--warning .kpi__icon { background: var(--c-warning-bg, #FFF5E6); color: var(--c-warning-fg); }
.kpi--danger .kpi__icon { background: var(--c-danger-bg, #FFF0F0); color: var(--c-danger-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }

.cat-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: var(--s-sm); }
.cat-card { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-radius: var(--r-lg); background: var(--c-surface); border: 1px solid var(--c-border-light); cursor: pointer; transition: all .12s; }
.cat-card:hover { border-color: var(--c-brand); }
.cat-card--off { border-color: var(--c-brand); background: var(--c-brand-soft); }
.cat-card__icon { width: 36px; height: 36px; border-radius: var(--r-md); background: var(--c-surface, #f7f8fa); color: var(--c-brand); display: flex; align-items: center; justify-content: center; flex: none; }
.cat-card__body { flex: 1; min-width: 0; }
.cat-card__title { font-size: var(--t-xs); color: var(--c-text-3); }
.cat-card__score { font-size: var(--t-lg); font-weight: 700; line-height: 1.1; }
.cat-card__bad { font-size: 11px; color: var(--c-danger-fg); background: var(--c-danger-bg, #FFF0F0); padding: 1px 8px; border-radius: var(--r-capsule); }

.cx-tabs { display: flex; align-items: center; gap: var(--s-xs); padding: 4px var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.cx-tab { border: none; background: none; padding: var(--s-sm) var(--s-md); font-size: var(--t-sm); font-weight: 600; color: var(--c-text-3); cursor: pointer; border-bottom: 2px solid transparent; }
.cx-tab.is-active { color: var(--c-brand); border-bottom-color: var(--c-brand); }
.cx-tabs__ops { margin-left: auto; }

.check-pane { padding: var(--s-md) var(--s-lg) var(--s-lg); }
.filter-row { display: flex; gap: 6px; margin-bottom: var(--s-md); flex-wrap: wrap; }
.chip { border: 1px solid var(--c-border-light); background: var(--c-surface); padding: 4px 14px; border-radius: var(--r-capsule); font-size: var(--t-xs); color: var(--c-text-2); cursor: pointer; }
.chip--on { background: var(--c-brand); color: #fff; border-color: var(--c-brand); }
.chip--fail.chip--on { background: var(--c-danger-fg); border-color: var(--c-danger-fg); }
.chip--warn.chip--on { background: var(--c-warning-fg); border-color: var(--c-warning-fg); }

.table-wrap { border: 1px solid var(--c-border-light); border-radius: var(--r-lg); overflow: auto; }
.dt { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.dt th { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); font-weight: 600; text-align: left; padding: 10px var(--s-md); font-size: var(--t-xs); white-space: nowrap; border-bottom: 1px solid var(--c-border-light); }
.dt td { padding: 10px var(--s-md); border-bottom: 1px solid var(--c-border-light); vertical-align: top; }
.dt tr:last-child td { border-bottom: none; }
.dt tr:hover { background: var(--c-surface, #f7f8fa); }
.row--fail { background: var(--c-danger-bg, #FFF0F033); }
.row--warn { background: var(--c-warning-bg, #FFF5E633); }
.cell-name { font-weight: 600; color: var(--c-text); }
.cat-tag { font-size: 11px; padding: 2px 8px; background: var(--c-brand-soft); color: var(--c-brand); border-radius: var(--r-capsule); white-space: nowrap; }
.req { font-size: var(--t-xs); color: var(--c-text-2); max-width: 240px; }
.sub { font-size: 11px; color: var(--c-text-3); font-weight: 400; margin-top: 2px; }
.sub--warn { color: var(--c-warning-fg); }
.muted { color: var(--c-text-3); font-size: var(--t-xs); }
.mono { font-family: var(--t-number, monospace); }
.ops-cell { white-space: nowrap; }
.empty-cell { text-align: center; color: var(--c-text-3); padding: var(--s-xl); }

.audit-pane { padding: var(--s-lg); }
.audit-tip { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-text-2); background: var(--c-surface, #f7f8fa); padding: var(--s-sm) var(--s-md); border-radius: var(--r-md); border-left: 3px solid var(--c-brand); margin-bottom: var(--s-md); }
.timeline { position: relative; padding-left: var(--s-md); }
.timeline::before { content: ''; position: absolute; left: 7px; top: 4px; bottom: 4px; width: 2px; background: var(--c-border-light); }
.tl-item { position: relative; padding-bottom: var(--s-md); }
.tl-dot { position: absolute; left: -16px; top: 4px; width: 12px; height: 12px; border-radius: 50%; background: var(--c-text-3); border: 2px solid var(--c-surface); }
.tl-item--high .tl-dot { background: var(--c-danger-fg); }
.tl-item--medium .tl-dot { background: var(--c-warning-fg); }
.tl-item--low .tl-dot { background: var(--c-info-fg); }
.tl-body { background: var(--c-surface, #f7f8fa); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); }
.tl-head { display: flex; align-items: center; gap: var(--s-sm); }
.tl-action { font-weight: 700; font-size: var(--t-sm); color: var(--c-text); }
.tl-time { margin-left: auto; font-size: 11px; color: var(--c-text-3); }
.tl-detail { font-size: var(--t-xs); color: var(--c-text-2); margin: 4px 0; }
.tl-meta { display: flex; gap: var(--s-md); font-size: 11px; color: var(--c-text-3); }
.tl-meta b { color: var(--c-text-2); }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--c-surface); border-radius: var(--r-xl); width: 480px; max-width: calc(100vw - 48px); max-height: 86vh; display: flex; flex-direction: column; }
.modal--sm { width: 440px; }
.modal__head { display: flex; align-items: center; justify-content: space-between; padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.modal__head h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; display: flex; align-items: center; gap: 6px; }
.danger-ic { color: var(--c-danger-fg); }
.modal__close { border: none; background: none; cursor: pointer; color: var(--c-text-3); padding: 4px; }
.modal__body { padding: var(--s-lg); overflow-y: auto; }
.modal__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-top: 1px solid var(--c-border-light); }
.confirm-txt { margin: 0 0 var(--s-md); font-size: var(--t-sm); color: var(--c-text-2); }
.imp-warn { background: var(--c-danger-bg, #FFF0F0); color: var(--c-danger-fg); font-size: var(--t-xs); padding: var(--s-sm) var(--s-md); border-radius: var(--r-md); margin-bottom: var(--s-md); }
.recheck-opts { display: flex; gap: var(--s-sm); }
.ro { flex: 1; display: flex; align-items: center; gap: 6px; padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); cursor: pointer; }
.ro--on { border-color: var(--c-success-fg); background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); font-weight: 600; }
.ro--bad.ro--on { border-color: var(--c-danger-fg); background: var(--c-danger-bg, #FFF0F0); color: var(--c-danger-fg); }
.field { display: flex; flex-direction: column; gap: 6px; }
.field--full { width: 100%; }
.field__label { font-size: var(--t-xs); color: var(--c-text-2); font-weight: 500; }
.field__label i { color: var(--c-danger-fg); font-style: normal; }
.inp { height: 36px; padding: 0 12px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); width: 100%; }
.form-err { margin-top: var(--s-sm); color: var(--c-danger-fg); font-size: var(--t-xs); }

@media (max-width: 1024px) {
  .cx-kpis { grid-template-columns: repeat(2, 1fr); }
  .cat-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
