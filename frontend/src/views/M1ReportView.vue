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

          <!-- 生成（首卡收窄：仅 R01/R02 真实生成，仅 CSV；XLSX/PDF 与其余模板已登记 backlog） -->
          <div v-if="canExport && supported" class="gen">
            <div class="gen__row">
              <label>统计周期</label>
              <CSelect v-model="period" :options="periodOptions" />
            </div>
            <div class="gen__row">
              <label>导出格式</label>
              <div class="fmts">
                <button v-for="f in (['CSV','XLSX','PDF'] as const)" :key="f"
                        :class="{ 'is-active': fmt === f }"
                        @click="fmt = f">{{ FORMAT_LABEL[f] }}</button>
              </div>
            </div>
            <CButton variant="primary" @click="doGen"><CIcon name="export" :size="14" /> 生成报表</CButton>
          </div>
          <div v-else-if="canExport" class="gen gen--note">该模板数据源待建，已登记 backlog，暂不支持在线生成与预览。</div>

          <!-- 数据预览 -->
          <div v-if="previewData" class="preview">
            <div class="preview__h">数据预览（真实数据 · 前 50 行）</div>
            <CTable :columns="previewCols" :rows="previewRows" />
          </div>

          <!-- 最近生成记录 -->
          <div class="jobs">
            <div class="jobs__h">最近生成</div>
            <div class="jobwrap" v-for="j in selJobs" :key="j.id">
            <div class="job">
              <div class="job__left">
                <CStatusPill :status="jobStatus(j.status)">{{ STATUS_LABEL[j.status] }}</CStatusPill>
                <span class="job__period">{{ j.period }} · {{ FORMAT_LABEL[j.format] }}</span>
                <span v-if="j.rowCount" class="job__meta">{{ j.rowCount }} 行 · {{ j.fileSize }}</span>
                <code v-if="j.contentHash" class="job__hash" :title="'SHA-256 指纹：' + j.contentHash">
                  <CIcon name="shield" :size="11" /> {{ j.contentHash.slice(0, 12) }}…
                </code>
                <span v-if="j.error" class="job__err">{{ j.error }}</span>
              </div>
              <div class="job__right">
                <span class="job__by">{{ j.createdBy }} · {{ j.createdAt }}</span>
                <CButton v-if="j.status === 'READY'" size="sm" variant="ghost"
                         :disabled="rp.verifyingIds.has(j.id)" @click="doVerify(j.id)">
                  <CIcon :name="rp.verifyingIds.has(j.id) ? 'loading' : 'shield'" :size="13" />
                  {{ rp.verifyingIds.has(j.id) ? '验真中…' : '哈希验真' }}
                </CButton>
                <CButton v-if="j.status === 'READY'" size="sm" variant="ghost" @click="download(j)">
                  <CIcon name="export" :size="13" /> 下载
                </CButton>
                <CButton v-if="j.status === 'FAILED' && canExport" size="sm" variant="ghost" @click="rp.retry(j.id)">重试</CButton>
              </div>
            </div>
            <!-- B56 验真结果（四态：MATCH 绿 / MISMATCH 红 / 两历史空态灰） -->
            <div v-if="vfyRes(j.id)" class="vfy" :class="vfyTone(vfyRes(j.id)!.reason)">
              <div class="vfy__head">
                <CIcon :name="vfyIcon(vfyRes(j.id)!.reason)" :size="15" />
                <span class="vfy__concl">{{ vfyRes(j.id)!.conclusion }}</span>
                <span class="vfy__tag">{{ VFY_REASON_LABEL[vfyRes(j.id)!.reason] }}</span>
                <span class="vfy__time">验真于 {{ vfyRes(j.id)!.verifiedAt }}</span>
              </div>
              <div v-if="vfyRes(j.id)!.fileName" class="vfy__file">
                <CIcon name="export" :size="11" /> {{ vfyRes(j.id)!.fileName }}
                <span class="vfy__time">（生成于 {{ vfyRes(j.id)!.generatedAt }}）</span>
              </div>
              <div v-if="vfyRes(j.id)!.expectedHash" class="vfy__hash">
                <span class="vfy__hash-l">落库指纹</span>
                <code>{{ vfyRes(j.id)!.expectedHash }}</code>
              </div>
              <div v-if="vfyRes(j.id)!.actualHash && vfyRes(j.id)!.actualHash !== vfyRes(j.id)!.expectedHash" class="vfy__hash">
                <span class="vfy__hash-l">当前重算</span>
                <code>{{ vfyRes(j.id)!.actualHash }}</code>
              </div>
              <div v-else-if="vfyRes(j.id)!.actualHash && !vfyRes(j.id)!.expectedHash" class="vfy__hash">
                <span class="vfy__hash-l">当前哈希</span>
                <code>{{ vfyRes(j.id)!.actualHash }}</code>
              </div>
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
import { ref, computed, watch, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CTable from '@/components/CTable.vue'
import CSelect from '@/components/CSelect.vue'
import CKpi from '@/components/CKpi.vue'
import { useM1ReportStore, CAT_LABEL, STATUS_LABEL, FORMAT_LABEL,
  type ReportStatus, type ExportFormat, type ReportJob, type ReportPreview,
  type ReportVerifyResult, type ReportVerifyReason } from '@/stores/m1Report'
import { useAuthStore } from '@/stores/auth'

const PERIOD_LABEL: Record<string, string> = { DAY: '日报', WEEK: '周报', MONTH: '月报', QUARTER: '季报', YEAR: '年报', RANGE: '自定义' }

const rp = useM1ReportStore()
const auth = useAuthStore()
onMounted(() => { void rp.seed() })

const canExport = computed(() => auth.can('report:export') || auth.isSuper)

const selId = ref('R02')
const sel = computed(() => rp.templates.find((t) => t.id === selId.value))
// 首卡收窄：仅 R01/R02 真实生成（其余模板后端 422「数据源待建」，已登记 backlog）
const supported = computed(() => !!sel.value && rp.isSupported(sel.value.id))

const period = ref('')
const fmt = ref<ExportFormat>('CSV')

// 统计周期按模板周期类型动态生成（日报=近 7 天 yyyy-MM-dd，月报=近 6 月 yyyy-MM，与后端校验口径对齐）
function fmtLocalDate(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const periodOptions = computed(() => {
  const opts: { label: string; value: string }[] = []
  const now = new Date()
  if (sel.value?.period === 'DAY') {
    for (let i = 0; i < 7; i++) {
      const d = new Date(now)
      d.setDate(d.getDate() - i)
      const v = fmtLocalDate(d)
      opts.push({ label: i === 0 ? `今日 (${v})` : v, value: v })
    }
  } else if (sel.value?.period === 'MONTH') {
    for (let i = 0; i < 6; i++) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
      const v = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
      opts.push({ label: i === 0 ? `本月 (${v})` : v, value: v })
    }
  }
  return opts
})

// 数据预览：真实接口（不支持/期段非法 → null 不渲染）；seq 守卫防快速切换模板时串台
const previewData = ref<ReportPreview | null>(null)
let previewSeq = 0
async function loadPreview() {
  const mySeq = ++previewSeq
  previewData.value = null
  const t = sel.value
  if (!t || !rp.isSupported(t.id)) return
  const data = await rp.preview(t.id, period.value || undefined)
  if (mySeq === previewSeq) previewData.value = data
}
watch(sel, () => { period.value = periodOptions.value[0]?.value || '' })
watch([sel, period], () => { void loadPreview() })

const previewCols = computed<{ key: string; label: string; align: 'left' | 'right' }[]>(() =>
  previewData.value ? previewData.value.headers.map((h, i) => ({ key: 'c' + i, label: h, align: i === 0 ? 'left' as const : 'right' as const })) : [])
const previewRows = computed(() => previewData.value ? previewData.value.rows.map((r) => { const o: Record<string, string | number> = {}; r.forEach((v, i) => (o['c' + i] = v)); return o }) : [])

const selJobs = computed(() => rp.jobs.filter((j) => j.templateId === selId.value))
const readyJobs = computed(() => rp.jobs.filter((j) => j.status === 'READY').length)
const failedJobs = computed(() => rp.jobs.filter((j) => j.status === 'FAILED').length)

function doGen() { if (sel.value) void rp.generate(sel.value.id, period.value, fmt.value) }
// 真实下载：blob + Content-Disposition 文件名另存（历史种子行 content=NULL 由 store toast 提示）
async function download(j: ReportJob) {
  const res = await rp.download(j.id)
  if (!res) return
  const a = document.createElement('a')
  a.href = URL.createObjectURL(res.blob)
  a.download = res.filename
  a.click()
  URL.revokeObjectURL(a.href)
}
function jobStatus(s: ReportStatus): 'success' | 'warning' | 'danger' {
  return s === 'READY' ? 'success' : s === 'GENERATING' ? 'warning' : 'danger'
}

// B56 哈希验真：结果按 jobId 取（store 留存），四态映射到面板色调/图标/中文标签
const VFY_REASON_LABEL: Record<ReportVerifyReason, string> = {
  MATCH: '指纹一致',
  MISMATCH: '指纹不一致',
  HASH_NOT_RECORDED: '历史无指纹',
  HISTORICAL_NOT_RETAINED: '历史文件未留存',
}
function vfyRes(jobId: string): ReportVerifyResult | undefined {
  return rp.verifyResults[jobId]
}
function doVerify(jobId: string) { void rp.verify(jobId) }
function vfyTone(reason: ReportVerifyReason): 'ok' | 'bad' | 'dim' {
  return reason === 'MATCH' ? 'ok' : reason === 'MISMATCH' ? 'bad' : 'dim'
}
function vfyIcon(reason: ReportVerifyReason): 'check-square' | 'alert' | 'clock' {
  return reason === 'MATCH' ? 'check-square' : reason === 'MISMATCH' ? 'alert' : 'clock'
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
.fmts button:disabled { opacity: .45; cursor: not-allowed; }
.fmts button:disabled:hover { border-color: var(--c-border); }
.gen--note { color: var(--c-text-2); font-size: var(--t-sm); }
.preview { margin-bottom: var(--s-lg); }
.preview__h { font-weight: 600; font-size: var(--t-sm); margin-bottom: var(--s-sm); }
.jobs__h { font-weight: 600; font-size: var(--t-sm); margin-bottom: var(--s-xs); padding-top: var(--s-md); border-top: 1px solid var(--c-border); }
.job { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border); gap: var(--s-sm); }
.jobwrap:last-child .job { border-bottom: none; }
.job__left { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; font-size: var(--t-xs); }
.job__period { font-weight: 600; }
.job__meta { color: var(--c-text-3); }
.job__hash { display: inline-flex; align-items: center; gap: 3px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10px; color: var(--c-text-3); background: var(--c-surface-muted); border-radius: var(--r-sm); padding: 2px 7px; cursor: help; }
.job__hash svg { color: var(--c-brand); }
.job__err { color: var(--c-danger-fg); }
.job__right { display: flex; align-items: center; gap: var(--s-xs); flex-shrink: 0; }
.job__by { font-size: 10px; color: var(--c-text-3); }
/* B56 验真结果卡：左色条+底色随四态（MATCH 绿 / MISMATCH 红 / 历史空态灰） */
.vfy { margin: var(--s-xs) 0 var(--s-sm); padding: var(--s-sm) var(--s-md); border-radius: var(--r-md); font-size: var(--t-xs); line-height: 1.7; }
.vfy.ok { background: var(--c-success-bg); box-shadow: inset 3px 0 0 var(--c-success-fg); }
.vfy.ok .vfy__head svg { color: var(--c-success-fg); }
.vfy.bad { background: var(--c-danger-bg); box-shadow: inset 3px 0 0 var(--c-danger-fg); }
.vfy.bad .vfy__head svg, .vfy.bad .vfy__concl { color: var(--c-danger-fg); }
.vfy.dim { background: var(--c-surface-muted); box-shadow: inset 3px 0 0 var(--c-text-3); }
.vfy.dim .vfy__head svg { color: var(--c-text-3); }
.vfy__head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.vfy__concl { font-weight: 600; }
.vfy__tag { padding: 1px 8px; border-radius: var(--r-sm); background: rgba(0,0,0,.06); font-size: 10px; color: var(--c-text-2); }
.vfy__time { color: var(--c-text-3); font-size: 10px; }
.vfy__file { display: flex; align-items: center; gap: 4px; color: var(--c-text-2); margin-top: 2px; }
.vfy__hash { display: flex; align-items: baseline; gap: var(--s-sm); margin-top: 2px; }
.vfy__hash-l { color: var(--c-text-3); flex-shrink: 0; }
.vfy__hash code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10px; color: var(--c-text-2); word-break: break-all; }
.empty { text-align: center; color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-xl) 0; }
@media (max-width: 900px) { .rp__body { grid-template-columns: 1fr; } .detail__grid { grid-template-columns: 1fr; } }
</style>
