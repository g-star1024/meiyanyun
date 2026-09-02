<script setup lang="ts">
/* ============================================================
 * A1-04 敏感词检测（红线页）
 * 路由：/ai/sensitive
 * 红线：实时拦截 M5/M4/M3；误报标注回流 A1-13 训练数据
 * ============================================================ */
import { ref, computed } from 'vue'
import CCard from '@/components/CCard.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'

const tab = ref<'hit' | 'dict'>('hit')

const tabOptions = [
  { label: '命中记录', value: 'hit' },
  { label: '词库管理', value: 'dict' },
]

const kpis = computed(() => [
  { label: '今日命中', icon: 'calendar', value: '347', tone: 'danger' as const, trend: '-12%', trendUp: false, trendGood: true },
  { label: '拦截率', icon: 'alert', value: '99.2%', tone: 'success' as const, trend: '+0.3pp', trendUp: true, trendGood: true },
  { label: '误报率', icon: 'alert', value: '1.2%', tone: 'teal' as const, trend: '-0.4pp', trendUp: false, trendGood: true },
  { label: '词库规模', icon: 'settings', value: '2,840 词', tone: 'purple' as const, trend: '+24', trendUp: true, trendGood: true },
])

// 命中记录
interface HitRow {
  id: number
  time: string
  channel: 'M5推送' | 'M4咨询' | 'M3关怀'
  word: string
  context: string
  action: '拦截' | '告警' | '放行'
}

const hitRows = ref<HitRow[]>([
  { id: 1, time: '2026-08-26 14:32:11', channel: 'M5推送', word: '最低价', context: '本次活动保证全网最低价，错过再等一年…', action: '拦截' },
  { id: 2, time: '2026-08-26 14:28:45', channel: 'M4咨询', word: '包治', context: '我们的项目可以包治您的皮肤问题，永不复发…', action: '拦截' },
  { id: 3, time: '2026-08-26 14:21:09', channel: 'M5推送', word: '国家级', context: '本机构荣获国家级认证，权威专家坐诊…', action: '告警' },
  { id: 4, time: '2026-08-26 14:15:30', channel: 'M3关怀', word: '治愈率', context: '根据历史数据，该疗程治愈率达 98%…', action: '拦截' },
  { id: 5, time: '2026-08-26 14:02:57', channel: 'M4咨询', word: '免费', context: '首次到店可免费体验全套护理方案…', action: '放行' },
  { id: 6, time: '2026-08-26 13:55:18', channel: 'M5推送', word: '100%有效', context: '使用本品 7 天 100% 有效，无效全额退款…', action: '拦截' },
  { id: 7, time: '2026-08-26 13:42:33', channel: 'M3关怀', word: '根除', context: '三个疗程根除您的所有面部困扰…', action: '拦截' },
  { id: 8, time: '2026-08-26 13:30:12', channel: 'M4咨询', word: '神药', context: '这款产品被誉为美容神药，很多老客户回购…', action: '告警' },
  { id: 9, time: '2026-08-26 13:18:04', channel: 'M5推送', word: '特效', context: '新品上线，特效抗衰老精华限时 5 折…', action: '告警' },
  { id: 10, time: '2026-08-26 13:05:48', channel: 'M3关怀', word: '无副作用', context: '本产品纯植物提取，无副作用，请您放心…', action: '拦截' },
])

const hitColumns = [
  { key: 'time', label: '时间', width: '170' },
  { key: 'channel', label: '渠道', width: '110' },
  { key: 'word', label: '命中词', width: '120' },
  { key: 'context', label: '上下文摘要' },
  { key: 'action', label: '处置', width: '100' },
  { key: 'op', label: '操作', width: '110', align: 'center' as const },
]

function channelPill(c: HitRow['channel']) {
  if (c === 'M5推送') return { status: 'primary' as const, text: 'M5 推送' }
  if (c === 'M4咨询') return { status: 'info' as const, text: 'M4 咨询' }
  return { status: 'success' as const, text: 'M3 关怀' }
}

function actionPill(a: HitRow['action']) {
  if (a === '拦截') return { status: 'danger' as const }
  if (a === '告警') return { status: 'warning' as const }
  return { status: 'default' as const }
}

function markFalsePositive(row: Record<string, any>) {
  const r = row as HitRow
  window.alert(`已将「${r.word}」标记为误报，将回流 A1-13 训练数据`)
}

// 词库管理
interface DictRow {
  id: number
  word: string
  level: '高危' | '中危' | '低危'
  synonyms: string
  hits: number
  status: '启用' | '停用'
}

const dictRows = ref<DictRow[]>([
  { id: 1, word: '100%有效', level: '高危', synonyms: '百分百有效 / 彻底有效', hits: 128, status: '启用' },
  { id: 2, word: '包治', level: '高危', synonyms: '包治好 / 根治', hits: 86, status: '启用' },
  { id: 3, word: '治愈率', level: '高危', synonyms: '治好比例 / 痊愈率', hits: 72, status: '启用' },
  { id: 4, word: '神药', level: '高危', synonyms: '灵药 / 神品', hits: 41, status: '启用' },
  { id: 5, word: '国家级', level: '中危', synonyms: '国家指定 / 国家认证', hits: 63, status: '启用' },
  { id: 6, word: '特效', level: '中危', synonyms: '强效 / 立竿见影', hits: 154, status: '启用' },
  { id: 7, word: '无副作用', level: '高危', synonyms: '零副作用 / 绝对安全', hits: 38, status: '启用' },
  { id: 8, word: '根除', level: '高危', synonyms: '彻底去除 / 杜绝复发', hits: 55, status: '启用' },
  { id: 9, word: '最低价', level: '中危', synonyms: '全网最低 / 底价', hits: 97, status: '启用' },
  { id: 10, word: '免费', level: '低危', synonyms: '0元 / 不要钱', hits: 212, status: '启用' },
])

const dictColumns = [
  { key: 'word', label: '敏感词', width: '140' },
  { key: 'level', label: '分级', width: '100' },
  { key: 'synonyms', label: '同义词' },
  { key: 'hits', label: '命中次数', width: '110', align: 'right' as const },
  { key: 'status', label: '状态', width: '100' },
  { key: 'op', label: '操作', width: '100', align: 'center' as const },
]

function levelPill(l: DictRow['level']) {
  if (l === '高危') return 'danger' as const
  if (l === '中危') return 'warning' as const
  return 'info' as const
}

function addWord() {
  window.alert('新增敏感词（演示）')
}

function editWord(row: Record<string, any>) {
  const r = row as DictRow
  window.alert(`编辑词「${r.word}」`)
}
</script>

<template>
  <div class="a1-sensitive">
    <div class="a1-sensitive__kpis">
      <CKpi v-for="k in kpis" :key="k.label" v-bind="k" />
    </div>

    <CCard padding="none">
      <template #header>
        <div class="card-head">
          <div class="card-head__title">
            <CIcon name="alert" :size="18" />
            <h3>敏感词实时检测</h3>
          </div>
          <div class="card-head__right">
            <CSegmented v-model="tab" :options="tabOptions" size="sm" />
            <CButton v-if="tab === 'dict'" size="sm" variant="primary" @click="addWord">
              <CIcon name="plus" :size="14" />
              新增词
            </CButton>
          </div>
        </div>
      </template>

      <!-- 命中记录 -->
      <div v-if="tab === 'hit'" class="tab-pane">
        <CTable :columns="hitColumns" :rows="hitRows" row-key="id">
          <template #col-channel="{ row }">
            <CStatusPill :status="channelPill(row.channel).status" dot>
              {{ channelPill(row.channel).text }}
            </CStatusPill>
          </template>
          <template #col-word="{ value }">
            <span class="word-hit">{{ value }}</span>
          </template>
          <template #col-context="{ value }">
            <span class="ctx">{{ value }}</span>
          </template>
          <template #col-action="{ row }">
            <CStatusPill :status="actionPill(row.action).status" dot>
              {{ row.action }}
            </CStatusPill>
          </template>
          <template #col-op="{ row }">
            <CButton size="sm" variant="text" @click="markFalsePositive(row)">误报标注</CButton>
          </template>
        </CTable>
      </div>

      <!-- 词库管理 -->
      <div v-else class="tab-pane">
        <CTable :columns="dictColumns" :rows="dictRows" row-key="id">
          <template #col-word="{ value }">
            <span class="word-dict">{{ value }}</span>
          </template>
          <template #col-level="{ value }">
            <CStatusPill :status="levelPill(value)" dot>{{ value }}</CStatusPill>
          </template>
          <template #col-hits="{ value }">
            <span class="hits-num">{{ value.toLocaleString() }}</span>
          </template>
          <template #col-status="{ value }">
            <CStatusPill :status="value === '启用' ? 'success' : 'disabled'" dot>
              {{ value }}
            </CStatusPill>
          </template>
          <template #col-op="{ row }">
            <CButton size="sm" variant="text" @click="editWord(row)">
              <CIcon name="edit" :size="13" />
              编辑
            </CButton>
          </template>
        </CTable>
      </div>
    </CCard>

    <!-- 红线提示条 -->
    <div class="redline-bar">
      <CIcon name="shield" :size="16" />
      <span class="redline-bar__text">
        A1-04 敏感词实时拦截已接入 M5 推送 / M4 咨询 / M3 关怀，误报标注回流 A1-13 训练数据
      </span>
    </div>
  </div>
</template>

<style scoped>
.a1-sensitive {
  display: flex;
  flex-direction: column;
  gap: var(--s-lg);
}
.a1-sensitive__kpis {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: var(--s-md);
}
@media (max-width: 1024px) {
  .a1-sensitive__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); }
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: var(--s-sm);
  flex-wrap: wrap;
}
.card-head__right { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.card-head__title {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  color: var(--c-danger-fg);
}
.card-head__title h3 {
  margin: 0;
  font-size: var(--t-md);
  font-weight: 600;
  color: var(--c-text);
}
.card-head__right {
  display: flex;
  align-items: center;
  gap: var(--s-md);
}

.tab-pane {
  padding: 0;
}

.word-hit {
  font-weight: 600;
  color: var(--c-danger-fg);
  background: var(--c-danger-bg);
  padding: 2px 8px;
  border-radius: var(--r-sm);
  font-size: var(--t-xs);
}
.word-dict {
  font-weight: 600;
  color: var(--c-text);
}
.ctx {
  color: var(--c-text-2);
  line-height: 1.5;
}
.hits-num {
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
  font-weight: 500;
}

.redline-bar {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md);
  background: var(--c-danger-bg);
  border: 1px solid var(--c-danger-fg);
  border-radius: var(--r-md);
  color: var(--c-danger-fg);
}
.redline-bar__text {
  font-size: var(--t-xs);
  line-height: 1.5;
  font-weight: 500;
}
</style>
