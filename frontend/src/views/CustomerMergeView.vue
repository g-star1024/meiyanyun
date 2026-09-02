<script setup lang="ts">
/* ============================================================
 * M3-15 客户合并去重 /customers/merge
 * 4 KPI；左疑似重复对列表；右字段对比 + 选择保留方 + 确认合并 / 标记非重复。
 * 受控：合并走 customer:merge 权限，留痕 merges。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useCustomerStore } from '@/stores/customer'
import type { CustomerLink } from '@/types/domain'

const store = useCustomerStore()
onMounted(() => store.seedGraph())

const selectedLinkId = ref<string | null>(null)

const links = computed(() =>
  [...store.links].sort((a, b) => b.score - a.score),
)

const selectedLink = computed<CustomerLink | null>(() => {
  if (selectedLinkId.value) return store.links.find((l) => l.id === selectedLinkId.value) ?? null
  return links.value[0] ?? null
})

const customerA = computed(() => {
  const l = selectedLink.value
  return l ? store.customers.find((c) => c.id === l.customerIdA) : null
})
const customerB = computed(() => {
  const l = selectedLink.value
  return l ? store.customers.find((c) => c.id === l.customerIdB) : null
})

const keepSide = ref<'A' | 'B'>('A')

// KPI
const pendingCount = computed(() => store.links.length)
const mergedCount = computed(() => store.merges.length)
const kpis = computed(() => [
  { label: '疑似重复对', icon: 'customer', value: String(pendingCount.value + mergedCount.value + 42), tone: 'text' as const },
  { label: '已合并处理', icon: 'customer', value: String(mergedCount.value + 42), tone: 'success' as const },
  { label: '待确认', icon: 'check-square', value: String(pendingCount.value), tone: 'warning' as const },
  { label: '自动匹配率', icon: 'trend-up', value: '94.2%', tone: 'teal' as const },
])

const reasonLabel: Record<string, string> = {
  PHONE: '手机',
  NAME_BIRTHDAY: '姓名+生日',
  DEVICE: '设备',
  IDCARD: '证件',
}

function scoreTone(score: number): 'success' | 'warning' | 'danger' {
  if (score >= 0.9) return 'success'
  if (score >= 0.8) return 'warning'
  return 'danger'
}
function scoreColor(score: number) {
  if (score >= 0.9) return 'var(--c-teal)'
  if (score >= 0.8) return 'var(--c-warning-fg)'
  return 'var(--c-danger-fg)'
}

const levelText: Record<string, string> = {
  KA: '黑钻会员', A: '钻石会员', B: '黄金会员', C: '白银会员', NEW: '新客/未分级',
}

interface FieldRow {
  label: string
  a: string
  b: string
  conflict: boolean
}

const compareRows = computed<FieldRow[]>(() => {
  if (!customerA.value || !customerB.value) return []
  const a = customerA.value
  const b = customerB.value
  const row = (label: string, av: string, bv: string): FieldRow => ({
    label, a: av || '—', b: bv || '—', conflict: !!av && !!bv && av !== bv,
  })
  return [
    row('客户 ID', a.id, b.id),
    row('姓名', a.name, b.name),
    row('手机号', a.phoneMask, b.phoneMask),
    row('等级', levelText[a.level] ?? a.level, levelText[b.level] ?? b.level),
    row('标签', (a.tags || []).join('、'), (b.tags || []).join('、')),
    row('累计消费', `¥${(a.totalSpend ?? 0).toLocaleString()}`, `¥${(b.totalSpend ?? 0).toLocaleString()}`),
    row('最后到店', a.lastVisitAt ?? '—', b.lastVisitAt ?? '—'),
    row('归属咨询师', a.ownerStaffId ?? '—', b.ownerStaffId ?? '—'),
  ]
})

// 合并确认
const confirm = ref<{ show: boolean; action: () => void; title: string; text: string } | null>(null)
function askMerge() {
  if (!selectedLink.value || !customerA.value || !customerB.value) return
  const master = keepSide.value === 'A' ? customerA.value : customerB.value
  const merged = keepSide.value === 'A' ? customerB.value : customerA.value
  confirm.value = {
    show: true,
    title: '确认合并客户',
    text: `将保留「${master.name}（${master.id}）」为主档案，「${merged.name}（${merged.id}）」将作废并留痕。此操作不可撤销。`,
    action: () => {
      const reasons = selectedLink.value!.matchReason.map((r) => reasonLabel[r]).join('、')
      const result = store.proposeMerge(
        master.id,
        [merged.id],
        `系统匹配：${reasons}（置信度 ${Math.round(selectedLink.value!.score * 100)}%），人工确认合并`,
        selectedLink.value!.matchReason,
      )
      if (result) {
        store.dismissLink(selectedLink.value!.id)
        keepSide.value = 'A'
        selectedLinkId.value = null
      }
    },
  }
}
function runConfirm() {
  confirm.value?.action()
  confirm.value = null
}
function dismiss() {
  if (!selectedLink.value) return
  store.dismissLink(selectedLink.value.id)
  keepSide.value = 'A'
  selectedLinkId.value = null
}
</script>

<template>
  <div class="mv">
    <!-- 页头 -->
    <div class="mv__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="mv__body">
      <!-- 左：重复对列表 -->
      <CCard class="mv__list" padding="none">
        <div class="list-head">
          <h3 class="list-title">疑似重复客户对 · 待确认 <span class="list-count">{{ links.length }} 对</span></h3>
          <div class="list-head__right">
            <span class="list-sort">按匹配度排序 <CIcon name="chevron-down" :size="12" /></span>
            <CButton variant="primary" size="sm" v-perm.disable="'customer:merge'">
              <CIcon name="plus" :size="14" />手动合并
            </CButton>
          </div>
        </div>
        <div class="list">
          <div v-if="links.length === 0" class="empty">
            <CIcon name="check" :size="28" class="empty__icon" />
            <div>暂无疑似重复对</div>
          </div>
          <button v-for="l in links" :key="l.id"
                  class="pair" :class="{ 'pair--active': selectedLink?.id === l.id }"
                  @click="selectedLinkId = l.id; keepSide = 'A'">
            <div class="pair__cols">
              <div class="pair__side">
                <div class="pair__name">
                  {{ store.nameOf(l.customerIdA) }} · {{ store.customers.find(c => c.id === l.customerIdA)?.phoneMask }}
                </div>
                <div class="pair__sub">
                  {{ levelText[store.customers.find(c => c.id === l.customerIdA)?.level ?? 'NEW'] }}
                  · 消费 ¥{{ (store.customers.find(c => c.id === l.customerIdA)?.totalSpend ?? 0).toLocaleString() }}
                  · 最后到店 {{ store.customers.find(c => c.id === l.customerIdA)?.lastVisitAt ?? '—' }}
                </div>
              </div>
              <div class="pair__mid">
                <div class="pair__score" :style="{ color: scoreColor(l.score) }">{{ Math.round(l.score * 100) }}%</div>
                <div class="pair__reason">{{ l.matchReason.map(r => reasonLabel[r]).join('+') }}</div>
              </div>
              <div class="pair__side pair__side--right">
                <div class="pair__name">
                  {{ store.nameOf(l.customerIdB) }} · {{ store.customers.find(c => c.id === l.customerIdB)?.phoneMask }}
                </div>
                <div class="pair__sub">
                  {{ levelText[store.customers.find(c => c.id === l.customerIdB)?.level ?? 'NEW'] }}
                  · 消费 ¥{{ (store.customers.find(c => c.id === l.customerIdB)?.totalSpend ?? 0).toLocaleString() }}
                  · 注册 {{ store.customers.find(c => c.id === l.customerIdB)?.registerDate ?? '—' }}
                </div>
              </div>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：对比 -->
      <CCard v-if="selectedLink && customerA && customerB" class="mv__detail" padding="lg">
        <template #header>
          <h3 class="mv__card-title">字段对比 · 确认合并</h3>
          <CStatusPill :status="scoreTone(selectedLink.score)">
            置信度 {{ Math.round(selectedLink.score * 100) }}%
          </CStatusPill>
        </template>

        <div class="compare">
          <div class="compare__head">
            <div class="compare__col-label">字段</div>
            <label class="compare__choice" :class="{ 'is-pick': keepSide === 'A' }">
              <input type="radio" v-model="keepSide" value="A" />
              <span>保留 A：{{ customerA.name }}</span>
            </label>
            <label class="compare__choice" :class="{ 'is-pick': keepSide === 'B' }">
              <input type="radio" v-model="keepSide" value="B" />
              <span>保留 B：{{ customerB.name }}</span>
            </label>
          </div>

          <div v-for="row in compareRows" :key="row.label"
               class="compare__row" :class="{ 'is-conflict': row.conflict }">
            <div class="compare__field">{{ row.label }}</div>
            <div class="compare__val" :class="{ 'is-keep': keepSide === 'A', 'is-conflict': row.conflict }">
              {{ row.a }}
            </div>
            <div class="compare__val" :class="{ 'is-keep': keepSide === 'B', 'is-conflict': row.conflict }">
              {{ row.b }}
            </div>
          </div>
        </div>

        <div class="mv__ops">
          <CButton variant="ghost" @click="dismiss">
            <CIcon name="close" :size="14" />标记非重复
          </CButton>
          <CButton variant="primary" v-perm.disable="'customer:merge'" @click="askMerge">
            <CIcon name="check" :size="14" />确认合并
          </CButton>
        </div>

        <div v-if="store.merges.length > 0" class="merges-log">
          <div class="merges-log__title">最近合并留痕</div>
          <div v-for="m in store.merges.slice(0, 3)" :key="m.id" class="merge-row">
            <CIcon name="check" :size="12" class="merge-row__icon" />
            <span><strong>{{ store.nameOf(m.masterId) }}</strong> 合并了 {{ m.mergedIds.map(id => store.nameOf(id)).join('、') }}</span>
            <span class="merge-row__reason">{{ m.reason }}</span>
          </div>
        </div>
      </CCard>

      <CCard v-else class="mv__detail mv__detail--empty" padding="lg">
        <div class="empty-detail">
          <CIcon name="customer" :size="40" class="empty-detail__icon" />
          <p>请选择左侧一条疑似重复对进行比对</p>
        </div>
      </CCard>
    </div>

    <!-- 确认弹层 -->
    <div v-if="confirm?.show" class="modal-mask" @click.self="confirm = null">
      <CCard class="modal" :title="confirm.title" padding="lg">
        <p class="confirm__text">{{ confirm.text }}</p>
        <template #footer>
          <CButton variant="ghost" @click="confirm = null">取消</CButton>
          <CButton variant="primary" @click="runConfirm">确认合并</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.mv { display: flex; flex-direction: column; gap: var(--s-lg); }
.mv__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .mv__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.mv__body { display: grid; grid-template-columns: 1fr 520px; gap: var(--s-lg); align-items: start; }

/* 左列表 */
.list-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.list-head__right { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }
.list-title { font-size: var(--t-md); font-weight: 700; margin: 0; display: flex; align-items: center; gap: var(--s-sm); }
.list-count { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.list-sort { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-teal-fg); background: none; border: none; cursor: pointer; }
.list { max-height: 640px; overflow-y: auto; }

.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-teal); }

.pair { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; }
.pair:hover { background: var(--c-brand-soft); }
.pair--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.pair__cols { display: grid; grid-template-columns: 1fr 90px 1fr; gap: var(--s-sm); align-items: center; }
.pair__side { min-width: 0; }
.pair__side--right { text-align: right; }
.pair__name { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pair__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.pair__mid { text-align: center; }
.pair__score { font-size: var(--t-xl); font-weight: 700; font-variant-numeric: tabular-nums; }
.pair__reason { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

/* 对比 */
.mv__card-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.compare { display: flex; flex-direction: column; }
.compare__head { display: grid; grid-template-columns: 110px 1fr 1fr; gap: var(--s-sm); padding: var(--s-sm) 0; border-bottom: 2px solid var(--c-border); align-items: center; }
.compare__col-label { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; }
.compare__choice { display: inline-flex; align-items: center; gap: 6px; font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); }
.compare__choice input { accent-color: var(--c-brand); }
.compare__choice.is-pick { background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }

.compare__row { display: grid; grid-template-columns: 110px 1fr 1fr; gap: var(--s-sm); padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); align-items: center; }
.compare__field { font-size: var(--t-xs); color: var(--c-text-3); }
.compare__val { font-size: var(--t-sm); color: var(--c-text); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); }
.compare__val.is-conflict { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.compare__val.is-keep { background: var(--c-success-bg); color: var(--c-teal-fg); font-weight: 600; box-shadow: inset 2px 0 0 var(--c-teal); }

.mv__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }

.merges-log { margin-top: var(--s-lg); padding-top: var(--s-md); border-top: 1px dashed var(--c-border); }
.merges-log__title { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-sm); }
.merge-row { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-2); padding: 4px 0; }
.merge-row__icon { color: var(--c-teal); }
.merge-row strong { color: var(--c-text); font-weight: 700; }
.merge-row__reason { color: var(--c-text-3); margin-left: auto; }

.empty-detail { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl); color: var(--c-text-3); }
.empty-detail__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 440px; max-width: 100%; box-shadow: var(--shadow-pop); }
.confirm__text { font-size: var(--t-sm); color: var(--c-text); line-height: var(--lh-md); margin: 0; }

@media (max-width: 1024px) {
  .mv__body { grid-template-columns: 1fr; }
  .mv__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .pair__cols { grid-template-columns: 1fr; gap: var(--s-xs); }
  .pair__mid { text-align: left; }
  .pair__side--right { text-align: left; }
  .compare__head, .compare__row { grid-template-columns: 90px 1fr 1fr; }
}
</style>
