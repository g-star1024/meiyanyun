<script setup lang="ts">
/* M5-03 短信/企微推送 /m5-push — 人群圈选 + 模板 + 周频≤3 + 违禁词双重合规拦截 */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import { useM5CoreStore, PUSH_CHANNEL_LABEL, PUSH_STATUS_LABEL, PUSH_STATUS_PILL, type PushChannel } from '@/stores/m5Core'
import { useSegmentStore } from '@/stores/segment'
import { useSensitiveWords } from '@/composables/useSensitiveWords'
import { useAuthStore } from '@/stores/auth'

const core = useM5CoreStore()
const segmentStore = useSegmentStore()
const auth = useAuthStore()
const sw = useSensitiveWords()

const filterStatus = ref('ALL')
const selectedId = ref<string | null>(null)
const showCreate = ref(false)
const formError = ref('')
const wordHits = ref<string[]>([])

const canSend = computed(() => auth.can('push:send'))
const canCreate = computed(() => auth.can('push:create'))

const filtered = computed(() => {
  if (filterStatus.value === 'ALL') return core.batches
  return core.batches.filter((b) => b.status === filterStatus.value)
})
const selected = computed(() => core.batches.find((b) => b.id === selectedId.value) ?? null)

const kpis = computed(() => [
  { label: '本周已发批次', icon: 'bell', value: String(core.batches.filter((b) => b.status === 'SENT').length), tone: 'brand' as const },
  { label: '本周触达人次', icon: 'bell', value: core.sentLast7Days.toLocaleString(), tone: 'teal' as const },
  { label: '周频剩余配额', icon: 'clock', value: `${core.weeklyRemaining} 人次`, tone: core.weeklyRemaining > 0 ? 'success' as const : 'danger' as const },
  { label: '累计转化', icon: 'trend-up', value: String(core.batches.reduce((s, b) => s + b.converted, 0)), tone: 'orange' as const },
])

const blankForm = () => ({
  name: '', channel: 'WECOM' as PushChannel, segmentId: '', templateTitle: '', templateBody: '',
  scheduledAt: new Date().toISOString().slice(0, 16),
})
const form = ref(blankForm())

const segmentOptions = computed(() => [
  { label: '全部门店客户', value: 'all' },
  ...segmentStore.segments.map((s) => ({ label: `${s.name}（${s.customerCount}人）`, value: s.id })),
])

function openCreate() {
  form.value = blankForm()
  formError.value = ''
  wordHits.value = []
  showCreate.value = true
}

function previewWords() {
  wordHits.value = []
  const hit = sw.checkAny(form.value.templateTitle, form.value.templateBody, form.value.name)
  wordHits.value = hit.words
}

function submitCreate() {
  formError.value = ''
  if (!form.value.name.trim()) { formError.value = '请输入任务名称'; return }
  if (!form.value.templateBody.trim()) { formError.value = '请输入推送内容'; return }
  const hit = sw.checkAny(form.value.templateTitle, form.value.templateBody, form.value.name)
  if (hit.hit) { formError.value = hit.message; wordHits.value = hit.words; return }
  if (!form.value.segmentId) { formError.value = '请选择目标人群'; return }
  const seg = segmentOptions.value.find((s) => s.value === form.value.segmentId)
  const reach = form.value.segmentId === 'all' ? 800 : (segmentStore.segments.find((s) => s.id === form.value.segmentId)?.customerCount ?? 0)
  if (reach <= 0) { formError.value = '目标人群人数为 0'; return }
  const b = core.createBatch({
    name: form.value.name, channel: form.value.channel, segmentId: form.value.segmentId,
    segmentName: seg?.label ?? '全部客户', templateTitle: form.value.templateTitle,
    templateBody: form.value.templateBody, reach, scheduledAt: form.value.scheduledAt,
  })
  showCreate.value = false
  selectedId.value = b.id
}

function doSend() {
  if (!selected.value) return
  formError.value = ''
  try {
    core.sendBatch(selected.value.id)
    // 刷新状态（可能被拦截）
    if (selected.value.status === 'BLOCKED') formError.value = selected.value.blockedReason ?? '发送被拦截'
  } catch (e) { formError.value = (e as Error).message }
}

function pct(n: number, d: number) { return d ? Math.round((n / d) * 100) : 0 }

onMounted(() => { core.seed(); segmentStore.seed(); if (core.batches.length) selectedId.value = core.batches[0].id })
</script>

<template>
  <div class="ps">
    <div class="ps__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard class="quota" padding="md">
      <div class="quota__left">
        <CIcon name="shield" :size="16" />
        <span>周频合规：同一人群 7 天内推送 ≤ <strong>{{ core.WEEKLY_LIMIT }}</strong> 次</span>
      </div>
      <div class="quota__bar">
        <div class="quota__track"><div class="quota__fill" :class="{ warn: core.quotaPct >= 70, danger: core.quotaPct >= 100 }" :style="{ width: core.quotaPct + '%' }"></div></div>
        <span class="quota__num">{{ core.sentLast7Days }} / {{ core.WEEKLY_LIMIT * 800 }} 人次</span>
      </div>
    </CCard>

    <CCard class="ps__toolbar" padding="none">
      <div class="ps__tools">
        <CSelect v-model="filterStatus" :options="[
          { value: 'ALL', label: '全部状态' },
          { value: 'DRAFT', label: '草稿' },
          { value: 'SCHEDULED', label: '待发送' },
          { value: 'SENDING', label: '发送中' },
          { value: 'SENT', label: '已发送' },
          { value: 'BLOCKED', label: '已拦截' },
        ]" />
        <CButton v-if="canCreate" size="sm" variant="primary" class="ps__tools-btn" @click="openCreate">
          <CIcon name="plus" :size="14" />新建推送
        </CButton>
      </div>
    </CCard>

    <div class="ps__body">
      <CCard class="ps__list" padding="none">
        <div class="list-head">
          <div class="list-head__left">
            <span class="list-head__title">推送任务</span>
            <span class="list-head__hint">{{ filtered.length }} 个</span>
          </div>
        </div>
        <div class="p-list">
          <button v-for="b in filtered" :key="b.id" class="p-row"
            :class="{ 'p-row--active': b.id === selectedId, 'p-row--blocked': b.status === 'BLOCKED' }"
            @click="selectedId = b.id">
            <div class="p-row__top">
              <span class="p-row__name">{{ b.name }}</span>
              <CStatusPill :status="PUSH_STATUS_PILL[b.status]" dot>{{ PUSH_STATUS_LABEL[b.status] }}</CStatusPill>
            </div>
            <div class="p-row__sub">{{ PUSH_CHANNEL_LABEL[b.channel] }} · {{ b.segmentName }} · {{ b.scheduledAt }}</div>
            <div class="p-row__nums">触达 {{ b.delivered || b.reach }} · 点击 {{ b.clicked }} · 转化 {{ b.converted }}</div>
          </button>
        </div>
      </CCard>

      <CCard v-if="selected" class="ps__detail" padding="lg">
        <div class="det-head">
          <div>
            <h3 class="det-head__name">{{ selected.name }}</h3>
            <div class="det-head__sub">{{ PUSH_CHANNEL_LABEL[selected.channel] }} · {{ selected.segmentName }} · 计划 {{ selected.scheduledAt }}</div>
          </div>
          <CStatusPill :status="PUSH_STATUS_PILL[selected.status]" dot>{{ PUSH_STATUS_LABEL[selected.status] }}</CStatusPill>
        </div>

        <CCard class="tpl-card" padding="md">
          <div class="tpl-title">{{ selected.templateTitle || selected.name }}</div>
          <div class="tpl-body">{{ selected.templateBody }}</div>
          <div class="tpl-foot">— 美研云 · 回 T 退订</div>
        </CCard>

        <div v-if="selected.status === 'BLOCKED'" class="block-box">
          <CIcon name="alert" :size="15" />
          <div>
            <div class="block-box__title">合规拦截</div>
            <div class="block-box__hint">{{ selected.blockedReason }}</div>
          </div>
        </div>

        <div v-if="selected.status !== 'BLOCKED' && selected.status !== 'SENT'" class="det-ops">
          <CButton v-if="canSend && (selected.status === 'SCHEDULED' || selected.status === 'DRAFT')" variant="primary" size="sm" @click="doSend">
            <CIcon name="bell" :size="14" />立即发送
          </CButton>
          <span class="ops-hint">发送前自动校验周频配额与违禁词</span>
        </div>

        <div v-if="selected.status === 'SENT'" class="effect">
          <div class="effect__title">发送效果</div>
          <div class="effect__grid">
            <div class="effect-item"><span class="effect-item__n">{{ selected.reach }}</span><span class="effect-item__l">目标人群</span></div>
            <div class="effect-item"><span class="effect-item__n">{{ selected.delivered }}</span><span class="effect-item__l">成功到达</span></div>
            <div class="effect-item"><span class="effect-item__n">{{ selected.clicked }}</span><span class="effect-item__l">点击</span></div>
            <div class="effect-item"><span class="effect-item__n">{{ selected.converted }}</span><span class="effect-item__l">转化</span></div>
          </div>
          <div class="funnel">
            <div class="funnel__row"><span>到达率</span><div class="funnel__bar"><div :style="{ width: pct(selected.delivered, selected.reach) + '%' }"></div></div><span>{{ pct(selected.delivered, selected.reach) }}%</span></div>
            <div class="funnel__row"><span>点击率</span><div class="funnel__bar"><div :style="{ width: pct(selected.clicked, selected.delivered) + '%' }"></div></div><span>{{ pct(selected.clicked, selected.delivered) }}%</span></div>
            <div class="funnel__row"><span>转化率</span><div class="funnel__bar"><div :style="{ width: pct(selected.converted, selected.clicked) + '%' }" class="conv"></div></div><span>{{ pct(selected.converted, selected.clicked) }}%</span></div>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 新建推送弹层 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <CCard class="modal modal--push" title="新建推送任务" padding="lg">
        <div class="form-row">
          <label class="form-label">任务名称</label>
          <CInput v-model="form.name" placeholder="如：暑期水光活动-高潜客户" />
        </div>
        <div class="form-grid">
          <div class="form-row">
            <label class="form-label">推送渠道</label>
            <CSelect v-model="form.channel" :options="[
              { value: 'SMS', label: '短信' },
              { value: 'WECOM', label: '企业微信' },
              { value: 'WECHAT_MP', label: '微信公众号' },
            ]" />
          </div>
          <div class="form-row">
            <label class="form-label">发送时间</label>
            <input v-model="form.scheduledAt" type="datetime-local" class="native-input" />
          </div>
        </div>
        <div class="form-row">
          <label class="form-label">目标人群</label>
          <CSelect v-model="form.segmentId" :options="segmentOptions" />
        </div>
        <div class="form-row">
          <label class="form-label">消息标题（可选）</label>
          <CInput v-model="form.templateTitle" placeholder="如：您有一张专属券待领取" />
        </div>
        <div class="form-row">
          <label class="form-label">推送内容</label>
          <CTextarea v-model="form.templateBody" :rows="4" placeholder="请输入推送文案..." @input="previewWords" />
          <div v-if="wordHits.length" class="word-hint">
            <CIcon name="alert" :size="12" />命中违禁词：<strong>{{ wordHits.join('、') }}</strong>，请修改
          </div>
        </div>
        <div class="compliance-note">
          <CIcon name="shield" :size="13" />提交时将校验：① 周频 ≤ {{ core.WEEKLY_LIMIT }} 次 ② 违禁词库。命中任一将被拦截。
        </div>
        <div v-if="formError" class="form-error"><CIcon name="alert" :size="13" />{{ formError }}</div>
        <div class="modal-foot">
          <CButton variant="ghost" size="sm" @click="showCreate = false">取消</CButton>
          <CButton variant="primary" size="sm" @click="submitCreate">创建（待发送）</CButton>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ps { display: flex; flex-direction: column; gap: var(--s-lg); }
.ps__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ps__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.quota { display: flex; align-items: center; gap: var(--s-lg); background: var(--c-brand-soft); border: 1px solid var(--c-brand-light, rgba(94,114,228,.2)); }
.quota__left { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-text-2); white-space: nowrap; }
.quota__left strong { color: var(--c-brand); }
.quota__bar { flex: 1; display: flex; align-items: center; gap: var(--s-md); min-width: 200px; }
.quota__track { flex: 1; height: 8px; background: var(--c-disabled-bg); border-radius: 4px; overflow: hidden; }
.quota__fill { height: 100%; background: var(--c-success-fg); border-radius: 4px; transition: width .3s; }
.quota__fill.warn { background: var(--c-warning-fg); }
.quota__fill.danger { background: var(--c-danger-fg); }
.quota__num { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; white-space: nowrap; }

.ps__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ps__list { min-width: 0; }
.list-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.list-head__left { display: flex; align-items: center; gap: var(--s-sm); }
.list-head__right { display: flex; align-items: center; gap: var(--s-sm); }
.list-head__title { font-size: var(--t-sm); font-weight: 700; }
.list-head__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.ps__tools { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); border-top: 1px solid var(--c-border-light); background: var(--c-surface); flex-wrap: nowrap; overflow-x: auto; }
.ps__tools > * { flex-shrink: 0; }
.ps__tools-btn { margin-left: auto; white-space: nowrap; }
.p-list { max-height: 600px; overflow-y: auto; }
.p-row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; border-left: 3px solid transparent; }
.p-row:hover { background: var(--c-brand-soft); }
.p-row--active { background: var(--c-brand-soft); border-left-color: var(--c-brand); }
.p-row--blocked { border-left-color: var(--c-danger-fg); }
.p-row__top { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); margin-bottom: 6px; }
.p-row__name { font-size: var(--t-sm); font-weight: 600; }
.p-row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.p-row__nums { font-size: var(--t-xs); color: var(--c-text-2); font-variant-numeric: tabular-nums; }

.ps__detail :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); min-width: 0; }
.det-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); }
.det-head > div { min-width: 0; flex: 1; }
.det-head__name { margin: 0; font-size: var(--t-lg); font-weight: 700; word-break: break-word; }
.det-head__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.tpl-card { background: var(--c-bg-right); }
:deep(.tpl-card .card__body) { display: flex; flex-direction: column; gap: var(--s-sm); }
.tpl-title { font-size: var(--t-base); font-weight: 700; color: var(--c-text); }
.tpl-body { font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; }
.tpl-foot { font-size: var(--t-xs); color: var(--c-text-4); }

.block-box { display: flex; gap: var(--s-sm); padding: var(--s-md); background: rgba(229,57,53,.08); border-radius: var(--r-md); color: var(--c-danger-fg); }
.block-box__title { font-weight: 700; font-size: var(--t-sm); }
.block-box__hint { font-size: var(--t-xs); margin-top: 2px; }
.det-ops { display: flex; align-items: center; gap: var(--s-sm); }
.ops-hint { font-size: var(--t-xs); color: var(--c-text-3); }

.effect { border-top: 1px solid var(--c-border-light); padding-top: var(--s-md); }
.effect__title { font-size: var(--t-sm); font-weight: 700; margin-bottom: var(--s-sm); }
.effect__grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-sm); margin-bottom: var(--s-md); }
.effect-item { display: flex; flex-direction: column; align-items: center; gap: 2px; background: var(--c-bg-right); border-radius: var(--r-sm); padding: var(--s-sm); }
.effect-item__n { font-size: var(--t-lg); font-weight: 700; font-variant-numeric: tabular-nums; }
.effect-item__l { font-size: var(--t-xs); color: var(--c-text-3); }
.funnel { display: flex; flex-direction: column; gap: var(--s-sm); }
.funnel__row { display: grid; grid-template-columns: 60px 1fr 40px; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-2); }
.funnel__bar { height: 8px; background: var(--c-disabled-bg); border-radius: 4px; overflow: hidden; }
.funnel__bar > div { height: 100%; background: var(--c-brand); border-radius: 4px; }
.funnel__bar div.conv { background: var(--c-success-fg); }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 540px; max-width: 100%; box-shadow: var(--shadow-pop); }
:deep(.modal .card__body) { display: flex; flex-direction: column; gap: var(--s-md); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form-row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form-label { font-size: var(--t-xs); color: var(--c-text-3); }
.native-input { width: 100%; padding: 10px; border: 1px solid #D1D1D9; border-radius: var(--r-sm); background: var(--c-surface); font-size: 13px; color: var(--c-text); font-family: inherit; }
.native-input:focus { outline: none; border-color: #4D5AD9; box-shadow: 0 0 0 2px rgba(77,90,217,.12); }
.word-hint { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-danger-fg); }
.compliance-note { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-bg-right); padding: var(--s-sm) var(--s-md); border-radius: var(--r-sm); }
.form-error { display: flex; align-items: center; gap: 4px; color: var(--c-danger-fg); font-size: var(--t-xs); background: rgba(229,57,53,.08); padding: var(--s-sm) var(--s-md); border-radius: var(--r-sm); }
.modal-foot { display: flex; justify-content: flex-end; gap: var(--s-sm); }

@media (max-width: 1200px) { .ps__body { grid-template-columns: 1fr; } }
@media (max-width: 1024px) {
  .ps__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .form-grid { grid-template-columns: 1fr; }
  .quota { flex-direction: column; align-items: stretch; }
}
</style>
