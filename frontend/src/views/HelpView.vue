<script setup lang="ts">
/* ============================================================
 * 帮助 / 培训 /m2-help（M2-22）
 * 左分类导航 + 右文档卡片网格 + 详情。只读浏览为主。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useHelpStore, type HelpArticle, type HelpType } from '@/stores/help'
import { HELP_TYPE, dictPill } from '@/config/dictionary'

const store = useHelpStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<HelpArticle | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '资料总数', icon: 'marketing', value: String(store.articles.length), tone: 'brand' as const },
  { label: '视频教程', icon: 'marketing', value: String(store.videoCount), tone: 'teal' as const },
  { label: '待完成考核', icon: 'check-square', value: String(store.examCount), tone: 'warning' as const },
  { label: '最近更新', icon: 'settings', value: fmtDate(store.latestAt), tone: 'text' as const },
])

function fmtDate(iso: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}


function typeIcon(t: HelpType) {
  if (t === 'VIDEO') return 'marketing'
  if (t === 'EXAM') return 'check-square'
  return 'order'
}
</script>

<template>
  <div class="hp">
    <div class="hp__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard class="hp__toolbar" padding="none">
      <div class="hp__tools">
        <CInput v-model="store.keyword" placeholder="搜索文档 / 标签" />
        <CButton variant="secondary">
          <CIcon name="phone" :size="16" />联系支持
        </CButton>
      </div>
    </CCard>

    <div class="hp__body">
      <!-- 左：分类导航 -->
      <CCard class="hp__nav" padding="none">
        <div class="nav__list">
          <button
            v-for="cat in store.categories" :key="cat.value"
            class="nav__item" :class="{ 'nav__item--active': store.currentCategory === cat.value }"
            @click="store.currentCategory = cat.value; selectedId = null"
          >
            <span class="nav__label">
              <CIcon :name="(cat.value === 'ALL' ? 'dashboard' : (store.CATEGORY_ICON[cat.value] as any))" :size="14" />
              {{ cat.label }}
            </span>
            <span class="nav__count">{{ cat.count }}</span>
          </button>
        </div>
      </CCard>

      <!-- 中：文档卡片网格 -->
      <CCard class="hp__grid-wrap" padding="none">
        <div class="grid-head">
          <span>共 {{ store.filtered.length }} 篇资料</span>
        </div>
        <div class="grid">
          <button
            v-for="a in store.filtered" :key="a.id"
            class="doc" :class="{ 'doc--active': selected?.id === a.id }"
            @click="selectedId = a.id"
          >
            <div class="doc__icon" :class="`doc__icon--${a.type.toLowerCase()}`">
              <CIcon :name="(typeIcon(a.type) as any)" :size="20" />
            </div>
            <div class="doc__main">
              <div class="doc__title">{{ a.title }}</div>
              <div class="doc__summary">{{ a.summary }}</div>
              <div class="doc__meta">
                <CStatusPill :status="dictPill(HELP_TYPE[a.type]).status">{{ dictPill(HELP_TYPE[a.type]).text }}</CStatusPill>
                <span class="doc__cat">{{ store.CATEGORY_LABEL[a.category] }}</span>
                <span class="doc__dot">·</span>
                <span>{{ a.readMin }} 分钟</span>
                <span class="doc__dot">·</span>
                <span>{{ fmtDate(a.updatedAt) }} 更新</span>
              </div>
            </div>
          </button>
          <div v-if="store.filtered.length === 0" class="grid-empty">
            <CIcon name="search" :size="28" class="grid-empty__icon" />
            <div>未找到匹配资料</div>
          </div>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="hp__detail" padding="lg">
        <template #header>
          <div class="detail__head">
            <div class="detail__icon" :class="`detail__icon--${selected.type.toLowerCase()}`">
              <CIcon :name="(typeIcon(selected.type) as any)" :size="22" />
            </div>
            <div class="detail__titles">
              <h3 class="detail__title">{{ selected.title }}</h3>
              <div class="detail__meta">
                <CStatusPill :status="dictPill(HELP_TYPE[selected.type]).status">{{ dictPill(HELP_TYPE[selected.type]).text }}</CStatusPill>
                <span>{{ store.CATEGORY_LABEL[selected.category] }}</span>
                <span>{{ selected.readMin }} 分钟阅读</span>
                <span>{{ fmtDate(selected.updatedAt) }} 更新</span>
              </div>
            </div>
          </div>
        </template>

        <p class="detail__summary">{{ selected.summary }}</p>

        <div class="detail__tags">
          <span v-for="t in selected.tags" :key="t" class="tag">#{{ t }}</span>
        </div>

        <div class="detail__content">
          <p v-for="(p, i) in selected.content" :key="i">{{ p }}</p>
        </div>

        <div class="detail__ops">
          <CButton variant="ghost" size="sm"><CIcon name="phone" :size="14" />提问反馈</CButton>
          <CButton v-if="selected.type === 'EXAM'" variant="primary" size="sm">
            <CIcon name="check-square" :size="14" />开始考核
          </CButton>
          <CButton v-else-if="selected.type === 'VIDEO'" variant="primary" size="sm">
            <CIcon name="marketing" :size="14" />观看视频
          </CButton>
          <CButton v-else variant="primary" size="sm">
            <CIcon name="order" :size="14" />阅读全文
          </CButton>
        </div>
      </CCard>

      <CCard v-else class="hp__detail hp__detail--empty" title="资料详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="order" :size="40" class="detail-empty__icon" />
          <p>请选择一篇资料查看</p>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.hp { display: flex; flex-direction: column; gap: var(--s-lg); }
.hp__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .hp__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.hp__body { display: grid; grid-template-columns: 220px 380px 1fr; gap: var(--s-lg); align-items: start; }
.hp__nav, .hp__grid-wrap, .hp__detail { min-width: 0; }

.hp__toolbar { flex-shrink: 0; }
.hp__tools { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); flex-wrap: nowrap; }
.hp__tools > .cinput { flex: 1; min-width: 0; }
.hp__tools :deep(.cbtn) { flex-shrink: 0; white-space: nowrap; }

.nav__list { padding: var(--s-xs); }
.nav__item {
  display: flex; align-items: center; justify-content: space-between;
  width: 100%; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md);
  background: none; border: none; border-radius: var(--r-md);
  font-size: var(--t-sm); color: var(--c-text-2);
  cursor: pointer; text-align: left;
}
.nav__item:hover { background: var(--c-brand-soft); color: var(--c-text); }
.nav__item--active { background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; box-shadow: inset 3px 0 0 var(--c-brand); }
.nav__label { display: inline-flex; align-items: center; gap: 6px; }
.nav__count { font-size: var(--t-xs); color: var(--c-text-3); padding: 0 8px; border-radius: var(--r-capsule); background: var(--c-surface-muted, #f0f2f5); }

.grid-head { padding: var(--s-md) var(--s-lg); font-size: var(--t-xs); color: var(--c-text-3); border-bottom: 1px solid var(--c-border-light); }
.grid { max-height: 640px; overflow-y: auto; padding: var(--s-sm); display: flex; flex-direction: column; gap: var(--s-xs); }
.grid-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.grid-empty__icon { color: var(--c-text-4); }

.doc {
  display: flex; gap: var(--s-md);
  padding: var(--s-md);
  background: none; border: 1px solid transparent; border-radius: var(--r-md);
  cursor: pointer; text-align: left; width: 100%;
}
.doc:hover { background: var(--c-brand-soft); }
.doc--active { background: var(--c-brand-soft); border-color: var(--c-brand-border); box-shadow: inset 3px 0 0 var(--c-brand); }
.doc__icon {
  width: 40px; height: 40px; flex-shrink: 0;
  border-radius: var(--r-md);
  display: flex; align-items: center; justify-content: center;
  background: var(--c-brand-soft); color: var(--c-brand);
}
.doc__icon--video { background: var(--c-info-bg, #e6f0ff); color: var(--c-info-fg, #2f6fed); }
.doc__icon--exam { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.doc__main { flex: 1; min-width: 0; }
.doc__title { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; margin-bottom: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.doc__summary { font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.5; margin-bottom: var(--s-xs); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.doc__meta { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); flex-wrap: wrap; }
.doc__cat { color: var(--c-text-2); }
.doc__dot { color: var(--c-text-4); }

.detail__head { display: flex; gap: var(--s-md); align-items: flex-start; width: 100%; }
.detail__icon {
  width: 48px; height: 48px; flex-shrink: 0;
  border-radius: var(--r-md);
  display: flex; align-items: center; justify-content: center;
  background: var(--c-brand-soft); color: var(--c-brand);
}
.detail__icon--video { background: var(--c-info-bg, #e6f0ff); color: var(--c-info-fg, #2f6fed); }
.detail__icon--exam { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.detail__titles { flex: 1; min-width: 0; }
.detail__title { font-size: var(--t-lg); font-weight: 700; margin: 0 0 var(--s-xs); color: var(--c-text); }
.detail__meta { display: flex; flex-wrap: wrap; gap: var(--s-sm); align-items: center; font-size: var(--t-xs); color: var(--c-text-3); }

.detail__summary { font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.7; margin: var(--s-md) 0; padding: var(--s-md); background: var(--c-surface-muted, #f7f8fa); border-radius: var(--r-md); }
.detail__tags { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-bottom: var(--s-md); }
.tag { font-size: var(--t-xs); color: var(--c-brand); padding: 2px 10px; background: var(--c-brand-soft); border-radius: var(--r-capsule); }

.detail__content { display: flex; flex-direction: column; gap: var(--s-sm); }
.detail__content p { font-size: var(--t-sm); color: var(--c-text); line-height: 1.8; margin: 0; }

.detail__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

@media (max-width: 1280px) {
  .hp__body { grid-template-columns: 200px 320px 1fr; }
}
@media (max-width: 1024px) {
  .hp__body { grid-template-columns: 1fr; }
  .hp__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .grid { max-height: 360px; }
}
</style>
