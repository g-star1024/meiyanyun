<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { useM1AuditStore, type AuditLogRow } from '@/stores/m1Audit'

const au = useM1AuditStore()
onMounted(() => au.init())

const selectedId = ref<number | null>(null)
const selected = computed(() => au.items.find((e) => e.id === selectedId.value) ?? null)
function select(e: AuditLogRow) { selectedId.value = e.id }

function fmtTime(iso: string) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
function relTime(iso: string) {
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const h = (Date.now() - t) / 3600000
  if (h < 1) return Math.max(1, Math.round(h * 60)) + '分钟前'
  if (h < 24) return Math.round(h) + '小时前'
  return Math.round(h / 24) + '天前'
}
function payloadDigest(payload: string): string {
  const s = (payload || '').replace(/\s+/g, ' ').trim()
  return s.length > 48 ? s.slice(0, 48) + '…' : s || '—'
}
function prettyPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}
function shortHash(h: string) { return h ? h.slice(0, 10) : '—' }

const hasFilter = computed(() =>
  au.filters.bizType || au.filters.actor || au.filters.keyword || au.filters.from || au.filters.to)
function applyFilter() { au.search(0) }
function resetFilter() { au.resetFilters() }

const chainTone = computed(() => {
  if (!au.verify) return 'disabled'
  return au.verify.ok ? 'success' : 'danger'
})
const chainBreaks = computed(() => au.verify?.breaks ?? [])
const chainText = computed(() => {
  if (!au.verify) return '校验中…'
  if (au.verify.ok) return `完整 · ${au.verify.total} 条`
  const n = chainBreaks.value.length
  return n > 1 ? `断链 ${n} 处 · #${au.verify.brokenAtId} 等` : `断链 #${au.verify.brokenAtId}`
})
const chainTitle = computed(() => {
  if (!au.verify) return '哈希链巡检中'
  if (au.verify.ok) return `哈希链完整，共 ${au.verify.total} 条；点击重新巡检`
  const ids = chainBreaks.value.map((b) => `#${b.id}`).join('、')
  return `检出 ${chainBreaks.value.length} 处断链：${ids}（点击重新巡检）`
})
function breakTime(iso: string) {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <div class="au-page">
    <div class="au-kpis">
      <div class="kpi kpi--brand"><div class="kpi__icon"><CIcon name="order" :size="20" /></div><div class="kpi__body"><div class="kpi__label">审计记录总数</div><div class="kpi__value">{{ au.stats.total }}</div></div></div>
      <div class="kpi kpi--warning"><div class="kpi__icon"><CIcon name="clock" :size="20" /></div><div class="kpi__body"><div class="kpi__label">近24小时新增</div><div class="kpi__value">{{ au.stats.last24 }}</div></div></div>
      <div class="kpi kpi--info"><div class="kpi__icon"><CIcon name="user" :size="20" /></div><div class="kpi__body"><div class="kpi__label">操作人数（含系统）</div><div class="kpi__value">{{ au.stats.actors }}</div></div></div>
      <div class="kpi kpi--danger kpi--clickable" :title="chainTitle" @click="au.checkChain()"><div class="kpi__icon"><CIcon name="shield" :size="20" /></div><div class="kpi__body"><div class="kpi__label">哈希链完整性</div><div class="kpi__value kpi__value--sm"><CStatusPill :status="chainTone" dot>{{ chainText }}</CStatusPill></div></div></div>
    </div>

    <CCard v-if="chainBreaks.length" padding="md" class="au-breaks">
      <div class="brk-head">
        <CIcon name="shield" :size="15" />
        <span>哈希链巡检：检出 <b>{{ chainBreaks.length }}</b> 处断链（append-only 存量不回改，仅列示），点击 KPI 卡可重新巡检</span>
      </div>
      <div class="brk-list">
        <div v-for="b in chainBreaks" :key="b.id" class="brk-item" :title="`期望前驱 ${b.expectedPrev}\n存储前驱 ${b.storedPrev}`">
          <CStatusPill status="danger" dot>#{{ b.id }}</CStatusPill>
          <span class="brk-meta">{{ breakTime(b.createdAt) }} · {{ au.displayActor(b.actor) }}（{{ b.actor }}） · {{ b.action }}</span>
        </div>
      </div>
    </CCard>

    <CCard padding="md">
      <div class="au-filter">
      <select v-model="au.filters.bizType" class="sel" @change="applyFilter">
        <option value="">全部模块</option>
        <option v-for="b in au.facets?.bizTypes ?? []" :key="b.bizType" :value="b.bizType">
          {{ au.bizLabel(b.bizType) }}（{{ b.count }}）
        </option>
      </select>
      <input v-model="au.filters.actor" class="sel sel--input" placeholder="操作人工号" @keyup.enter="applyFilter" />
      <input v-model="au.filters.from" type="datetime-local" class="sel" title="起始时间" @change="applyFilter" />
      <input v-model="au.filters.to" type="datetime-local" class="sel" title="截止时间" @change="applyFilter" />
      <CInput v-model="au.filters.keyword" placeholder="搜索单号/动作/载荷" @keyup.enter="applyFilter" />
      <CButton variant="text" size="sm" @click="applyFilter">查询</CButton>
      <CButton v-if="hasFilter" variant="text" size="sm" @click="resetFilter">清除筛选</CButton>
      </div>
    </CCard>

    <div class="au-main">
      <CCard padding="none" class="au-list">
        <div class="table-wrap">
          <table class="dt">
            <thead><tr><th>时间</th><th>操作人</th><th>模块</th><th>动作</th><th>业务单号</th><th>载荷摘要</th><th>链哈希</th></tr></thead>
            <tbody>
              <tr v-for="e in au.items" :key="e.id" :class="{ 'row--active': selectedId === e.id, 'row--high': au.isSensitive(e.action) }" @click="select(e)">
                <td class="mono"><div>{{ fmtTime(e.createdAt) }}</div><div class="sub">{{ relTime(e.createdAt) }}</div></td>
                <td><div class="cell-name">{{ au.displayActor(e.actor) }}</div><div class="sub">{{ e.actor }}</div></td>
                <td><span class="mod-tag">{{ au.bizLabel(e.bizType) }}</span></td>
                <td>{{ e.action }}<CStatusPill v-if="au.isSensitive(e.action)" status="warning" dot>敏感</CStatusPill></td>
                <td class="mono target">{{ e.txnNo || '—' }}</td>
                <td class="target" :title="e.payload">{{ payloadDigest(e.payload) }}</td>
                <td class="mono" :title="e.curHash">{{ shortHash(e.curHash) }}</td>
              </tr>
              <tr v-if="!au.loading && au.items.length === 0"><td colspan="7" class="empty-cell">{{ au.error || '无匹配审计记录' }}</td></tr>
              <tr v-if="au.loading"><td colspan="7" class="empty-cell">加载中…</td></tr>
            </tbody>
          </table>
        </div>
        <div class="au-pager">
          <span class="au-pager__info">第 {{ au.page + 1 }} / {{ au.totalPages }} 页 · 共 {{ au.total }} 条</span>
          <select class="sel sel--sm" :value="au.size" @change="au.setSize(Number(($event.target as HTMLSelectElement).value))">
            <option :value="20">20 条/页</option>
            <option :value="50">50 条/页</option>
            <option :value="100">100 条/页</option>
          </select>
          <CButton variant="text" size="sm" :disabled="au.page <= 0 || au.loading" @click="au.search(au.page - 1)">上一页</CButton>
          <CButton variant="text" size="sm" :disabled="au.page >= au.totalPages - 1 || au.loading" @click="au.search(au.page + 1)">下一页</CButton>
        </div>
      </CCard>

      <CCard v-if="selected" padding="none" class="au-detail">
        <div class="ad-head">
          <div>
            <div class="ad-action">{{ selected.action }}</div>
            <div class="ad-time"><CIcon name="clock" :size="13" /> {{ new Date(selected.createdAt).toLocaleString('zh-CN') }} · {{ relTime(selected.createdAt) }}</div>
          </div>
          <CStatusPill v-if="au.isSensitive(selected.action)" status="warning" dot>敏感操作</CStatusPill>
        </div>
        <div class="ad-body">
          <div class="ad-row"><span class="lbl">操作人</span><span class="val"><b>{{ au.displayActor(selected.actor) }}</b>（{{ selected.actor }}）</span></div>
          <div class="ad-row"><span class="lbl">所属模块</span><span class="val"><span class="mod-tag">{{ au.bizLabel(selected.bizType) }}</span><span class="sub">{{ selected.bizType }}</span></span></div>
          <div class="ad-row"><span class="lbl">业务单号</span><span class="val mono">{{ selected.txnNo || '—' }}</span></div>
          <div class="ad-row"><span class="lbl">记录编号</span><span class="val mono">#{{ selected.id }}</span></div>
          <div class="ad-row"><span class="lbl">当前哈希</span><span class="val mono hash" :title="selected.curHash">{{ selected.curHash }}</span></div>
          <div class="ad-row"><span class="lbl">前序哈希</span><span class="val mono hash" :title="selected.prevHash">{{ selected.prevHash }}</span></div>

          <div class="ad-detail-block">
            <span class="lbl">业务载荷（jsonb）</span>
            <pre class="payload-pre">{{ prettyPayload(selected.payload) }}</pre>
          </div>
        </div>
        <div class="ad-foot">
          <CIcon name="shield" :size="14" />
          <span>审计日志 append-only，不可删除或修改；「敏感操作」为动作语义的展示层派生口径。</span>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.au-page { display: flex; flex-direction: column; gap: var(--s-md); }
.au-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi--clickable { cursor: pointer; }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--danger .kpi__icon { background: var(--c-danger-bg, #FFF0F0); color: var(--c-danger-fg); }
.kpi--warning .kpi__icon { background: var(--c-warning-bg, #FFF5E6); color: var(--c-warning-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); display: flex; align-items: baseline; gap: 6px; }
.kpi__value--sm { font-size: var(--t-md); }

.au-breaks { border-color: var(--c-danger-fg, #e03e3e); }
.brk-head { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-text-2); }
.brk-head b { color: var(--c-danger-fg, #e03e3e); }
.brk-list { display: flex; flex-direction: column; gap: 6px; margin-top: var(--s-sm); }
.brk-item { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); }
.brk-meta { color: var(--c-text-2); }

.au-filter { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: nowrap; overflow-x: auto; }
.au-filter .sel { flex-shrink: 0; }
.au-filter > :deep(.cinput) { flex: 1; min-width: 160px; max-width: 260px; }
.au-filter .cbtn { flex-shrink: 0; white-space: nowrap; }
.sel { height: 36px; padding: 0 12px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); }
.sel--input { width: 120px; }
.sel--sm { height: 30px; font-size: var(--t-xs); }

.au-main { display: grid; grid-template-columns: 1fr 380px; gap: var(--s-md); align-items: start; }
.au-list { max-height: calc(100vh - 340px); overflow: auto; }
.table-wrap { width: 100%; }
.dt { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.dt th { position: sticky; top: 0; background: var(--c-surface, #f7f8fa); color: var(--c-text-3); font-weight: 600; text-align: left; padding: 10px var(--s-md); font-size: var(--t-xs); white-space: nowrap; border-bottom: 1px solid var(--c-border-light); z-index: 1; }
.dt td { padding: 10px var(--s-md); border-bottom: 1px solid var(--c-border-light); vertical-align: middle; }
.dt tbody tr { cursor: pointer; transition: background .1s; }
.dt tbody tr:hover { background: var(--c-surface, #f7f8fa); }
.row--active { background: var(--c-brand-soft) !important; }
.row--high { border-left: 3px solid var(--c-warning-fg, #d9822b); }
.mono { font-family: var(--t-number, monospace); font-size: var(--t-xs); color: var(--c-text-2); }
.sub { font-size: 11px; color: var(--c-text-3); margin-top: 2px; }
.cell-name { font-weight: 600; color: var(--c-text); }
.mod-tag { font-size: 11px; padding: 2px 8px; background: var(--c-brand-soft); color: var(--c-brand); border-radius: var(--r-capsule); white-space: nowrap; }
.target { font-size: var(--t-xs); color: var(--c-text-2); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty-cell { text-align: center; color: var(--c-text-3); padding: var(--s-xl); }

.au-pager { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); border-top: 1px solid var(--c-border-light); position: sticky; bottom: 0; background: var(--c-surface); }
.au-pager__info { font-size: var(--t-xs); color: var(--c-text-3); margin-right: auto; }

.au-detail { position: sticky; top: 0; }
.ad-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-md); padding: var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.ad-action { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.ad-time { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.ad-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-md); }
.ad-row { display: flex; align-items: center; gap: var(--s-md); font-size: var(--t-sm); }
.ad-row .lbl { width: 72px; flex: none; font-size: var(--t-xs); color: var(--c-text-3); }
.ad-row .val { color: var(--c-text); display: flex; align-items: center; gap: 6px; min-width: 0; }
.hash { word-break: break-all; }
.ad-detail-block { display: flex; flex-direction: column; gap: 4px; }
.ad-detail-block .lbl { font-size: var(--t-xs); color: var(--c-text-3); }
.payload-pre { margin: 0; font-size: var(--t-xs); color: var(--c-text); line-height: 1.6; padding: var(--s-sm) var(--s-md); background: var(--c-surface, #f7f8fa); border-radius: var(--r-md); border-left: 3px solid var(--c-brand); max-height: 320px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.ad-foot { display: flex; align-items: center; gap: 6px; padding: var(--s-sm) var(--s-lg); border-top: 1px solid var(--c-border-light); font-size: 11px; color: var(--c-text-3); }

@media (max-width: 1024px) {
  .au-kpis { grid-template-columns: repeat(2, 1fr); }
  .au-main { grid-template-columns: 1fr; }
  .au-list { max-height: none; }
}
</style>
