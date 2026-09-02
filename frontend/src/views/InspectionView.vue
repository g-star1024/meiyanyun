<script setup lang="ts">
/* ============================================================
 * 巡店检查 /m2-inspection（M2-10）
 * 双栏：左检查单列表，右检查项打分明细 + 整改跟踪。
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
import { useAuthStore } from '@/stores/auth'
import {
  useInspectionStore,
  type Inspection,
  type InspectionType,
} from '@/stores/inspection'
import { INSPECTION_STATUS, RECTIFY_STATUS, dictPill } from '@/config/dictionary'

const auth = useAuthStore()
const store = useInspectionStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<Inspection | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '本月检查', icon: 'check-square', value: String(store.monthCount), tone: 'brand' as const },
  { label: '平均得分', icon: 'trend-up', value: String(store.avgScore), tone: 'teal' as const },
  { label: '待整改', icon: 'alert', value: String(store.pending.length + store.inProgress.length), tone: 'warning' as const },
  { label: '逾期整改', icon: 'alert', value: String(store.overdue.length), tone: 'danger' as const },
])

const typeOptions = [
  { value: 'ALL', label: '全部类型' },
  { value: 'ENV', label: '环境' },
  { value: 'SERVICE', label: '服务' },
  { value: 'COMPLIANCE', label: '合规' },
]
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PENDING', label: '待整改' },
  { value: 'IN_PROGRESS', label: '整改中' },
  { value: 'DONE', label: '已完成' },
]

function fmtDate(iso: string) {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function scoreTone(score: number): 'success' | 'warning' | 'danger' {
  if (score >= 8) return 'success'
  if (score >= 6) return 'warning'
  return 'danger'
}

// 新建检查单
const showForm = ref(false)
const defaultItems = [
  '大厅整洁度', '治疗室消毒', '设备维护', '员工仪容仪表', '顾客接待流程',
]
const form = ref({
  store: '静安旗舰店',
  type: 'ENV' as InspectionType,
  inspector: '陈野',
  inspectedAt: new Date().toISOString().slice(0, 10),
  items: defaultItems.map((name) => ({ name, score: 8, note: '' })),
})
function setScore(idx: number, delta: number) {
  const it = form.value.items[idx]
  it.score = Math.max(0, Math.min(10, it.score + delta))
}
function submitForm() {
  const o = store.create({
    store: form.value.store,
    type: form.value.type,
    inspector: form.value.inspector,
    inspectedAt: new Date(form.value.inspectedAt).toISOString(),
    items: form.value.items,
  })
  if (o) {
    showForm.value = false
    form.value = {
      store: '静安旗舰店', type: 'ENV', inspector: '陈野',
      inspectedAt: new Date().toISOString().slice(0, 10),
      items: defaultItems.map((name) => ({ name, score: 8, note: '' })),
    }
    selectedId.value = o.id
  }
}

// 整改指派 / 完成
const showAssign = ref<{ inspectionId: string; issueId: string } | null>(null)
const assignOwner = ref('李娜（前台主管）')
const ownerOptions = [
  { value: '李娜（前台主管）', label: '李娜（前台主管）' },
  { value: '吴桐（运营）', label: '吴桐（运营）' },
  { value: '周敏（美容师）', label: '周敏（美容师）' },
  { value: '张磊（设备主管）', label: '张磊（设备主管）' },
]
function openAssign(inspectionId: string, issueId: string) {
  showAssign.value = { inspectionId, issueId }
}
function submitAssign() {
  if (!showAssign.value) return
  store.assignIssue(showAssign.value.inspectionId, showAssign.value.issueId, assignOwner.value)
  showAssign.value = null
}
function doComplete(inspectionId: string, issueId: string) {
  store.completeIssue(inspectionId, issueId)
}
</script>

<template>
  <div class="ip">
    <div class="ip__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="ip__body">
      <CCard class="ip__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterType" :options="typeOptions" width="120px" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="120px" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="shield" :size="28" class="empty__icon" />
            <div>暂无检查单</div>
          </div>
          <button
            v-for="o in store.filtered" :key="o.id"
            class="row" :class="{ 'row--active': selected?.id === o.id }"
            @click="selectedId = o.id"
          >
            <div class="row__top">
              <span class="row__no">{{ o.no }}</span>
              <CStatusPill :status="dictPill(INSPECTION_STATUS[o.status]).status">{{ dictPill(INSPECTION_STATUS[o.status]).text }}</CStatusPill>
            </div>
            <div class="row__title">
              <CIcon :name="(store.TYPE_ICON[o.type] as any)" :size="12" />
              {{ store.TYPE_LABEL[o.type] }} · {{ o.store }}
            </div>
            <div class="row__meta">
              <span>得分 <b :class="`score--${scoreTone(o.totalScore)}`">{{ o.totalScore }}</b></span>
              <span>问题 {{ o.issueCount }}</span>
              <span>{{ fmtDate(o.inspectedAt) }}</span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '新建检查', disabled: !auth.can('inspection:create'), onClick: () => { showForm = true } }]"
          />
        </div>
      </CCard>

      <CCard v-if="selected" class="ip__detail" padding="lg">
        <template #header>
          <div class="detail__head">
            <div>
              <h3 class="detail__no">{{ selected.no }}</h3>
              <div class="detail__sub">
                <span class="tag tag--type">
                  <CIcon :name="(store.TYPE_ICON[selected.type] as any)" :size="12" />
                  {{ store.TYPE_LABEL[selected.type] }}
                </span>
                <span class="tag">{{ selected.store }}</span>
                <span class="tag">检查人 {{ selected.inspector }}</span>
                <span class="tag">{{ fmtDate(selected.inspectedAt) }}</span>
              </div>
            </div>
            <div class="detail__score">
              <div class="detail__score-label">总分</div>
              <div class="detail__score-val" :class="`score--${scoreTone(selected.totalScore)}`">{{ selected.totalScore }}</div>
              <CStatusPill :status="dictPill(INSPECTION_STATUS[selected.status]).status">{{ dictPill(INSPECTION_STATUS[selected.status]).text }}</CStatusPill>
            </div>
          </div>
        </template>

        <div class="section">
          <div class="section__title">检查项打分</div>
          <div class="items">
            <div v-for="(it, idx) in selected.items" :key="idx" class="item">
              <div class="item__name">{{ it.name }}</div>
              <div v-if="it.note" class="item__note">{{ it.note }}</div>
              <span class="score-pill" :class="`score-pill--${scoreTone(it.score * 10)}`">
                {{ it.score }}/10
              </span>
            </div>
          </div>
        </div>

        <div class="section">
          <div class="section__title">整改跟踪（{{ selected.issues.length }}）</div>
          <div v-if="selected.issues.length === 0" class="issues-empty">
            <CIcon name="check" :size="14" /> 全部达标，无需整改
          </div>
          <div v-else class="issues">
            <div v-for="iss in selected.issues" :key="iss.id" class="issue">
              <div class="issue__main">
                <div class="issue__desc">{{ iss.desc }}</div>
                <div class="issue__meta">
                  <span><CIcon name="profile" :size="12" /> {{ iss.owner }}</span>
                  <span><CIcon name="clock" :size="12" /> 截止 {{ fmtDate(iss.dueAt) }}</span>
                  <span v-if="iss.hasPhoto" class="issue__photo">
                    <CIcon name="box" :size="12" /> 已附照片
                  </span>
                </div>
              </div>
              <div class="issue__ops">
                <CStatusPill :status="dictPill(RECTIFY_STATUS[iss.status]).status">{{ dictPill(RECTIFY_STATUS[iss.status]).text }}</CStatusPill>
                <CButton
                  v-if="iss.status !== 'DONE'"
                  variant="ghost" size="sm"
                  v-perm.disable="'inspection:edit'"
                  @click="openAssign(selected.id, iss.id)"
                >
                  <CIcon name="profile" :size="14" />指派
                </CButton>
                <CButton
                  v-if="iss.status !== 'DONE'"
                  variant="primary" size="sm"
                  v-perm.disable="'inspection:edit'"
                  @click="doComplete(selected.id, iss.id)"
                >
                  <CIcon name="check" :size="14" />完成整改
                </CButton>
              </div>
            </div>
          </div>
        </div>
      </CCard>

      <CCard v-else class="ip__detail ip__detail--empty" title="检查单详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="shield" :size="40" class="detail-empty__icon" />
          <p>请选择一条检查单</p>
        </div>
      </CCard>
    </div>

    <!-- 新建检查弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建巡店检查" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">门店</label>
              <CInput v-model="form.store" placeholder="如：静安旗舰店" />
            </div>
            <div>
              <label class="form__label">检查类型</label>
              <CSelect v-model="form.type" :options="[
                { value: 'ENV', label: '环境' },
                { value: 'SERVICE', label: '服务' },
                { value: 'COMPLIANCE', label: '合规' },
              ]" width="100%" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">检查人</label>
              <CInput v-model="form.inspector" placeholder="检查人姓名" />
            </div>
            <div>
              <label class="form__label">检查日期</label>
              <CInput v-model="form.inspectedAt" placeholder="YYYY-MM-DD" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">检查项打分（0-10）</label>
            <div class="score-rows">
              <div v-for="(it, idx) in form.items" :key="idx" class="score-row">
                <CInput v-model="it.name" />
                <div class="score-row__ctl">
                  <button type="button" class="step" @click="setScore(idx, -1)">−</button>
                  <span class="score-row__val" :class="`score--${scoreTone(it.score * 10)}`">{{ it.score }}</span>
                  <button type="button" class="step" @click="setScore(idx, 1)">+</button>
                </div>
              </div>
            </div>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" @click="submitForm">提交检查</CButton>
        </template>
      </CCard>
    </div>

    <!-- 指派弹层 -->
    <div v-if="showAssign" class="modal-mask" @click.self="showAssign = null">
      <CCard class="modal modal--sm" title="指派整改责任人" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">责任人</label>
            <CSelect v-model="assignOwner" :options="ownerOptions" width="100%" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showAssign = null">取消</CButton>
          <CButton variant="primary" @click="submitAssign">确认指派</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ip { display: flex; flex-direction: column; gap: var(--s-lg); }
.ip__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ip__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.ip__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ip__list { min-width: 0; display: flex; flex-direction: column; }
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
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__title { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); }
.row__meta b { font-weight: 600; }
.score--success { color: var(--c-success-fg); }
.score--warning { color: var(--c-warning-fg); }
.score--danger { color: var(--c-danger-fg); }

.detail__head { display: flex; justify-content: space-between; gap: var(--s-md); width: 100%; align-items: flex-start; }
.detail__no { font-size: var(--t-lg); font-weight: 700; margin: 0 0 var(--s-xs); color: var(--c-text); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); }
.tag { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-surface-muted, #f0f2f5); color: var(--c-text-2); }
.tag--type { background: var(--c-brand-soft); color: var(--c-brand); }
.detail__score { display: flex; flex-direction: column; align-items: flex-end; gap: var(--s-xs); }
.detail__score-label { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__score-val { font-size: var(--t-xl); font-weight: 700; line-height: 1; }

.section { margin-top: var(--s-lg); }
.section__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }

.items { display: flex; flex-direction: column; gap: var(--s-xs); }
.item {
  display: grid; grid-template-columns: 1fr auto; gap: var(--s-sm); align-items: center;
  padding: var(--s-sm) var(--s-md); background: var(--c-surface-muted, #f7f8fa);
  border-radius: var(--r-md);
}
.item__name { font-size: var(--t-sm); color: var(--c-text); }
.item__note { grid-column: 1 / 2; font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.score-pill {
  font-size: var(--t-xs); font-weight: 600; padding: 2px 10px; border-radius: var(--r-capsule);
}
.score-pill--success { background: var(--c-success-bg); color: var(--c-success-fg); }
.score-pill--warning { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.score-pill--danger { background: var(--c-danger-bg); color: var(--c-danger-fg); }

.issues { display: flex; flex-direction: column; gap: var(--s-sm); }
.issues-empty { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-success-fg); padding: var(--s-md); background: var(--c-success-bg); border-radius: var(--r-md); }
.issue {
  display: flex; justify-content: space-between; gap: var(--s-md); align-items: center;
  padding: var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-md);
}
.issue__main { flex: 1; min-width: 0; }
.issue__desc { font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); }
.issue__meta { display: flex; flex-wrap: wrap; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); }
.issue__meta span { display: inline-flex; align-items: center; gap: 4px; }
.issue__photo { color: var(--c-teal-dark, var(--c-brand)); }
.issue__ops { display: flex; align-items: center; gap: var(--s-xs); flex-shrink: 0; }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 620px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--sm { width: 380px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }

.score-rows { display: flex; flex-direction: column; gap: var(--s-xs); }
.score-row { display: grid; grid-template-columns: 1fr 120px; gap: var(--s-sm); align-items: center; }
.score-row__ctl { display: flex; align-items: center; gap: var(--s-sm); justify-content: flex-end; }
.step { width: 28px; height: 28px; border-radius: var(--r-capsule); border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text); cursor: pointer; font-size: var(--t-md); line-height: 1; }
.step:hover { border-color: var(--c-brand); color: var(--c-brand); }
.score-row__val { min-width: 28px; text-align: center; font-weight: 600; font-size: var(--t-md); }

@media (max-width: 1024px) {
  .ip__body { grid-template-columns: 1fr; }
  .ip__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .list { max-height: 320px; }
  .detail__head { flex-direction: column; }
  .detail__score { align-items: flex-start; flex-direction: row; gap: var(--s-sm); align-items: center; }
  .issue { flex-direction: column; align-items: flex-start; }
  .issue__ops { width: 100%; justify-content: flex-end; }
}
</style>
