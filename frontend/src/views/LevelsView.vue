<script setup lang="ts">
/* ============================================================
 * 会员等级体系 /m3-levels（M3-04）
 * Desktop：4 KPI + 5 张等级卡片横排 + 升降级规则 + 审计追踪。
 * Tablet：等级卡片单列、信息压缩为一行。
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import { useLevelStore, type MemberLevel } from '@/stores/level'

const store = useLevelStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '总会员数', icon: 'customer', value: store.totalMembers.toLocaleString(), tone: 'text' as const },
  { label: '最高等级', icon: 'customer', value: `${store.topLevel?.name ?? '—'} ${store.topLevel?.memberCount.toLocaleString() ?? ''}`, tone: 'brand' as const },
  { label: '本月升级', icon: 'customer', value: String(store.monthUpgraded), tone: 'success' as const },
  { label: '本月降级', icon: 'customer', value: String(store.monthDowngraded), tone: 'warning' as const },
])

// 等级阈值编辑（本地草稿）
const drafts = ref<Record<string, { threshold: string }>>({})
function ensureDraft(l: MemberLevel) {
  if (!drafts.value[l.id]) drafts.value[l.id] = { threshold: String(l.upgradeThreshold) }
  return drafts.value[l.id]
}
function isDirty(l: MemberLevel) {
  const d = drafts.value[l.id]
  return d && d.threshold !== String(l.upgradeThreshold)
}
function saveLevel(l: MemberLevel) {
  const d = drafts.value[l.id]
  if (!d) return
  const n = Number(d.threshold)
  if (Number.isNaN(n) || n < 0) return
  store.updateLevel(l.id, { upgradeThreshold: n })
}

// 规则表单
const ruleDraft = reactive({ ...store.rule })
const ruleDirty = ref(false)
function syncRule<K extends keyof typeof ruleDraft>(key: K, v: (typeof ruleDraft)[K]) {
  ruleDraft[key] = v
  ruleDirty.value = true
}
function saveRule() {
  if (store.saveRule({ ...ruleDraft })) {
    ruleDirty.value = false
    toast.value = '规则已保存'
    setTimeout(() => (toast.value = ''), 2000)
  }
}
function resetDefault() {
  if (store.resetDefault()) {
    Object.assign(ruleDraft, store.rule)
    ruleDirty.value = false
    toast.value = '已恢复默认'
    setTimeout(() => (toast.value = ''), 2000)
  }
}

const toast = ref('')
</script>

<template>
  <div class="lv">
    <div class="lv__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- 提示条 -->
    <div class="lv__tip">
      <CIcon name="alert" :size="14" />规则修改次日生效 · 变更写入审计日志
    </div>

    <!-- 等级卡片 -->
    <div class="lv__cards">
      <div
        v-for="l in store.levels" :key="l.id"
        class="lv-card" :style="{ '--lv-color': l.color }"
      >
        <div class="lv-card__head">
          <span class="lv-card__dot" />
          <span class="lv-card__name">{{ l.name }}</span>
          <CStatusPill v-if="l.isTop" status="success" class="lv-card__top">最高级</CStatusPill>
        </div>
        <div class="lv-card__cond">
          <div class="lv-card__cond-label">升级条件</div>
          <div class="lv-card__cond-text">{{ l.upgradeCondition }}</div>
        </div>
        <ul class="lv-card__benefits">
          <li v-for="(b, i) in l.benefits" :key="i">
            <CIcon name="check" :size="12" class="lv-card__check" />{{ b }}
          </li>
        </ul>
        <div class="lv-card__members">
          当前 <b>{{ l.memberCount.toLocaleString() }}</b> 人（{{ l.memberPercent }}%）
        </div>

        <!-- 阈值编辑 -->
        <div v-if="l.tier !== 'NORMAL'" class="lv-card__edit">
          <CInput
            label="升级阈值（元）"
            type="number"
            :model-value="ensureDraft(l).threshold"
            @update:model-value="(v) => (ensureDraft(l).threshold = v)"
          />
          <CButton
            variant="text" size="sm"
            v-perm.disable="'level:edit'"
            :disabled="!isDirty(l)"
            @click="saveLevel(l)"
          >保存</CButton>
        </div>
      </div>
    </div>

    <!-- 升降级规则配置 -->
    <CCard class="lv__rule" padding="lg">
      <template #header>
        <div class="rule-head">
          <h3 class="rule-head__title">升降级规则配置</h3>
          <div class="rule-head__actions">
            <CButton variant="secondary" size="sm" v-perm.disable="'level:edit'" @click="resetDefault">
              <CIcon name="edit" :size="14" />重置默认
            </CButton>
            <CButton variant="primary" size="sm" v-perm.disable="'level:edit'" :disabled="!ruleDirty" @click="saveRule">
              <CIcon name="check" :size="14" />保存规则
            </CButton>
          </div>
        </div>
      </template>
      <div class="rule-grid">
        <div class="rule-field">
          <label class="rule-field__label">等级计算周期</label>
          <CInput :model-value="ruleDraft.calcPeriod" @update:model-value="(v) => syncRule('calcPeriod', v)" />
        </div>
        <div class="rule-field">
          <label class="rule-field__label">降级保护期（月）</label>
          <CInput
            type="number"
            :model-value="String(ruleDraft.downgradeProtectMonths)"
            @update:model-value="(v) => syncRule('downgradeProtectMonths', Number(v) || 0)"
          />
        </div>
        <div class="rule-field rule-field--full">
          <label class="rule-switch">
            <span class="rule-switch__text">
              <span class="rule-switch__title">达标后自动升级</span>
              <span class="rule-switch__desc">无需手动审核，系统于周期结束后自动升级</span>
            </span>
            <input
              type="checkbox"
              :checked="ruleDraft.autoUpgrade"
              @change="syncRule('autoUpgrade', ($event.target as HTMLInputElement).checked)"
            />
          </label>
        </div>
        <div class="rule-field">
          <label class="rule-field__label">消费积分倍率</label>
          <CInput
            type="number"
            :model-value="String(ruleDraft.pointsMultiplier)"
            @update:model-value="(v) => syncRule('pointsMultiplier', Number(v) || 1)"
          />
        </div>
      </div>

      <!-- 审计追踪 -->
      <div class="audit">
        <div class="audit__title">最近变更记录（审计追踪）</div>
        <ul class="audit__list">
          <li v-for="a in store.audits" :key="a.id" class="audit__item">
            <span class="audit__date">{{ a.date }}</span>
            <span class="audit__text">{{ a.text }}</span>
            <span class="audit__by">操作人：{{ a.operator }}</span>
          </li>
        </ul>
      </div>
    </CCard>

    <transition name="toast">
      <div v-if="toast" class="toast"><CIcon name="check" :size="16" />{{ toast }}</div>
    </transition>
  </div>
</template>

<style scoped>
.lv { display: flex; flex-direction: column; gap: var(--s-lg); }
.lv__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .lv__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.rule-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); width: 100%; flex-wrap: wrap; }
.rule-head__title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.rule-head__actions { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; }

.lv__tip {
  display: flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-sm) var(--s-md);
  background: var(--c-success-bg);
  color: var(--c-success-fg);
  border-radius: var(--r-md);
  font-size: var(--t-sm);
}

.lv__cards {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--s-md);
}
.lv-card {
  --lv-color: var(--c-brand);
  display: flex; flex-direction: column; gap: var(--s-sm);
  padding: var(--s-md);
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-left: 3px solid var(--lv-color);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-card);
  min-width: 0;
}
.lv-card__head { display: flex; align-items: center; gap: var(--s-xs); }
.lv-card__dot { width: 12px; height: 12px; border-radius: 50%; background: var(--lv-color); flex-shrink: 0; }
.lv-card__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.lv-card__top { margin-left: auto; }

.lv-card__cond {
  background: color-mix(in srgb, var(--lv-color) 12%, var(--c-surface));
  border-radius: var(--r-md);
  padding: var(--s-xs) var(--s-sm);
}
.lv-card__cond-label { font-size: var(--t-xs); color: var(--c-text-3); }
.lv-card__cond-text { font-size: var(--t-sm); font-weight: 700; color: var(--lv-color); }

.lv-card__benefits { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; flex: 1; }
.lv-card__benefits li {
  display: flex; align-items: center; gap: 6px;
  font-size: var(--t-sm); color: var(--c-text-2);
}
.lv-card__check { color: var(--lv-color); flex-shrink: 0; }

.lv-card__members { font-size: var(--t-xs); color: var(--c-text-3); }
.lv-card__members b { color: var(--c-text); font-weight: 700; }

.lv-card__edit { display: flex; align-items: flex-end; gap: var(--s-xs); padding-top: var(--s-sm); border-top: 1px dashed var(--c-border-light); }
.lv-card__edit .cinput { flex: 1; }

.lv__rule { }
.rule-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.rule-field { display: flex; flex-direction: column; gap: var(--s-xs); }
.rule-field--full { grid-column: 1 / -1; }
.rule-field__label { font-size: var(--t-xs); color: var(--c-text-3); }

.rule-switch {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-md);
  padding: var(--s-md) var(--s-lg);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-md);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.rule-switch:hover { border-color: var(--c-brand-border); background: var(--c-brand-soft); }
.rule-switch__text { display: flex; flex-direction: column; gap: 2px; }
.rule-switch__title { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.rule-switch__desc { font-size: var(--t-xs); color: var(--c-text-3); }
.rule-switch input {
  appearance: none; width: 40px; height: 22px; border-radius: var(--r-capsule);
  background: var(--c-border); position: relative; cursor: pointer; transition: background 0.15s;
}
.rule-switch input::after {
  content: ''; position: absolute; top: 2px; left: 2px; width: 18px; height: 18px;
  border-radius: 50%; background: var(--c-surface); box-shadow: 0 1px 2px rgba(0,0,0,.2);
  transition: transform 0.15s;
}
.rule-switch input:checked { background: var(--c-brand); }
.rule-switch input:checked::after { transform: translateX(18px); }

.audit { margin-top: var(--s-lg); padding: var(--s-md) var(--s-lg); background: var(--c-surface-muted, #f5f6fa); border-radius: var(--r-md); }
.audit__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.audit__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--s-xs); }
.audit__item { display: flex; gap: var(--s-md); font-size: var(--t-sm); color: var(--c-text-2); flex-wrap: wrap; }
.audit__date { color: var(--c-text-3); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.audit__text { color: var(--c-text); }
.audit__by { color: var(--c-text-3); margin-left: auto; }

.toast {
  position: fixed; bottom: var(--s-xl); left: 50%; transform: translateX(-50%);
  display: inline-flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-sm) var(--s-lg);
  background: var(--c-success-fg); color: #fff;
  border-radius: var(--r-capsule);
  font-size: var(--t-sm); font-weight: 600;
  box-shadow: var(--shadow-pop);
  z-index: 300;
}
.toast-enter-active, .toast-leave-active { transition: opacity 0.2s, transform 0.2s; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translate(-50%, 10px); }

@media (max-width: 1280px) {
  .lv__cards { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 1024px) {
  .lv__cards { grid-template-columns: 1fr; }
  .lv-card { flex-direction: row; flex-wrap: wrap; align-items: center; gap: var(--s-sm); }
  .lv-card__head { width: auto; }
  .lv-card__cond { flex: 1; min-width: 140px; }
  .lv-card__benefits { display: none; }
  .lv-card__members { width: auto; margin-left: auto; }
  .lv-card__edit { width: 100%; }
  .rule-grid { grid-template-columns: 1fr; }
  .audit__by { margin-left: 0; width: 100%; }
}
</style>
