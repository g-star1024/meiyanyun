<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import {
  useM1AuditStore, type AuditEntry, type AuditModule, type AuditRisk,
} from '@/stores/m1Audit'

const au = useM1AuditStore()
onMounted(() => au.seed())

const kw = ref('')
const fModule = ref<AuditModule | ''>('')
const fRisk = ref<AuditRisk | ''>('')
const fResult = ref<'' | 'SUCCESS' | 'FAILED'>('')

const filtered = computed(() => au.logs.filter((e) => {
  if (fModule.value && e.module !== fModule.value) return false
  if (fRisk.value && e.risk !== fRisk.value) return false
  if (fResult.value && e.result !== fResult.value) return false
  if (kw.value) {
    const q = kw.value.trim()
    if (!`${e.actor} ${e.action} ${e.target} ${e.detail}`.includes(q)) return false
  }
  return true
}))

const selectedId = ref('')
const selected = computed(() => au.logs.find((e) => e.id === selectedId.value))
function select(e: AuditEntry) { selectedId.value = e.id }

function riskTone(r: AuditRisk) { return r === 'HIGH' ? 'danger' : r === 'MEDIUM' ? 'warning' : 'disabled' }
function resultTone(r: string) { return r === 'SUCCESS' ? 'success' : 'danger' }
function fmtTime(iso: string) {
  const d = new Date(iso)
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
function relTime(iso: string) {
  const h = (Date.now() - new Date(iso).getTime()) / 3600000
  if (h < 1) return Math.round(h * 60) + '分钟前'
  if (h < 24) return Math.round(h) + '小时前'
  return Math.round(h / 24) + '天前'
}

const modules = Object.keys(au.MODULE_LABEL) as AuditModule[]
const hasFilter = computed(() => fModule.value || fRisk.value || fResult.value || kw.value)
function resetFilter() { fModule.value = ''; fRisk.value = ''; fResult.value = ''; kw.value = '' }
</script>

<template>
  <div class="au-page">
    <div class="au-kpis">
      <div class="kpi kpi--brand"><div class="kpi__icon"><CIcon name="order" :size="20" /></div><div class="kpi__body"><div class="kpi__label">审计记录总数</div><div class="kpi__value">{{ au.stats.total }}</div></div></div>
      <div class="kpi kpi--danger"><div class="kpi__icon"><CIcon name="alert" :size="20" /></div><div class="kpi__body"><div class="kpi__label">高风险操作</div><div class="kpi__value">{{ au.stats.high }}</div></div></div>
      <div class="kpi kpi--warning"><div class="kpi__icon"><CIcon name="clock" :size="20" /></div><div class="kpi__body"><div class="kpi__label">近24小时</div><div class="kpi__value">{{ au.stats.last24 }}</div></div></div>
      <div class="kpi kpi--info"><div class="kpi__icon"><CIcon name="user" :size="20" /></div><div class="kpi__body"><div class="kpi__label">操作人数 / 失败</div><div class="kpi__value">{{ au.stats.actors }}<span class="kpi__sub kpi__sub--danger">{{ au.stats.failed }} 失败</span></div></div></div>
    </div>

    <CCard padding="md">
      <div class="au-filter">
      <select v-model="fModule" class="sel">
        <option value="">全部模块</option>
        <option v-for="m in modules" :key="m" :value="m">{{ au.MODULE_LABEL[m] }}</option>
      </select>
      <select v-model="fRisk" class="sel">
        <option value="">全部风险</option>
        <option value="HIGH">高风险</option>
        <option value="MEDIUM">中风险</option>
        <option value="LOW">常规</option>
      </select>
      <select v-model="fResult" class="sel">
        <option value="">全部结果</option>
        <option value="SUCCESS">成功</option>
        <option value="FAILED">失败</option>
      </select>
      <CInput v-model="kw" placeholder="搜索操作人/动作/对象/详情" />
      <CButton v-if="hasFilter" variant="text" size="sm" @click="resetFilter">清除筛选</CButton>
      </div>
    </CCard>

    <div class="au-main">
      <CCard padding="none" class="au-list">
        <div class="table-wrap">
          <table class="dt">
            <thead><tr><th>时间</th><th>操作人</th><th>模块</th><th>动作</th><th>对象</th><th>风险</th><th>结果</th></tr></thead>
            <tbody>
              <tr v-for="e in filtered" :key="e.id" :class="{ 'row--active': selectedId === e.id, 'row--fail': e.result === 'FAILED', 'row--high': e.risk === 'HIGH' && e.result === 'SUCCESS' }" @click="select(e)">
                <td class="mono"><div>{{ fmtTime(e.at) }}</div><div class="sub">{{ relTime(e.at) }}</div></td>
                <td><div class="cell-name">{{ e.actor }}</div><div class="sub">{{ e.actorRole }}</div></td>
                <td><span class="mod-tag">{{ au.MODULE_LABEL[e.module] }}</span></td>
                <td>{{ e.action }}</td>
                <td class="target">{{ e.target }}</td>
                <td><CStatusPill :status="riskTone(e.risk)" dot>{{ au.RISK_LABEL[e.risk] }}</CStatusPill></td>
                <td><CStatusPill :status="resultTone(e.result)" dot>{{ e.result === 'SUCCESS' ? '成功' : '失败' }}</CStatusPill></td>
              </tr>
              <tr v-if="filtered.length === 0"><td colspan="7" class="empty-cell">无匹配审计记录</td></tr>
            </tbody>
          </table>
        </div>
      </CCard>

      <CCard v-if="selected" padding="none" class="au-detail">
        <div class="ad-head">
          <div>
            <div class="ad-action">{{ selected.action }}</div>
            <div class="ad-time"><CIcon name="clock" :size="13" /> {{ new Date(selected.at).toLocaleString('zh-CN') }} · {{ relTime(selected.at) }}</div>
          </div>
          <CStatusPill :status="resultTone(selected.result)" dot>{{ selected.result === 'SUCCESS' ? '成功' : '失败' }}</CStatusPill>
        </div>
        <div class="ad-body">
          <div class="ad-row"><span class="lbl">操作人</span><span class="val"><b>{{ selected.actor }}</b>（{{ selected.actorRole }}）</span></div>
          <div class="ad-row"><span class="lbl">所属模块</span><span class="val"><span class="mod-tag">{{ au.MODULE_LABEL[selected.module] }}</span></span></div>
          <div class="ad-row"><span class="lbl">操作对象</span><span class="val mono">{{ selected.target }}</span></div>
          <div class="ad-row"><span class="lbl">风险等级</span><span class="val"><CStatusPill :status="riskTone(selected.risk)" dot>{{ au.RISK_LABEL[selected.risk] }}</CStatusPill></span></div>
          <div class="ad-row"><span class="lbl">来源 IP</span><span class="val mono">{{ selected.ip }}</span></div>

          <div v-if="selected.before || selected.after" class="ad-diff">
            <div v-if="selected.before" class="diff diff--before"><span class="diff__tag">变更前</span>{{ selected.before }}</div>
            <div class="diff-arrow"><CIcon name="chevron-right" :size="16" /></div>
            <div v-if="selected.after" class="diff diff--after"><span class="diff__tag">变更后</span>{{ selected.after }}</div>
          </div>

          <div class="ad-detail-block">
            <span class="lbl">操作详情</span>
            <p>{{ selected.detail }}</p>
          </div>
        </div>
        <div class="ad-foot">
          <CIcon name="shield" :size="14" />
          <span>审计日志 append-only，不可删除或修改。高风险操作已同步至集团合规中心。</span>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.au-page { display: flex; flex-direction: column; gap: var(--s-md); }
.au-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--danger .kpi__icon { background: var(--c-danger-bg, #FFF0F0); color: var(--c-danger-fg); }
.kpi--warning .kpi__icon { background: var(--c-warning-bg, #FFF5E6); color: var(--c-warning-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); display: flex; align-items: baseline; gap: 6px; }
.kpi__sub { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); }
.kpi__sub--danger { color: var(--c-danger-fg); font-weight: 600; }

.au-filter { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: nowrap; overflow-x: auto; }
.au-filter .sel { flex-shrink: 0; }
.au-filter > :deep(.cinput) { flex: 1; min-width: 200px; max-width: 320px; }
.au-filter .cbtn { flex-shrink: 0; white-space: nowrap; }
.sel { height: 36px; padding: 0 12px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); }

.au-main { display: grid; grid-template-columns: 1fr 380px; gap: var(--s-md); align-items: start; }
.au-list { max-height: calc(100vh - 340px); overflow: auto; }
.table-wrap { width: 100%; }
.dt { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.dt th { position: sticky; top: 0; background: var(--c-surface, #f7f8fa); color: var(--c-text-3); font-weight: 600; text-align: left; padding: 10px var(--s-md); font-size: var(--t-xs); white-space: nowrap; border-bottom: 1px solid var(--c-border-light); z-index: 1; }
.dt td { padding: 10px var(--s-md); border-bottom: 1px solid var(--c-border-light); vertical-align: middle; }
.dt tbody tr { cursor: pointer; transition: background .1s; }
.dt tbody tr:hover { background: var(--c-surface, #f7f8fa); }
.row--active { background: var(--c-brand-soft) !important; }
.row--fail { background: var(--c-danger-bg, #FFF0F033); }
.row--high { border-left: 3px solid var(--c-danger-fg); }
.mono { font-family: var(--t-number, monospace); font-size: var(--t-xs); color: var(--c-text-2); }
.sub { font-size: 11px; color: var(--c-text-3); margin-top: 2px; }
.cell-name { font-weight: 600; color: var(--c-text); }
.mod-tag { font-size: 11px; padding: 2px 8px; background: var(--c-brand-soft); color: var(--c-brand); border-radius: var(--r-capsule); white-space: nowrap; }
.target { font-size: var(--t-xs); color: var(--c-text-2); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty-cell { text-align: center; color: var(--c-text-3); padding: var(--s-xl); }

.au-detail { position: sticky; top: 0; }
.ad-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-md); padding: var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.ad-action { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.ad-time { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.ad-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-md); }
.ad-row { display: flex; align-items: center; gap: var(--s-md); font-size: var(--t-sm); }
.ad-row .lbl { width: 72px; flex: none; font-size: var(--t-xs); color: var(--c-text-3); }
.ad-row .val { color: var(--c-text); display: flex; align-items: center; gap: 6px; }
.ad-diff { display: flex; align-items: stretch; gap: var(--s-sm); margin-top: 4px; }
.diff { flex: 1; padding: var(--s-sm) var(--s-md); border-radius: var(--r-md); font-size: var(--t-xs); position: relative; padding-top: 22px; }
.diff--before { background: var(--c-danger-bg, #FFF0F0); color: var(--c-text-2); }
.diff--after { background: var(--c-success-bg, #f0fbf0); color: var(--c-text-2); }
.diff__tag { position: absolute; top: 4px; left: 10px; font-size: 10px; font-weight: 700; color: var(--c-text-3); }
.diff-arrow { display: flex; align-items: center; color: var(--c-text-3); }
.ad-detail-block { display: flex; flex-direction: column; gap: 4px; }
.ad-detail-block .lbl { font-size: var(--t-xs); color: var(--c-text-3); }
.ad-detail-block p { margin: 0; font-size: var(--t-sm); color: var(--c-text); line-height: 1.6; padding: var(--s-sm) var(--s-md); background: var(--c-surface, #f7f8fa); border-radius: var(--r-md); border-left: 3px solid var(--c-brand); }
.ad-foot { display: flex; align-items: center; gap: 6px; padding: var(--s-sm) var(--s-lg); border-top: 1px solid var(--c-border-light); font-size: 11px; color: var(--c-text-3); }

@media (max-width: 1024px) {
  .au-kpis { grid-template-columns: repeat(2, 1fr); }
  .au-main { grid-template-columns: 1fr; }
  .au-list { max-height: none; }
}
</style>
