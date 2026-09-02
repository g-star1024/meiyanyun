<script setup lang="ts">
/* ============================================================
 * 客户域设置 /m3-settings（M3-18）
 * 脱敏规则、等级来源、标签自动化、隐私合规、跟进规则。
 * ============================================================ */
import { onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import { useM3SettingsStore } from '@/stores/m3settings'

const store = useM3SettingsStore()
onMounted(() => {})

const saved = ref(false)
function doSave() {
  if (store.save()) {
    saved.value = true
    setTimeout(() => (saved.value = false), 2500)
  }
}

function fmt(iso: string) {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const kpis = [
  { label: '脱敏规则', icon: 'settings', value: String(Number(store.settings.maskPhone) + Number(store.settings.maskIdCard) + Number(store.settings.maskPhoneInExport)), tone: 'brand' as const },
  { label: '自动标签', icon: 'customer', value: String(Number(store.settings.autoTagDormant) + Number(store.settings.autoTagHighValue) + Number(store.settings.autoTagChurnRisk)), tone: 'teal' as const },
  { label: '数据保留', icon: 'settings', value: `${store.settings.dataRetentionMonths}月`, tone: 'text' as const },
  { label: 'EMR 锁定', icon: 'settings', value: `${store.settings.emrLockDays}天`, tone: 'warning' as const },
]

const levelOptions = [
  { value: 'AUTO', label: '自动升降级' },
  { value: 'MANUAL', label: '仅手动调整' },
  { value: 'HYBRID', label: '自动+手动复核' },
]
</script>

<template>
  <div class="ms">
    <div class="ms__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div v-if="saved" class="ms__toast"><CIcon name="check" :size="16" /> 设置已保存</div>

    <div class="ms__grid">
      <!-- 脱敏与隐私 -->
      <CCard class="ms__card" padding="lg">
        <template #header>
          <h3 class="card-title"><CIcon name="shield" :size="16" /> 脱敏与隐私合规</h3>
        </template>
        <div class="setting">
          <label class="switch-row">
            <span><strong>手机号脱敏展示</strong><small>列表与详情中手机号显示为 138****2046</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.maskPhone" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
          <label class="switch-row">
            <span><strong>身份证号脱敏</strong><small>身份证号仅显示后 4 位</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.maskIdCard" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
          <label class="switch-row">
            <span><strong>导出强制脱敏</strong><small>任何导出任务手机号/身份证自动脱敏</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.maskPhoneInExport" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
          <label class="switch-row">
            <span><strong>明文查看需审批</strong><small>解密手机号需主管审批，操作留痕</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.decryptRequiresApproval" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
          <div class="input-row">
            <span>解密后明文保留时长（小时）</span>
            <CInput :model-value="String(store.settings.decryptRetentionHours)" :disabled="!store.canEdit" style="max-width:120px" @update:model-value="store.settings.decryptRetentionHours = Number($event) || 0; store.markDirty()" />
          </div>
        </div>
      </CCard>

      <!-- 等级与积分 -->
      <CCard class="ms__card" padding="lg">
        <template #header>
          <h3 class="card-title"><CIcon name="sign" :size="16" /> 等级与积分规则</h3>
        </template>
        <div class="setting">
          <div class="input-row">
            <span>等级升降级方式</span>
            <CSelect v-model="store.settings.levelSource" :options="levelOptions" :disabled="!store.canEdit" @change="store.markDirty()" />
          </div>
          <div class="input-row">
            <span>降级保护期（月）</span>
            <CInput :model-value="String(store.settings.downgradeProtectionMonths)" :disabled="!store.canEdit" style="max-width:120px" @update:model-value="store.settings.downgradeProtectionMonths = Number($event) || 0; store.markDirty()" />
          </div>
          <div class="input-row">
            <span>消费积分倍率</span>
            <CInput :model-value="String(store.settings.pointsMultiplier)" :disabled="!store.canEdit" style="max-width:120px" @update:model-value="store.settings.pointsMultiplier = Number($event) || 1; store.markDirty()" />
          </div>
          <div class="input-row">
            <span>客户数据保留（月）</span>
            <CInput :model-value="String(store.settings.dataRetentionMonths)" :disabled="!store.canEdit" style="max-width:120px" @update:model-value="store.settings.dataRetentionMonths = Number($event) || 0; store.markDirty()" />
          </div>
          <label class="switch-row">
            <span><strong>跨店共享客户档案</strong><small>连锁门店间可查看同品牌客户档案</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.allowCrossStoreShare" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
        </div>
      </CCard>

      <!-- 自动标签 -->
      <CCard class="ms__card" padding="lg">
        <template #header>
          <h3 class="card-title"><CIcon name="profile" :size="16" /> 自动化标签规则</h3>
        </template>
        <div class="setting">
          <label class="switch-row">
            <span><strong>沉睡客户自动打标</strong><small>超过阈值天数未到店自动标记沉睡</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.autoTagDormant" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
          <div class="input-row">
            <span>沉睡阈值（天）</span>
            <CInput :model-value="String(store.settings.dormantDays)" :disabled="!store.canEdit" style="max-width:120px" @update:model-value="store.settings.dormantDays = Number($event) || 0; store.markDirty()" />
          </div>
          <label class="switch-row">
            <span><strong>高价值客户自动打标</strong><small>累计消费超过阈值自动标记高价值</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.autoTagHighValue" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
          <div class="input-row">
            <span>高价值阈值（元）</span>
            <CInput :model-value="String(store.settings.highValueThreshold)" :disabled="!store.canEdit" style="max-width:120px" @update:model-value="store.settings.highValueThreshold = Number($event) || 0; store.markDirty()" />
          </div>
          <label class="switch-row">
            <span><strong>流失风险自动预警</strong><small>结合 RFM 模型自动识别流失风险客户</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.autoTagChurnRisk" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
        </div>
      </CCard>

      <!-- 跟进与合规 -->
      <CCard class="ms__card" padding="lg">
        <template #header>
          <h3 class="card-title"><CIcon name="check-square" :size="16" /> 跟进任务与合规</h3>
        </template>
        <div class="setting">
          <label class="switch-row">
            <span><strong>到店后自动创建跟进任务</strong><small>客户消费后 24 小时自动生成回访任务</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.autoCreateFollowTask" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
          <label class="switch-row">
            <span><strong>投诉自动创建跟进任务</strong><small>投诉登记后自动指派责任人跟进</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.complaintAutoTask" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
          <label class="switch-row">
            <span><strong>NPS 差评自动跟进</strong><small>0-6 分贬损者评价自动创建挽回任务</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.npsDetractorAutoTask" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
          <label class="switch-row">
            <span><strong>对比照水印追溯</strong><small>治疗前后照片自动加水印（机构·时间·操作人）</small></span>
            <label class="switch"><input type="checkbox" v-model="store.settings.enableWatermark" :disabled="!store.canEdit" @change="store.markDirty()" /><span class="slider" /></label>
          </label>
          <div class="input-row">
            <span>EMR 记录锁定（天）</span>
            <CInput :model-value="String(store.settings.emrLockDays)" :disabled="!store.canEdit" style="max-width:120px" @update:model-value="store.settings.emrLockDays = Number($event) || 0; store.markDirty()" />
          </div>
        </div>
      </CCard>
    </div>

    <!-- 变更记录 -->
    <CCard class="ms__log" padding="lg">
      <template #header><h3 class="card-title">最近变更记录（审计追踪）</h3></template>
      <div class="log-list">
        <div v-for="l in store.logs" :key="l.id" class="log-row">
          <CIcon name="check" :size="14" class="log-icon" />
          <span class="log-action">{{ l.action }}</span>
          <span class="log-by">{{ l.by }}</span>
          <span class="log-at">{{ fmt(l.at) }}</span>
        </div>
      </div>
    </CCard>

    <!-- 底部操作条 -->
    <div class="ms__footer">
      <span class="ms__footer-hint"><CIcon name="shield" :size="14" />规则修改次日生效 · 变更写入审计日志</span>
      <div class="ms__footer-actions">
        <CButton variant="ghost" size="sm" @click="store.resetDefault()">重置默认</CButton>
        <CButton variant="primary" size="sm" :disabled="!store.canEdit || !store.dirty" @click="doSave">
          <CIcon name="check" :size="14" />保存设置
        </CButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ms { display: flex; flex-direction: column; gap: var(--s-lg); }
.ms__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ms__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.ms__toast { display: flex; align-items: center; gap: var(--s-xs); padding: var(--s-sm) var(--s-md); background: var(--c-success-bg); color: var(--c-success-fg); border-radius: var(--r-md); font-size: var(--t-sm); }
.ms__footer { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); flex-wrap: wrap; background: var(--c-surface); border: 1px solid var(--c-border); border-radius: var(--r-xl); box-shadow: var(--shadow-card); padding: var(--s-md) var(--s-lg); position: sticky; bottom: var(--s-md); }
.ms__footer-hint { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); }
.ms__footer-actions { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }

.card-title { font-size: var(--t-md); font-weight: 700; margin: 0; display: flex; align-items: center; gap: var(--s-xs); }
.ms__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-lg); align-items: start; }

.setting { display: flex; flex-direction: column; gap: var(--s-md); }
.switch-row { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.switch-row:last-child { border-bottom: none; }
.switch-row strong { display: block; font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.switch-row small { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.input-row { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); padding: var(--s-xs) 0; font-size: var(--t-sm); color: var(--c-text-2); }

.switch { position: relative; display: inline-block; width: 42px; height: 22px; flex-shrink: 0; }
.switch input { opacity: 0; width: 0; height: 0; }
.slider { position: absolute; cursor: pointer; inset: 0; background: var(--c-border); border-radius: 22px; transition: .2s; }
.slider::before { content: ''; position: absolute; height: 18px; width: 18px; left: 2px; top: 2px; background: #fff; border-radius: 50%; transition: .2s; }
.switch input:checked + .slider { background: var(--c-brand); }
.switch input:checked + .slider::before { transform: translateX(20px); }
.switch input:disabled + .slider { opacity: .5; cursor: not-allowed; }

.ms__log { }
.log-list { display: flex; flex-direction: column; }
.log-row { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); }
.log-row:last-child { border-bottom: none; }
.log-icon { color: var(--c-success-fg); }
.log-action { flex: 1; color: var(--c-text); }
.log-by { color: var(--c-text-3); font-size: var(--t-xs); }
.log-at { color: var(--c-text-4); font-size: var(--t-xs); min-width: 120px; text-align: right; }

@media (max-width: 1024px) {
  .ms__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .ms__grid { grid-template-columns: 1fr; }
}
</style>
