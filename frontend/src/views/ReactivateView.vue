<script setup lang="ts">
/* ============================================================
 * 沉睡客户唤醒 /m2-reactivate（M2-17）
 * 双栏：左客户名单，右客户档案 + 唤醒任务 + 回访记录。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CFab from '@/components/CFab.vue'
import { useAuthStore } from '@/stores/auth'
import {
  useReactivateStore,
  type ReactivateCustomer,
  type SleepTier,
  type Channel,
} from '@/stores/reactivate'
import { REACTIVATE_STATUS, dictPill } from '@/config/dictionary'

const auth = useAuthStore()
const store = useReactivateStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<ReactivateCustomer | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '沉睡客户', icon: 'customer', value: String(store.total), tone: 'warning' as const },
  { label: '30 天内', icon: 'clock', value: String(store.t30), tone: 'brand' as const },
  { label: '90 天+', icon: 'clock', value: String(store.t90), tone: 'danger' as const },
  { label: '本月挽回', icon: 'customer', value: String(store.monthRecovered), tone: 'success' as const },
])

const tierOptions = [
  { value: 'ALL', label: '全部分层' },
  { value: 'T30', label: '30 天沉睡' },
  { value: 'T60', label: '60 天沉睡' },
  { value: 'T90', label: '90 天+ 深度' },
]
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PENDING', label: '待唤醒' },
  { value: 'ASSIGNED', label: '已指派' },
  { value: 'VISITED', label: '已回访' },
  { value: 'RECOVERED', label: '已挽回' },
]


const tierTone: Record<SleepTier, 'brand' | 'warning' | 'danger'> = {
  T30: 'brand', T60: 'warning', T90: 'danger',
}

function fmtDate(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function fmtTime(iso: string) {
  const d = new Date(iso)
  return `${fmtDate(iso)} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function levelTone(level: string): 'warning' | 'brand' | 'text' {
  if (level === '钻石') return 'warning'
  if (level === '金卡') return 'brand'
  return 'text'
}

// 指派
const showAssign = ref(false)
const assignForm = ref({ assignee: '林微（资深咨询师）', channel: 'PHONE' as Channel })
const assigneeOptions = [
  { value: '林微（资深咨询师）', label: '林微（资深咨询师）' },
  { value: '白桥（私域运营）', label: '白桥（私域运营）' },
  { value: '苏晴（店长）', label: '苏晴（店长）' },
]
const channelOptions = [
  { value: 'PHONE', label: '电话' },
  { value: 'WECHAT', label: '企业微信' },
  { value: 'SMS', label: '短信' },
]
function openAssign() {
  if (!selected.value) return
  assignForm.value = {
    assignee: selected.value.assignee ? `${selected.value.assignee}` : '林微（资深咨询师）',
    channel: selected.value.channel || 'PHONE',
  }
  showAssign.value = true
}
function submitAssign() {
  if (!selected.value) return
  store.assign(selected.value.id, assignForm.value.assignee, assignForm.value.channel)
  showAssign.value = false
}

// 回访
const showVisit = ref(false)
const visitForm = ref({ result: '', recovered: false })
function openVisit() {
  visitForm.value = { result: '', recovered: false }
  showVisit.value = true
}
function submitVisit() {
  if (!selected.value || !visitForm.value.result.trim()) return
  store.logVisit(selected.value.id, visitForm.value.result.trim(), visitForm.value.recovered)
  showVisit.value = false
}
</script>

<template>
  <div class="ra">
    <div class="ra__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="ra__body">
      <CCard class="ra__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterTier" :options="tierOptions" width="120px" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="120px" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="customer" :size="28" class="empty__icon" />
            <div>暂无沉睡客户</div>
          </div>
          <button
            v-for="c in store.filtered" :key="c.id"
            class="row" :class="{ 'row--active': selected?.id === c.id }"
            @click="selectedId = c.id"
          >
            <div class="row__top">
              <span class="row__name">{{ c.name }}</span>
              <CStatusPill :status="dictPill(REACTIVATE_STATUS[c.status]).status">{{ dictPill(REACTIVATE_STATUS[c.status]).text }}</CStatusPill>
            </div>
            <div class="row__meta">
              <span class="lvl" :class="`lvl--${levelTone(c.level)}`">{{ c.level }}</span>
              <span>未到店 <b>{{ c.lastVisitDays }}</b> 天</span>
            </div>
            <div class="row__foot">
              <span class="tier-dot" :class="`tier-dot--${tierTone[c.tier]}`">{{ store.TIER_LABEL[c.tier] }}</span>
              <span class="bal">卡余 ¥{{ c.cardBalance.toLocaleString() }}</span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'phone', label: '指派唤醒', disabled: !auth.can('reactivate:edit') || !selected, onClick: openAssign }]"
          />
        </div>
      </CCard>

      <CCard v-if="selected" class="ra__detail" padding="lg">
        <template #header>
          <div class="detail__head">
            <div class="detail__who">
              <div class="avatar">{{ selected.name.slice(0, 1) }}</div>
              <div>
                <h3 class="detail__name">{{ selected.name }}</h3>
                <div class="detail__sub">
                  <span class="lvl" :class="`lvl--${levelTone(selected.level)}`">{{ selected.level }}会员</span>
                  <span class="tag">{{ selected.phone }}</span>
                  <CStatusPill :status="dictPill(REACTIVATE_STATUS[selected.status]).status">{{ dictPill(REACTIVATE_STATUS[selected.status]).text }}</CStatusPill>
                </div>
              </div>
            </div>
            <div class="detail__ops">
              <CButton variant="ghost" size="sm" v-perm.disable="'reactivate:edit'" @click="openAssign">
                <CIcon name="profile" :size="14" />重新指派
              </CButton>
              <CButton
                v-if="selected.status !== 'RECOVERED'"
                variant="primary" size="sm"
                v-perm.disable="'reactivate:edit'"
                @click="openVisit"
              >
                <CIcon name="check-square" :size="14" />记录回访
              </CButton>
            </div>
          </div>
        </template>

        <div class="detail__grid">
          <div class="field"><span class="field__label">最后到店</span><span class="field__val">{{ selected.lastVisitDays }} 天前</span></div>
          <div class="field"><span class="field__label">卡余金额</span><span class="field__val bal">¥{{ selected.cardBalance.toLocaleString() }}</span></div>
          <div class="field"><span class="field__label">沉睡分层</span><span class="field__val" :class="`tier-text--${tierTone[selected.tier]}`">{{ store.TIER_LABEL[selected.tier] }}</span></div>
          <div class="field"><span class="field__label">下次跟进</span><span class="field__val">{{ fmtDate(selected.nextFollowAt) }}</span></div>
          <div class="field"><span class="field__label">指派给</span><span class="field__val">{{ selected.assignee || '—' }}</span></div>
          <div class="field"><span class="field__label">唤醒方式</span><span class="field__val">{{ selected.channel ? store.CHANNEL_LABEL[selected.channel] : '—' }}</span></div>
        </div>

        <div class="section">
          <div class="section__title">跟进记录</div>
          <div v-if="selected.logs.length === 0" class="logs-empty">暂无跟进记录，请先指派唤醒任务</div>
          <div v-else class="logs">
            <div v-for="log in selected.logs" :key="log.id" class="log">
              <div class="log__dot" :class="{ 'log__dot--ok': log.action === '客户已挽回' }" />
              <div class="log__body">
                <div class="log__top">
                  <span class="log__action" :class="{ 'log__action--ok': log.action === '客户已挽回' }">{{ log.action }}</span>
                  <span class="log__time">{{ fmtTime(log.at) }}</span>
                </div>
                <div v-if="log.result" class="log__result">{{ log.result }}</div>
                <div class="log__by">— {{ log.by }}</div>
              </div>
            </div>
          </div>
        </div>
      </CCard>

      <CCard v-else class="ra__detail ra__detail--empty" title="客户详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="customer" :size="40" class="detail-empty__icon" />
          <p>请选择一位客户</p>
        </div>
      </CCard>
    </div>

    <!-- 指派弹层 -->
    <div v-if="showAssign && selected" class="modal-mask" @click.self="showAssign = false">
      <CCard class="modal modal--sm" title="指派唤醒任务" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">客户</label>
            <div class="form__static">{{ selected.name }} · {{ selected.phone }}</div>
          </div>
          <div class="form__row">
            <label class="form__label">指派给</label>
            <CSelect v-model="assignForm.assignee" :options="assigneeOptions" width="100%" />
          </div>
          <div class="form__row">
            <label class="form__label">唤醒方式</label>
            <CSelect v-model="assignForm.channel" :options="channelOptions" width="100%" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showAssign = false">取消</CButton>
          <CButton variant="primary" @click="submitAssign">确认指派</CButton>
        </template>
      </CCard>
    </div>

    <!-- 回访弹层 -->
    <div v-if="showVisit && selected" class="modal-mask" @click.self="showVisit = false">
      <CCard class="modal" title="记录回访结果" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">回访对象</label>
            <div class="form__static">{{ selected.name }}</div>
          </div>
          <div class="form__row">
            <label class="form__label">回访结果</label>
            <CTextarea v-model="visitForm.result" :rows="4" placeholder="如：客户反馈近期出差，预计下周到店体验热玛吉" />
          </div>
          <label class="check">
            <input type="checkbox" v-model="visitForm.recovered" />
            <span>客户已挽回（已到店 / 已充值 / 已预约）</span>
          </label>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showVisit = false">取消</CButton>
          <CButton variant="primary" :disabled="!visitForm.result.trim()" @click="submitVisit">保存记录</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ra { display: flex; flex-direction: column; gap: var(--s-lg); }
.ra__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ra__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.ra__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ra__list { min-width: 0; display: flex; flex-direction: column; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.filters > * { flex-shrink: 0; }
.list { max-height: 600px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__meta { display: flex; gap: var(--s-sm); align-items: center; font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.row__meta b { color: var(--c-danger-fg); font-weight: 600; }
.row__foot { display: flex; justify-content: space-between; align-items: center; }
.bal { font-size: var(--t-xs); color: var(--c-text-2); font-variant-numeric: tabular-nums; }

.lvl { font-size: var(--t-xs); padding: 1px 8px; border-radius: var(--r-capsule); background: var(--c-surface-muted, #f0f2f5); color: var(--c-text-2); }
.lvl--warning { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.lvl--brand { background: var(--c-brand-soft); color: var(--c-brand); }
.tier-dot { font-size: var(--t-xs); padding: 1px 8px; border-radius: var(--r-capsule); }
.tier-dot--brand { background: var(--c-brand-soft); color: var(--c-brand); }
.tier-dot--warning { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.tier-dot--danger { background: var(--c-danger-bg); color: var(--c-danger-fg); }

.detail__head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); width: 100%; }
.detail__who { display: flex; gap: var(--s-md); align-items: center; }
.avatar {
  width: 48px; height: 48px; border-radius: 50%;
  background: linear-gradient(135deg, var(--c-brand), var(--c-brand-press, #ff8bb1));
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-size: var(--t-lg); font-weight: 600;
}
.detail__name { font-size: var(--t-lg); font-weight: 700; margin: 0 0 var(--s-xs); color: var(--c-text); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); align-items: center; }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-surface-muted, #f0f2f5); color: var(--c-text-2); }
.detail__ops { display: flex; gap: var(--s-xs); flex-shrink: 0; }

.detail__grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; padding: var(--s-md); background: var(--c-surface-muted, #f7f8fa); border-radius: var(--r-md); }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.tier-text--brand { color: var(--c-brand); font-weight: 600; }
.tier-text--warning { color: var(--c-warning-fg); font-weight: 600; }
.tier-text--danger { color: var(--c-danger-fg); font-weight: 600; }

.section { margin-top: var(--s-md); }
.section__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.logs-empty { font-size: var(--t-sm); color: var(--c-text-3); padding: var(--s-md); text-align: center; background: var(--c-surface-muted, #f7f8fa); border-radius: var(--r-md); }
.logs { display: flex; flex-direction: column; }
.log { display: flex; gap: var(--s-sm); padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.log:last-child { border-bottom: none; }
.log__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-brand); margin-top: 6px; flex-shrink: 0; }
.log__dot--ok { background: var(--c-success-fg); }
.log__body { flex: 1; min-width: 0; }
.log__top { display: flex; justify-content: space-between; gap: var(--s-sm); align-items: baseline; }
.log__action { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.log__action--ok { color: var(--c-success-fg); }
.log__time { font-size: var(--t-xs); color: var(--c-text-3); flex-shrink: 0; }
.log__result { font-size: var(--t-sm); color: var(--c-text-2); margin-top: 2px; line-height: 1.6; }
.log__by { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 480px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--sm { width: 380px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.form__static { font-size: var(--t-sm); color: var(--c-text); padding: 8px 12px; background: var(--c-surface-muted, #f7f8fa); border-radius: var(--r-sm); }
.check { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text); cursor: pointer; }
.check input { accent-color: var(--c-brand); }

@media (max-width: 1024px) {
  .ra__body { grid-template-columns: 1fr; }
  .ra__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .list { max-height: 320px; }
  .detail__head { flex-direction: column; }
  .detail__grid { grid-template-columns: 1fr 1fr; }
}
</style>
