<script setup lang="ts">
/* ============================================================
 * 价目表管理 /m2-pricelist（M2-14）
 * 4 KPI（项目总数/启用中/调价审批中/停用）
 * 左：项目列表（筛选+搜索）；右：详情 + 调价审批弹层
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { usePricelistStore, type PriceItem, type PriceStatus } from '@/stores/pricelist'

const store = usePricelistStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<PriceItem | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '项目总数', icon: 'package', value: String(store.items.length), tone: 'text' as const },
  { label: '启用中', icon: 'settings', value: String(store.active.length), tone: 'success' as const },
  { label: '调价审批中', icon: 'check-square', value: String(store.pending.length), tone: store.pending.length ? ('warning' as const) : ('text' as const) },
  { label: '停用', icon: 'settings', value: String(store.disabled.length), tone: 'danger' as const },
])

const categoryOptions = [
  { value: 'ALL', label: '全部分类' },
  { value: 'INJECTION', label: '注射美容' },
  { value: 'LASER', label: '光电仪器' },
  { value: 'SKINCARE', label: '皮肤管理' },
  { value: 'BODY', label: '形体管理' },
  { value: 'EXAM', label: '检测咨询' },
]
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'ACTIVE', label: '启用' },
  { value: 'PENDING', label: '待审批' },
  { value: 'DISABLED', label: '停用' },
]

function money(n: number) { return n === 0 ? '免费' : `¥${n.toLocaleString('zh-CN')}` }
function fmtDateTime(iso?: string) { return iso ? iso.replace('T', ' ').slice(0, 16) : '—' }

// 调价弹层
const showPrice = ref(false)
const priceForm = ref({ memberPrice: 0, promoPrice: 0, hasPromo: false, reason: '' })
function openPriceForm() {
  if (!selected.value) return
  priceForm.value = {
    memberPrice: selected.value.memberPrice,
    promoPrice: selected.value.promoPrice ?? 0,
    hasPromo: selected.value.promoPrice != null,
    reason: '',
  }
  showPrice.value = true
}
function submitPrice() {
  if (!selected.value) return
  const ok = store.requestPriceChange(selected.value.id, {
    memberPrice: Number(priceForm.value.memberPrice) || 0,
    promoPrice: priceForm.value.hasPromo ? Number(priceForm.value.promoPrice) || 0 : null,
    reason: priceForm.value.reason.trim(),
  })
  if (ok) showPrice.value = false
}

// 审批确认
const confirmBox = ref<{ show: boolean; title: string; text: string; action: () => void } | null>(null)
function ask(title: string, text: string, action: () => void) {
  confirmBox.value = { show: true, title, text, action }
}
function runConfirm() {
  confirmBox.value?.action()
  confirmBox.value = null
}
function doApprove() {
  if (!selected.value) return
  ask('确认通过调价', `审批通过后，新价格将立即生效并对客展示。`, () => {
    if (selected.value) store.approvePriceChange(selected.value.id)
  })
}
function doReject() {
  if (!selected.value) return
  ask('确认驳回调价', '驳回后项目将恢复原价，调价申请作废。', () => {
    if (selected.value) store.rejectPriceChange(selected.value.id)
  })
}
function doToggle() {
  if (!selected.value) return
  const will = selected.value.status === 'ACTIVE' ? '停用' : '启用'
  ask(`确认${will}项目`, `${will}后该项目${will === '停用' ? '将不在前台展示和下单' : '将恢复对客展示'}。`, () => {
    if (selected.value) store.toggleStatus(selected.value.id)
  })
}

function statusPill(s: PriceStatus) { return store.STATUS_PILL[s] }
</script>

<template>
  <div class="pl">
    <div class="pl__head">
      <div class="pl__kpis">
        <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
      </div>
    </div>

    <div class="pl__body">
      <!-- 左：项目列表 -->
      <CCard class="pl__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterCategory" :options="categoryOptions" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" />
        </div>
        <div class="search">
          <CInput v-model="store.keyword" placeholder="搜索项目名称 / 编码" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="order" :size="28" class="empty__icon" />
            <div>暂无项目</div>
          </div>
          <button
            v-for="it in store.filtered" :key="it.id"
            class="row" :class="{ 'row--active': selected?.id === it.id }"
            @click="selectedId = it.id"
          >
            <div class="row__top">
              <span class="row__code">{{ it.code }}</span>
              <CStatusPill :status="statusPill(it.status)" dot>{{ store.STATUS_LABEL[it.status] }}</CStatusPill>
            </div>
            <div class="row__name">{{ it.name }}</div>
            <div class="row__meta">
              <span class="row__cat">{{ store.CATEGORY_LABEL[it.category] }}</span>
              <span class="row__price">{{ money(it.memberPrice) }}</span>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="pl__detail" padding="none">
        <template #header>
          <div class="pl__detail-head">
            <div>
              <h3 class="pl__name">{{ selected.name }}</h3>
              <div class="pl__sub">
                <span class="pl__code">{{ selected.code }}</span>
                <span>{{ store.CATEGORY_LABEL[selected.category] }}</span>
                <span>{{ selected.duration }} 分钟 / {{ selected.unit }}</span>
              </div>
            </div>
            <CStatusPill :status="statusPill(selected.status)" dot>{{ store.STATUS_LABEL[selected.status] }}</CStatusPill>
          </div>
        </template>

        <div class="detail-body">
          <!-- 价格区 -->
          <div class="price-grid">
            <div class="price">
              <div class="price__label">原价</div>
              <div class="price__value price__value--del">{{ money(selected.originalPrice) }}</div>
            </div>
            <div class="price">
              <div class="price__label">会员价</div>
              <div class="price__value price__value--brand">{{ money(selected.memberPrice) }}</div>
            </div>
            <div class="price">
              <div class="price__label">活动价</div>
              <div class="price__value price__value--orange">{{ selected.promoPrice != null ? money(selected.promoPrice) : '—' }}</div>
            </div>
          </div>

          <!-- 调价审批中 -->
          <div v-if="selected.status === 'PENDING' && selected.pendingPrice" class="pending">
            <div class="pending__title">
              <CIcon name="bell" :size="14" /> 调价审批中
            </div>
            <div class="pending__grid">
              <div class="pending__item">
                <span class="pending__label">申请会员价</span>
                <span class="pending__value">{{ money(selected.pendingPrice.memberPrice) }}</span>
              </div>
              <div class="pending__item">
                <span class="pending__label">申请活动价</span>
                <span class="pending__value">{{ selected.pendingPrice.promoPrice != null ? money(selected.pendingPrice.promoPrice) : '不设活动价' }}</span>
              </div>
              <div class="pending__item">
                <span class="pending__label">申请人</span>
                <span class="pending__value">{{ selected.pendingPrice.requestedBy }}</span>
              </div>
              <div class="pending__item">
                <span class="pending__label">申请时间</span>
                <span class="pending__value">{{ fmtDateTime(selected.pendingPrice.requestedAt) }}</span>
              </div>
            </div>
            <div class="pending__reason">
              <span class="pending__label">调价原因：</span>{{ selected.pendingPrice.reason }}
            </div>
            <div class="pending__ops">
              <CButton variant="ghost" v-perm.disable="'pricelist:edit'" @click="doReject">驳回</CButton>
              <CButton variant="primary" v-perm.disable="'pricelist:edit'" @click="doApprove">
                <CIcon name="check" :size="16" />审批通过
              </CButton>
            </div>
          </div>

          <!-- 基本信息 -->
          <div class="info-grid">
            <div class="field"><span class="field__label">项目编码</span><span class="field__val">{{ selected.code }}</span></div>
            <div class="field"><span class="field__label">服务分类</span><span class="field__val">{{ store.CATEGORY_LABEL[selected.category] }}</span></div>
            <div class="field"><span class="field__label">服务时长</span><span class="field__val">{{ selected.duration }} 分钟</span></div>
            <div class="field"><span class="field__label">计价单位</span><span class="field__val">{{ selected.unit }}</span></div>
            <div class="field"><span class="field__label">最后更新</span><span class="field__val">{{ fmtDateTime(selected.updatedAt) }}</span></div>
            <div class="field"><span class="field__label">更新人</span><span class="field__val">{{ selected.updatedBy }}</span></div>
          </div>

          <!-- 操作区 -->
          <div class="ops">
            <CButton
              v-if="selected.status !== 'PENDING'"
              variant="ghost"
              v-perm.disable="'pricelist:edit'"
              @click="doToggle"
            >
              <CIcon :name="selected.status === 'ACTIVE' ? 'close' : 'check'" :size="16" />
              {{ selected.status === 'ACTIVE' ? '停用项目' : '启用项目' }}
            </CButton>
            <CButton
              v-if="selected.status === 'ACTIVE'"
              variant="primary"
              v-perm.disable="'pricelist:edit'"
              @click="openPriceForm"
            >
              <CIcon name="edit" :size="16" />申请调价
            </CButton>
            <span v-if="selected.status === 'PENDING'" class="ops__hint">调价审批中，暂不可再次申请</span>
            <span v-else-if="selected.status === 'DISABLED'" class="ops__hint">项目已停用，启用后可申请调价</span>
          </div>
        </div>
      </CCard>

      <CCard v-else class="pl__detail pl__detail--empty" title="项目详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="order" :size="40" class="detail-empty__icon" />
          <p>请选择一个项目</p>
        </div>
      </CCard>
    </div>

    <!-- 调价弹层 -->
    <div v-if="showPrice" class="modal-mask" @click.self="showPrice = false">
      <CCard class="modal" title="申请调价" padding="lg">
        <p class="modal__tip">调价需经审批通过后生效。审批期间项目仍按原价执行。</p>
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">新会员价（元）</label>
              <CInput :model-value="String(priceForm.memberPrice)"
                @update:model-value="priceForm.memberPrice = Number($event) || 0" placeholder="0" />
            </div>
            <div>
              <label class="form__label">新活动价（元）</label>
              <CInput :model-value="priceForm.hasPromo ? String(priceForm.promoPrice) : ''"
                :disabled="!priceForm.hasPromo"
                @update:model-value="priceForm.promoPrice = Number($event) || 0" placeholder="不设活动价" />
            </div>
          </div>
          <label class="form__check">
            <input type="checkbox" v-model="priceForm.hasPromo" />
            <span>设置活动价</span>
          </label>
          <div class="form__row">
            <label class="form__label">调价原因</label>
            <CInput v-model="priceForm.reason" placeholder="如：配合暑期促销活动 / 供应商调价" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showPrice = false">取消</CButton>
          <CButton variant="primary" :disabled="!priceForm.reason.trim()" @click="submitPrice">提交审批</CButton>
        </template>
      </CCard>
    </div>

    <!-- 确认弹层 -->
    <div v-if="confirmBox?.show" class="modal-mask" @click.self="confirmBox = null">
      <CCard class="modal modal--sm" :title="confirmBox.title" padding="lg">
        <p class="confirm__text">{{ confirmBox.text }}</p>
        <template #footer>
          <CButton variant="ghost" @click="confirmBox = null">取消</CButton>
          <CButton variant="primary" @click="runConfirm">确认</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.pl { display: flex; flex-direction: column; gap: var(--s-lg); }
.pl__head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); flex-wrap: wrap; }
.pl__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); flex: 1; min-width: 480px; }
:deep(.ckpi) { min-width: 0; }

.pl__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.pl__list { min-width: 0; }
.filters { display: flex; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.search { padding: 0 var(--s-md) var(--s-sm); }
.list { max-height: 560px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__code { font-size: var(--t-xs); color: var(--c-text-3); font-family: var(--f-latin); }
.row__name { font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__meta { display: flex; justify-content: space-between; align-items: center; font-size: var(--t-xs); }
.row__cat { color: var(--c-text-3); }
.row__price { font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }

.pl__detail-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); width: 100%; }
.pl__name { font-size: var(--t-lg); font-weight: 700; margin: 0; }
.pl__sub { display: flex; flex-wrap: wrap; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.pl__code { font-family: var(--f-latin); }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }

.price-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.price { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); text-align: center; }
.price__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.price__value { font-size: var(--t-lg); font-weight: 700; font-variant-numeric: tabular-nums; }
.price__value--del { color: var(--c-text-4); text-decoration: line-through; font-size: var(--t-md); }
.price__value--brand { color: var(--c-brand); }
.price__value--orange { color: var(--c-warning-fg); }

.pending { background: var(--c-warning-bg); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.pending__title { display: inline-flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-warning-fg); }
.pending__grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--s-sm); }
.pending__item { display: flex; justify-content: space-between; font-size: var(--t-sm); }
.pending__label { color: var(--c-text-3); }
.pending__value { color: var(--c-text); font-weight: 600; font-variant-numeric: tabular-nums; }
.pending__reason { font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); }
.pending__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-xs); }

.info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }

.ops { display: flex; justify-content: flex-end; align-items: center; gap: var(--s-sm); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__hint { font-size: var(--t-sm); color: var(--c-text-3); margin-right: auto; }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 480px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--sm { width: 360px; }
.modal__tip { font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-bg-right); padding: var(--s-sm); border-radius: var(--r-sm); margin: 0 0 var(--s-md); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.form__check { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; }
.confirm__text { font-size: var(--t-sm); color: var(--c-text); text-align: center; margin: var(--s-md) 0; line-height: var(--lh-md); }

@media (max-width: 1024px) {
  .pl__body { grid-template-columns: 1fr; }
  .pl__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .price-grid { grid-template-columns: repeat(3, 1fr); }
  .list { max-height: 320px; }
}
</style>
