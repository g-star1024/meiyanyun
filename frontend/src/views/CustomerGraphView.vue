<script setup lang="ts">
/* ============================================================
 * 客户图谱与撞单消解 /customer-graph（Desktop 优先 · 平板堆叠）
 * 系统只产生疑似重复关联(CustomerLink)，不自动合并；
 * 合并需 customer:merge 权限，执行后留痕(CustomerMerge)。
 * 对齐 docs/domain-model.md §2.2、business-flows.md §2.10。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import { useCustomerStore } from '@/stores/customer'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CTextarea from '@/components/CTextarea.vue'
import type { Customer, CustomerLink } from '@/types/domain'

const customer = useCustomerStore()
onMounted(() => customer.seedGraph())

// 疑似重复关联（排除已被合并作废的客户）
const activeLinks = computed(() =>
  customer.links.filter((l) => {
    const a = customer.customers.find((c) => c.id === l.customerIdA)
    const b = customer.customers.find((c) => c.id === l.customerIdB)
    return a && b && !a.masterId && !b.masterId
  }),
)

const REASON_LABEL: Record<string, string> = {
  PHONE: '同手机号',
  DEVICE: '同设备',
  IDCARD: '同证件号',
  NAME_BIRTHDAY: '同姓名+生日',
}

function cust(id: string): Customer | undefined {
  return customer.customers.find((c) => c.id === id)
}

const kpis = computed(() => [
  { label: '疑似重复', icon: 'customer', value: String(activeLinks.value.length), tone: (activeLinks.value.length ? 'warning' : 'text') as 'warning' | 'text' },
  { label: '已合并', icon: 'customer', value: String(customer.merges.length), tone: 'success' as const },
  { label: '有效客户', icon: 'customer', value: String(customer.customers.filter((c) => !c.masterId).length), tone: 'brand' as const },
])

// ---- 合并操作 ----
const mergeTarget = ref<CustomerLink | null>(null)
const masterChoice = ref<'A' | 'B'>('A')
const mergeReason = ref('')

function openMerge(link: CustomerLink) {
  mergeTarget.value = link
  masterChoice.value = 'A'
  mergeReason.value = ''
}
function closeMerge() {
  mergeTarget.value = null
}
function doMerge() {
  if (!mergeTarget.value || !mergeReason.value.trim()) return
  const l = mergeTarget.value
  const masterId = masterChoice.value === 'A' ? l.customerIdA : l.customerIdB
  const mergedIds = [masterChoice.value === 'A' ? l.customerIdB : l.customerIdA]
  customer.proposeMerge(masterId, mergedIds, mergeReason.value.trim(), l.matchReason)
  closeMerge()
}

// ---- 忽略（从关联列表移除，不做数据变更；演示期仅前端隐藏） ----
const dismissed = ref<Set<string>>(new Set())
function dismiss(id: string) {
  dismissed.value.add(id)
}
const visibleLinks = computed(() => activeLinks.value.filter((l) => !dismissed.value.has(l.id)))

function fmtDate(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<template>
  <div class="cg-page">
    <div class="cg-kpis">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="cg-tip">
      <CIcon name="alert" :size="16" />
      <span>
        系统基于同手机号/设备/证件等特征<strong>自动识别疑似重复，但不会自动合并</strong>。
        合并需人工核对并由具备 <code>customer:merge</code> 权限的人员执行，全程留痕可追溯。
      </span>
    </div>

    <div class="cg-body">
      <!-- 左：疑似重复 -->
      <CCard class="cg-main" padding="none">
        <template #header>
          <h3 class="cg-col-title">疑似重复关联</h3>
          <span class="cg-col-count">{{ visibleLinks.length }} 组</span>
        </template>
        <div class="cg-list">
          <div v-if="!visibleLinks.length" class="cg-empty">
            <CIcon name="check-square" :size="36" class="cg-empty__icon" />
            <p>暂无疑似重复客户</p>
          </div>

          <div v-for="link in visibleLinks" :key="link.id" class="dup">
            <!-- 双客户对比 -->
            <div class="dup__pair">
              <div class="dup__cust" :class="{ 'dup__cust--master': mergeTarget?.id === link.id && masterChoice === 'A' }">
                <div class="dup__avatar">{{ cust(link.customerIdA)?.avatarLetter }}</div>
                <div class="dup__info">
                  <div class="dup__name">{{ cust(link.customerIdA)?.name }}</div>
                  <div class="dup__sub">{{ cust(link.customerIdA)?.phoneMask }}</div>
                  <div class="dup__sub">{{ cust(link.customerIdA)?.channel === 'WALK_IN' ? '自然到店' : cust(link.customerIdA)?.channel === 'REFERRAL' ? '转介绍' : cust(link.customerIdA)?.channel === 'ONLINE_APPT' ? '线上预约' : '营销' }} · {{ cust(link.customerIdA)?.id }}</div>
                </div>
              </div>

              <div class="dup__center">
                <div class="dup__score">{{ Math.round(link.score * 100) }}%</div>
                <CIcon name="scan" :size="20" class="dup__link-icon" />
                <div class="dup__reasons">
                  <CStatusPill v-for="r in link.matchReason" :key="r" status="warning">{{ REASON_LABEL[r] || r }}</CStatusPill>
                </div>
              </div>

              <div class="dup__cust" :class="{ 'dup__cust--master': mergeTarget?.id === link.id && masterChoice === 'B' }">
                <div class="dup__avatar">{{ cust(link.customerIdB)?.avatarLetter }}</div>
                <div class="dup__info">
                  <div class="dup__name">{{ cust(link.customerIdB)?.name }}</div>
                  <div class="dup__sub">{{ cust(link.customerIdB)?.phoneMask }}</div>
                  <div class="dup__sub">{{ cust(link.customerIdB)?.channel === 'WALK_IN' ? '自然到店' : cust(link.customerIdB)?.channel === 'REFERRAL' ? '转介绍' : cust(link.customerIdB)?.channel === 'ONLINE_APPT' ? '线上预约' : '营销' }} · {{ cust(link.customerIdB)?.id }}</div>
                </div>
              </div>
            </div>

            <div class="dup__ops">
              <CButton variant="ghost" size="sm" @click="dismiss(link.id)">忽略</CButton>
              <CButton variant="primary" size="sm" v-perm.disable="'customer:merge'" @click="openMerge(link)">
                <CIcon name="check" :size="14" />核对并合并
              </CButton>
            </div>
          </div>
        </div>
      </CCard>

      <!-- 右：合并历史 -->
      <CCard class="cg-side" padding="none">
        <template #header>
          <h3 class="cg-col-title">合并留痕</h3>
          <span class="cg-col-count">{{ customer.merges.length }}</span>
        </template>
        <div class="cg-history">
          <div v-if="!customer.merges.length" class="cg-empty cg-empty--sm">
            <p>暂无合并记录</p>
          </div>
          <div v-for="m in customer.merges" :key="m.id" class="hist">
            <div class="hist__top">
              <CIcon name="check-square" :size="16" class="hist__icon" />
              <span class="hist__ids">{{ m.mergedIds.join(', ') }} → {{ m.masterId }}</span>
            </div>
            <div class="hist__reason">{{ m.reason }}</div>
            <div class="hist__meta">{{ m.approvedBy }} · {{ fmtDate(m.executedAt) }}</div>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 合并确认弹层 -->
    <div v-if="mergeTarget" class="mask" @click.self="closeMerge">
      <CCard class="dlg" title="核对并合并客户" padding="lg">
        <p class="dlg__intro">请选择保留的主客户，另一条将被作废（数据归档至主客户，可追溯）。</p>
        <div class="dlg__choice">
          <label class="choice" :class="{ 'choice--on': masterChoice === 'A' }">
            <input type="radio" v-model="masterChoice" value="A" />
            <div class="choice__main">
              <div class="choice__name">{{ cust(mergeTarget.customerIdA)?.name }}</div>
              <div class="choice__sub">{{ cust(mergeTarget.customerIdA)?.phoneMask }} · {{ mergeTarget.customerIdA }}</div>
            </div>
          </label>
          <label class="choice" :class="{ 'choice--on': masterChoice === 'B' }">
            <input type="radio" v-model="masterChoice" value="B" />
            <div class="choice__main">
              <div class="choice__name">{{ cust(mergeTarget.customerIdB)?.name }}</div>
              <div class="choice__sub">{{ cust(mergeTarget.customerIdB)?.phoneMask }} · {{ mergeTarget.customerIdB }}</div>
            </div>
          </label>
        </div>
        <div class="dlg__reason">
          <label class="dlg__label">合并原因（必填）</label>
          <CTextarea v-model="mergeReason" placeholder="请说明核对依据，如：确认为同一人同手机号，重复建档" />
        </div>
        <template #footer>
          <CButton variant="ghost" @click="closeMerge">取消</CButton>
          <CButton variant="primary" :disabled="!mergeReason.trim()" @click="doMerge">
            <CIcon name="check" :size="14" />确认合并
          </CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.cg-page { display: flex; flex-direction: column; gap: var(--s-lg); }
.cg-kpis { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }

.cg-tip {
  display: flex; align-items: flex-start; gap: var(--s-sm);
  padding: var(--s-md); background: var(--c-brand-soft);
  border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6;
}
.cg-tip :deep(svg) { color: var(--c-warning-fg); flex-shrink: 0; margin-top: 2px; }
.cg-tip strong { color: var(--c-text); }
.cg-tip code { background: rgba(0,0,0,0.06); padding: 1px 6px; border-radius: 4px; font-size: var(--t-xs); }

.cg-body { display: grid; grid-template-columns: 1fr 320px; gap: var(--s-lg); align-items: start; }
.cg-col-title { font-size: var(--t-base); font-weight: 700; color: var(--c-text); margin: 0; }
.cg-col-count { font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-bg-page); padding: 2px 10px; border-radius: 999px; }

.cg-list { display: flex; flex-direction: column; max-height: 600px; overflow-y: auto; }
.cg-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl); color: var(--c-text-3); }
.cg-empty--sm { padding: var(--s-xl); }
.cg-empty__icon { color: var(--c-success-fg); }
.cg-empty p { margin: 0; font-size: var(--t-sm); }

.dup { padding: var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.dup:last-child { border-bottom: none; }
.dup__pair { display: grid; grid-template-columns: 1fr auto 1fr; gap: var(--s-md); align-items: center; }
.dup__cust { display: flex; gap: var(--s-sm); align-items: center; padding: var(--s-md); border: 2px solid transparent; border-radius: var(--r-md); transition: all 0.15s; }
.dup__cust--master { border-color: var(--c-brand); background: var(--c-brand-soft); }
.dup__avatar {
  width: 44px; height: 44px; border-radius: 50%;
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; font-weight: 600; flex-shrink: 0;
}
.dup__cust--master .dup__avatar { background: var(--c-brand); color: #fff; }
.dup__info { min-width: 0; }
.dup__name { font-size: var(--t-base); font-weight: 600; color: var(--c-text); }
.dup__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.dup__center { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 0 var(--s-xs); }
.dup__score { font-size: var(--t-xl); font-weight: 700; color: var(--c-warning-fg); font-variant-numeric: tabular-nums; }
.dup__link-icon { color: var(--c-text-4); }
.dup__reasons { display: flex; flex-direction: column; gap: 4px; align-items: center; margin-top: 4px; }
.dup__ops { display: flex; justify-content: flex-end; gap: var(--s-xs); margin-top: var(--s-md); }

/* 合并历史 */
.cg-history { max-height: 600px; overflow-y: auto; }
.hist { padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.hist:last-child { border-bottom: none; }
.hist__top { display: flex; align-items: center; gap: var(--s-xs); margin-bottom: 4px; }
.hist__icon { color: var(--c-success-fg); }
.hist__ids { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); font-variant-numeric: tabular-nums; }
.hist__reason { font-size: var(--t-xs); color: var(--c-text-2); margin-bottom: 2px; line-height: 1.5; }
.hist__meta { font-size: 11px; color: var(--c-text-4); }

/* 弹层 */
.mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.dlg { width: 520px; max-width: 100%; box-shadow: var(--shadow-pop); }
.dlg__intro { margin: 0 0 var(--s-md); font-size: var(--t-sm); color: var(--c-text-3); line-height: 1.6; }
.dlg__choice { display: flex; flex-direction: column; gap: var(--s-sm); margin-bottom: var(--s-md); }
.choice {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-md); border: 1px solid var(--c-border); border-radius: var(--r-md);
  cursor: pointer;
}
.choice:hover { border-color: var(--c-brand); }
.choice--on { border-color: var(--c-brand); background: var(--c-brand-soft); }
.choice input { accent-color: var(--c-brand); }
.choice__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.choice__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.dlg__reason { display: flex; flex-direction: column; gap: var(--s-xs); }
.dlg__label { font-size: var(--t-xs); color: var(--c-text-3); }

@media (max-width: 834px) {
  .cg-kpis { grid-template-columns: repeat(3, 1fr); }
  .cg-body { grid-template-columns: 1fr; }
  .dup__pair { grid-template-columns: 1fr; gap: var(--s-sm); }
  .dup__center { flex-direction: row; justify-content: center; padding: var(--s-xs) 0; }
  .dup__reasons { flex-direction: row; margin-top: 0; }
}
</style>
