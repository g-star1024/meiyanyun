<script setup lang="ts">
/* ============================================================
 * G-04 全局搜索（/search）
 * 顶栏大搜索框 + 分类 Tab + 结果列表 + 最近访问 / 热门搜索
 * 结果按 RBAC 过滤；敏感检索留痕 T1-04
 * ============================================================ */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CSegmented from '@/components/CSegmented.vue'
import CIcon from '@/components/CIcon.vue'
import { NAV_GROUPS, PAGE_TITLES } from '@/config/nav'
import { useRecentVisitsStore } from '@/stores/recentVisits'

type Cat = 'all' | 'customer' | 'appointment' | 'order' | 'kb' | 'page'

interface SearchItem {
  id: number
  cat: Exclude<Cat, 'all'>
  title: string
  summary: string
  path: string
  tag: string
  tagStatus: 'default' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'disabled' | 'draft'
}

const router = useRouter()
const recentVisits = useRecentVisitsStore()
const keyword = ref('')
const cat = ref<Cat>('all')
const currentPage = ref(1)
const PAGE_SIZE = 20

const CATS: { label: string; value: Cat }[] = [
  { label: '全部', value: 'all' },
  { label: '客户', value: 'customer' },
  { label: '预约', value: 'appointment' },
  { label: '订单', value: 'order' },
  { label: '知识库', value: 'kb' },
  { label: '页面', value: 'page' },
]

const items: SearchItem[] = [
  { id: 1, cat: 'customer', title: '李雨桐（138****6612）', summary: '金卡会员 · 最近到店 2026-08-20 · 累计消费 ¥12,860', path: '/customers/10021', tag: '客户', tagStatus: 'primary' },
  { id: 2, cat: 'customer', title: '陈思琪（139****0188）', summary: '新客 · 来源 小红书 · 待首诊跟进', path: '/customers/10045', tag: '客户', tagStatus: 'primary' },
  { id: 3, cat: 'appointment', title: '今日 14:30 王雪预约 热玛吉', summary: '操作医生 张医生 · 房间 3 号 · 状态待到店', path: '/appointment', tag: '预约', tagStatus: 'info' },
  { id: 4, cat: 'appointment', title: '明日 10:00 团体预约 5 人', summary: '企微团购 · 套餐 水光基础 · 待分诊', path: '/appointment', tag: '预约', tagStatus: 'info' },
  { id: 5, cat: 'order', title: '订单 #O2026082500198', summary: '金额 ¥3,980 · 支付成功 · 未核销 · 客户 赵琳', path: '/order', tag: '订单', tagStatus: 'success' },
  { id: 6, cat: 'order', title: '退款单 #R2026082400012', summary: '金额 ¥1,200 · 待主管审批 · 超 L1 单签阈值', path: '/refund', tag: '订单', tagStatus: 'warning' },
  { id: 7, cat: 'kb', title: '热玛吉操作 SOP（v3.2）', summary: '适用第五代设备 · 含禁忌症核对清单 · 已发布', path: '/ai/knowledge', tag: '知识库', tagStatus: 'draft' },
  { id: 8, cat: 'kb', title: '客诉处理话术：效果不达预期', summary: '门店店长 / 咨询师必读 · 更新于 2026-08-10', path: '/ai/knowledge', tag: '知识库', tagStatus: 'draft' },
]

// 动态生成搜索项：从导航配置和页面标题中提取
function buildDynamicItems(): SearchItem[] {
  const pageItems: SearchItem[] = []
  const seenPaths = new Set(items.map(i => i.path))

  Object.entries(PAGE_TITLES).forEach(([path, info]) => {
    if (seenPaths.has(path)) return
    if (path.includes(':')) return

    pageItems.push({
      id: pageItems.length + 1000,
      cat: 'page',
      title: info.title || path,
      summary: info.breadcrumb || '',
      path,
      tag: '页面',
      tagStatus: 'default'
    })
  })

  NAV_GROUPS.forEach(group => {
    group.items.forEach(item => {
      if (seenPaths.has(item.to) || pageItems.some(p => p.path === item.to)) return
      if (item.to.includes(':')) return

      pageItems.push({
        id: pageItems.length + 1000,
        cat: 'page',
        title: item.label,
        summary: `${group.title || ''} - ${item.label}`,
        path: item.to,
        tag: '页面',
        tagStatus: 'default'
      })
    })
  })

  return pageItems
}

const dynamicItems = buildDynamicItems()
const allSearchItems = [...items, ...dynamicItems]

const hot = ['热玛吉', '今日预约', '退款审批', '新客', '库存预警', '经营周报', 'SOP', '双签阈值']

const filtered = computed<SearchItem[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  return allSearchItems.filter((it) => {
    if (cat.value !== 'all' && it.cat !== cat.value) return false
    if (!k) return true
    return it.title.toLowerCase().includes(k) || it.summary.toLowerCase().includes(k)
  })
})

const totalPages = computed(() => Math.ceil(filtered.value.length / PAGE_SIZE) || 1)
const paged = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE
  return filtered.value.slice(start, start + PAGE_SIZE)
})

function setPage(p: number) {
  currentPage.value = Math.max(1, Math.min(p, totalPages.value))
}

function go(path: string) {
  router.push(path)
}

function formatTime(ts: number): string {
  const diff = Date.now() - ts
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}小时前`
  if (diff < 172800000) return '昨天'
  return `${Math.floor(diff / 86400000)}天前`
}
</script>

<template>
  <div class="g-search">
    <CCard padding="lg">
      <div class="search-box">
        <CIcon name="search" :size="20" class="search-box__ico" />
        <input
          v-model="keyword"
          class="search-box__input"
          type="text"
          placeholder="搜索客户、预约、订单、知识库、页面……（敏感检索将留痕 T1-04）"
          @input="currentPage = 1"
        />
        <CButton variant="primary" size="md" @click="() => {}">搜索</CButton>
      </div>
      <div class="cats">
        <CSegmented v-model="cat" :options="CATS" />
        <span class="result-count">共 {{ filtered.length }} 条结果</span>
      </div>
    </CCard>

    <div class="g-search__main">
      <div class="g-search__results">
        <CCard v-if="!paged.length" padding="lg">
          <div class="empty">
            <CIcon name="search" :size="28" />
            <p>未找到相关结果，换个关键词试试</p>
          </div>
        </CCard>
        <template v-else>
          <CCard padding="none">
            <div class="result">
              <button
                v-for="it in paged"
                :key="it.id"
                class="result__item"
                @click="go(it.path)"
              >
                <div class="result__head">
                  <CStatusPill :status="it.tagStatus" dot>{{ it.tag }}</CStatusPill>
                  <span class="result__title">{{ it.title }}</span>
                </div>
                <p class="result__sum">{{ it.summary }}</p>
                <span class="result__path">{{ it.path }}</span>
              </button>
            </div>
          </CCard>
          <!-- 分页 -->
          <div v-if="totalPages > 1" class="pagination">
            <button
              class="pagination__btn"
              :disabled="currentPage <= 1"
              @click="setPage(currentPage - 1)"
            >
              <CIcon name="chevron-left" :size="14" />
              上一页
            </button>
            <span class="pagination__info">{{ currentPage }} / {{ totalPages }}</span>
            <button
              class="pagination__btn"
              :disabled="currentPage >= totalPages"
              @click="setPage(currentPage + 1)"
            >
              下一页
              <CIcon name="chevron-right" :size="14" />
            </button>
          </div>
        </template>
      </div>

      <aside class="g-search__side">
        <CCard title="最近访问" padding="md">
          <div v-if="!recentVisits.recentItems.length" class="recent-empty">
            <CIcon name="clock" :size="18" />
            <span>暂无访问记录</span>
          </div>
          <ul v-else class="recent">
            <li v-for="r in recentVisits.recentItems.slice(0, 8)" :key="r.path">
              <button class="recent__item" @click="go(r.path)">
                <CIcon name="chevron-right" :size="14" />
                <span class="recent__label">{{ r.title }}</span>
                <span class="recent__time">{{ formatTime(r.timestamp) }}</span>
              </button>
            </li>
          </ul>
          <div v-if="recentVisits.recentItems.length > 8" class="recent-actions">
            <button class="recent-clear" @click="recentVisits.clearAll()">
              <CIcon name="delete" :size="12" />
              清空记录
            </button>
          </div>
        </CCard>
        <CCard title="热门搜索" padding="md">
          <div class="hot">
            <button v-for="h in hot" :key="h" class="hot__tag" @click="keyword = h; currentPage = 1">
              # {{ h }}
            </button>
          </div>
        </CCard>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.g-search { display: flex; flex-direction: column; gap: var(--s-lg); }
.search-box {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-xs) var(--s-xs) var(--s-xs) var(--s-md);
  border: 1px solid var(--c-border);
  border-radius: var(--r-capsule);
  background: var(--c-surface);
  box-shadow: var(--shadow-card);
}
.search-box__ico { color: var(--c-text-3); flex-shrink: 0; }
.search-box__input {
  flex: 1; border: none; outline: none; background: none;
  font-size: var(--t-md); color: var(--c-text);
  padding: var(--s-sm) 0;
}
.search-box__input::placeholder { color: var(--c-text-3); }
.cats { margin-top: var(--s-md); display: flex; align-items: center; gap: var(--s-md); }
.result-count { font-size: var(--t-sm); color: var(--c-text-3); }
.g-search__main { display: grid; grid-template-columns: 1fr 300px; gap: var(--s-lg); align-items: start; }
.result { display: flex; flex-direction: column; }
.result__item {
  display: flex; flex-direction: column; gap: var(--s-xs);
  padding: var(--s-md) var(--s-lg);
  border: none; background: none; cursor: pointer; text-align: left;
  border-bottom: 1px solid var(--c-border-light);
  transition: background .15s;
}
.result__item:last-child { border-bottom: none; }
.result__item:hover { background: var(--c-bg-page); }
.result__head { display: flex; align-items: center; gap: var(--s-sm); }
.result__title { font-size: var(--t-md); font-weight: 600; color: var(--c-text); }
.result__sum { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); }
.result__path { font-size: var(--t-xs); color: var(--c-text-3); font-family: monospace; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); color: var(--c-text-3); padding: var(--s-xl) 0; }
.g-search__side { display: flex; flex-direction: column; gap: var(--s-lg); }
.recent { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.recent-empty { display: flex; align-items: center; gap: var(--s-xs); color: var(--c-text-3); padding: var(--s-md) 0; font-size: var(--t-sm); }
.recent__item {
  display: flex; align-items: center; gap: var(--s-xs);
  width: 100%; padding: var(--s-xs) 0; border: none; background: none; cursor: pointer;
  font-size: var(--t-sm); color: var(--c-text-2); text-align: left;
}
.recent__item:hover { color: var(--c-brand); }
.recent__label { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recent__time { font-size: var(--t-xs); color: var(--c-text-3); flex-shrink: 0; }
.recent-actions { margin-top: var(--s-sm); padding-top: var(--s-sm); border-top: 1px solid var(--c-border-light); }
.recent-clear {
  display: flex; align-items: center; gap: var(--s-xxs);
  padding: var(--s-xxs) var(--s-xs);
  background: none; border: none; cursor: pointer;
  font-size: var(--t-xs); color: var(--c-text-3);
}
.recent-clear:hover { color: var(--c-danger); }
.hot { display: flex; flex-wrap: wrap; gap: var(--s-xs); }
.hot__tag {
  padding: var(--s-xxs) var(--s-sm);
  background: var(--c-bg-page);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-capsule);
  font-size: var(--t-xs); color: var(--c-text-2);
  cursor: pointer; transition: all .15s;
}
.hot__tag:hover { background: var(--c-brand-soft); color: var(--c-brand); border-color: var(--c-brand-border); }

.pagination {
  display: flex; align-items: center; justify-content: center; gap: var(--s-md);
  padding: var(--s-md);
  margin-top: var(--s-sm);
}
.pagination__btn {
  display: flex; align-items: center; gap: var(--s-xxs);
  padding: var(--s-xs) var(--s-md);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
  font-size: var(--t-sm); color: var(--c-text);
  cursor: pointer; transition: all .15s;
}
.pagination__btn:hover:not(:disabled) { background: var(--c-bg-page); border-color: var(--c-brand); color: var(--c-brand); }
.pagination__btn:disabled { opacity: 0.5; cursor: not-allowed; }
.pagination__info { font-size: var(--t-sm); color: var(--c-text-2); }
</style>
