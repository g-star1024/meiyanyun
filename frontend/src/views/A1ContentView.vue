<script setup lang="ts">
/* A1-10 内容生成 /ai/content — 合规过滤 + 下发 M5 */
import { computed, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CInput from '@/components/CInput.vue'
import CIcon from '@/components/CIcon.vue'

const typeTab = ref('wechat')
const typeOptions = [
  { label: '公众号文案', value: 'wechat' },
  { label: '海报文案', value: 'poster' },
  { label: '短信文案', value: 'sms' },
]
const typeLabel = (v: string) => typeOptions.find(o => o.value === v)?.label ?? ''
const topicInput = ref<InstanceType<typeof CInput> | null>(null)

const kpis = [
  { label: '今日生成', icon: 'calendar', value: '128', tone: 'purple' as const },
  { label: '采纳率', icon: 'trend-up', value: '54%', tone: 'brand' as const },
  { label: '合规拦截', icon: 'alert', value: '8', tone: 'danger' as const },
  { label: '下发 M5', icon: 'marketing', value: '46', tone: 'teal' as const },
]

const historyCols = [
  { key: 'title', label: '标题' }, { key: 'type', label: '类型', width: '90' },
  { key: 'time', label: '生成时间', width: '150' },
  { key: 'compliance', label: '合规', width: '100' },
  { key: 'ops', label: '操作', width: '140' },
]
const history = ref([
  { id: 1, title: '秋季护肤新品推广', type: '公众号文案', channel: 'wechat', time: '2026-08-26 10:30', compliance: '通过' },
  { id: 2, title: '会员日倒计时海报', type: '海报文案', channel: 'poster', time: '2026-08-26 09:15', compliance: '通过' },
  { id: 3, title: '限时秒杀提醒', type: '短信文案', channel: 'sms', time: '2026-08-25 18:40', compliance: '拦截' },
  { id: 4, title: '老客专属福利', type: '公众号文案', channel: 'wechat', time: '2026-08-25 16:20', compliance: '通过' },
  { id: 5, title: '新店开业邀请函', type: '海报文案', channel: 'poster', time: '2026-08-25 14:00', compliance: '通过' },
  { id: 6, title: '疗程升级推荐', type: '短信文案', channel: 'sms', time: '2026-08-25 11:30', compliance: '通过' },
  { id: 7, title: '换季敏感肌护理指南', type: '公众号文案', channel: 'wechat', time: '2026-08-24 15:10', compliance: '通过' },
])
const filteredHistory = computed(() => history.value.filter(h => h.channel === typeTab.value))

const generatedContent = ref('秋季护肤新品上市！针对干燥、敏感、暗沉三大肌肤问题，精选天然植物精华，深层滋养修复。现在预约体验，新客专享 8 折优惠，老客推荐更有好礼相送。')
const generating = ref(false)
const topic = ref('')

const channelTemplates: Record<string, (t: string) => string> = {
  wechat: t => `【${t}】\n\n亲爱的会员，秋季护肤正当时！我们为您精选了多款深层滋养项目，针对换季干燥、敏感等问题提供专业解决方案。\n\n预约即享：\n✓ 新客 8 折体验\n✓ 老客推荐赠高端面膜一盒\n✓ 疗程升级立减 ¥200\n\n名额有限，点击立即预约。`,
  poster: t => `${t}\n━━━━━━━━━━\n秋季焕新 · 限时礼遇\n新客到店 8 折｜老客带新赠好礼\n名额有限 先约先得\n📞 点击预约 · 到店体验`,
  sms: t => `【美研云】${t}，秋季护肤新客8折、老客带新赠面膜，退订回T`,
}
const channelPlaceholders: Record<string, string> = {
  wechat: '如：秋季护肤新品推广（公众号长文）',
  poster: '如：会员日倒计时（海报主标题/短句）',
  sms: '如：疗程升级推荐（70 字内短信）',
}
const topicPlaceholder = computed(() => channelPlaceholders[typeTab.value])

function focusTopic() {
  topicInput.value?.focus?.()
}

function generate() {
  if (!topic.value.trim()) { window.alert('请输入主题'); focusTopic(); return }
  generating.value = true
  setTimeout(() => {
    generatedContent.value = channelTemplates[typeTab.value](topic.value.trim())
    const now = new Date()
    const pad = (n: number) => String(n).padStart(2, '0')
    const time = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`
    history.value.unshift({ id: Date.now(), title: topic.value.trim(), type: typeLabel(typeTab.value), channel: typeTab.value, time, compliance: '通过' })
    generating.value = false
  }, 800)
}

function deploy() {
  if (generatedContent.value.includes('秒杀') || generatedContent.value.includes('最低价')) {
    window.alert('A1-04 敏感词检测未通过：命中"秒杀/最低价"等违禁词，请修改后重试。')
    return
  }
  window.alert('内容已下发至 M5 营销中心，可在 M5-01 活动 / M5-13 素材中使用。')
}

function compliancePill(c: string) {
  return c === '通过' ? 'success' : 'danger'
}
</script>

<template>
  <div class="a1-content">
    <div class="kpis"><CKpi v-for="k in kpis" :key="k.label" v-bind="k" /></div>
    <div class="bar"><CSegmented v-model="typeTab" :options="typeOptions" /><CButton variant="primary" @click="focusTopic"><CIcon name="plus" :size="14" />新建生成</CButton></div>
    <div class="layout">
      <CCard padding="lg" class="layout__list">
        <template #header><h3>生成历史 · {{ typeLabel(typeTab) }}（{{ filteredHistory.length }}）</h3></template>
        <CTable :columns="historyCols" :rows="filteredHistory" row-key="id" :empty-text="`暂无${typeLabel(typeTab)}记录`">
          <template #col-compliance="{ value }"><CStatusPill :status="compliancePill(value)" dot>{{ value }}</CStatusPill></template>
          <template #col-ops><CButton size="sm" variant="text">预览</CButton><CButton size="sm" variant="text">下发</CButton></template>
        </CTable>
      </CCard>
      <CCard padding="lg" class="layout__preview">
        <template #header>
          <div class="ph"><h3>预览与编辑 · {{ typeLabel(typeTab) }}</h3><CStatusPill status="success" dot>A1-04 合规通过</CStatusPill></div>
        </template>
        <div class="topic">
          <label>主题</label>
          <div class="topic__row">
            <CInput ref="topicInput" v-model="topic" :placeholder="topicPlaceholder" @keyup.enter="generate" />
            <CButton variant="primary" :disabled="generating" @click="generate"><CIcon name="refresh" :size="14" />{{ generating ? '生成中…' : '生成文案' }}</CButton>
          </div>
        </div>
        <textarea class="editor" v-model="generatedContent" :rows="10" />
        <div class="ops">
          <span class="hint">所有生成内容经 A1-04 敏感词过滤，命中违禁词将自动拦截</span>
          <CButton variant="primary" :disabled="generating" @click="deploy">一键下发 M5</CButton>
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
