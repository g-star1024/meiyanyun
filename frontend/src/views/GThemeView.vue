<script setup lang="ts">
/* ============================================================
 * G-07 主题/外观（/theme）
 * 主题模式 / 品牌色 / 字号 / 侧栏风格 / 紧凑模式 + 实时预览
 * 深色模式不实现全站切换，只做 UI 选择器 + 预览
 * ============================================================ */
import { computed, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CSegmented from '@/components/CSegmented.vue'
import CIcon from '@/components/CIcon.vue'

const mode = ref<'light' | 'dark' | 'system'>('light')
const brand = ref('#FF6B9D')
const fontSize = ref('std')
const sidebar = ref<'light' | 'dark'>('dark')
const compact = ref(false)

const MODES: { key: 'light' | 'dark' | 'system'; label: string; ico: string }[] = [
  { key: 'light', label: '浅色', ico: 'sun' },
  { key: 'dark', label: '深色', ico: 'dashboard' },
  { key: 'system', label: '跟随系统', ico: 'shield' },
]
const BRANDS = [
  { c: '#FF6B9D', n: '品牌粉' },
  { c: '#6B8AFF', n: '蓝' },
  { c: '#2ED4BF', n: '青' },
  { c: '#8C5CF5', n: '紫' },
  { c: '#FA8C16', n: '橙' },
  { c: '#52C41A', n: '绿' },
]
const FONT_OPTS = [
  { label: '标准', value: 'std' },
  { label: '大', value: 'lg' },
  { label: '特大', value: 'xl' },
]
const SIDE_OPTS = [
  { label: '浅色', value: 'light' },
  { label: '深色', value: 'dark' },
]

const previewStyle = computed(() => {
  const dark = mode.value === 'dark'
  return {
    '--pv-bg': dark ? '#14152b' : '#f4f6fc',
    '--pv-surface': dark ? '#1f2040' : '#ffffff',
    '--pv-text': dark ? '#f0f0f5' : '#1a1a2e',
    '--pv-text-2': dark ? '#b5b7cc' : '#4b4b5a',
    '--pv-border': dark ? '#2d2e4f' : '#e8e8ee',
    '--pv-sidebar': sidebar.value === 'dark' ? '#14152b' : '#ffffff',
    '--pv-sidebar-text': sidebar.value === 'dark' ? '#e8e8ee' : '#4b4b5a',
    '--pv-brand': brand.value,
    '--pv-radius': compact.value ? '6px' : '10px',
  } as Record<string, string>
})
const previewFontSize = computed(() => {
  if (fontSize.value === 'xl') return '15px'
  if (fontSize.value === 'lg') return '14px'
  return '13px'
})

function save() {
  alert('外观设置已保存')
}
</script>

<template>
  <div class="g-theme">
    <div class="g-theme__main">
      <CCard title="主题模式" padding="lg">
        <div class="modes">
          <button
            v-for="m in MODES"
            :key="m.key"
            class="mode-card"
            :class="{ 'is-active': mode === m.key }"
            @click="mode = m.key"
          >
            <span class="mode-card__ico"><CIcon :name="(m.ico as any)" :size="20" /></span>
            <span class="mode-card__label">{{ m.label }}</span>
            <span v-if="mode === m.key" class="mode-card__check"><CIcon name="check" :size="12" /></span>
          </button>
        </div>
      </CCard>

      <CCard title="品牌色" padding="lg">
        <div class="brands">
          <button
            v-for="b in BRANDS"
            :key="b.c"
            class="brand"
            :class="{ 'is-active': brand === b.c }"
            :style="{ background: b.c }"
            :title="b.n"
            @click="brand = b.c"
          >
            <CIcon v-if="brand === b.c" name="check" :size="16" />
          </button>
        </div>
      </CCard>

      <CCard title="字号与布局" padding="lg">
        <div class="opt">
          <span class="opt__label">字号</span>
          <CSegmented v-model="fontSize" :options="FONT_OPTS" />
        </div>
        <div class="opt">
          <span class="opt__label">侧边栏风格</span>
          <CSegmented v-model="sidebar" :options="SIDE_OPTS" />
        </div>
        <div class="opt">
          <div class="opt__text">
            <span class="opt__label">紧凑模式</span>
            <span class="opt__hint">减小行高与间距，单屏展示更多数据</span>
          </div>
          <button
            type="button"
            class="toggle"
            :class="{ 'is-on': compact }"
            :aria-pressed="compact"
            @click="compact = !compact"
          >
            <span class="toggle__dot" />
          </button>
        </div>
      </CCard>

      <div class="g-theme__foot">
        <CButton variant="primary" size="md" @click="save">保存外观</CButton>
      </div>
    </div>

    <CCard title="实时预览" class="g-theme__preview" padding="none">
      <div class="pv" :style="previewStyle">
        <div class="pv__side" :style="{ background: 'var(--pv-sidebar)', color: 'var(--pv-sidebar-text)' }">
          <div class="pv__logo">美研云</div>
          <div class="pv__nav">
            <span v-for="i in 4" :key="i" class="pv__nav-item" :style="{ background: i === 1 ? 'var(--pv-brand)' : 'transparent' }">菜单项 {{ i }}</span>
          </div>
        </div>
        <div class="pv__body" :style="{ background: 'var(--pv-bg)', color: 'var(--pv-text)', fontSize: previewFontSize }">
          <div class="pv__top" :style="{ background: 'var(--pv-surface)', borderColor: 'var(--pv-border)' }">
            <span>页面标题</span>
            <span class="pv__bell" :style="{ color: 'var(--pv-brand)' }">●</span>
          </div>
          <div class="pv__kpis">
            <div v-for="i in 3" :key="i" class="pv__kpi" :style="{ background: 'var(--pv-surface)', borderRadius: 'var(--pv-radius)', borderColor: 'var(--pv-border)' }">
              <span class="pv__kpi-l" :style="{ color: 'var(--pv-text-2)' }">指标 {{ i }}</span>
              <span class="pv__kpi-v" :style="{ color: 'var(--pv-brand)' }">{{ i === 1 ? '128' : i === 2 ? '92%' : '¥3.2w' }}</span>
            </div>
          </div>
          <div class="pv__table" :style="{ background: 'var(--pv-surface)', borderRadius: 'var(--pv-radius)', borderColor: 'var(--pv-border)' }">
            <div v-for="i in 4" :key="i" class="pv__tr" :style="{ borderColor: 'var(--pv-border)' }">
              <span>数据行 {{ i }}</span>
              <span :style="{ color: 'var(--pv-brand)' }">●</span>
            </div>
          </div>
        </div>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.g-theme { display: grid; grid-template-columns: 1fr 380px; gap: var(--s-lg); align-items: start; }
.g-theme__main { display: flex; flex-direction: column; gap: var(--s-lg); }
.g-theme__preview { position: sticky; top: var(--s-md); overflow: hidden; }

.modes { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.mode-card {
  position: relative;
  display: flex; flex-direction: column; align-items: center; gap: var(--s-sm);
  padding: var(--s-lg) var(--s-md);
  background: var(--c-surface);
  border: 2px solid var(--c-border);
  border-radius: var(--r-lg);
  cursor: pointer;
  transition: all .15s;
  color: var(--c-text-2);
}
.mode-card:hover { border-color: var(--c-brand-border); }
.mode-card.is-active { border-color: var(--c-brand); background: var(--c-brand-soft); color: var(--c-brand); }
.mode-card__ico {
  width: 40px; height: 40px; border-radius: var(--r-lg);
  background: var(--c-bg-page); color: var(--c-brand);
  display: inline-flex; align-items: center; justify-content: center;
}
.mode-card.is-active .mode-card__ico { background: var(--c-surface); }
.mode-card__label { font-size: var(--t-sm); font-weight: 600; }
.mode-card__check {
  position: absolute; top: var(--s-xs); right: var(--s-xs);
  width: 18px; height: 18px; border-radius: 50%;
  background: var(--c-brand); color: #fff;
  display: inline-flex; align-items: center; justify-content: center;
}

.brands { display: flex; gap: var(--s-md); flex-wrap: wrap; }
.brand {
  width: 36px; height: 36px; border-radius: 50%;
  border: 3px solid transparent; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  color: #fff; transition: transform .15s;
}
.brand:hover { transform: scale(1.08); }
.brand.is-active { border-color: var(--c-text); }

.opt {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-md) 0;
  border-bottom: 1px solid var(--c-border-light);
}
.opt:last-child { border-bottom: none; }
.opt__label { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.opt__text { display: flex; flex-direction: column; gap: 2px; }
.opt__hint { font-size: var(--t-xs); color: var(--c-text-3); }

.toggle {
  position: relative; width: 40px; height: 22px;
  border-radius: var(--r-capsule);
  background: var(--c-border);
  border: none; cursor: pointer; padding: 0; flex-shrink: 0;
  transition: background .2s;
}
.toggle__dot {
  position: absolute; top: 2px; left: 2px;
  width: 18px; height: 18px; border-radius: 50%;
  background: var(--c-surface);
  box-shadow: 0 1px 3px rgba(20,21,43,.2);
  transition: transform .2s;
}
.toggle.is-on { background: var(--c-brand); }
.toggle.is-on .toggle__dot { transform: translateX(18px); }

.g-theme__foot { display: flex; justify-content: flex-end; }

/* Preview */
.pv { display: flex; height: 460px; overflow: hidden; border-radius: var(--r-xl); }
.pv__side { width: 96px; padding: var(--s-sm); display: flex; flex-direction: column; gap: var(--s-sm); border-right: 1px solid var(--pv-border); }
.pv__logo { font-weight: 700; font-size: var(--t-sm); padding: var(--s-xs) 0; }
.pv__nav { display: flex; flex-direction: column; gap: 2px; }
.pv__nav-item { padding: 6px var(--s-xs); font-size: 11px; border-radius: 6px; color: inherit; opacity: .85; }
.pv__body { flex: 1; display: flex; flex-direction: column; padding: var(--s-sm); gap: var(--s-sm); overflow: hidden; }
.pv__top { display: flex; align-items: center; justify-content: space-between; padding: var(--s-xs) var(--s-sm); border-bottom: 1px solid; font-size: 12px; font-weight: 600; }
.pv__bell { font-size: 12px; }
.pv__kpis { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.pv__kpi { padding: 8px; border: 1px solid; display: flex; flex-direction: column; gap: 2px; }
.pv__kpi-l { font-size: 10px; }
.pv__kpi-v { font-size: 16px; font-weight: 700; }
.pv__table { flex: 1; border: 1px solid; overflow: hidden; display: flex; flex-direction: column; }
.pv__tr { display: flex; align-items: center; justify-content: space-between; padding: 6px var(--s-sm); font-size: 11px; border-bottom: 1px solid; }
.pv__tr:last-child { border-bottom: none; }
</style>
