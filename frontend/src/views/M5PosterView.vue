<script setup lang="ts">
/* ============================================================
 * M5-04 裂变海报 /m5-poster
 * 4 KPI（累计分享/扫码人数/成交订单/裂变佣金）
 * 左：海报模板列表；右：预览 + 分销绑定 + 漏斗 + 佣金试算
 * 主按钮「生成海报」：选模板/标题/副标题/项目/推荐人，提交前敏感词校验
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import { useM5PosterStore } from '@/stores/m5Poster'
import { useAuthStore } from '@/stores/auth'
import { checkSensitive } from '@/composables/useSensitiveWords'

const store = useM5PosterStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const canEdit = computed(() => auth.can('poster:edit'))

const selectedId = ref<string | null>(null)
const selected = computed(() => {
  if (selectedId.value) return store.get(selectedId.value)
  return store.filteredTemplates[0] ?? null
})

// 该模板下最近一张已生成海报（取漏斗/绑定数据）
const latestPoster = computed(() =>
  selected.value ? store.posters.find((p) => p.templateId === selected.value!.id) ?? null : null)

const kpis = computed(() => [
  { label: '累计分享数', icon: 'marketing', value: store.totalShares.toLocaleString('zh-CN'), tone: 'brand' as const },
  { label: '扫码人数', icon: 'scan', value: store.totalScans.toLocaleString('zh-CN'), tone: 'teal' as const },
  { label: '成交订单', icon: 'order', value: `${store.totalDeals} 单`, tone: 'orange' as const },
  { label: '裂变佣金', icon: 'marketing', value: money(store.totalCommission), tone: 'success' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'ENABLED', label: '启用中' },
  { value: 'DISABLED', label: '已停用' },
]

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN')}`
}

const ACCENT_BG: Record<string, string> = {
  brand: 'linear-gradient(135deg, var(--c-brand), var(--c-brand-press))',
  teal: 'linear-gradient(135deg, var(--c-teal), var(--c-teal-fg))',
  orange: 'linear-gradient(135deg, var(--c-orange-dark), var(--c-warning-fg))',
  purple: 'linear-gradient(135deg, var(--c-purple), var(--c-draft-fg))',
  blue: 'linear-gradient(135deg, var(--c-blue), var(--c-brand-secondary))',
  gold: 'linear-gradient(135deg, var(--c-gold), var(--c-warning-fg))',
}
const ACCENT_TAG: Record<string, string> = {
  brand: 'var(--c-brand)', teal: 'var(--c-teal-fg)', orange: 'var(--c-orange-dark)',
  purple: 'var(--c-purple)', blue: 'var(--c-blue)', gold: 'var(--c-warning-fg)',
}

// 漏斗
const funnelSteps = computed(() => {
  const f = latestPoster.value?.funnel
  if (!f) return []
  const base = f.share || 1
  return [
    { key: 'share', label: '分享', value: f.share, color: 'var(--c-brand)' },
    { key: 'scan', label: '扫码', value: f.scan, color: 'var(--c-series-2)' },
    { key: 'lead', label: '留资', value: f.lead, color: 'var(--c-series-3)' },
    { key: 'visit', label: '到店', value: f.visit, color: 'var(--c-series-4)' },
    { key: 'deal', label: '成交', value: f.deal, color: 'var(--c-success-fg)' },
  ].map((s) => ({ ...s, pct: Math.round((s.value / base) * 100) }))
})

// 佣金试算
const simulateAmount = ref(0)
const commissionPreview = computed(() =>
  store.simulateCommission(Number(simulateAmount.value) || 0, store.DEFAULT_COMMISSION_RATE))
watch(latestPoster, (p) => {
  simulateAmount.value = p?.dealAmount ?? 0
}, { immediate: true })

// ---------- 生成海报弹层 ----------
const showCreate = ref(false)
const form = ref({
  templateId: '',
  title: '',
  subtitle: '',
  project: '',
  referrerName: '',
})
const formError = ref('')

function openCreate() {
  if (!canEdit.value) return
  form.value = {
    templateId: selected.value?.id ?? store.templates[0]?.id ?? '',
    title: selected.value?.defaultTitle ?? '',
    subtitle: selected.value?.defaultSubtitle ?? '',
    project: '',
    referrerName: store.referrerOptions[0]?.name ?? '',
  }
  formError.value = ''
  showCreate.value = true
}

const templateOptions = computed(() =>
  store.templates
    .filter((t) => t.status === 'ENABLED')
    .map((t) => ({ value: t.id, label: `${t.name}（${store.STYLE_LABEL[t.style]}）` })))
const referrerOptions = computed(() =>
  store.referrerOptions.map((r) => ({ value: r.name, label: `${r.name} · ${r.level} · 已推荐 ${r.total} 人` })))

function submitCreate() {
  formError.value = ''
  if (!form.value.templateId) { formError.value = '请选择海报模板'; return }
  if (!form.value.title.trim()) { formError.value = '请填写海报主标题'; return }
  if (!form.value.project.trim()) { formError.value = '请填写主推项目'; return }
  if (!form.value.referrerName) { formError.value = '请选择绑定推荐人'; return }
  const hit = checkSensitive(`${form.value.title} ${form.value.subtitle}`)
  if (hit.hit) { formError.value = hit.message; return }
  const p = store.createPoster({
    templateId: form.value.templateId,
    title: form.value.title.trim(),
    subtitle: form.value.subtitle.trim(),
    project: form.value.project.trim(),
    referrerName: form.value.referrerName,
  })
  if (p) {
    selectedId.value = p.templateId
    showCreate.value = false
  }
}
</script>

<template>
  <div class="mp">
    <div class="mp__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="mp__body">
      <!-- 左：模板列表 -->
      <CCard class="mp__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterStatus" width="160px" :options="statusOptions" />
          <CButton v-if="canEdit" variant="primary" size="sm" class="filters__btn" @click="openCreate">
            <CIcon name="plus" :size="14" />生成海报
          </CButton>
        </div>
        <div class="list">
          <div v-if="store.filteredTemplates.length === 0" class="empty">
            <CIcon name="marketing" :size="28" class="empty__icon" />
            <div>暂无海报模板</div>
          </div>
          <button
            v-for="t in store.filteredTemplates" :key="t.id"
            class="row" :class="{ 'row--active': selected?.id === t.id }"
            @click="selectedId = t.id"
          >
            <div class="row__cover" :style="{ background: ACCENT_BG[t.accent] }">
              <CIcon name="marketing" :size="18" />
            </div>
            <div class="row__main">
              <div class="row__top">
                <span class="row__name">{{ t.name }}</span>
                <CStatusPill :status="store.TEMPLATE_STATUS_PILL[t.status]" dot>
                  {{ store.TEMPLATE_STATUS_LABEL[t.status] }}
                </CStatusPill>
              </div>
              <div class="row__sub">{{ store.STYLE_LABEL[t.style] }} · 已使用 {{ t.uses }} 次</div>
            </div>
            <CIcon name="chevron-right" :size="16" class="row__caret" />
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="mp__detail" padding="none">
        <template #header>
          <div class="mp__detail-head">
            <div class="mp__detail-title">
              <h3>{{ selected.name }}</h3>
              <div class="mp__detail-sub">
                <span class="tag" :style="{ color: ACCENT_TAG[selected.accent], background: 'var(--c-brand-soft)' }">
                  {{ store.STYLE_LABEL[selected.style] }}
                </span>
                <CStatusPill :status="store.TEMPLATE_STATUS_PILL[selected.status]" dot>
                  {{ store.TEMPLATE_STATUS_LABEL[selected.status] }}
                </CStatusPill>
                <span class="mp__uses">累计使用 {{ selected.uses }} 次</span>
              </div>
            </div>
            <CButton v-if="canEdit" variant="text" size="sm" @click="store.toggleTemplateStatus(selected.id)">
              <CIcon name="settings" :size="14" />
              {{ selected.status === 'ENABLED' ? '停用模板' : '启用模板' }}
            </CButton>
          </div>
        </template>

        <div class="detail-body">
          <!-- 预览 + 绑定 -->
          <div class="preview-grid">
            <div class="poster" :style="{ background: ACCENT_BG[selected.accent] }">
              <div class="poster__bar">
                <span class="poster__logo">美研云 · 限时礼遇</span>
              </div>
              <div class="poster__content">
                <div class="poster__title">{{ latestPoster?.title || selected.defaultTitle }}</div>
                <div class="poster__subtitle">{{ latestPoster?.subtitle || selected.defaultSubtitle }}</div>
                <div v-if="latestPoster?.project" class="poster__project">
                  <CIcon name="package" :size="14" />{{ latestPoster.project }}
                </div>
              </div>
              <div class="poster__foot">
                <div class="poster__qr">
                  <CIcon name="scan" :size="28" />
                </div>
                <div class="poster__foot-text">
                  <div>长按识别 · 立即预约</div>
                  <div class="poster__referrer">推荐人：{{ latestPoster?.referrerName || '—' }}</div>
                </div>
              </div>
            </div>

            <div class="bind">
              <div class="block__title"><span>分销码绑定</span></div>
              <div class="bind__row">
                <span class="bind__label">当前推荐人</span>
                <span class="bind__value">{{ latestPoster?.referrerName || '未绑定' }}</span>
              </div>
              <div class="bind__row">
                <span class="bind__label">奖励比例</span>
                <span class="bind__value">{{ (store.DEFAULT_COMMISSION_RATE * 100).toFixed(0) }}%</span>
              </div>
              <div class="bind__row">
                <span class="bind__label">最近生成</span>
                <span class="bind__value">{{ latestPoster?.createdAt || '—' }}</span>
              </div>
              <div v-if="!latestPoster" class="bind__empty">
                <CIcon name="bell" :size="14" />该模板暂无已生成海报，点击右上角「生成海报」开始裂变
              </div>
            </div>
          </div>

          <!-- 漏斗 -->
          <div class="block">
            <div class="block__title">
              <span>裂变漏斗</span>
              <span v-if="latestPoster" class="block__hint">分享 → 扫码 → 留资 → 到店 → 成交</span>
            </div>
            <div v-if="latestPoster" class="funnel">
              <div v-for="s in funnelSteps" :key="s.key" class="funnel__row">
                <div class="funnel__label">{{ s.label }}</div>
                <div class="funnel__bar">
                  <CProgressBar
                    :value="s.pct" :max="100" :color="s.color"
                    :height="10" :show-label="false"
                  />
                </div>
                <div class="funnel__value">{{ s.value }}</div>
                <div class="funnel__pct">{{ s.pct }}%</div>
              </div>
            </div>
            <div v-else class="block__empty">
              <CIcon name="trend-up" :size="16" />生成海报并产生分享后，漏斗数据将自动汇聚
            </div>
          </div>

          <!-- 佣金试算 -->
          <div class="block">
            <div class="block__title">
              <span>佣金试算</span>
              <span class="block__hint">按成交金额 × {{ (store.DEFAULT_COMMISSION_RATE * 100).toFixed(0) }}% 估算</span>
            </div>
            <div class="commission">
              <div class="commission__result">
                <div class="commission__num">{{ money(commissionPreview) }}</div>
                <div class="commission__label">预估推荐奖励</div>
              </div>
              <div class="commission__sim">
                <label class="sim__label">成交金额（元）</label>
                <CInput
                  :model-value="String(simulateAmount)"
                  @update:model-value="simulateAmount = Number($event) || 0"
                  placeholder="如 6800"
                />
                <div class="sim__tip">
                  <CIcon name="alert" :size="12" />奖励在被推荐人成交后由店长在「老带新」页审核发放
                </div>
              </div>
            </div>
          </div>
        </div>
      </CCard>

      <CCard v-else class="mp__detail mp__detail--empty" title="模板详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="marketing" :size="40" class="detail-empty__icon" />
          <p>请选择一个海报模板</p>
        </div>
      </CCard>
    </div>

    <!-- 生成海报弹层 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <CCard class="modal" title="生成裂变海报" padding="lg">
        <div class="form">
          <label class="form__label">选择模板</label>
          <CSelect v-model="form.templateId" width="100%" :options="templateOptions" />

          <label class="form__label">海报主标题</label>
          <CInput v-model="form.title" placeholder="如：双11 狂欢季 礼遇焕新" />

          <label class="form__label">海报副标题</label>
          <CTextarea v-model="form.subtitle" :rows="2" placeholder="一句话补充利益点" />

          <label class="form__label">主推项目</label>
          <CInput v-model="form.project" placeholder="如：水光嫩肤年卡" />

          <label class="form__label">绑定推荐人（分销码归属）</label>
          <CSelect v-model="form.referrerName" width="100%" :options="referrerOptions" />

          <div v-if="formError" class="form__error">
            <CIcon name="alert" :size="14" />{{ formError }}
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showCreate = false">取消</CButton>
          <CButton variant="primary" @click="submitCreate">生成</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.mp { display: flex; flex-direction: column; gap: var(--s-lg); }
.mp__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .mp__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.mp__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.mp__list { min-width: 0; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.filters__btn { margin-left: auto; }
.list { max-height: 640px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: flex; align-items: center; gap: var(--s-sm); width: 100%; text-align: left;
  padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__cover {
  width: 44px; height: 44px; border-radius: var(--r-md);
  display: flex; align-items: center; justify-content: center; color: #fff; flex-shrink: 0;
}
.row__main { flex: 1; min-width: 0; }
.row__top { display: flex; align-items: center; justify-content: space-between; gap: var(--s-xs); margin-bottom: 4px; }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.row__caret { color: var(--c-text-4); flex-shrink: 0; }

.mp__detail-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); width: 100%; }
.mp__detail-title { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.mp__detail-title h3 { font-size: var(--t-lg); font-weight: 700; }
.mp__detail-sub { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); }
.tag { padding: 2px 8px; border-radius: var(--r-pill); font-size: var(--t-xs); font-weight: 600; }
.mp__uses { color: var(--c-text-3); }

.mp__detail :deep(.card__body) { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }

.preview-grid { display: grid; grid-template-columns: 280px 1fr; gap: var(--s-lg); align-items: stretch; }
.poster {
  border-radius: var(--r-xl); padding: var(--s-lg); color: #fff;
  display: flex; flex-direction: column; gap: var(--s-md); min-height: 360px;
  box-shadow: var(--shadow-pop);
}
.poster__bar { display: flex; justify-content: space-between; align-items: center; font-size: var(--t-xs); opacity: .9; }
.poster__logo { font-weight: 700; letter-spacing: 1px; }
.poster__content { flex: 1; display: flex; flex-direction: column; gap: var(--s-sm); justify-content: center; }
.poster__title { font-size: var(--t-xl); font-weight: 700; line-height: var(--lh-xl); }
.poster__subtitle { font-size: var(--t-sm); line-height: var(--lh-sm); opacity: .95; }
.poster__project {
  display: inline-flex; align-items: center; gap: 4px; align-self: flex-start;
  background: rgba(255,255,255,.22); padding: 4px 10px; border-radius: var(--r-capsule);
  font-size: var(--t-xs); font-weight: 600; margin-top: var(--s-xs);
}
.poster__foot { display: flex; align-items: center; gap: var(--s-sm); background: rgba(255,255,255,.18); border-radius: var(--r-md); padding: var(--s-sm); }
.poster__qr {
  width: 52px; height: 52px; background: #fff; border-radius: var(--r-sm);
  display: flex; align-items: center; justify-content: center; color: var(--c-text); flex-shrink: 0;
}
.poster__foot-text { font-size: var(--t-xs); line-height: var(--lh-xs); }
.poster__referrer { opacity: .9; margin-top: 2px; }

.bind { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.bind__row { display: flex; justify-content: space-between; align-items: center; font-size: var(--t-sm); }
.bind__label { color: var(--c-text-3); }
.bind__value { color: var(--c-text); font-weight: 600; font-variant-numeric: tabular-nums; }
.bind__empty {
  margin-top: var(--s-xs); padding: var(--s-sm); background: var(--c-surface);
  border-radius: var(--r-sm); font-size: var(--t-xs); color: var(--c-text-3);
  display: flex; align-items: center; gap: 4px;
}

.block { display: flex; flex-direction: column; gap: var(--s-sm); }
.block__title {
  display: flex; justify-content: space-between; align-items: center;
  font-size: var(--t-sm); font-weight: 600; color: var(--c-text);
}
.block__hint { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.block__empty {
  display: flex; align-items: center; gap: 4px;
  padding: var(--s-md); background: var(--c-bg-right); border-radius: var(--r-md);
  font-size: var(--t-xs); color: var(--c-text-3);
}

.funnel { display: flex; flex-direction: column; gap: var(--s-sm); }
.funnel__row { display: grid; grid-template-columns: 48px 1fr 56px 44px; align-items: center; gap: var(--s-sm); }
.funnel__label { font-size: var(--t-sm); color: var(--c-text-2); }
.funnel__value { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); text-align: right; font-variant-numeric: tabular-nums; }
.funnel__pct { font-size: var(--t-xs); color: var(--c-text-3); text-align: right; font-variant-numeric: tabular-nums; }

.commission { display: grid; grid-template-columns: 200px 1fr; gap: var(--s-lg); align-items: center; background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.commission__result { text-align: center; }
.commission__num { font-size: var(--t-xl); font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }
.commission__label { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.commission__sim { display: flex; flex-direction: column; gap: var(--s-xs); }
.sim__label { font-size: var(--t-xs); color: var(--c-text-3); }
.sim__tip { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 480px; max-width: 100%; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-sm); }
.form__label { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-xs); }
.form__label:first-child { margin-top: 0; }
.form__error {
  display: flex; align-items: center; gap: 4px; margin-top: var(--s-xs);
  padding: var(--s-sm); background: var(--c-danger-bg); color: var(--c-danger-fg);
  border-radius: var(--r-sm); font-size: var(--t-xs);
}

@media (max-width: 1024px) {
  .mp__body { grid-template-columns: 1fr; }
  .mp__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .preview-grid { grid-template-columns: 1fr; }
  .commission { grid-template-columns: 1fr; gap: var(--s-md); }
  .list { max-height: 360px; }
}
</style>
