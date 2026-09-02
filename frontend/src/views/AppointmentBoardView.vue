<script setup lang="ts">
/* ============================================================
 * M4-01 预约看板（/appointment）
 * 数据源：txn-service 预约域真实 API（/txn/appointment、/board）。
 * 库内状态中文四态：已预约 / 已到店 / 未到诊 / 已取消；来源 B端登记/C端小程序/C端App。
 * 列表客户名/门店名/医生名由后端读模型冗余返回（零技术码外露）。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import CKpi from '@/components/CKpi.vue'
import CCard from '@/components/CCard.vue'
import CTable from '@/components/CTable.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CSegmented from '@/components/CSegmented.vue'
import CButton from '@/components/CButton.vue'
import CSelect from '@/components/CSelect.vue'
import { useToast } from '@/composables/useToast'
import {
  listAppointments, appointmentBoard, checkIn, noShow, cancelAppointment,
  type AppointmentView, type BoardStats,
} from '@/api/appointment'
import { useStoreContext } from '@/stores/storeContext'

const router = useRouter()
const toast = useToast()
const storeCtx = useStoreContext()

/* ---------- 筛选条件 ---------- */
// 门店隔离：默认锁定全局当前门店（侧栏切换联动），业务页不跨店混排
const storeOptions = computed(() =>
  storeCtx.stores.map((s) => ({ label: s.storeName || s.storeCode, value: s.storeCode })),
)
const storeCode = computed(() => storeCtx.currentStoreCode)
const dateOptions = [
  { label: '今天', value: 'TODAY' },
  { label: '明天', value: 'TOMORROW' },
  { label: '全部日期', value: 'ALL' },
]
const dateRange = ref('TODAY')

function dateParam(): string | undefined {
  if (dateRange.value === 'ALL') return undefined
  const d = new Date()
  if (dateRange.value === 'TOMORROW') d.setDate(d.getDate() + 1)
  return d.toISOString().slice(0, 10)
}

/* ---------- 数据 ---------- */
const loading = ref(false)
const list = ref<AppointmentView[]>([])
const stats = ref<BoardStats>({ total: 0, booked: 0, arrived: 0, noShow: 0, cancelled: 0, arrivalRate: 0 })

const FILTERS = [
  { label: '全部', value: '全部' },
  { label: '已预约', value: '已预约' },
  { label: '已到店', value: '已到店' },
  { label: '未到诊', value: '未到诊' },
  { label: '已取消', value: '已取消' },
]
const filter = ref('全部')

async function load() {
  loading.value = true
  try {
    const date = dateParam()
    const [l, b] = await Promise.all([
      listAppointments(storeCode.value || undefined, date),
      appointmentBoard(storeCode.value || undefined, date),
    ])
    list.value = l.data
    stats.value = b.data
  } catch (e: any) {
    console.error('[appointment-board] 加载失败', e)
    toast.error('预约数据加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // 门店列表来自全局上下文（main.ts 已 init）；本页不再单独请求
  if (!storeCtx.loaded) await storeCtx.loadStores()
  await load()
})

// 侧栏 / 页内切换门店 → 重新拉取
watch(() => storeCtx.currentStoreCode, () => { load() })

const rows = computed(() => {
  const data = filter.value === '全部' ? list.value : list.value.filter((a) => a.status === filter.value)
  return [...data]
    .sort((a, b) => (a.apptDate + a.apptTime).localeCompare(b.apptDate + b.apptTime))
    .map((a) => ({
      id: a.apptNo,
      date: a.apptDate.slice(5),
      time: a.apptTime,
      customer: a.customerName || (a.customerId ? a.customerId : '散客'),
      project: a.project || '—',
      store: a.storeName || a.storeCode || '—',
      doctor: a.doctorName || a.doctor || '不指定',
      source: a.source,
      status: a.status,
    }))
})

const columns = [
  { key: 'date', label: '日期', width: 64 },
  { key: 'time', label: '时间', width: 70 },
  { key: 'customer', label: '客户' },
  { key: 'project', label: '预约项目' },
  { key: 'store', label: '门店', width: 110 },
  { key: 'doctor', label: '医生', width: 90 },
  { key: 'source', label: '来源', width: 96 },
  { key: 'status', label: '状态', width: 90, align: 'center' as const },
  { key: 'actions', label: '操作', width: 176, align: 'right' as const },
]

function pillOf(status: string) {
  switch (status) {
    case '已预约': return { status: 'info', text: '已预约' }
    case '已到店': return { status: 'success', text: '已到店' }
    case '未到诊': return { status: 'danger', text: '未到诊' }
    case '已取消': return { status: 'default', text: '已取消' }
    default: return { status: 'default', text: status }
  }
}

const busy = ref<string>('')
async function act(no: string, kind: 'in' | 'noshow' | 'cancel') {
  busy.value = no + kind
  try {
    if (kind === 'in') {
      await checkIn(no)
      toast.success('已签到到店')
    } else if (kind === 'noshow') {
      await noShow(no)
      toast.success('已标记未到诊')
    } else {
      await cancelAppointment(no)
      toast.success('预约已取消')
    }
    await load()
  } catch (e: any) {
    toast.error('操作失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <div class="board">
    <div class="board__kpis">
      <CKpi :value="String(stats.total)" label="预约总数" tone="brand" icon="calendar" />
      <CKpi :value="String(stats.booked)" label="已预约" tone="text" icon="clock" />
      <CKpi :value="String(stats.arrived)" label="已到店" tone="teal" icon="user-check" />
      <CKpi :value="String(stats.noShow)" label="未到诊" tone="danger" icon="alert" />
      <CKpi :value="Math.round(stats.arrivalRate * 100) + '%'" label="到店率" tone="warning" icon="trend-up" />
    </div>

    <CCard>
      <div class="board__toolbar">
        <CSegmented v-model="filter" :options="FILTERS" size="sm" />
        <div class="board__filters">
          <CSelect v-model="dateRange" :options="dateOptions" width="120" @change="load" />
          <CSelect
            :model-value="storeCode"
            :options="storeOptions"
            width="140"
            @update:model-value="(v: string) => storeCtx.setStore(v)"
          />
          <CButton variant="primary" size="sm" v-perm="'appointment:create'" @click="router.push('/appointment/new')">
            新建预约
          </CButton>
        </div>
      </div>

      <CTable :columns="columns" :rows="rows" row-key="id" :loading="loading" empty-text="暂无预约记录">
        <template #col-status="{ row }">
          <CStatusPill :status="pillOf(row.status).status as any">{{ pillOf(row.status).text }}</CStatusPill>
        </template>
        <template #col-actions="{ row }">
          <div class="row-actions" v-if="row.status === '已预约'">
            <CButton
              v-perm.disable="'appointment:edit'"
              variant="primary" size="sm" :disabled="busy === row.id + 'in'"
              @click="act(row.id, 'in')"
            >到店</CButton>
            <CButton
              v-perm.disable="'appointment:edit'"
              variant="ghost" size="sm" :disabled="busy === row.id + 'noshow'"
              @click="act(row.id, 'noshow')"
            >未到诊</CButton>
            <CButton
              v-perm.disable="'appointment:edit'"
              variant="ghost" size="sm" :disabled="busy === row.id + 'cancel'"
              @click="act(row.id, 'cancel')"
            >取消</CButton>
          </div>
          <span v-else class="row-done">—</span>
        </template>
      </CTable>
    </CCard>
  </div>
</template>

<style scoped>
.board { display: flex; flex-direction: column; gap: var(--s-md); }
.board__kpis { display: grid; grid-template-columns: repeat(5, 1fr); gap: var(--s-md); }
.board__toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); margin-bottom: var(--s-md); flex-wrap: wrap; }
.board__filters { display: flex; gap: var(--s-xs); align-items: center; }
.row-actions { display: flex; gap: 4px; justify-content: flex-end; }
.row-done { color: var(--c-text-4); }
@media (max-width: 1024px) {
  .board__kpis { grid-template-columns: repeat(2, 1fr); }
}
</style>
