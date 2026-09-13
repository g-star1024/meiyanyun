<script setup lang="ts">
/* A1-10 内容生成 /ai/content — 渠道真实出网 + 合规过滤 + 站内下发（B46 卡3 去 mock） */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CInput from '@/components/CInput.vue'
import CIcon from '@/components/CIcon.vue'
import CPagination from '@/components/CPagination.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { fmtDateTime } from '@/utils/datetime'
import {
  generateContent, listContentRecords, getContentStats, deployContent,
  type ContentView, type ContentStats,
} from '@/api/ai'

const toast = useToast()

const typeTab = ref('wechat')
const typeOptions = [
  { label: '公众号文案', value: 'wechat' },
  { label: '海报文案', value: 'poster' },
  { label: '短信文案', value: 'sms' },
]
const typeLabel = (v: string) => typeOptions.find(o => o.value === v)?.label ?? ''
const topicInput = ref<InstanceType<typeof CInput> | null>(null)

const stats = ref<ContentStats>({
  todayGenerated: 0, totalGenerated: 0, todayDeployed: 0,
  totalDeployed: 0, todayBlocked: 0, adoptRatePct: 0,
})

const kpis = computed(() => [
  { label: '今日生成', icon: 'calendar', value: String(stats.value.todayGenerated), tone: 'purple' as const },
  { label: '采纳率', icon: 'trend-up', value: `${stats.value.adoptRatePct}%`, tone: 'brand' as const },
  { label: '今日合规拦截', icon: 'alert', value: String(stats.value.todayBlocked), tone: 'danger' as const },
  { label: '今日下发', icon: 'marketing', value: String(stats.value.todayDeployed), tone: 'teal' as const },
])

const historyCols = [
  { key: 'title', label: '标题' }, { key: 'type', label: '类型', width: '100' },
  { key: 'time', label: '生成时间', width: '160' },
  { key: 'compliance', label: '状态', width: '100' },
  { key: 'ops', label: '操作', width: '140' },
]

const history = ref<ContentView[]>([])
const historyLoading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

const channelPlaceholders: Record<string, string> = {
  wechat: '如：秋季护肤新品推广（公众号长文）',
  poster: '如：会员日倒计时（海报主标题/短句）',
  sms: '如：疗程升级推荐（70 字内短信）',
}
const topicPlaceholder = computed(() => channelPlaceholders[typeTab.value])

// 当前预览/编辑中的记录与文案
const current = ref<ContentView | null>(null)
const generatedContent = ref('')
const generating = ref(false)
const deploying = ref(false)
const topic = ref('')

const previewStatus = computed(() => {
  if (!current.value) return null
  return current.value.status === 'DEPLOYED'
    ? { status: 'success' as const, text: '已下发' }
    : { status: 'primary' as const, text: '已生成待下发' }
})
const deployed = computed(() => current.value?.status === 'DEPLOYED')

function toRow(r: ContentView) {
  return {
    id: r.recordId,
    title: r.title,
    type: typeLabel(r.channel),
    channel: r.channel,
    time: fmtDateTime(r.createdAt),
    compliance: r.status === 'DEPLOYED' ? '已下发' : '待下发',
    raw: r,
  }
}
const rows = computed(() => history.value.map(toRow))

function compliancePill(c: string) {
  return c === '已下发' ? 'success' : 'primary'
}

async function loadStats() {
  try {
    stats.value = await getContentStats()
  } catch (e) {
    toast.error('统计加载失败：' + errMsg(e))
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const res = await listContentRecords({
      channel: typeTab.value,
      page: page.value - 1,
      size: pageSize.value,
    })
    history.value = res.content
    total.value = res.totalElements
  } catch (e) {
    toast.error('生成历史加载失败：' + errMsg(e))
  } finally {
    historyLoading.value = false
  }
}

function switchChannel(v: string) {
  typeTab.value = v
  page.value = 1
  loadHistory()
}

function changePage(n: number) {
  page.value = n
  loadHistory()
}

function focusTopic() {
  topicInput.value?.focus?.()
}

function preview(r: ContentView) {
  current.value = r
  generatedContent.value = r.content
  topic.value = r.topic
  typeTab.value = r.channel
}

async function generate() {
  const t = topic.value.trim()
  if (!t) {
    toast.warning('请输入主题')
    focusTopic()
    return
  }
  generating.value = true
  try {
    const v = await generateContent({ channel: typeTab.value, topic: t })
    current.value = v
    generatedContent.value = v.content
    toast.success('文案已生成，可在右侧预览后下发')
    await Promise.all([loadStats(), loadHistory()])
  } catch (e) {
    toast.error('生成失败：' + errMsg(e))
  } finally {
    generating.value = false
  }
}

async function deploy() {
  if (!current.value) {
    toast.warning('请先生成或选择一条文案')
    return
  }
  if (current.value.status === 'DEPLOYED') {
    toast.warning('该内容已下发，无需重复操作')
    return
  }
  deploying.value = true
  try {
    const res = await deployContent(current.value.recordId)
    if (!res.changed) {
      toast.warning('该内容已下发，无需重复操作')
    } else {
      toast.success('内容已登记下发至 M5 营销中心')
    }
    current.value = { ...current.value, status: res.status }
    await Promise.all([loadStats(), loadHistory()])
  } catch (e) {
    toast.error('下发失败：' + errMsg(e))
  } finally {
    deploying.value = false
  }
}

onMounted(() => {
  loadStats()
  loadHistory()
})
</script>

<template>
  <div class="a1-content">
    <div class="kpis"><CKpi v-for="k in kpis" :key="k.label" v-bind="k" /></div>
    <div class="bar"><CSegmented :model-value="typeTab" :options="typeOptions" @update:model-value="switchChannel" /><CButton variant="primary" @click="focusTopic"><CIcon name="plus" :size="14" />新建生成</CButton></div>
    <div class="layout">
      <CCard padding="lg" class="layout__list">
        <template #header><h3>生成历史 · {{ typeLabel(typeTab) }}（{{ total }}）</h3></template>
        <CTable :columns="historyCols" :rows="rows" row-key="id" :empty-text="historyLoading ? '加载中…' : `暂无${typeLabel(typeTab)}记录`">
          <template #col-compliance="{ value }"><CStatusPill :status="compliancePill(value)" dot>{{ value }}</CStatusPill></template>
          <template #col-ops="{ row }"><CButton size="sm" variant="text" @click="preview(row.raw)">预览</CButton><CButton size="sm" variant="text" @click="preview(row.raw); deploy()" :disabled="row.raw.status === 'DEPLOYED'">下发</CButton></template>
        </CTable>
        <CPagination :page="page" :page-size="pageSize" :total="total" @update:page="changePage" />
      </CCard>
      <CCard padding="lg" class="layout__preview">
        <template #header>
          <div class="ph">
            <h3>预览与编辑 · {{ typeLabel(typeTab) }}</h3>
            <CStatusPill v-if="previewStatus" :status="previewStatus.status" dot>{{ previewStatus.text }}</CStatusPill>
          </div>
        </template>
        <div class="topic">
          <label>主题</label>
          <div class="topic__row">
            <CInput ref="topicInput" v-model="topic" :placeholder="topicPlaceholder" @keyup.enter="generate" />
            <CButton variant="primary" :disabled="generating" @click="generate"><CIcon name="refresh" :size="14" />{{ generating ? '生成中…（长文案约需 1-3 分钟）' : '生成文案' }}</CButton>
          </div>
        </div>
        <textarea v-model="generatedContent" class="editor" :rows="10" :placeholder="`输入主题后点击「生成文案」，将由 AI 生成可直接使用的${typeLabel(typeTab)}`" />
        <div class="ops">
          <span class="hint">所有生成内容经 A1-04 敏感词过滤，命中违禁词将自动拦截</span>
          <CButton variant="primary" :disabled="generating || deploying || deployed" @click="deploy">
            <CIcon name="marketing" :size="14" />{{ deployed ? '已下发' : (deploying ? '下发中…' : '一键下发 M5') }}
          </CButton>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.a1-content { display: flex; flex-direction: column; gap: var(--s-lg); }
.kpis { display: flex; gap: var(--s-md); }
.bar { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); flex-wrap: nowrap; overflow-x: auto; }
.bar > :last-child { flex-shrink: 0; }
.layout { display: grid; grid-template-columns: 1fr 420px; gap: var(--s-lg); }
@media (max-width: 1100px) { .layout { grid-template-columns: 1fr; } }
.ph { display: flex; align-items: center; justify-content: space-between; width: 100%; }
h3 { margin: 0; font-size: var(--t-md); font-weight: 600; }
.topic { margin-bottom: var(--s-md); }
.topic label { display: block; font-size: 13px; color: var(--c-text); margin-bottom: 6px; }
.topic__row { display: flex; gap: var(--s-sm); align-items: center; }
.topic__row > :first-child { flex: 1; min-width: 0; }
.topic__row .cbtn { flex-shrink: 0; white-space: nowrap; }
.editor { width: 100%; border: 1px solid var(--c-border); border-radius: var(--r-md); padding: var(--s-sm); font-size: var(--t-sm); line-height: 1.7; color: var(--c-text); resize: vertical; font-family: inherit; }
.editor:focus { outline: none; border-color: var(--c-brand); }
.ops { display: flex; justify-content: space-between; align-items: center; margin-top: var(--s-md); }
.hint { font-size: var(--t-xs); color: var(--c-text-3); }
</style>
