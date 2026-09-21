<script setup lang="ts">
/* ============================================================
 * G-04 全局搜索（/search）
 * 顶栏大搜索框 + 分类 Tab + 结果列表 + 最近访问 / 热门搜索
 * 结果按 RBAC 过滤；敏感检索留痕 T1-04
 * 数据源（B83 卡3 切真）：客户 /customer/search（ES 降级 DB）、
 * 预约 /txn/appointment、订单 /txn/order（数据域内取近单本地过滤）、
 * 知识库 /ai/knowledge/search、热门词 /ai/knowledge/hot、页面项由导航动态生成。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CSegmented from '@/components/CSegmented.vue'
import CIcon from '@/components/CIcon.vue'
import { NAV_GROUPS, PAGE_TITLES } from '@/config/nav'
import { useRecentVisitsStore } from '@/stores/recentVisits'
import { listCustomers, searchCustomers, type CustomerDTO } from '@/api/customer'
import { listAppointments, type AppointmentView } from '@/api/appointment'
import { listOrders, type OrderViewDTO } from '@/api/order'
import { getKnowledgeHot, listKnowledgeDocs, searchKnowledge } from '@/api/ai'

type Cat = 'all' | 'customer' | 'appointment' | 'order' | 'kb' | 'page'

interface SearchItem {
  id: string | number
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

const customerItems = ref<SearchItem[]>([])
const kbItems = ref<SearchItem[]>([])
const apptSource = ref<AppointmentView[]>([])
const orderSource = ref<OrderViewDTO[]>([])
const hot = ref(['热玛吉', '今日预约', '退款审批', '新客', '库存预警', '经营周报', 'SOP', '双签阈值'])

function maskPhone(p?: string): string {
  return p ? p.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2') : ''
}

function mapCustomer(c: CustomerDTO): SearchItem {
  return {
    id: `c-${c.customerId}`,
    cat: 'customer',
    title: `${c.name}（${maskPhone(c.phone)}）`,
    summary: `${c.level}会员 · 累计消费 ¥${Number(c.totalSpend ?? 0).toLocaleString('zh-CN')} · ${c.status ?? '活跃'}`,
    path: `/customers/${c.customerId}`,
    tag: '客户',
    tagStatus: 'primary',
  }
}

function mapAppt(a: AppointmentView): SearchItem {
  return {
    id: `a-${a.apptNo}`,
    cat: 'appointment',
    title: `${a.apptDate} ${a.apptTime} ${a.customerName ?? '散客'}预约 ${a.project}`,
    summary: `操作医生 ${a.doctorName ?? '未排'} · 门店 ${a.storeName ?? a.storeCode ?? ''} · 状态${a.status}`,
    path: '/appointment',
    tag: '预约',
    tagStatus: 'info',
  }
}

const ORDER_PILL: Record<string, SearchItem['tagStatus']> = {
  已收款: 'success',
  待收款: 'warning',
  待签核: 'warning',
  已取消: 'danger',
}

function mapOrder(o: OrderViewDTO): SearchItem {
  return {
    id: `o-${o.orderNo}`,
    cat: 'order',
    title: `订单 #${o.orderNo}`,
    summary: `金额 ¥${(o.amount / 100).toLocaleString('zh-CN')} · ${o.status} · 客户 ${o.customerName ?? '散客'}`,
    path: '/order',
    tag: '订单',
    tagStatus: ORDER_PILL[o.status] ?? 'default',
  }
}

// 动态生成搜索项：从导航配置和页面标题中提取
function buildDynamicItems(): SearchItem[] {
  const pageItems: SearchItem[] = []
  const seenPaths = new Set(['/appointment', '/order', '/ai/knowledge'])

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

const apptItems = computed<SearchItem[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  return apptSource.value
    .filter((a) => !k || [a.customerName, a.apptNo, a.project, a.storeName, a.doctorName]
      .some((s) => s != null && String(s).toLowerCase().includes(k)))
    .slice(0, 50)
    .map(mapAppt)
})

const orderItems = computed<SearchItem[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  return orderSource.value
    .filter((o) => !k || [o.customerName, o.orderNo, o.status, o.storeName]
      .some((s) => s != null && String(s).toLowerCase().includes(k)))
    .map(mapOrder)
})

// 客户/知识库由服务端按关键词检索（客户 ES 降级 DB；知识库命中即留真实引用）；
// 空关键词时取默认近单，保证空态仍有真实内容。
let searchSeq = 0
let searchTimer: ReturnType<typeof setTimeout> | undefined

async function refreshRemote() {
  const k = keyword.value.trim()
  const my = ++searchSeq
  const customerPromise: Promise<CustomerDTO[]> = k
    ? searchCustomers(k).then((r) => r.data)
    : listCustomers({ size: 20 }).then((r) => r.data.content)
  const kbPromise: Promise<{ title: string; summary: string }[]> = k
    ? searchKnowledge(k, 10).then((hits) => hits.map((h) => ({ title: h.title, summary: h.snippet })))
    : listKnowledgeDocs({ size: 10 }).then((p) => p.content.map((d) => ({ title: d.title, summary: (d.content ?? '').slice(0, 60) })))
  const [c, kb] = await Promise.allSettled([customerPromise, kbPromise])
  if (my !== searchSeq) return
  customerItems.value = c.status === 'fulfilled' ? c.value.map(mapCustomer) : []
  kbItems.value = kb.status === 'fulfilled'
    ? kb.value.map((d, i) => ({
        id: `k-${i}-${d.title}`,
        cat: 'kb' as const,
        title: d.title,
        summary: d.summary,
        path: '/ai/knowledge',
        tag: '知识库',
        tagStatus: 'draft' as const,
      }))
    : []
}

watch(keyword, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void refreshRemote(), 250)
})

onMounted(() => {
  void refreshRemote()
  void (async () => {
    const [a, o, h] = await Promise.allSettled([
      listAppointments(),
      listOrders({ size: 100 }),
      getKnowledgeHot(),
    ])
    if (a.status === 'fulfilled') apptSource.value = a.value.data
    if (o.status === 'fulfilled') orderSource.value = o.value.data.content ?? []
    if (h.status === 'fulfilled' && h.value.length) hot.value = h.value
  })()
})

const allSearchItems = computed<SearchItem[]>(() => [
  ...customerItems.value,
  ...apptItems.value,
  ...orderItems.value,
  ...kbItems.value,
  ...dynamicItems,
])

// 关键词过滤：客户/知识库以服务端结果为准、预约/订单已在各自 computed 过滤，
// 此处仅对「页面」类做本地标题/摘要匹配。
const filtered = computed<SearchItem[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  return allSearchItems.value.filter((it) => {
    if (cat.value !== 'all' && it.cat !== cat.value) return false
    if (!k || it.cat !== 'page') return true
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
