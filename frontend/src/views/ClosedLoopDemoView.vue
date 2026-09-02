<script setup lang="ts">
/* ============================================================
 * 客户到店闭环样板（/closed-loop）
 * 目的：展示"有逻辑的页面"长什么样——页面只是状态机的可视化。
 * 数据：来自独立领域 store（customer/arrival/consultation/appointment/activity/settings），
 *       不再集中在单一 clinic.ts。每个 store 对应一个聚合根 + 其状态机。
 * 权限：分诊=reception:edit；咨询=consult:edit（v-perm 同源）。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import { useCustomerStore } from '@/stores/customer'
import { useArrivalStore } from '@/stores/arrival'
import { useConsultationStore } from '@/stores/consultation'
import { useAppointmentStore } from '@/stores/appointment'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import { ADVISORS, DOCTORS, staffName } from '@/config/staff'
import { seedClinicData } from '@/composables/seedClinicData'
import type { Role, TriageType } from '@/types/domain'
import CButton from '@/components/CButton.vue'
import CCard from '@/components/CCard.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CKpi from '@/components/CKpi.vue'
import CInput from '@/components/CInput.vue'

const customer = useCustomerStore()
const arrival = useArrivalStore()
const consultation = useConsultationStore()
const appointment = useAppointmentStore()
const activity = useActivityStore()
const auth = useAuthStore()

onMounted(() => seedClinicData())

// 角色 chip（支持多角色叠加，演示"一人兼咨询师+店长"权限并集）
const ROLE_CHIPS = [
  { label: '集团管理员', value: 'SUPER_ADMIN' },
  { label: '区域经理', value: 'REGION_MGR' },
  { label: '门店店长', value: 'STORE_MGR' },
  { label: '咨询顾问', value: 'CONSULTANT' },
  { label: '医生', value: 'DOCTOR' },
  { label: '前台/收银', value: 'FRONT_DESK' },
  { label: '运营', value: 'OPERATOR' },
  { label: '财务', value: 'FINANCE' },
] as { label: string; value: Role }[]

function isRoleOn(r: Role) {
  return auth.currentRoles.includes(r)
}
function toggleRole(r: Role) {
  auth.toggleRole(r)
}
function comboMgrConsult() {
  auth.loginAs('STORE_MGR')
  auth.toggleRole('CONSULTANT')
}

const canSeePhone = computed(() => auth.can('customer:phone:decrypt'))
const canSeeMargin = computed(() => auth.can('finance:margin:view'))
const canTriage = computed(() => auth.can('reception:edit'))
const canConsult = computed(() => auth.can('consult:edit'))

const waiting = computed(() => arrival.waiting)
const consulting = computed(() => [...consultation.pending, ...consultation.active])
const doneArrivals = computed(() => arrival.done)

const customerName = (id: string) => customer.nameOf(id)
const customerPhone = (id: string) => customer.phoneOf(id)

// ---- 分诊表单 ----
const triageTarget = ref('')
const triageType = ref<TriageType>('CONSULT')
const triageAssign = ref('')
const triageNote = ref('')
const assignOptions = computed(() =>
  (triageType.value === 'MEDICAL' ? DOCTORS : ADVISORS).map((s) => ({
    label: `${s.name}（${s.title}）`,
    value: s.id,
  })),
)
function openTriage(arrivalId: string) {
  triageTarget.value = arrivalId
  triageType.value = 'CONSULT'
  triageAssign.value = ADVISORS[0].id
  triageNote.value = ''
}
function onTypeChange() {
  triageAssign.value = triageType.value === 'MEDICAL' ? DOCTORS[0].id : ADVISORS[0].id
}
function confirmTriage() {
  if (!triageTarget.value || !triageAssign.value) return
  arrival.triage(triageTarget.value, { type: triageType.value, assignedTo: triageAssign.value, note: triageNote.value })
  triageTarget.value = ''
}

// ---- 咨询完成表单 ----
const consultTarget = ref('')
const consultConclusion = ref('')
const consultAmount = ref('')
const consultBookAt = ref('明日 14:00')
function openConsult(cid: string) {
  consultTarget.value = cid
  consultConclusion.value = ''
  consultAmount.value = ''
  consultBookAt.value = '明日 14:00'
}
function startConsult(cid: string) {
  consultation.start(cid)
}
function confirmConsult() {
  if (!consultTarget.value) return
  const c = consultation.get(consultTarget.value)
  if (!c) return
  const amount = Number(consultAmount.value) || 0
  // 咨询师快捷开单 → 提交医生二次审核（不再直接派生预约/收款）
  consultation.submitPlan(consultTarget.value, {
    conclusion: consultConclusion.value || '初步方案已确认',
    planItems: amount > 0
      ? [{ name: consultConclusion.value || '咨询方案项目', qty: 1, price: amount }]
      : (c.planItems ?? [{ name: '咨询方案项目', qty: 1, price: 1000 }]),
    doctorId: 'staff-gu',
    contraindications: c.contraindications ?? {
      pregnant: false, allergy: false, scarConstitution: false,
      skinLesion: false, coagulationAbn: false, seriousIllness: false,
    },
    consentConsultant: true,
    consentCustomer: true,
    customerName: customer.nameOf(c.customerId),
  })
  consultTarget.value = ''
}

function apptFor(customerId: string) {
  return appointment.byCustomer(customerId)[0]?.id
}
function planFor(customerId: string) {
  return consultation.byCustomer(customerId).find((c) => c.planAmount)
}

function pillOf(status: string): { status: any; text: string } {
  switch (status) {
    case 'WAITING': return { status: 'warning', text: '候诊' }
    case 'TRIAGED': return { status: 'primary', text: '已分诊' }
    case 'CALLED': return { status: 'info', text: '已呼叫' }
    case 'DONE': return { status: 'success', text: '已完成' }
    case 'LEFT': return { status: 'danger', text: '已离开' }
    case 'PENDING': return { status: 'default', text: '待咨询' }
    case 'ACTIVE': return { status: 'primary', text: '咨询中' }
    case 'PLANNED': return { status: 'success', text: '已出方案' }
    default: return { status: 'default', text: status }
  }
}
</script>

<template>
  <div class="loop">
    <!-- 角色切换 + 说明 -->
    <div class="loop__bar">
      <div class="loop__bar-left">
        <span class="loop__hint">这是逻辑样板：菜单与按钮随角色权限实时变化，状态集中在 store 流转</span>
      </div>
      <div class="loop__bar-right">
        <span class="loop__role-label">模拟角色（可叠加）</span>
        <div class="chips">
          <button
            v-for="r in ROLE_CHIPS"
            :key="r.value"
            class="chip"
            :class="{ 'chip--on': isRoleOn(r.value) }"
            @click="toggleRole(r.value)"
          >{{ r.label }}</button>
          <button class="chip chip--combo" @click="comboMgrConsult">店长+咨询师</button>
        </div>
        <CStatusPill :status="auth.isSuper ? 'danger' : 'info'" dot>
          {{ auth.user.roleLabels }} · 数据域 {{ auth.scope }}
        </CStatusPill>
      </div>
    </div>

    <!-- KPI -->
    <div class="loop__kpis">
      <CKpi :value="String(waiting.length)" label="候诊中" tone="warning" icon="calendar" />
      <CKpi :value="String(consulting.length)" label="咨询中" tone="brand" icon="chat" />
      <CKpi :value="String(appointment.appointments.length)" label="已生成预约" tone="teal" icon="export" />
      <CKpi :value="String(activity.items.length)" label="活动流水" tone="text" icon="marketing" />
    </div>

    <div class="loop__main">
      <!-- 三列看板 -->
      <div class="loop__board">
        <!-- 列1 候诊 -->
        <CCard title="① 候诊（到店/排队）" class="col">
          <div v-if="!waiting.length" class="col__empty">暂无候诊客户</div>
          <div v-for="a in waiting" :key="a.id" class="cust">
            <div class="cust__top">
              <span class="cust__avatar">{{ customerName(a.customerId)[0] }}</span>
              <div class="cust__meta">
                <div class="cust__name">{{ customerName(a.customerId) }}
                  <span class="cust__id">{{ a.customerId }}</span>
                </div>
                <div class="cust__sub">号 {{ a.queueNo }} · {{ a.arrivedAt }} · {{ a.channel === 'WALK_IN' ? '自然到店' : a.channel === 'REFERRAL' ? '转介绍' : '线上预约' }}</div>
                <div class="cust__sub">
                  <span v-if="canSeePhone">{{ customerPhone(a.customerId) }}</span>
                  <span v-else class="cust__mask">{{ customer.customers.find(c => c.id === a.customerId)?.phoneMask }}</span>
                </div>
              </div>
              <CStatusPill :status="pillOf(a.status).status">{{ pillOf(a.status).text }}</CStatusPill>
            </div>
            <div v-if="triageTarget === a.id" class="form">
              <div class="form__row">
                <span class="form__lbl">类型</span>
                <CSelect v-model="triageType" :options="[{label:'顾问咨询',value:'CONSULT'},{label:'医生面诊',value:'MEDICAL'},{label:'直接服务',value:'SERVICE'}]" width="120px" @update:modelValue="onTypeChange" />
                <span class="form__lbl">分配</span>
                <CSelect v-model="triageAssign" :options="assignOptions" width="140px" />
              </div>
              <CInput v-model="triageNote" placeholder="分诊备注（可选）" />
              <div class="form__actions">
                <CButton variant="ghost" size="sm" @click="triageTarget = ''">取消</CButton>
                <CButton variant="primary" size="sm" @click="confirmTriage">确认分诊</CButton>
              </div>
            </div>
            <CButton v-else v-perm.disable="'reception:edit'" variant="secondary" size="sm" block @click="openTriage(a.id)">
              {{ canTriage ? '分诊' : '无分诊权限' }}
            </CButton>
          </div>
        </CCard>

        <!-- 列2 咨询中 -->
        <CCard title="② 咨询（分诊 → 出方案）" class="col">
          <div v-if="!consulting.length" class="col__empty">暂无进行中的咨询</div>
          <div v-for="c in consulting" :key="c.id" class="cust">
            <div class="cust__top">
              <span class="cust__avatar">{{ customerName(c.customerId)[0] }}</span>
              <div class="cust__meta">
                <div class="cust__name">{{ customerName(c.customerId) }}
                  <span class="cust__id">{{ c.customerId }}</span>
                </div>
                <div class="cust__sub">分配给 {{ staffName(c.consultantId) }} · {{ c.id }}</div>
              </div>
              <CStatusPill :status="pillOf(c.status).status">{{ pillOf(c.status).text }}</CStatusPill>
            </div>
            <div v-if="consultTarget === c.id" class="form">
              <CInput v-model="consultConclusion" placeholder="咨询结论" />
              <div class="form__row">
                <CInput v-model="consultAmount" placeholder="方案金额 ¥" type="number" />
                <CInput v-model="consultBookAt" placeholder="预约时间" />
              </div>
              <div class="form__actions">
                <CButton variant="ghost" size="sm" @click="consultTarget = ''">取消</CButton>
                <CButton variant="primary" size="sm" @click="confirmConsult">完成咨询并生成预约</CButton>
              </div>
            </div>
            <CButton v-else-if="c.status === 'PENDING'" v-perm.disable="'consult:edit'" variant="secondary" size="sm" block @click="startConsult(c.id)">
              {{ canConsult ? '开始咨询' : '无咨询权限' }}
            </CButton>
            <CButton v-else v-perm.disable="'consult:edit'" variant="primary" size="sm" block @click="openConsult(c.id)">
              {{ canConsult ? '完成咨询' : '无咨询权限' }}
            </CButton>
          </div>
        </CCard>

        <!-- 列3 已完成 -->
        <CCard title="③ 已闭环（到达店终点）" class="col">
          <div v-if="!doneArrivals.length" class="col__empty">暂无完成的到店</div>
          <div v-for="a in doneArrivals" :key="a.id" class="cust cust--done">
            <div class="cust__top">
              <span class="cust__avatar">{{ customerName(a.customerId)[0] }}</span>
              <div class="cust__meta">
                <div class="cust__name">{{ customerName(a.customerId) }}</div>
                <div class="cust__sub">已离店 · 关联预约 {{ apptFor(a.customerId) || '—' }}</div>
                <div class="cust__sub">
                  方案金额 ¥{{ planFor(a.customerId)?.planAmount ?? 0 }}
                  <template v-if="canSeeMargin">
                    · 成本 ¥{{ planFor(a.customerId)?.planCost ?? 0 }}
                    <span class="cust__margin">（毛利可见）</span>
                  </template>
                  <span v-else class="cust__mask">· 毛利 ****</span>
                </div>
              </div>
              <CStatusPill status="success">已完成</CStatusPill>
            </div>
          </div>
        </CCard>
      </div>

      <!-- 活动流水（闭环可见性） -->
      <CCard title="实时活动流水" class="loop__feed">
        <div v-for="act in activity.items" :key="act.id" class="feed__item">
          <span class="feed__time">{{ act.at }}</span>
          <span class="feed__text"><b>{{ act.actor }}</b> {{ act.text }}</span>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.loop { display: flex; flex-direction: column; gap: var(--s-md); }
.loop__bar {
  display: flex; align-items: center; justify-content: space-between;
  gap: var(--s-md); flex-wrap: wrap;
  background: var(--c-surface); border: 1px solid var(--c-border-light);
  border-radius: var(--r-xl); padding: var(--s-sm) var(--s-md);
}
.loop__hint { font-size: var(--t-sm); color: var(--c-text-3); }
.loop__bar-right { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.loop__role-label { font-size: var(--t-sm); color: var(--c-text-2); }
.chips { display: flex; flex-wrap: wrap; gap: 4px; }
.chip {
  font-size: var(--t-xs); padding: 3px 10px; border-radius: var(--r-pill);
  border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text-2);
  cursor: pointer; transition: all .15s;
}
.chip:hover { border-color: var(--c-brand); color: var(--c-brand); }
.chip--on { background: var(--c-brand); border-color: var(--c-brand); color: #fff; }
.chip--combo { border-style: dashed; border-color: var(--c-brand); color: var(--c-brand); }
.cust__mask { color: var(--c-text-3); letter-spacing: 1px; }
.cust__margin { color: var(--c-teal, #12b886); font-size: var(--t-xs); }
.loop__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.loop__main { display: flex; gap: var(--s-md); align-items: flex-start; }
.loop__board { flex: 1; min-width: 0; display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.col { display: flex; flex-direction: column; }
.col__empty { color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-lg) 0; text-align: center; }
.cust { border: 1px solid var(--c-border-light); border-radius: var(--r-lg); padding: var(--s-sm); margin-bottom: var(--s-sm); background: var(--c-surface); }
.cust--done { opacity: 0.92; }
.cust__top { display: flex; align-items: center; gap: var(--s-sm); }
.cust__avatar { width: 32px; height: 32px; border-radius: var(--r-avatar); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.cust__meta { flex: 1; min-width: 0; }
.cust__name { font-size: var(--t-base); font-weight: 600; color: var(--c-text); display: flex; align-items: center; gap: var(--s-xs); }
.cust__id { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.cust__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.cust :deep(.c-btn) { margin-top: var(--s-sm); }
.form { margin-top: var(--s-sm); display: flex; flex-direction: column; gap: var(--s-xs); border-top: 1px dashed var(--c-border); padding-top: var(--s-sm); }
.form__row { display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.form__lbl { font-size: var(--t-xs); color: var(--c-text-2); }
.form__actions { display: flex; gap: var(--s-xs); justify-content: flex-end; }
.loop__feed { width: 320px; flex-shrink: 0; max-height: 560px; overflow-y: auto; }
.feed__item { display: flex; gap: var(--s-xs); padding: var(--s-xs) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); line-height: var(--lh-sm); }
.feed__time { color: var(--c-text-3); flex-shrink: 0; font-variant-numeric: tabular-nums; }
.feed__text { color: var(--c-text-2); }
.feed__text b { color: var(--c-text); }

@media (max-width: 1024px) {
  .loop__kpis { grid-template-columns: repeat(2, 1fr); }
  .loop__main { flex-direction: column; }
  .loop__board { grid-template-columns: 1fr; }
  .loop__feed { width: 100%; max-height: none; }
}
</style>
