<script setup lang="ts">
/* ============================================================
 * T2-01 数据采集 /data/collect（P5-B98 接真：纯监控页）
 * 采集通道（touch_event 五通道聚合）+ 触点时间线，KPI×4
 * v1 无写动作（DESIGN-T2 §6）：创建/测试/同步/编辑等 mock 动作已摘除
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSegmented from '@/components/CSegmented.vue'
import { useT2DataCollectStore, type ChannelCard } from '@/stores/t2DataCollect'
import type { TouchType } from '@/api/touchEvent'

const store = useT2DataCollectStore()
onMounted(() => store.seed())

const tab = ref<'channels' | 'events'>('channels')
const tabOpts = [
  { value: 'channels', label: '采集通道' },
  { value: 'events', label: '触点时间线' },
]

const kpis = computed(() => [
  { label: '采集通道', icon: 'layers', value: String(store.channels.length), tone: 'brand' as const },
  { label: '活跃通道', icon: 'check-square', value: String(store.activeCount), tone: 'success' as const },
  { label: '今日触点', icon: 'clock', value: store.todayTouches.toLocaleString(), tone: 'teal' as const },
  { label: '待接入', icon: 'alert', value: String(store.emptyCount), tone: store.emptyCount > 0 ? 'danger' as const : 'text' as const },
])

const CHANNEL_ICON: Record<TouchType, 'navigate' | 'edit' | 'scan' | 'marketing' | 'handover'> = {
  LANDING_VISIT: 'navigate',
  LANDING_LEAD: 'edit',
  POSTER_SCAN: 'scan',
  PUSH_SEND: 'marketing',
  RETURNBACK: 'handover',
}

function statusPill(s: ChannelCard['status']) {
  return s === 'ACTIVE' ? 'success' : 'disabled'
}

/** 时间线触点类型 tag 色 */
function touchTagClass(t: TouchType) {
  switch (t) {
    case 'LANDING_VISIT': return 'tag--primary'
    case 'LANDING_LEAD': return 'tag--success'
    case 'POSTER_SCAN': return 'tag--info'
    case 'PUSH_SEND': return 'tag--warn'
    default: return 'tag--purple'
  }
}

function fmtTime(iso: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<template>
  <div class="col">
    <div class="col__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard class="col__main" padding="none">
      <div class="col__toolbar">
        <CSegmented v-model="tab" :options="tabOpts" size="sm" />
        <div class="col__toolbar-right">
          <span v-if="store.loadError" class="load-err"><CIcon name="alert" :size="14" />{{ store.loadError }}</span>
          <CButton variant="secondary" size="sm" :disabled="store.loading" @click="store.load()">
            <CIcon name="refresh" :size="16" />{{ store.loading ? '加载中…' : '刷新' }}
          </CButton>
        </div>
      </div>

      <!-- 采集通道（数据源列表＝五通道聚合） -->
      <div v-if="tab === 'channels'" class="src-grid">
        <div v-for="c in store.channels" :key="c.touchType" class="src-card">
          <div class="src-card__head">
            <div class="src-card__title">
              <span class="src-card__icon"><CIcon :name="CHANNEL_ICON[c.touchType]" :size="18" /></span>
              <div>
                <div class="src-card__name">{{ c.name }}</div>
                <div class="src-card__meta">{{ c.touchType }}</div>
              </div>
            </div>
            <CStatusPill :status="statusPill(c.status)" dot>{{ c.status === 'ACTIVE' ? '已接入' : '待接入' }}</CStatusPill>
          </div>
          <div class="src-card__body">
            <div class="src-line">
              <span class="src-line__label">接入方式</span>
              <span class="src-line__val">{{ c.desc }}</span>
            </div>
            <div class="src-line">
              <span class="src-line__label">累计触点</span>
              <span class="src-line__val num">{{ c.count.toLocaleString() }}</span>
            </div>
            <div class="src-line">
              <span class="src-line__label">今日触点</span>
              <span class="src-line__val num">{{ c.todayCount.toLocaleString() }}</span>
            </div>
            <div class="src-line">
              <span class="src-line__label">最近触点</span>
              <span class="src-line__val">{{ fmtTime(c.latestAt) }}</span>
            </div>
          </div>
        </div>
        <div v-if="store.loaded && store.channels.length === 0" class="empty-hint">暂无通道数据</div>
      </div>

      <!-- 触点时间线（同步任务＝触点倒序流） -->
      <table v-else class="ctable">
        <thead>
          <tr>
            <th style="width:130px">触点类型</th>
            <th style="width:110px">渠道</th>
            <th style="width:120px">客户</th>
            <th>来源</th>
            <th style="width:150px">发生时刻</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in store.events" :key="e.id">
            <td><span class="tag" :class="touchTagClass(e.touchType)">{{ store.TOUCH_TYPE_LABEL[e.touchType] ?? e.touchType }}</span></td>
            <td>{{ store.channelLabel(e.channel) }}</td>
            <td class="muted">{{ e.customerId || '—' }}</td>
            <td class="muted">{{ store.refTypeLabel(e.refType) }}<template v-if="e.refId"> #{{ e.refId }}</template></td>
            <td>{{ fmtTime(e.at) }}</td>
          </tr>
          <tr v-if="store.loaded && store.events.length === 0">
            <td colspan="5" class="empty-cell">暂无触点数据</td>
          </tr>
        </tbody>
      </table>
    </CCard>
  </div>
</template>

<style scoped>
.col { display: flex; flex-direction: column; gap: var(--s-md); }
.col__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
.col__head :deep(.ckpi) { min-width: 0; }
@media (max-width: 1024px) {
  .col__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); }
}

.col__main :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); padding: 0; }
.col__toolbar {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-md) var(--s-lg) 0;
}
.col__toolbar-right { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }
.load-err {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: var(--t-xs); color: var(--c-danger-fg);
}

.src-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--s-md);
  padding: 0 var(--s-lg) var(--s-lg);
}
.src-card {
  display: flex;
  flex-direction: column;
  gap: var(--s-sm);
  padding: var(--s-md);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  transition: border-color .15s, box-shadow .15s;
}
.src-card:hover { border-color: var(--c-brand-border); box-shadow: var(--shadow-card); }
.src-card__head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-sm); }
.src-card__title { display: flex; gap: var(--s-sm); align-items: center; min-width: 0; }
.src-card__icon {
  width: 36px; height: 36px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--c-brand-soft); color: var(--c-brand);
  border-radius: var(--r-md);
}
.src-card__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); line-height: 1.3; }
.src-card__meta { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.src-card__body { display: flex; flex-direction: column; gap: 6px; padding: var(--s-xs) 0; border-top: 1px dashed var(--c-border); }
.src-line { display: flex; justify-content: space-between; gap: var(--s-sm); font-size: var(--t-xs); }
.src-line__label { color: var(--c-text-3); flex-shrink: 0; }
.src-line__val { color: var(--c-text-2); text-align: right; word-break: break-all; }
.src-line__val.num { font-variant-numeric: tabular-nums; color: var(--c-text); font-weight: 600; }
.empty-hint { grid-column: 1 / -1; text-align: center; color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-xl) 0; }

.ctable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.ctable thead th { padding: 12px var(--s-lg); background: var(--c-bg-page); color: var(--c-text); font-weight: 600; font-size: var(--t-xs); text-align: left; border-bottom: 1px solid var(--c-border); white-space: nowrap; }
.ctable tbody td { padding: 14px var(--s-lg); color: var(--c-text-2); border-bottom: 1px solid var(--c-border); vertical-align: middle; }
.ctable tbody tr:last-child td { border-bottom: none; }
.ctable tbody tr:hover { background: var(--c-brand-soft); }
.ctable .muted { color: var(--c-text-3); font-size: var(--t-xs); }
.empty-cell { text-align: center; color: var(--c-text-3); padding: var(--s-xl) var(--s-lg) !important; }

.tag { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: var(--r-pill); font-size: var(--t-xs); font-weight: 500; white-space: nowrap; }
.tag--primary { color: var(--c-brand); background: var(--c-brand-soft); }
.tag--info { color: var(--c-info-fg); background: var(--c-info-bg); }
.tag--success { color: var(--c-success-fg); background: var(--c-success-bg); }
.tag--warn { color: var(--c-warning-fg); background: var(--c-warning-bg); }
.tag--purple { color: #7C3AED; background: #F3E8FF; }
</style>
