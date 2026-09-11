<script setup lang="ts">
// 客户检索 outbox「DEAD 事件处置台」（B32）
// 全局运维视图（区域/集团级，无门店维度）：四态 KPI + 筛选分页 + 事件详情 + 人工重试/立即重放/丢弃。
// 全部端点需 customer:search:admin；写动作错误经 errMsg 透传后端中文 400。
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CPagination from '@/components/CPagination.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { useSearchEventStore } from '@/stores/searchEvent'
import type { SearchEventDTO, SearchEventStatus } from '@/api/searchEvent'

const se = useSearchEventStore()
const toast = useToast()

onMounted(async () => {
  try {
    await Promise.all([se.load(), se.loadStats()])
  } catch (e) {
    toast.error(errMsg(e, '处置台数据加载失败'))
  }
})

const STATUS_OPTIONS: { value: SearchEventStatus; label: string }[] = [
  { value: 'PENDING', label: '待投递' },
  { value: 'SENT', label: '已投递' },
  { value: 'DEAD', label: '投递失败' },
  { value: 'DISCARDED', label: '已丢弃' },
]
const STATUS_LABEL: Record<SearchEventStatus, string> = {
  PENDING: '待投递',
  SENT: '已投递',
  DEAD: '投递失败',
  DISCARDED: '已丢弃',
}
function statusTone(s: SearchEventStatus): 'info' | 'success' | 'danger' | 'disabled' {
  if (s === 'SENT') return 'success'
  if (s === 'DEAD') return 'danger'
  if (s === 'DISCARDED') return 'disabled'
  return 'info'
}

const eventIdInput = ref('')
const selectedId = ref<number | null>(null)
const selected = computed(() => se.events.find((e) => e.eventId === selectedId.value) ?? null)
function selectRow(e: SearchEventDTO) { selectedId.value = e.eventId }

const hasFilter = computed(() => !!(se.fStatus || se.fCustomerId.trim() || se.fEventId !== undefined))
async function onSearch() {
  const v = eventIdInput.value.trim()
  se.fEventId = v ? Number(v) : undefined
  if (v && (!Number.isFinite(se.fEventId) || (se.fEventId as number) <= 0)) {
    toast.warning('事件ID需为正整数')
    se.fEventId = undefined
    return
  }
  selectedId.value = null
  await se.search()
}
async function onReset() {
  se.resetFilter()
  eventIdInput.value = ''
  selectedId.value = null
  await se.search()
}
async function turnPage(p: number) {
  await se.load(p)
}
async function toggleKpi(s: SearchEventStatus) {
  se.fStatus = se.fStatus === s ? '' : s
  selectedId.value = null
  await se.search()
}

function canRetry(e: SearchEventDTO) { return e.status === 'DEAD' }
function canReplay(e: SearchEventDTO) { return e.status === 'PENDING' || e.status === 'DEAD' }
const canDiscard = canReplay

async function onRetry(e: SearchEventDTO) {
  try {
    await se.retry(e.eventId)
    toast.success(`事件 #${e.eventId} 已交回中继重试`)
  } catch (err) {
    toast.error(errMsg(err, '重试失败'))
  }
}
async function onReplay(e: SearchEventDTO) {
  try {
    await se.replay(e.eventId)
    const fresh = se.events.find((x) => x.eventId === e.eventId)
    if (fresh?.status === 'SENT') toast.success(`事件 #${e.eventId} 重放成功，已投递`)
    else if (fresh?.status === 'DEAD') toast.warning(`事件 #${e.eventId} 重放后仍投递失败，请查看最后错误`)
    else toast.info(`事件 #${e.eventId} 重放完成，当前状态：${fresh ? STATUS_LABEL[fresh.status] : '未知'}`)
  } catch (err) {
    toast.error(errMsg(err, '重放失败'))
  }
}

const discardTarget = ref<SearchEventDTO | null>(null)
const discardNote = ref('')
const discardError = ref('')
function openDiscard(e: SearchEventDTO) {
  discardTarget.value = e
  discardNote.value = ''
  discardError.value = ''
}
function closeDiscard() {
  if (se.acting) return
  discardTarget.value = null
  discardNote.value = ''
  discardError.value = ''
}
async function confirmDiscard() {
  if (!discardTarget.value) return
  const note = discardNote.value.trim()
  if (note.length < 2) {
    discardError.value = '丢弃原因至少 2 个字，便于审计追溯'
    return
  }
  try {
    const id = discardTarget.value.eventId
    await se.discard(id, note)
    toast.success(`事件 #${id} 已丢弃`)
    closeDiscard()
  } catch (err) {
    discardError.value = errMsg(err, '丢弃失败')
  }
}

async function onReindex() {
  try {
    const n = await se.reindex()
    toast.success(`全量重建完成，共索引 ${n} 位客户`)
  } catch (err) {
    toast.error(errMsg(err, '索引重建失败'))
  }
}

function fmtTime(iso?: string | null): string {
  if (!iso) return '—'
  return iso.replace('T', ' ').slice(0, 19)
}
function eventTypeLabel(t: string): string {
  const map: Record<string, string> = {
    CUSTOMER_UPSERT: '客户写入',
    CUSTOMER_DELETE: '客户删除',
  }
  return map[t] ?? t
}
</script>

<template>
  <div class="se-page">
    <div class="se-kpis">
      <button
        v-for="k in ([
          { key: 'PENDING', cls: 'kpi--info', icon: 'clock', label: '待投递', value: se.stats.pending },
          { key: 'SENT', cls: 'kpi--success', icon: 'check-square', label: '已投递', value: se.stats.sent },
          { key: 'DEAD', cls: 'kpi--danger', icon: 'alert', label: '投递失败', value: se.stats.dead },
          { key: 'DISCARDED', cls: 'kpi--muted', icon: 'close', label: '已丢弃', value: se.stats.discarded },
        ] as const)"
        :key="k.key"
        class="kpi"
        :class="[k.cls, { 'kpi--on': se.fStatus === k.key }]"
        @click="toggleKpi(k.key)"
      >
        <div class="kpi__icon"><CIcon :name="k.icon" :size="20" /></div>
        <div class="kpi__body">
          <div class="kpi__label">{{ k.label }}</div>
          <div class="kpi__value">{{ k.value }}</div>
        </div>
      </button>
    </div>

    <CCard padding="md">
      <div class="se-filter">
        <select v-model="se.fStatus" class="sel">
          <option value="">全部状态</option>
          <option v-for="o in STATUS_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
        <CInput v-model="se.fCustomerId" placeholder="客户ID" />
        <CInput v-model="eventIdInput" placeholder="事件ID（精确）" />
        <CButton variant="primary" size="sm" @click="onSearch">查询</CButton>
        <CButton v-if="hasFilter" variant="text" size="sm" @click="onReset">清除筛选</CButton>
        <div class="se-filter__spacer" />
        <CButton
          variant="secondary"
          size="sm"
          :disabled="se.reindexing"
          v-perm.disable="'customer:search:admin'"
          @click="onReindex"
        >
          <CIcon name="refresh" :size="14" /> {{ se.reindexing ? '重建中…' : '全量重建索引' }}
        </CButton>
      </div>
    </CCard>

    <div class="se-main">
      <CCard padding="none" class="se-list">
        <div class="table-wrap">
          <table class="dt">
            <thead>
              <tr><th>事件ID</th><th>类型</th><th>客户</th><th>状态</th><th>重试</th><th>发生时间</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr
                v-for="e in se.events"
                :key="e.eventId"
                :class="{ 'row--active': selectedId === e.eventId, 'row--dead': e.status === 'DEAD' }"
                @click="selectRow(e)"
              >
                <td class="mono">#{{ e.eventId }}</td>
                <td><span class="type-tag">{{ eventTypeLabel(e.eventType) }}</span></td>
                <td>
                  <div class="cell-name">{{ e.customerName ?? '（客户已删除）' }}</div>
                  <div class="sub mono">{{ e.customerId }}<span v-if="e.storeCode"> · {{ e.storeCode }}</span></div>
                </td>
                <td><CStatusPill :status="statusTone(e.status)" dot>{{ e.statusLabel || STATUS_LABEL[e.status] }}</CStatusPill></td>
                <td class="mono">{{ e.retryCount }}</td>
                <td class="mono"><div>{{ fmtTime(e.createdAt) }}</div></td>
                <td @click.stop>
                  <div class="row-acts">
                    <CButton
                      variant="text"
                      size="sm"
                      :disabled="!canRetry(e) || se.acting"
                      v-perm.disable="'customer:search:admin'"
                      @click="onRetry(e)"
                    >重试</CButton>
                    <CButton
                      variant="text"
                      size="sm"
                      :disabled="!canReplay(e) || se.acting"
                      v-perm.disable="'customer:search:admin'"
                      @click="onReplay(e)"
                    >重放</CButton>
                    <CButton
                      variant="text"
                      size="sm"
                      class="act-danger"
                      :disabled="!canDiscard(e) || se.acting"
                      v-perm.disable="'customer:search:admin'"
                      @click="openDiscard(e)"
                    >丢弃</CButton>
                  </div>
                </td>
              </tr>
              <tr v-if="se.events.length === 0">
                <td colspan="7" class="empty-cell">{{ se.loading ? '加载中…' : '无匹配事件（投递链路健康）' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="se.total > 0" class="se-pager">
          <CPagination :page="se.page" :page-size="se.pageSize" :total="se.total" @update:page="turnPage" />
        </div>
      </CCard>

      <CCard v-if="selected" padding="none" class="se-detail">
        <div class="ad-head">
          <div>
            <div class="ad-action">事件 #{{ selected.eventId }}</div>
            <div class="ad-time"><CIcon name="clock" :size="13" /> {{ fmtTime(selected.createdAt) }}</div>
          </div>
          <CStatusPill :status="statusTone(selected.status)" dot>{{ selected.statusLabel || STATUS_LABEL[selected.status] }}</CStatusPill>
        </div>
        <div class="ad-body">
          <div class="ad-row"><span class="lbl">事件类型</span><span class="val"><span class="type-tag">{{ eventTypeLabel(selected.eventType) }}</span></span></div>
          <div class="ad-row"><span class="lbl">客户</span><span class="val"><b>{{ selected.customerName ?? '（客户已删除）' }}</b></span></div>
          <div class="ad-row"><span class="lbl">客户ID</span><span class="val mono">{{ selected.customerId }}</span></div>
          <div class="ad-row" v-if="selected.phone"><span class="lbl">手机号</span><span class="val mono">{{ selected.phone }}</span></div>
          <div class="ad-row" v-if="selected.storeCode"><span class="lbl">归属门店</span><span class="val mono">{{ selected.storeCode }}</span></div>
          <div class="ad-row"><span class="lbl">重试次数</span><span class="val">{{ selected.retryCount }}</span></div>
          <div class="ad-row"><span class="lbl">投递时间</span><span class="val mono">{{ fmtTime(selected.sentAt) }}</span></div>
          <div class="ad-row" v-if="selected.resolvedAt">
            <span class="lbl">人工处置</span>
            <span class="val mono">{{ fmtTime(selected.resolvedAt) }} · {{ selected.resolvedBy ?? '—' }}</span>
          </div>

          <div v-if="selected.lastError" class="ad-block ad-block--err">
            <span class="lbl">最后错误</span>
            <p>{{ selected.lastError }}</p>
          </div>
          <div v-if="selected.resolveNote" class="ad-block">
            <span class="lbl">丢弃原因</span>
            <p>{{ selected.resolveNote }}</p>
          </div>
        </div>
        <div v-if="canDiscard(selected) || canReplay(selected)" class="ad-foot">
          <CButton
            variant="secondary"
            size="sm"
            :disabled="!canRetry(selected) || se.acting"
            v-perm.disable="'customer:search:admin'"
            @click="onRetry(selected)"
          >交回重试</CButton>
          <CButton
            variant="secondary"
            size="sm"
            :disabled="!canReplay(selected) || se.acting"
            v-perm.disable="'customer:search:admin'"
            @click="onReplay(selected)"
          >立即重放</CButton>
          <CButton
            variant="danger"
            size="sm"
            :disabled="!canDiscard(selected) || se.acting"
            v-perm.disable="'customer:search:admin'"
            @click="openDiscard(selected)"
          >丢弃</CButton>
        </div>
      </CCard>
    </div>

    <div v-if="discardTarget" class="modal-mask" @click.self="closeDiscard">
      <CCard class="modal" :title="`丢弃事件 #${discardTarget.eventId}`" padding="lg">
        <div class="dl-form">
          <p class="dl-tip">
            丢弃为终态操作，该事件将不再投递到检索引擎。仅适用于确认无法恢复的毒消息（如客户已删除且无需检索）。
          </p>
          <div class="dl-meta">
            <span class="type-tag">{{ eventTypeLabel(discardTarget.eventType) }}</span>
            <span class="mono">{{ discardTarget.customerId }}</span>
            <CStatusPill :status="statusTone(discardTarget.status)" dot>{{ STATUS_LABEL[discardTarget.status] }}</CStatusPill>
          </div>
          <CTextarea
            v-model="discardNote"
            placeholder="请填写丢弃原因（至少 2 个字），将写入审计日志"
            :rows="4"
            :error="!!discardError"
          />
          <p v-if="discardError" class="dl-err">{{ discardError }}</p>
        </div>
        <template #footer>
          <CButton variant="ghost" :disabled="se.acting" @click="closeDiscard">取消</CButton>
          <CButton variant="danger" :disabled="se.acting" @click="confirmDiscard">
            {{ se.acting ? '提交中…' : '确认丢弃' }}
          </CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.se-page { display: flex; flex-direction: column; gap: var(--s-md); }
.se-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi {
  display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md);
  border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light);
  cursor: pointer; text-align: left; font: inherit; transition: border-color .15s, box-shadow .15s;
}
.kpi:hover { border-color: var(--c-brand-border, var(--c-brand)); }
.kpi--on { border-color: var(--c-brand); box-shadow: 0 0 0 2px rgba(255, 107, 158, 0.12); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--success .kpi__icon { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg, #16a34a); }
.kpi--danger .kpi__icon { background: var(--c-danger-bg, #FFF0F0); color: var(--c-danger-fg); }
.kpi--muted .kpi__icon { background: var(--c-disabled-bg, #f1f2f5); color: var(--c-text-3); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); }

.se-filter { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: nowrap; overflow-x: auto; }
.se-filter .sel { flex-shrink: 0; }
.se-filter > :deep(.cinput) { flex: 1; min-width: 140px; max-width: 220px; }
.se-filter__spacer { flex: 1; }
.sel { height: 36px; padding: 0 12px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); }

.se-main { display: grid; grid-template-columns: 1fr 380px; gap: var(--s-md); align-items: start; }
.se-list { max-height: calc(100vh - 340px); display: flex; flex-direction: column; overflow: hidden; }
.table-wrap { width: 100%; overflow: auto; flex: 1; }
.dt { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.dt th { position: sticky; top: 0; background: var(--c-surface, #f7f8fa); color: var(--c-text-3); font-weight: 600; text-align: left; padding: 10px var(--s-md); font-size: var(--t-xs); white-space: nowrap; border-bottom: 1px solid var(--c-border-light); z-index: 1; }
.dt td { padding: 10px var(--s-md); border-bottom: 1px solid var(--c-border-light); vertical-align: middle; }
.dt tbody tr { cursor: pointer; transition: background .1s; }
.dt tbody tr:hover { background: var(--c-surface, #f7f8fa); }
.row--active { background: var(--c-brand-soft) !important; }
.row--dead { border-left: 3px solid var(--c-danger-fg); }
.mono { font-family: var(--t-number, monospace); font-size: var(--t-xs); color: var(--c-text-2); }
.sub { font-size: 11px; color: var(--c-text-3); margin-top: 2px; }
.cell-name { font-weight: 600; color: var(--c-text); }
.type-tag { font-size: 11px; padding: 2px 8px; background: var(--c-brand-soft); color: var(--c-brand); border-radius: var(--r-capsule); white-space: nowrap; }
.empty-cell { text-align: center; color: var(--c-text-3); padding: var(--s-xl); }
.row-acts { display: flex; align-items: center; gap: 2px; }
.row-acts :deep(.cbtn) { padding: 2px 8px; }
.act-danger { color: var(--c-danger-fg) !important; }
.se-pager { border-top: 1px solid var(--c-border-light); padding: 0 var(--s-md); }
.se-pager :deep(.cpag) { padding: var(--s-sm) 0; }

.se-detail { position: sticky; top: 0; max-height: calc(100vh - 260px); overflow-y: auto; }
.ad-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-md); padding: var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.ad-action { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.ad-time { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.ad-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-md); }
.ad-row { display: flex; align-items: center; gap: var(--s-md); font-size: var(--t-sm); }
.ad-row .lbl { width: 72px; flex: none; font-size: var(--t-xs); color: var(--c-text-3); }
.ad-row .val { color: var(--c-text); display: flex; align-items: center; gap: 6px; }
.ad-block { display: flex; flex-direction: column; gap: 4px; }
.ad-block .lbl { font-size: var(--t-xs); color: var(--c-text-3); }
.ad-block p { margin: 0; font-size: var(--t-sm); color: var(--c-text); line-height: 1.6; padding: var(--s-sm) var(--s-md); background: var(--c-surface, #f7f8fa); border-radius: var(--r-md); border-left: 3px solid var(--c-brand); word-break: break-all; white-space: pre-wrap; }
.ad-block--err p { background: var(--c-danger-bg, #FFF0F0); border-left-color: var(--c-danger-fg); }
.ad-foot { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-lg); border-top: 1px solid var(--c-border-light); }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.dl-form { display: flex; flex-direction: column; gap: var(--s-md); }
.dl-tip { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; }
.dl-meta { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); }
.dl-err { margin: 0; font-size: var(--t-xs); color: var(--c-danger-fg); }

@media (max-width: 1024px) {
  .se-kpis { grid-template-columns: repeat(2, 1fr); }
  .se-main { grid-template-columns: 1fr; }
  .se-list { max-height: none; }
  .se-detail { position: static; max-height: none; }
}
</style>
