<script setup lang="ts">
/* ============================================================
 * M4-02 新建预约（/appointment/new）
 * 双路径：搜已有客户 / 新客建档（真实 customer-service）。
 * 预约落 txn-service：来源仅 B端登记/C端小程序/C端App；doctor 为员工工号（后端富化中文名）。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { useToast } from '@/composables/useToast'
import { searchCustomers, createCustomer, type CustomerDTO } from '@/api/customer'
import { listStores, listStaff, type Staff } from '@/api/org'
import { createAppointment, crossCheck, type AppointmentView } from '@/api/appointment'

const router = useRouter()
const toast = useToast()

/* ---------- 基础下拉数据 ---------- */
const storeOptions = ref<{ label: string; value: string }[]>([])
const doctorOptions = ref<{ label: string; value: string }[]>([{ label: '不指定', value: '' }])
const sourceOptions = [
  { label: 'B端登记（门店代约）', value: 'B端登记' },
  { label: 'C端小程序', value: 'C端小程序' },
  { label: 'C端App', value: 'C端App' },
]
onMounted(async () => {
  // 门店与员工独立容错：门店服务不可用时不拖垮医生下拉（医生来自 org-service）
  const [storesRes, staffRes] = await Promise.allSettled([listStores(), listStaff()])
  if (storesRes.status === 'fulfilled') {
    storeOptions.value = ((storesRes.value.data as any[]) || []).map((s) => ({
      label: s.storeName || s.storeCode, value: s.storeCode,
    }))
  } else {
    console.error('[appt-new] 门店列表加载失败', storesRes.reason)
  }
  if (staffRes.status === 'fulfilled') {
    // 仅列执业资质医生（medicalLicensed=true）：治疗师等 DOCTOR 角色无执业资质者不进接诊下拉
    const docs = ((staffRes.value.data as Staff[]) || []).filter(
      (s) => s.roleCode === 'DOCTOR' && s.medicalLicensed === true,
    )
    doctorOptions.value = [
      { label: '不指定', value: '' },
      ...docs.map((s) => ({ label: s.staffName, value: s.staffId })),
    ]
  } else {
    console.error('[appt-new] 员工列表加载失败', staffRes.reason)
    toast.error('医生数据加载失败')
  }
})

/* ---------- 客户：搜已有 / 新建 ---------- */
const keyword = ref('')
const searching = ref(false)
const searchResults = ref<CustomerDTO[]>([])
const selectedCustomer = ref<CustomerDTO | null>(null)
const showNewForm = ref(false)

let searchTimer: any = null
function onSearch() {
  clearTimeout(searchTimer)
  const kw = keyword.value.trim()
  if (!kw) { searchResults.value = []; return }
  searchTimer = setTimeout(async () => {
    searching.value = true
    try {
      const res = await searchCustomers(kw)
      searchResults.value = res.data || []
    } catch (e) {
      console.error('[appt-new] 客户搜索失败', e)
    } finally {
      searching.value = false
    }
  }, 300)
}

function pickCustomer(c: CustomerDTO) {
  selectedCustomer.value = c
  keyword.value = ''
  searchResults.value = []
  showNewForm.value = false
  loadRecent(c.customerId)
}
function startNewCustomer() {
  selectedCustomer.value = null
  showNewForm.value = true
  recentAppts.value = []
}

const newName = ref('')
const newPhone = ref('')
const newGender = ref('女')
const genderOptions = [
  { label: '女', value: '女' },
  { label: '男', value: '男' },
]
const creatingCust = ref(false)
async function saveNewCustomer() {
  if (!newName.value.trim() || !/^1\d{10}$/.test(newPhone.value.trim())) {
    toast.error('请填写姓名与 11 位手机号')
    return
  }
  creatingCust.value = true
  try {
    const customerId = 'C' + Date.now()
    const res = await createCustomer({
      customerId,
      name: newName.value.trim(),
      phone: newPhone.value.trim(),
      gender: newGender.value,
      level: '普通',
      channel: 'WALK_IN',
      storeCode: apptStore.value || null,
    } as any)
    selectedCustomer.value = res.data
    toast.success('新客建档成功')
    newName.value = ''
    newPhone.value = ''
  } catch (e: any) {
    toast.error('建档失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  } finally {
    creatingCust.value = false
  }
}

/* ---------- 该客户近期预约（右侧摘要） ---------- */
const recentAppts = ref<AppointmentView[]>([])
async function loadRecent(customerId: string) {
  try {
    const res = await crossCheck(customerId)
    recentAppts.value = (res.data || []).slice(0, 5)
  } catch (e) {
    console.error('[appt-new] 近期预约加载失败', e)
  }
}

/* ---------- 预约信息 ---------- */
function isoDate(offset: number) {
  const d = new Date()
  d.setDate(d.getDate() + offset)
  return d.toISOString().slice(0, 10)
}
const dateOptions = computed(() => {
  const out: { label: string; value: string }[] = []
  const wk = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  for (let i = 0; i < 7; i++) {
    const d = new Date(); d.setDate(d.getDate() + i)
    const v = d.toISOString().slice(0, 10)
    out.push({ label: `${v.slice(5)} ${i === 0 ? '今天' : i === 1 ? '明天' : wk[d.getDay()]}`, value: v })
  }
  return out
})
const timeOptions = [
  '09:00', '09:30', '10:00', '10:30', '11:00', '11:30',
  '13:30', '14:00', '14:30', '15:00', '15:30', '16:00', '16:30', '17:00',
].map((t) => ({ label: t, value: t }))

const apptStore = ref('')
const apptDate = ref(isoDate(0))
const apptTime = ref('10:00')
const apptProject = ref('')
const apptDoctor = ref('')
const apptSource = ref('B端登记')
const submitting = ref(false)

const canSubmit = computed(
  () => !!selectedCustomer.value && !!apptStore.value && !!apptDate.value
    && !!apptTime.value && !!apptProject.value.trim() && !submitting.value,
)

async function submit() {
  if (!selectedCustomer.value || !canSubmit.value) return
  submitting.value = true
  try {
    await createAppointment({
      customerId: selectedCustomer.value.customerId,
      storeCode: apptStore.value,
      project: apptProject.value.trim(),
      apptDate: apptDate.value,
      apptTime: apptTime.value,
      doctor: apptDoctor.value || null,
      source: apptSource.value,
      operator: '前台',
    })
    toast.success('预约创建成功')
    router.push('/appointment')
  } catch (e: any) {
    const msg = e?.response?.data?.message
      || (Array.isArray(e?.response?.data?.messages) ? e.response.data.messages.join('；') : null)
      || e?.message || '网络异常'
    toast.error('预约失败：' + msg)
  } finally {
    submitting.value = false
  }
}
function cancel() { router.push('/appointment') }

function pillOf(status: string) {
  switch (status) {
    case '已预约': return { status: 'info', text: '已预约' }
    case '已到店': return { status: 'success', text: '已到店' }
    case '未到诊': return { status: 'danger', text: '未到诊' }
    case '已取消': return { status: 'default', text: '已取消' }
    default: return { status: 'default', text: status }
  }
}
</script>

<template>
  <div class="apc">
    <div class="apc__main">
      <!-- 客户卡 -->
      <CCard>
        <template #header>
          <div class="card-h">
            <h3 class="card-h__title">选择客户</h3>
            <CButton v-if="!showNewForm" variant="text" size="sm" @click="startNewCustomer">
              <CIcon name="plus" :size="14" /> 新客建档
            </CButton>
          </div>
        </template>

        <!-- 已选客户 -->
        <div v-if="selectedCustomer" class="picked">
          <div class="picked__avatar">{{ (selectedCustomer.name || '客').slice(0, 1) }}</div>
          <div class="picked__info">
            <div class="picked__name">{{ selectedCustomer.name }}</div>
            <div class="picked__sub">{{ selectedCustomer.customerId }} · {{ selectedCustomer.level }}客户</div>
          </div>
          <CButton variant="ghost" size="sm" @click="selectedCustomer = null">重选</CButton>
        </div>

        <!-- 新客建档表单 -->
        <div v-else-if="showNewForm" class="newcust">
          <div class="grid-2">
            <CInput v-model="newName" label="客户姓名" placeholder="请输入姓名" />
            <CInput v-model="newPhone" label="手机号" placeholder="11 位手机号" />
          </div>
          <div class="fld">
            <label class="fld-label">性别</label>
            <CSelect v-model="newGender" :options="genderOptions" width="100%" />
          </div>
          <div class="newcust__actions">
            <CButton variant="ghost" size="sm" @click="showNewForm = false">取消</CButton>
            <CButton variant="primary" size="sm" :disabled="creatingCust" @click="saveNewCustomer">
              {{ creatingCust ? '建档中…' : '保存并选择' }}
            </CButton>
          </div>
        </div>

        <!-- 搜索 -->
        <div v-else>
          <CInput v-model="keyword" label="搜索已有客户" placeholder="输入姓名或手机号" @input="onSearch" />
          <div v-if="keyword" class="results">
            <div v-if="searching" class="empty">搜索中…</div>
            <div
              v-for="c in searchResults"
              :key="c.customerId"
              class="result"
              @click="pickCustomer(c)"
            >
              <div class="result__avatar">{{ (c.name || '客').slice(0, 1) }}</div>
              <div class="result__info">
                <div class="result__name">{{ c.name }}</div>
                <div class="result__sub">{{ c.customerId }} · {{ c.level }}客户 · {{ c.storeName || '—' }}</div>
              </div>
              <CIcon name="chevron-right" :size="16" class="result__caret" />
            </div>
            <div v-if="!searching && !searchResults.length" class="empty">
              未找到匹配客户，可点右上角「新客建档」
            </div>
          </div>
        </div>
      </CCard>

      <!-- 预约信息卡 -->
      <CCard title="预约信息" class="form-card">
        <div class="grid-2">
          <div class="fld">
            <label class="fld-label">预约门店</label>
            <CSelect v-model="apptStore" :options="storeOptions" width="100%" />
          </div>
          <div class="fld">
            <label class="fld-label">预约来源</label>
            <CSelect v-model="apptSource" :options="sourceOptions" width="100%" />
          </div>
        </div>
        <div class="grid-2">
          <div class="fld">
            <label class="fld-label">预约日期</label>
            <CSelect v-model="apptDate" :options="dateOptions" width="100%" />
          </div>
          <div class="fld">
            <label class="fld-label">到店时间</label>
            <CSelect v-model="apptTime" :options="timeOptions" width="100%" />
          </div>
        </div>
        <CInput v-model="apptProject" label="预约项目" placeholder="如：光子嫩肤 / 热玛吉面诊" />
        <div class="fld">
          <label class="fld-label">操作医生</label>
          <CSelect v-model="apptDoctor" :options="doctorOptions" width="100%" />
        </div>
      </CCard>

      <!-- 底部操作 -->
      <div class="footer">
        <CButton variant="ghost" @click="cancel">取消</CButton>
        <CButton
          v-perm.disable="'appointment:create'"
          variant="primary"
          :disabled="!canSubmit"
          @click="submit"
        >
          {{ submitting ? '提交中…' : '确认创建预约' }}
        </CButton>
      </div>
    </div>

    <!-- 右侧摘要 -->
    <aside class="apc__side">
      <CCard title="近期预约" :header-border="false">
        <div v-if="!selectedCustomer" class="side-empty">
          <CIcon name="calendar" :size="28" class="side-empty__icon" />
          <p>选择客户后查看其近 30 天预约记录</p>
        </div>
        <ul v-else class="recent">
          <li v-for="a in recentAppts" :key="a.apptNo" class="recent__item">
            <div class="recent__time">{{ a.apptDate.slice(5) }}<br />{{ a.apptTime }}</div>
            <div class="recent__main">
              <div class="recent__proj">{{ a.project || '—' }}</div>
              <div class="recent__meta">{{ a.storeName || '—' }} · {{ a.doctorName || '不指定' }}</div>
            </div>
            <CStatusPill :status="pillOf(a.status).status as any">{{ pillOf(a.status).text }}</CStatusPill>
          </li>
          <li v-if="!recentAppts.length" class="side-empty side-empty--sm">近 30 天暂无预约</li>
        </ul>
      </CCard>
    </aside>
  </div>
</template>

<style scoped>
.apc { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: var(--s-md); align-items: start; }
.apc__main { display: flex; flex-direction: column; gap: var(--s-md); min-width: 0; }

.card-h { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.card-h__title { font-size: var(--t-md); font-weight: 700; }

.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.fld { display: flex; flex-direction: column; gap: 6px; }
.fld-label { font-size: var(--t-sm); color: var(--c-text); line-height: 18px; }

.form-card :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); }

.picked { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm); background: var(--c-brand-soft); border-radius: var(--r-md); }
.picked__avatar { width: 40px; height: 40px; border-radius: 50%; background: var(--c-brand); color: #fff; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.picked__info { flex: 1; min-width: 0; }
.picked__name { font-size: var(--t-base); font-weight: 600; color: var(--c-text); }
.picked__sub { font-size: var(--t-sm); color: var(--c-text-3); }

.newcust { display: flex; flex-direction: column; gap: var(--s-md); }
.newcust__actions { display: flex; justify-content: flex-end; gap: var(--s-xs); }

.results { margin-top: var(--s-sm); display: flex; flex-direction: column; gap: var(--s-xxs); max-height: 320px; overflow-y: auto; }
.result { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-md); cursor: pointer; transition: background 0.15s; }
.result:hover { background: var(--c-brand-soft); }
.result__avatar { width: 32px; height: 32px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand); font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.result__info { flex: 1; min-width: 0; }
.result__name { font-size: var(--t-base); color: var(--c-text); }
.result__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.result__caret { color: var(--c-text-4); }
.empty { padding: var(--s-md); text-align: center; font-size: var(--t-sm); color: var(--c-text-3); }

.footer { display: flex; justify-content: flex-end; gap: var(--s-sm); padding: var(--s-sm) 0; }

.apc__side { position: sticky; top: 0; }
.side-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xl) var(--s-md); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); }
.side-empty__icon { color: var(--c-text-4); }
.side-empty--sm { padding: var(--s-md); }
.recent { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--s-sm); }
.recent__item { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-xs) 0; border-bottom: 1px solid var(--c-border-light); }
.recent__item:last-child { border-bottom: none; }
.recent__time { font-size: var(--t-xs); font-weight: 600; color: var(--c-brand); font-variant-numeric: tabular-nums; min-width: 52px; text-align: center; line-height: 1.4; }
.recent__main { flex: 1; min-width: 0; }
.recent__proj { font-size: var(--t-sm); color: var(--c-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recent__meta { font-size: var(--t-xs); color: var(--c-text-3); }

@media (max-width: 1024px) {
  .apc { grid-template-columns: 1fr; }
  .apc__side { position: static; }
  .grid-2 { grid-template-columns: 1fr; }
}
</style>
