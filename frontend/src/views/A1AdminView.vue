<script setup lang="ts">
/* A1-21/22/23/24 平台管理 /ai/admin — 权限/计费/模板/配置 */
import { ref, reactive } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'

const tab = ref('perm')
const tabOptions = [
  { label: '权限管理', value: 'perm' },
  { label: '用量计费', value: 'bill' },
  { label: '模板市场', value: 'tpl' },
  { label: '全局配置', value: 'cfg' },
]
const kpis = [
  { label: 'AI 功能数', icon: 'dashboard', value: '12', tone: 'purple' as const },
  { label: '本月用量', icon: 'dashboard', value: '1.2M次', tone: 'brand' as const },
  { label: '预估费用', icon: 'finance', value: '¥3,280', tone: 'orange' as const },
  { label: '启用模板', icon: 'settings', value: '8', tone: 'teal' as const },
]

// 权限矩阵
const roles = ['超级管理员', '区域经理', '店长', '咨询师', '市场专员']
const features = [
  { key: 'profile', name: '客户画像' },
  { key: 'churn', name: '流失预警' },
  { key: 'scripts', name: '智能话术' },
  { key: 'scheduling', name: '智能排班' },
  { key: 'content', name: '内容生成' },
  { key: 'govern', name: '审批评估' },
]
const matrix = reactive<Record<string, Record<string, boolean>>>({
  '超级管理员': { profile: true, churn: true, scripts: true, scheduling: true, content: true, govern: true },
  '区域经理': { profile: true, churn: true, scripts: false, scheduling: true, content: true, govern: true },
  '店长': { profile: true, churn: true, scripts: true, scheduling: true, content: false, govern: false },
  '咨询师': { profile: true, churn: false, scripts: true, scheduling: false, content: false, govern: false },
  '市场专员': { profile: false, churn: true, scripts: false, scheduling: false, content: true, govern: false },
})

// 计费
const billCols = [
  { key: 'feature', label: '功能' }, { key: 'calls', label: '本月调用', align: 'right' as const },
  { key: 'tokens', label: 'Token 消耗', align: 'right' as const },
  { key: 'cost', label: '预估费用', align: 'right' as const },
  { key: 'trend', label: '环比' },
]
const bills = [
  { id: 1, feature: '智能话术', calls: '428,000', tokens: '2.1M', cost: '¥856', trend: '+12%' },
  { id: 2, feature: '客户画像', calls: '312,000', tokens: '1.5M', cost: '¥624', trend: '+8%' },
  { id: 3, feature: 'AI 客服', calls: '186,000', tokens: '4.2M', cost: '¥1,260', trend: '+24%' },
  { id: 4, feature: '内容生成', calls: '42,000', tokens: '3.8M', cost: '¥760', trend: '-5%' },
  { id: 5, feature: '流失预警', calls: '156,000', tokens: '0.8M', cost: '¥320', trend: '+3%' },
  { id: 6, feature: '复购预测', calls: '98,000', tokens: '0.5M', cost: '¥200', trend: '+15%' },
]

// 模板
const templates = [
  { id: 1, name: '新客破冰话术包', desc: '20 条标准破冰话术，适配首次到店客户', uses: 128, tag: '话术' },
  { id: 2, name: '秋季营销文案模板', desc: '朋友圈/企微/短信三端文案，含合规词过滤', uses: 86, tag: '营销' },
  { id: 3, name: '流失召回 SOP', desc: '高/中/低风险客户分层召回策略与话术', uses: 64, tag: '运营' },
  { id: 4, name: '排班优化模型配置', desc: '基于客流预测的人力排班推荐参数模板', uses: 32, tag: '排班' },
]

// 全局配置
const config = reactive({
  defaultModel: 'gpt-4',
  grayScale: '20',
  retention: '12',
  autoAudit: true,
  sensitiveCheck: true,
  explainability: true,
})
const modelOptions = [
  { label: 'GPT-4 (推荐)', value: 'gpt-4' },
  { label: 'GPT-3.5 Turbo', value: 'gpt-3.5' },
  { label: '自研模型 v2', value: 'custom-v2' },
]
const retentionOptions = [
  { label: '6 个月', value: '6' }, { label: '12 个月', value: '12' },
  { label: '24 个月', value: '24' }, { label: '36 个月', value: '36' },
]

function saveCfg() {
  window.alert('配置已保存。模型保留期配置已满足训练集「授权来源/去标识/保留期」三要素要求。')
}
</script>

<template>
  <div class="a1-admin">
    <div class="kpis"><CKpi v-for="k in kpis" :key="k.label" v-bind="k" /></div>
    <CCard padding="lg">
      <CSegmented v-model="tab" :options="tabOptions" />

      <!-- 权限管理 -->
      <div v-if="tab === 'perm'" class="mt">
        <div class="perm-table">
          <div class="perm-row perm-head">
            <div class="perm-cell perm-cell--role">角色</div>
            <div v-for="f in features" :key="f.key" class="perm-cell">{{ f.name }}</div>
          </div>
          <div v-for="role in roles" :key="role" class="perm-row">
            <div class="perm-cell perm-cell--role">{{ role }}</div>
            <div v-for="f in features" :key="f.key" class="perm-cell perm-cell--check">
              <input type="checkbox" v-model="matrix[role][f.key]" />
            </div>
          </div>
        </div>
        <p class="hint">AI 功能权限受 T1 RBAC 统一管控，权限变更写入 T1-04 审计日志。</p>
      </div>

      <!-- 用量计费 -->
      <div v-else-if="tab === 'bill'" class="mt">
        <CTable :columns="billCols" :rows="bills" row-key="id" stripe>
          <template #col-cost="{ value }"><strong>¥{{ value }}</strong></template>
          <template #col-trend="{ value }"><span :class="{ up: value.startsWith('+') }">{{ value }}</span></template>
        </CTable>
      </div>

      <!-- 模板市场 -->
      <div v-else-if="tab === 'tpl'" class="mt">
        <div class="tpl-grid">
          <div v-for="t in templates" :key="t.id" class="tpl-card">
            <div class="tpl-card__tag"><CStatusPill status="info">{{ t.tag }}</CStatusPill></div>
            <h4>{{ t.name }}</h4>
            <p>{{ t.desc }}</p>
            <div class="tpl-card__foot"><span>{{ t.uses }} 次使用</span><CButton size="sm" variant="primary">选用</CButton></div>
          </div>
        </div>
      </div>

      <!-- 全局配置 -->
      <div v-else class="mt cfg">
        <div class="cfg__row">
          <label>默认模型</label>
          <CSelect v-model="config.defaultModel" :options="modelOptions" width="240px" />
        </div>
        <div class="cfg__row">
          <label>灰度发布比例</label>
          <div class="cfg__inline"><CInput v-model="config.grayScale" width="100px" type="number" /><span>%</span></div>
        </div>
        <div class="cfg__row">
          <label>训练数据保留期</label>
          <CSelect v-model="config.retention" :options="retentionOptions" width="240px" />
        </div>
        <div class="cfg__row cfg__row--toggle">
          <label>敏感词实时拦截（A1-04）</label>
          <button class="toggle" :class="{ on: config.sensitiveCheck }" @click="config.sensitiveCheck = !config.sensitiveCheck"><span /></button>
        </div>
        <div class="cfg__row cfg__row--toggle">
          <label>AI 决策可解释性输出</label>
          <button class="toggle" :class="{ on: config.explainability }" @click="config.explainability = !config.explainability"><span /></button>
        </div>
        <div class="cfg__row cfg__row--toggle">
          <label>操作审计自动记录（T1-04）</label>
          <button class="toggle" :class="{ on: config.autoAudit }" @click="config.autoAudit = !config.autoAudit"><span /></button>
        </div>
        <div class="cfg__foot"><CButton variant="primary" @click="saveCfg">保存配置</CButton></div>
        <p class="hint">红线：模型保留期配置须满足训练集「授权来源/去标识/保留期」三要素要求。</p>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.a1-admin { display: flex; flex-direction: column; gap: var(--s-lg); }
.kpis { display: flex; gap: var(--s-md); }
.mt { margin-top: var(--s-md); }
.hint { font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-md); }

.perm-table { border: 1px solid var(--c-border-light); border-radius: var(--r-lg); overflow: hidden; }
.perm-row { display: grid; grid-template-columns: 140px repeat(6, 1fr); }
.perm-row + .perm-row { border-top: 1px solid var(--c-border-light); }
.perm-head { background: var(--c-bg-page); font-weight: 600; font-size: var(--t-sm); }
.perm-cell { padding: var(--s-sm) var(--s-md); display: flex; align-items: center; font-size: var(--t-sm); color: var(--c-text-2); justify-content: center; }
.perm-cell--role { justify-content: flex-start; color: var(--c-text); font-weight: 500; }
.perm-cell--check input { width: 16px; height: 16px; accent-color: var(--c-brand); cursor: pointer; }

.tpl-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.tpl-card { padding: var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-lg); display: flex; flex-direction: column; gap: var(--s-sm); }
.tpl-card h4 { margin: 0; font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.tpl-card p { margin: 0; font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.5; flex: 1; }
.tpl-card__foot { display: flex; justify-content: space-between; align-items: center; font-size: 11px; color: var(--c-text-3); }

.cfg { max-width: 560px; }
.cfg__row { display: flex; align-items: center; justify-content: space-between; padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.cfg__row label { font-size: var(--t-sm); color: var(--c-text); }
.cfg__inline { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); color: var(--c-text-2); }
.cfg__foot { margin-top: var(--s-lg); text-align: right; }
.toggle { width: 40px; height: 22px; border-radius: 11px; background: var(--c-border); border: none; position: relative; cursor: pointer; transition: background .2s; padding: 0; }
.toggle span { position: absolute; top: 2px; left: 2px; width: 18px; height: 18px; border-radius: 50%; background: #fff; transition: left .2s; }
.toggle.on { background: var(--c-brand); }
.toggle.on span { left: 20px; }
.up { color: var(--c-success-fg); }
</style>
