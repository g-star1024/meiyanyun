<script setup lang="ts">
/* ============================================================
 * M3-09 生日节日关怀 /m3-care
 * 左列表（tab 筛选）+ 右详情（模板预览/渠道/效果回收）+ 新建弹层。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CFab from '@/components/CFab.vue'
import {
  useCareStore,
  type CareTask,
  type CareType,
  type CareChannel,
} from '@/stores/care'
import { CARE_STATUS, dictPill } from '@/config/dictionary'
import { useAuthStore } from '@/stores/auth'

const store = useCareStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<CareTask | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '本月待关怀', icon: 'bell', value: String(store.pendingThisMonth.length), tone: 'brand' as const },
  { label: '已发送', icon: 'bell', value: String(store.sent.length), tone: 'teal' as const },
  { label: '触达率', icon: 'marketing', value: `${store.reachRate}%`, tone: 'warning' as const },
  { label: '带来预约', icon: 'marketing', value: String(store.converted.length), tone: 'success' as const },
])

const tabs = computed(() => [
  { key: 'PENDING' as const, label: `待发送 (${store.pending.length})` },
  { key: 'SENT' as const, label: `已发送 (${store.sent.length})` },
  { key: 'ALL' as const, label: `全部 (${store.tasks.length})` },
])

const channelIcon: Record<CareChannel, string> = { SMS: 'message', WECHAT: 'chat', PHONE: 'phone' }

function fmtDate(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 新建弹层
const showForm = ref(false)
const form = ref({
  customerName: '',
  customerLevel: '普通',
  type: 'BIRTHDAY' as CareType,
  channel: 'SMS' as CareChannel,
  templateId: '',
  scheduledAt: '',
})
const canSubmit = computed(() => form.value.customerName.trim() && form.value.scheduledAt)
function openForm() {
  const d = new Date(Date.now() + 24 * 3600_000)
  const local = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}T${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  form.value = { customerName: '', customerLevel: '普通', type: 'BIRTHDAY', channel: 'SMS', templateId: '', scheduledAt: local }
  showForm.value = true
}
function submitForm() {
  if (!canSubmit.value) return
  const t = store.create({ ...form.value })
  if (t) {
    showForm.value = false
    selectedId.value = t.id
  }
}
const templateOptions = computed(() =>
  store.templates
    .filter((t) => t.channel === form.value.channel)
    .map((t) => ({ value: t.id, label: t.name })),
)

function doSend() {
  if (selected.value) store.send(selected.value.id)
}
function toggleReached() {
  if (selected.value) store.markReached(selected.value.id, !selected.value.reached)
}
function toggleConverted() {
  if (selected.value) store.markConverted(selected.value.id, !selected.value.convertedBooking)
}
</script>

<template>
  <div class="care">
    <div class="care__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="care__body">
      <CCard class="care__list" padding="none">
        <div class="tabs">
          <button
            v-for="t in tabs" :key="t.key"
            class="tab" :class="{ 'tab--active': store.filterTab === t.key }"
            @click="store.filterTab = t.key"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon :name="('bell' as any)" :size="28" class="empty__icon" />
            <div>暂无关怀任务</div>
          </div>
          <button
            v-for="t in store.filtered" :key="t.id"
            class="row" :class="{ 'row--active': selected?.id === t.id }"
            @click="selectedId = t.id"
          >
            <div class="row__top">
              <span class="row__name">
                <CIcon :name="(store.TYPE_ICON[t.type] as any)" :size="14" />
                {{ t.customerName }} · {{ t.customerLevel }}
              </span>
              <CStatusPill :status="dictPill(CARE_STATUS[t.status]).status">{{ dictPill(CARE_STATUS[t.status]).text }}</CStatusPill>
            </div>
            <div class="row__title">{{ t.templateName }}</div>
            <div class="row__meta">
              <span class="row__type">{{ store.TYPE_LABEL[t.type] }}</span>
              <span><CIcon :name="(channelIcon[t.channel] as any)" :size="12" /> {{ store.CHANNEL_LABEL[t.channel] }}</span>
              <span><CIcon name="clock" :size="12" /> {{ fmtDate(t.scheduledAt) }}</span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '新建关怀', disabled: !auth.can('care:edit'), onClick: openForm }]"
          />
        </div>
      </CCard>

      <CCard v-if="selected" class="care__detail" :title="`${selected.customerName} 的关怀任务`">
        <template #header>
          <h3 class="care__detail-title">{{ selected.customerName }} · {{ selected.customerLevel }}</h3>
          <CStatusPill :status="dictPill(CARE_STATUS[selected.status]).status">{{ dictPill(CARE_STATUS[selected.status]).text }}</CStatusPill>
        </template>

        <div class="detail__head">
          <div>
            <div class="detail__type">
              <CIcon :name="(store.TYPE_ICON[selected.type] as any)" :size="16" />
              {{ store.TYPE_LABEL[selected.type] }}
            </div>
            <div class="detail__sub">
              <span class="tag tag--channel"><CIcon :name="(channelIcon[selected.channel] as any)" :size="12" /> {{ store.CHANNEL_LABEL[selected.channel] }}</span>
              <span class="tag">{{ selected.templateName }}</span>
            </div>
          </div>
          <div class="detail__assign">
            <div class="detail__assign-label">归属人</div>
            <div class="detail__assign-name">{{ selected.assignee }}</div>
          </div>
        </div>

        <div class="detail__grid">
          <div class="field"><span class="field__label">客户</span><span class="field__val">{{ selected.customerName }}（{{ selected.customerLevel }}）</span></div>
          <div class="field"><span class="field__label">渠道</span><span class="field__val">{{ store.CHANNEL_LABEL[selected.channel] }}</span></div>
          <div class="field"><span class="field__label">计划发送</span><span class="field__val">{{ fmtDate(selected.scheduledAt) }}</span></div>
          <div class="field"><span class="field__label">实际发送</span><span class="field__val">{{ fmtDate(selected.sentAt) }}</span></div>
        </div>

        <div class="detail__tpl">
          <div class="detail__sec-title">关怀内容预览</div>
          <div class="tpl-box">
            <div class="tpl-box__name">{{ selected.templateName }}</div>
            <p class="tpl-box__content">{{ selected.templateContent }}</p>
          </div>
        </div>

        <div class="detail__effect">
          <div class="detail__sec-title">效果回收</div>
          <div class="effect-grid">
            <div class="effect" :class="{ 'effect--on': selected.reached }">
              <CIcon :name="(selected.reached ? 'check' : 'bell') as any" :size="16" />
              <span>{{ selected.reached ? '已触达' : '未触达' }}</span>
            </div>
            <div class="effect" :class="{ 'effect--on': selected.replied }">
              <CIcon :name="(selected.replied ? 'chat' : 'bell') as any" :size="16" />
              <span>{{ selected.replied ? '客户已回复' : '未回复' }}</span>
            </div>
            <div class="effect" :class="{ 'effect--on effect--success': selected.convertedBooking }">
              <CIcon :name="(selected.convertedBooking ? 'check-square' : 'calendar') as any" :size="16" />
              <span>{{ selected.convertedBooking ? '已转化预约' : '未预约' }}</span>
            </div>
          </div>
        </div>

        <div class="detail__ops">
          <template v-if="selected.status === 'PENDING'">
            <CButton variant="ghost" v-perm.disable="'care:edit'" @click="toggleReached" disabled>标记已触达</CButton>
            <CButton variant="primary" v-perm.disable="'care:send'" @click="doSend">
              <CIcon name="upload" :size="16" />立即发送
            </CButton>
          </template>
          <template v-else>
            <CButton variant="ghost" v-perm.disable="'care:edit'" @click="toggleReached">
              <CIcon name="check" :size="16" />{{ selected.reached ? '取消触达' : '标记已触达' }}
            </CButton>
            <CButton variant="primary" v-perm.disable="'care:edit'" @click="toggleConverted">
              <CIcon name="check-square" :size="16" />{{ selected.convertedBooking ? '取消预约登记' : '登记到店预约' }}
            </CButton>
          </template>
        </div>
      </CCard>

      <CCard v-else class="care__detail care__detail--empty" title="关怀详情">
        <div class="detail-empty">
          <CIcon :name="('bell' as any)" :size="40" class="detail-empty__icon" />
          <p>请选择一条关怀任务</p>
        </div>
      </CCard>
    </div>

    <!-- 新建关怀弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建关怀任务" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">客户姓名</label>
              <CInput v-model="form.customerName" placeholder="如：王芳" />
            </div>
            <div>
              <label class="form__label">客户等级</label>
              <CSelect v-model="form.customerLevel" :options="[
                { value: '普通', label: '普通' },
                { value: '银卡', label: '银卡' },
                { value: '黄金', label: '黄金' },
                { value: '金卡', label: '金卡' },
                { value: '白金', label: '白金' },
                { value: '钻石', label: '钻石' },
              ]" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">关怀类型</label>
              <CSelect v-model="form.type" :options="[
                { value: 'BIRTHDAY', label: '生日关怀' },
                { value: 'HOLIDAY', label: '节日问候' },
                { value: 'REPURCHASE', label: '复购窗口' },
                { value: 'REACTIVATE', label: '沉睡唤醒' },
              ]" />
            </div>
            <div>
              <label class="form__label">发送渠道</label>
              <CSelect v-model="form.channel" :options="[
                { value: 'SMS', label: '短信' },
                { value: 'WECHAT', label: '企微' },
                { value: 'PHONE', label: '电话' },
              ]" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">关怀模板</label>
            <CSelect v-model="form.templateId" :options="templateOptions" />
          </div>
          <div class="form__row">
            <label class="form__label">计划发送时间</label>
            <input v-model="form.scheduledAt" type="datetime-local" class="native-input" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">提交</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.care { display: flex; flex-direction: column; gap: var(--s-lg); }
.care__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .care__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.care__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.care__list { min-width: 0; position: relative; display: flex; flex-direction: column; }

.tabs { display: flex; gap: var(--s-xs); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; align-items: center; }
.tab { padding: 6px 14px; border-radius: var(--r-md); border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text-2); font-size: var(--t-sm); cursor: pointer; white-space: nowrap; flex-shrink: 0; }
.tab:hover { color: var(--c-brand); border-color: var(--c-brand); }
.tab--active { background: var(--c-brand-soft); border-color: var(--c-brand); color: var(--c-brand); font-weight: 600; }

.list { max-height: 560px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg) 64px; color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); display: inline-flex; align-items: center; gap: 6px; }
.row__title { font-size: var(--t-sm); color: var(--c-text-2); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.row__meta > span { display: inline-flex; align-items: center; gap: 3px; }
.row__type { color: var(--c-brand) !important; font-weight: 600; }

.care__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.detail__head { display: flex; justify-content: space-between; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.detail__type { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); display: inline-flex; align-items: center; gap: var(--s-xs); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-top: var(--s-xs); }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-surface-muted); color: var(--c-text-2); display: inline-flex; align-items: center; gap: 4px; }
.tag--channel { background: var(--c-brand-soft); color: var(--c-brand); }
.detail__assign { text-align: right; }
.detail__assign-label { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__assign-name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }

.detail__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }

.detail__tpl, .detail__effect { margin-bottom: var(--s-lg); }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.tpl-box { background: var(--c-brand-soft); border-radius: var(--r-md); padding: var(--s-md); border: 1px solid var(--c-brand); }
.tpl-box__name { font-size: var(--t-xs); color: var(--c-brand); font-weight: 600; margin-bottom: var(--s-xs); }
.tpl-box__content { font-size: var(--t-sm); color: var(--c-text); line-height: var(--lh-md); margin: 0; }

.effect-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.effect {
  display: flex; align-items: center; justify-content: center; gap: var(--s-xs);
  padding: var(--s-md); border-radius: var(--r-md);
  background: var(--c-surface-muted); color: var(--c-text-3);
  font-size: var(--t-sm); font-weight: 600;
}
.effect--on { background: var(--c-brand-soft); color: var(--c-brand); }
.effect--on.effect--success { background: var(--c-success-bg, #e6f9ed); color: var(--c-success-fg); }

.detail__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.native-input {
  width: 100%;
  padding: var(--s-sm) var(--s-md);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  font-size: var(--t-sm);
  color: var(--c-text);
}
.native-input:focus { outline: none; border-color: var(--c-brand); }

@media (max-width: 1024px) {
  .care__body { grid-template-columns: 1fr; }
  .care__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .detail__head { flex-direction: column; gap: var(--s-sm); }
  .detail__assign { text-align: left; }
  .effect-grid { grid-template-columns: 1fr; }
  .list { max-height: 320px; }
}
</style>
