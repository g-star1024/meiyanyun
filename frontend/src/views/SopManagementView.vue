<script setup lang="ts">
/* ============================================================
 * 术后 SOP 编排与执行 /sop（Desktop 优先 · 平板堆叠）
 * 双面板：
 *  1) 批次执行看板——治疗完成自动生成的多节点随访批次（客户/项目/进度/超期升级）
 *  2) SOP 模板编排——节点增删改 / 启停 / 恢复默认（仅影响此后新批次）
 * 链路打通：
 *  - 批次由咨询→医生「治疗完成」时 followup.schedulePostOpSop 自动生成
 *  - 客户名跳客户画像 360；「去随访工作台」跳 /followup 登记回访
 *  - 超期节点一键升级提醒主管/医生（followup.escalateAllOverdue）
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import {
  useFollowupStore,
  type Followup,
  type SopBatch,
} from '@/stores/followup'
import { FOLLOWUP_METHOD, type FollowupMethod } from '@/config/dictionary'

const followup = useFollowupStore()
const router = useRouter()
onMounted(() => followup.seed())

type Tab = 'batches' | 'template'
const tab = ref<Tab>('batches')

const methodOptions = [
  { value: 'PHONE', label: '电话' },
  { value: 'WECHAT', label: '微信' },
  { value: 'IN_STORE', label: '到店' },
]

// ---------------- KPI ----------------
const activeBatches = computed(() => followup.sopBatches.filter((b) => !b.finished))
const finishedBatches = computed(() => followup.sopBatches.filter((b) => b.finished))
const kpis = computed(() => [
  { label: '进行中批次', value: String(activeBatches.value.length), tone: 'brand' as const, icon: 'layers' },
  { label: 'SOP 待办节点', value: String(followup.sopPending.length), tone: 'warning' as const, icon: 'clock' },
  { label: '超期节点', value: String(followup.sopOverdue.length), tone: 'danger' as const, icon: 'alert' },
  { label: '已完结批次', value: String(finishedBatches.value.length), tone: 'success' as const, icon: 'check' },
])

// ---------------- 批次看板 ----------------
const keyword = ref('')
const batches = computed<SopBatch[]>(() => {
  const kw = keyword.value.trim()
  if (!kw) return followup.sopBatches
  return followup.sopBatches.filter(
    (b) => b.customerName.includes(kw) || b.project.includes(kw) || b.batchId.includes(kw),
  )
})

function isOverdue(f: Followup) {
  if (f.status !== 'PENDING') return false
  const t = new Date(); t.setHours(0, 0, 0, 0)
  return new Date(f.planDate) < t
}
function daysFromNow(iso: string) {
  const t = new Date(); t.setHours(0, 0, 0, 0)
  const d = new Date(iso); d.setHours(0, 0, 0, 0)
  return Math.round((d.getTime() - t.getTime()) / 86400000)
}
function nodePlanLabel(f: Followup) {
  if (f.status !== 'PENDING') return f.planDate.slice(0, 10)
  const d = daysFromNow(f.planDate)
  if (d === 0) return '今日到期'
  if (d < 0) return `超期 ${-d} 天`
  if (d === 1) return '明日到期'
  return `${d} 天后`
}
function nodeStatus(f: Followup): { text: string; status: 'success' | 'warning' | 'info' | 'danger' } {
  if (f.status === 'DONE') return { text: '已回访', status: 'success' }
  if (f.status === 'SKIPPED') return { text: '无需回访', status: 'info' }
  if (f.escalated) return { text: '超期已升级', status: 'danger' }
  if (isOverdue(f)) return { text: '超期待回访', status: 'danger' }
  return { text: '待回访', status: 'warning' }
}
function openCustomer(id: string) {
  router.push(`/customers/${id}`)
}
function goFollowup() {
  router.push('/followup')
}
function doEscalateAll() {
  const n = followup.escalateAllOverdue()
  if (n > 0) {
    // 活动流已在 store 内记录，此处无需额外提示
  }
}

// ---------------- 模板编排 ----------------
const newNode = ref({ label: '', dayOffset: 14, method: 'WECHAT' as FollowupMethod })
const canAddNode = computed(() => newNode.value.label.trim() && newNode.value.dayOffset >= 0)
function addNode() {
  if (!canAddNode.value) return
  followup.addSopNode({ ...newNode.value, label: newNode.value.label.trim() })
  newNode.value = { label: '', dayOffset: 14, method: 'WECHAT' }
}
</script>

<template>
  <div class="sop">
    <!-- 超期升级预警条 -->
    <div v-if="followup.sopOverdueNeedEscalation.length > 0" class="warnbar">
      <CIcon name="alert" :size="16" />
      <span>
        有 <strong>{{ followup.sopOverdueNeedEscalation.length }}</strong> 个 SOP 节点超期未回访且未升级，
        涉及客户术后关怀，请及时处理或升级主管 / 主诊医生。
      </span>
      <CButton variant="primary" size="sm" class="warnbar__btn" v-perm.disable="'followup:edit'" @click="doEscalateAll">
        一键升级
      </CButton>
    </div>
    <!-- 已有升级节点提示条 -->
    <div v-else-if="followup.sopOverdue.some((n) => n.escalated)" class="infobar">
      <CIcon name="bell" :size="16" />
      <span>已有 <strong>{{ followup.sopOverdue.filter((n) => n.escalated).length }}</strong> 个超期节点升级至主管 / 医生，请在随访工作台跟进闭环。</span>
      <CButton variant="ghost" size="sm" @click="goFollowup">去随访工作台</CButton>
    </div>

    <!-- KPI 行 -->
    <div class="sop__kpis">
      <CKpi v-for="k in kpis" :key="k.label" :value="k.value" :label="k.label" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- Tab + 工具条 -->
    <div class="sop__bar">
      <div class="tabs">
        <button class="tab" :class="{ 'tab--active': tab === 'batches' }" @click="tab = 'batches'">
          批次执行看板（{{ followup.sopBatches.length }}）
        </button>
        <button class="tab" :class="{ 'tab--active': tab === 'template' }" @click="tab = 'template'">
          SOP 模板编排（{{ followup.enabledSopNodes.length }}/{{ followup.sopTemplate.length }} 节点启用）
        </button>
      </div>
      <div class="sop__bar-right">
        <CInput v-if="tab === 'batches'" v-model="keyword" placeholder="搜索客户 / 项目 / 批次号" class="sop__search" />
        <CButton variant="ghost" size="sm" @click="goFollowup">
          <CIcon name="phone" :size="14" />随访工作台
        </CButton>
      </div>
    </div>

    <!-- ============ 批次执行看板 ============ -->
    <div v-if="tab === 'batches'" class="batches">
      <div v-if="batches.length === 0" class="empty">
        <CIcon name="layers" :size="32" class="empty__icon" />
        <div>暂无 SOP 批次</div>
        <p>医生在医师台完成治疗后，系统会按 SOP 模板自动生成 24h 关怀 / 3 天回访 / 7 天评估 / 30 天复诊等随访节点。</p>
      </div>

      <CCard
        v-for="b in batches"
        :key="b.batchId"
        class="batch"
        :class="{ 'batch--finished': b.finished, 'batch--overdue': !b.finished && b.overdue > 0 }"
        padding="md"
      >
        <div class="batch__head">
          <div class="batch__who">
            <button class="batch__name" @click="openCustomer(b.customerId)">
              {{ b.customerName }}<CIcon name="chevron-right" :size="13" />
            </button>
            <span class="batch__proj">{{ b.project }}</span>
            <span class="batch__no">批次 {{ b.batchId }}</span>
          </div>
          <div class="batch__tags">
            <CStatusPill v-if="b.finished" status="success">已完结</CStatusPill>
            <CStatusPill v-else-if="b.overdue > 0" status="danger">{{ b.overdue }} 节点超期</CStatusPill>
            <CStatusPill v-else status="warning">进行中</CStatusPill>
          </div>
        </div>

        <div class="batch__meta">
          <span><CIcon name="clock" :size="12" />服务日期 {{ b.serviceDate.slice(0, 10) }}</span>
          <span v-if="b.nodes[0]?.relatedOrderNo"><CIcon name="box" :size="12" />订单 {{ b.nodes[0].relatedOrderNo }}</span>
        </div>

        <div class="batch__prog">
          <CProgressBar
            :value="b.done"
            :max="b.total"
            :color="b.finished ? 'var(--c-success-fg)' : b.overdue > 0 ? 'var(--c-danger-fg)' : 'var(--c-brand)'"
            :label="`${b.done}/${b.total} 节点完成`"
          />
        </div>

        <div class="nodes">
          <div
            v-for="n in b.nodes"
            :key="n.id"
            class="node"
            :class="{
              'node--done': n.status === 'DONE',
              'node--skipped': n.status === 'SKIPPED',
              'node--overdue': isOverdue(n),
              'node--escalated': n.escalated,
            }"
          >
            <div class="node__dot">
              <CIcon v-if="n.status === 'DONE'" name="check" :size="11" />
              <CIcon v-else-if="n.status === 'SKIPPED'" name="close" :size="10" />
              <CIcon v-else-if="n.escalated" name="bell" :size="10" />
              <span v-else class="node__dot-pending" />
            </div>
            <div class="node__body">
              <div class="node__label">
                {{ n.sopLabel || '随访节点' }}
                <CStatusPill :status="nodeStatus(n).status" dot>{{ nodeStatus(n).text }}</CStatusPill>
              </div>
              <div class="node__sub">
                <span><CIcon name="clock" :size="11" />{{ nodePlanLabel(n) }}</span>
                <span><CIcon name="phone" :size="11" />{{ FOLLOWUP_METHOD[n.method]?.label }}</span>
                <span v-if="n.followupByName" class="node__by">{{ n.followupByName }}</span>
              </div>
            </div>
          </div>
        </div>
      </CCard>
    </div>

    <!-- ============ SOP 模板编排 ============ -->
    <CCard v-else class="tpl" padding="md">
      <template #header>
        <div class="tpl__head">
          <h3 class="tpl__title">术后随访 SOP 模板</h3>
          <CButton variant="ghost" size="sm" @click="followup.resetSopTemplate()">
            <CIcon name="refresh" :size="13" />恢复默认
          </CButton>
        </div>
      </template>

      <p class="tpl__hint">
        <CIcon name="info" :size="13" />
        模板在医生「治疗完成」时自动实例化为客户批次：停用节点不再生成；调整天数 / 方式仅影响<strong>此后新生成</strong>的批次，历史批次不变。内置 4 个节点可停用但不可删除，自定义节点可删除。
      </p>

      <div class="tpl__list">
        <div
          v-for="(n, i) in followup.sopTemplate"
          :key="i"
          class="tpl-row"
          :class="{ 'tpl-row--off': n.enabled === false }"
        >
          <div class="tpl-row__main">
            <span class="tpl-row__name">
              <CIcon :name="n.stage === 'MANUAL' ? 'plus' : 'bell'" :size="13" />
              {{ n.label }}
              <CStatusPill v-if="n.stage !== 'MANUAL'" status="info" dot>内置</CStatusPill>
            </span>
            <span class="tpl-row__stage">术后第 {{ n.dayOffset }} 天 · {{ FOLLOWUP_METHOD[n.method]?.label }}</span>
          </div>
          <div class="tpl-row__ops">
            <div class="tpl-field">
              <label>术后第</label>
              <input
                type="number" min="0" max="365" class="tpl-num"
                :value="n.dayOffset"
                @input="followup.updateSopNode(i, { dayOffset: Number(($event.target as HTMLInputElement).value) })"
              />
              <label>天</label>
            </div>
            <CSelect
              :model-value="n.method"
              width="110px"
              :options="methodOptions"
              @update:model-value="(v) => followup.updateSopNode(i, { method: v as FollowupMethod })"
            />
            <label class="switch">
              <input type="checkbox" :checked="n.enabled !== false" @change="followup.toggleSopNode(i, ($event.target as HTMLInputElement).checked)" />
              <span class="switch__track"><span class="switch__thumb" /></span>
              <span class="switch__txt">{{ n.enabled === false ? '已停用' : '启用中' }}</span>
            </label>
            <CButton
              v-if="n.stage === 'MANUAL'"
              variant="ghost" size="sm"
              @click="followup.removeSopNode(i)"
            >删除</CButton>
          </div>
        </div>
      </div>

      <div class="tpl-add">
        <div class="tpl-add__title"><CIcon name="plus" :size="14" />新增自定义节点</div>
        <div class="tpl-add__form">
          <CInput v-model="newNode.label" placeholder="节点名称，如：第 90 天抗衰复诊" class="tpl-add__name" />
          <div class="tpl-field">
            <label>术后第</label>
            <input type="number" min="0" max="365" v-model.number="newNode.dayOffset" class="tpl-num" />
            <label>天</label>
          </div>
          <CSelect v-model="newNode.method" width="120px" :options="methodOptions" />
          <CButton variant="primary" size="sm" :disabled="!canAddNode" v-perm.disable="'followup:edit'" @click="addNode">
            添加节点
          </CButton>
        </div>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.sop { display: flex; flex-direction: column; gap: var(--s-lg); }

.warnbar {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md); border-radius: var(--r-md);
  background: var(--c-danger-bg); color: var(--c-danger-fg); font-size: var(--t-sm);
  border: 1px solid var(--c-danger-fg);
}
.warnbar strong { margin: 0 2px; }
.warnbar__btn { margin-left: auto; flex-shrink: 0; }
.infobar {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md); border-radius: var(--r-md);
  background: var(--c-warning-bg); color: var(--c-warning-fg); font-size: var(--t-sm);
  border: 1px solid var(--c-warning-fg);
}
.infobar strong { margin: 0 2px; }
.infobar :deep(.cbtn) { margin-left: auto; flex-shrink: 0; }

.sop__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }

.sop__bar { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); flex-wrap: wrap; }
.sop__bar-right { display: flex; gap: var(--s-sm); align-items: center; }
.sop__search { width: 220px; }
.tabs { display: flex; gap: var(--s-xs); border-bottom: 1px solid var(--c-border); flex: 1; min-width: 280px; }
.tab {
  padding: var(--s-sm) var(--s-md); font-size: var(--t-sm); white-space: nowrap;
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent; margin-bottom: -1px;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

/* ---------- 批次看板 ---------- */
.batches { display: flex; flex-direction: column; gap: var(--s-md); }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); text-align: center; }
.empty__icon { color: var(--c-text-4); }
.empty p { max-width: 420px; line-height: 1.7; margin: 0; }

.batch { border: 1px solid var(--c-border); }
.batch--overdue { border-color: var(--c-danger-fg); }
.batch--finished { opacity: 0.82; }

.batch__head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); flex-wrap: wrap; }
.batch__who { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.batch__name {
  display: inline-flex; align-items: center; gap: 2px;
  font-size: var(--t-md); font-weight: 700; color: var(--c-brand);
  background: none; border: none; cursor: pointer; padding: 0; font-family: inherit;
}
.batch__name:hover { text-decoration: underline; }
.batch__proj { font-size: var(--t-sm); color: var(--c-text-2); font-weight: 600; }
.batch__no { font-size: var(--t-xs); color: var(--c-text-4); }
.batch__tags { display: flex; gap: var(--s-xs); flex-shrink: 0; }

.batch__meta { display: flex; gap: var(--s-lg); margin-top: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); }
.batch__meta span { display: inline-flex; align-items: center; gap: 3px; }
.batch__prog { margin: var(--s-md) 0; }

.nodes { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--s-sm) var(--s-lg); }
.node { display: flex; gap: var(--s-sm); align-items: flex-start; padding: var(--s-sm); border-radius: var(--r-md); background: var(--c-bg-page); border: 1px solid var(--c-border-light); }
.node__dot {
  width: 20px; height: 20px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: var(--c-chart-track); color: var(--c-text-3); margin-top: 1px;
}
.node__dot-pending { width: 8px; height: 8px; border-radius: 50%; background: var(--c-text-4); }
.node--done .node__dot { background: var(--c-success-fg); color: #fff; }
.node--skipped .node__dot { background: var(--c-text-4); color: #fff; }
.node--overdue .node__dot { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.node--escalated .node__dot { background: var(--c-danger-fg); color: #fff; }
.node__body { min-width: 0; }
.node__label { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-text); flex-wrap: wrap; }
.node__sub { display: flex; gap: var(--s-md); margin-top: 3px; font-size: var(--t-xs); color: var(--c-text-3); flex-wrap: wrap; }
.node__sub span { display: inline-flex; align-items: center; gap: 3px; }
.node__by { color: var(--c-text-4); }

/* ---------- 模板编排 ---------- */
.tpl__head { display: flex; justify-content: space-between; align-items: center; }
.tpl__title { font-size: var(--t-md); font-weight: 700; color: var(--c-text); margin: 0; }
.tpl__hint {
  display: flex; gap: var(--s-xs); align-items: flex-start;
  margin: var(--s-md) 0; padding: var(--s-sm) var(--s-md);
  background: var(--c-brand-soft); border-radius: var(--r-md);
  font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.7;
}
.tpl__hint strong { color: var(--c-brand); }

.tpl__list { display: flex; flex-direction: column; gap: var(--s-sm); }
.tpl-row {
  display: flex; justify-content: space-between; align-items: center; gap: var(--s-md);
  padding: var(--s-md); border: 1px solid var(--c-border); border-radius: var(--r-lg);
  background: var(--c-surface); flex-wrap: wrap;
}
.tpl-row--off { background: var(--c-bg-page); opacity: 0.7; }
.tpl-row__main { display: flex; flex-direction: column; gap: 3px; }
.tpl-row__name { display: inline-flex; align-items: center; gap: 6px; font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.tpl-row__stage { font-size: var(--t-xs); color: var(--c-text-3); }
.tpl-row__ops { display: flex; align-items: center; gap: var(--s-md); flex-wrap: wrap; }

.tpl-field { display: inline-flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-text-3); }
.tpl-num {
  width: 64px; padding: 6px 8px; border: 1px solid var(--c-border); border-radius: var(--r-md);
  font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface);
  font-family: inherit; text-align: center;
}
.tpl-num:focus { outline: none; border-color: var(--c-brand); }

.switch { display: inline-flex; align-items: center; gap: var(--s-xs); cursor: pointer; font-size: var(--t-xs); color: var(--c-text-2); }
.switch input { position: absolute; opacity: 0; width: 0; height: 0; }
.switch__track {
  width: 36px; height: 20px; border-radius: 999px; background: var(--c-chart-track);
  position: relative; transition: background 0.2s; flex-shrink: 0;
}
.switch__thumb {
  position: absolute; top: 2px; left: 2px; width: 16px; height: 16px; border-radius: 50%;
  background: #fff; transition: transform 0.2s; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}
.switch input:checked + .switch__track { background: var(--c-brand); }
.switch input:checked + .switch__track .switch__thumb { transform: translateX(16px); }

.tpl-add { margin-top: var(--s-lg); padding-top: var(--s-md); border-top: 1px dashed var(--c-border); }
.tpl-add__title { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.tpl-add__form { display: flex; gap: var(--s-sm); align-items: center; flex-wrap: wrap; }
.tpl-add__name { width: 240px; }

@media (max-width: 1024px) {
  .sop__kpis { grid-template-columns: repeat(2, 1fr); }
  .nodes { grid-template-columns: 1fr; }
  .sop__search { width: 160px; }
}
</style>
