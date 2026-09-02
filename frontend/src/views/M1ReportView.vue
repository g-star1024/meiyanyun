<template>
  <div class="rp">
    <div class="rp__kpis">
      <CKpi :value="String(rp.templates.length)" label="报表模板" tone="brand" icon="settings" />
      <CKpi :value="String(rp.subscribedCount)" label="订阅报表" tone="success" icon="trend-up" />
      <CKpi :value="String(readyJobs)" label="已生成文件" icon="export" />
      <CKpi :value="String(failedJobs)" label="失败任务" tone="danger" icon="alert" />
    </div>

    <div class="rp__body">
      <CCard title="报表模板" padding="none" class="rp__list">
        <div class="cats">
          <button :class="{ 'is-active': rp.catFilter === 'ALL' }" @click="rp.catFilter = 'ALL'">全部</button>
          <button v-for="(l, k) in CAT_LABEL" :key="k" :class="{ 'is-active': rp.catFilter === k }" @click="rp.catFilter = k as any">{{ l }}</button>
        </div>
        <div class="tpl" v-for="t in rp.filtered" :key="t.id"
             :class="{ 'is-active': selId === t.id }" @click="selId = t.id">
          <div class="tpl__top">
            <span class="tpl__cat">{{ CAT_LABEL[t.category] }}</span>
            <CStatusPill v-if="t.subscribed" status="success">已订阅</CStatusPill>
          </div>
          <div class="tpl__name">{{ t.name }}</div>
          <div class="tpl__desc">{{ t.desc }}</div>
          <div class="tpl__meta">
            <span v-for="m in t.metrics.slice(0,3)" :key="m" class="chip">{{ m }}</span>
          </div>
          <div class="tpl__foot">
            <span>{{ PERIOD_LABEL[t.period] }}</span>
            <span v-if="t.lastRunAt">最近 {{ t.lastRunAt }}</span>
          </div>
        </div>
      </CCard>

      <CCard class="rp__detail" padding="lg">
        <template v-if="sel">
          <div class="detail__head">
            <div>
              <h3>{{ sel.name }}</h3>
              <div class="detail__sub">
                <span class="tag tag--dim">{{ CAT_LABEL[sel.category] }}</span>
                <span>{{ PERIOD_LABEL[sel.period] }}</span>
                <span v-if="sel.lastRunAt">最近生成 {{ sel.lastRunAt }}</span>
              </div>
            </div>
            <CButton v-if="canExport" size="sm" :variant="sel.subscribed ? 'ghost' : 'secondary'"
                     @click="rp.toggleSubscribe(sel.id)">{{ sel.subscribed ? '取消订阅' : '订阅' }}</CButton>
          </div>

          <p class="detail__desc">{{ sel.desc }}</p>

          <div class="detail__grid">
            <div class="blk"><div class="blk__l">分析维度</div>
              <span v-for="d in sel.dimensions" :key="d" class="chip">{{ d }}</span></div>
            <div class="blk"><div class="blk__l">核心指标</div>
              <span v-for="m in sel.metrics" :key="m" class="chip">{{ m }}</span></div>
          </div>

          <!-- 生成 -->
          <div v-if="canExport" class="gen">
            <div class="gen__row">
              <label>统计周期</label>
              <CSelect v-model="period" :options="periodOptions" />
            </div>
            <div class="gen__row">
              <label>导出格式</label>
              <div class="fmts">
                <button v-for="f in (['XLSX','PDF','CSV'] as const)" :key="f"
                        :class="{ 'is-active': fmt === f }" @click="fmt = f">{{ FORMAT_LABEL[f] }}</button>
              </div>
            </div>
            <CButton variant="primary" @click="doGen"><CIcon name="export" :size="14" /> 生成报表</CButton>
          </div>

          <!-- 数据预览 -->
          <div v-if="previewData" class="preview">
            <div class="preview__h">数据预览（示例）</div>
            <CTable :columns="previewCols" :rows="previewRows" />
          </div>

          <!-- 最近生成记录 -->
          <div class="jobs">
            <div class="jobs__h">最近生成</div>
            <div class="job" v-for="j in selJobs" :key="j.id">
              <div class="job__left">
                <CStatusPill :status="jobStatus(j.status)">{{ STATUS_LABEL[j.status] }}</CStatusPill>
                <span class="job__period">{{ j.period }} · {{ FORMAT_LABEL[j.format] }}</span>
                <span v-if="j.rowCount" class="job__meta">{{ j.rowCount }} 行 · {{ j.fileSize }}</span>
                <span v-if="j.error" class="job__err">{{ j.error }}</span>
              </div>
              <div class="job__right">
                <span class="job__by">{{ j.createdBy }} · {{ j.createdAt }}</span>
                <CButton v-if="j.status === 'READY'" size="sm" variant="ghost" @click="download(j)">
                  <CIcon name="export" :size="13" /> 下载
                </CButton>
                <CButton v-if="j.status === 'FAILED' && canExport" size="sm" variant="ghost" @click="rp.retry(j.id)">重试</CButton>
              </div>
            </div>
            <div v-if="!selJobs.length" class="empty">暂无生成记录</div>
          </div>
        </template>
      </CCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CTable from '@/components/CTable.vue'
import CSelect from '@/components/CSelect.vue'
import CKpi from '@/components/CKpi.vue'
import { useM1ReportStore, CAT_LABEL, STATUS_LABEL, FORMAT_LABEL,
  type ReportStatus, type ExportFormat } from '@/stores/m1Report'
import { useAuthStore } from '@/stores/auth'

const PERIOD_LABEL: Record<string, string> = { DAY: '日报', WEEK: '周报', MONTH: '月报', QUARTER: '季报', YEAR: '年报', RANGE: '自定义' }

const rp = useM1ReportStore()
const auth = useAuthStore()
onMounted(() => rp.seed())

const canExport = computed(() => auth.can('report:export') || auth.isSuper)

const selId = ref('R02')
const sel = computed(() => rp.templates.find((t) => t.id === selId.value))
const period = ref('2026-08')
const fmt = ref<ExportFormat>('XLSX')
const periodOptions = [
  { label: '今日 (2026-08-25)', value: '2026-08-25' },
  { label: '本周 (W34)', value: '2026-W34' },
  { label: '本月 (2026-08)', value: '2026-08' },
  { label: '上月 (2026-07)', value: '2026-07' },
  { label: '本季度 (2026-Q3)', value: '2026-Q3' },
]

const previewData = computed(() => sel.value ? rp.preview(sel.value.id) : null)
const previewCols = computed<{ key: string; label: string; align: 'left' | 'right' }[]>(() =>
  previewData.value ? previewData.value.headers.map((h, i) => ({ key: 'c' + i, label: h, align: i === 0 ? 'left' as const : 'right' as const })) : [])
const previewRows = computed(() => previewData.value ? previewData.value.rows.map((r) => { const o: Record<string, string | number> = {}; r.forEach((v, i) => (o['c' + i] = v)); return o }) : [])

const selJobs = computed(() => rp.jobs.filter((j) => j.templateId === selId.value))
const readyJobs = computed(() => rp.jobs.filter((j) => j.status === 'READY').length)
const failedJobs = computed(() => rp.jobs.filter((j) => j.status === 'FAILED').length)

function doGen() { if (sel.value) rp.generate(sel.value.id, period.value, fmt.value) }
function download(j: { templateName: string; period: string; format: ExportFormat }) {
  // 模拟下载
  const blob = new Blob([`${j.templateName} ${j.period}`], { type: 'text/plain' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${j.templateName}_${j.period}.${j.format.toLowerCase()}`
  a.click()
  URL.revokeObjectURL(a.href)
}
function jobStatus(s: ReportStatus): 'success' | 'warning' | 'danger' {
  return s === 'READY' ? 'success' : s === 'GENERATING' ? 'warning' : 'danger'
}
void CAT_LABEL
</script>

<style scoped>
.rp { display: flex; flex-direction: column; gap: var(--s-lg); }
.rp__kpis { display: flex; gap: var(--s-md); flex-wrap: wrap; }
.rp__kpis :deep(.ckpi) { flex: 1 1 0; min-width: 168px; padding: var(--s-lg); }
.rp__body { display: grid; grid-template-columns: 360px 1fr; gap: var(--s-lg); align-items: start; }
.cats { display: flex; flex-wrap: wrap; gap: var(--s-xs); padding: var(--s-sm) var(--s-md); border-bottom: 1px solid var(--c-border); }
.cats button { border: none; background: none; padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); font-size: var(--t-xs); cursor: pointer; color: var(--c-text-2); transition: all .15s; }
.cats button:hover { background: var(--c-surface-muted); }
.cats button.is-active { background: var(--c-brand); color: #fff; }
.tpl { padding: var(--s-md) var(--s-lg); cursor: pointer; border-bottom: 1px solid var(--c-border); transition: background .15s; }
.tpl:last-child { border-bottom: none; }
.tpl:hover { background: var(--c-surface-muted); }
.tpl.is-active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.tpl__top { display: flex; justify-content: space-between; align-items: center; }
.tpl__cat { font-size: var(--t-xs); color: var(--c-brand); font-weight: 600; }
.tpl__name { font-weight: 600; font-size: var(--t-sm); margin: var(--s-xs) 0 2px; }
.tpl__desc { font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.6; }
.tpl__meta { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin: var(--s-xs) 0; }
.chip { font-size: 10px; padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-surface-muted); color: var(--c-text-2); }
.tpl__foot { display: flex; gap: var(--s-md); font-size: 10px; color: var(--c-text-3); }
.detail__head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); margin-bottom: var(--s-sm); }
.detail__head h3 { margin: 0 0 var(--s-xs); font-size: var(--t-lg); font-weight: 700; }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); }
.tag--dim { background: var(--c-brand-soft); color: var(--c-brand); }
.detail__desc { font-size: var(--t-sm); color: var(--c-text-2); margin: 0 0 var(--s-md); line-height: 1.6; }
.detail__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); margin-bottom: var(--s-lg); }
.blk { background: var(--c-surface-muted); border-radius: var(--r-md); padding: var(--s-md); }
.blk__l { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); font-weight: 600; }
.blk .chip { margin: 0 var(--s-xs) var(--s-xs) 0; display: inline-flex; }
.gen { background: var(--c-surface-muted); border-radius: var(--r-md); padding: var(--s-lg); margin-bottom: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-md); }
.gen__row { display: flex; align-items: center; gap: var(--s-md); }
.gen__row label { font-size: var(--t-sm); width: 72px; color: var(--c-text-2); flex-shrink: 0; }
.fmts { display: flex; gap: var(--s-xs); }
.fmts button { border: 1px solid var(--c-border); background: var(--c-surface); padding: var(--s-xs) var(--s-md); border-radius: var(--r-sm); font-size: var(--t-sm); cursor: pointer; transition: all .15s; }
.fmts button:hover { border-color: var(--c-brand); }
.fmts button.is-active { border-color: var(--c-brand); background: var(--c-brand-soft); color: var(--c-brand); }
.preview { margin-bottom: var(--s-lg); }
.preview__h { font-weight: 600; font-size: var(--t-sm); margin-bottom: var(--s-sm); }
.jobs__h { font-weight: 600; font-size: var(--t-sm); margin-bottom: var(--s-xs); padding-top: var(--s-md); border-top: 1px solid var(--c-border); }
.job { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border); gap: var(--s-sm); }
.job:last-child { border-bottom: none; }
.job__left { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; font-size: var(--t-xs); }
.job__period { font-weight: 600; }
.job__meta { color: var(--c-text-3); }
.job__err { color: var(--c-danger-fg); }
.job__right { display: flex; align-items: center; gap: var(--s-xs); flex-shrink: 0; }
.job__by { font-size: 10px; color: var(--c-text-3); }
.empty { text-align: center; color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-xl) 0; }
@media (max-width: 900px) { .rp__body { grid-template-columns: 1fr; } .detail__grid { grid-template-columns: 1fr; } }
</style>
