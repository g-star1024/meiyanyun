<script setup lang="ts">
/* ============================================================
 * 会员列表（/customers）
 * 数据源：真实 customer-service（经国密网关 /api/customer 转发）。
 * 列表受数据域约束（SELF/STORE/REGION/GROUP）——由后端按 auth 过滤。
 * 手机号字段级 RBAC：持 customer:phone:decrypt 显明文，否则本地脱敏。
 * 枚举契约对齐真实库（全站中文展示）：level=普通/银卡/金卡/钻石/黑卡。
 * channel 为内部来源码，展示时经 CUSTOMER_SOURCE 转中文。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { listCustomers, type CustomerDTO } from '@/api/customer'
import CCard from '@/components/CCard.vue'
import CTable from '@/components/CTable.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import { CUSTOMER_SOURCE } from '@/config/dictionary'

const router = useRouter()
const auth = useAuthStore()

function openProfile(id: string) {
  router.push(`/customers/${id}`)
}

// ---- 状态 ----
const loading = ref(false)
const total = ref(0)
const rows = ref<{
  id: string
  name: string
  phone: string
  level: string
  channel: string
  owner: string
  tags: string[]
}[]>([])

const keyword = ref('')
const level = ref('ALL')

// 会员等级全站中文契约（与真实库 customer.level 一致）：value=中文等级，ALL=不过滤
const LEVELS = [
  { label: '全部等级', value: 'ALL' },
  { label: '普通会员', value: '普通' },
  { label: '银卡会员', value: '银卡' },
  { label: '金卡会员', value: '金卡' },
  { label: '钻石会员', value: '钻石' },
  { label: '黑卡会员', value: '黑卡' },
]

// 等级 -> 状态胶囊（全中文展示，等级越高色越重）
type PillStatus = 'default' | 'info' | 'warning' | 'primary' | 'danger'
const LEVEL_PILL: Record<string, { status: PillStatus; text: string }> = {
  '普通': { status: 'default', text: '普通会员' },
  '银卡': { status: 'info', text: '银卡会员' },
  '金卡': { status: 'warning', text: '金卡会员' },
  '钻石': { status: 'primary', text: '钻石会员' },
  '黑卡': { status: 'danger', text: '黑卡会员' },
}
function levelPill(lv?: string | null): { status: PillStatus; text: string } {
  return (lv && LEVEL_PILL[lv]) || { status: 'default', text: '普通会员' }
}

const canSeePhone = computed(() => auth.can('customer:phone:decrypt'))

function maskPhone(p?: string | null): string {
  if (!p) return '—'
  return p.length === 11 ? p.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2') : p
}

function safeChannel(code?: string | null): string {
  if (!code) return '未登记'
  return CUSTOMER_SOURCE[code as keyof typeof CUSTOMER_SOURCE]?.label || code
}

async function load() {
  loading.value = true
  try {
    const res = await listCustomers({
      page: 0,
      size: 200,
      level: level.value === 'ALL' ? undefined : level.value,
      keyword: keyword.value.trim() || undefined,
    })
    const data = res.data
    total.value = data.totalElements
    rows.value = data.content.map((c: CustomerDTO) => ({
      id: c.customerId,
      name: c.name,
      phone: canSeePhone.value ? (c.phone ?? '—') : maskPhone(c.phone),
      level: c.level ?? '普通',
      channel: safeChannel(c.channel),
      owner: c.ownerStaffName || c.ownerStaffId || '公海',
      tags: c.tags ?? [],
    }))
  } catch (e) {
    console.error('[CustomerList] 加载客户列表失败', e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const columns = [
  { key: 'name', label: '客户' },
  { key: 'phone', label: '手机号' },
  { key: 'level', label: '等级', width: 110, align: 'center' as const },
  { key: 'channel', label: '来源', width: 100 },
  { key: 'owner', label: '归属', width: 120 },
  { key: 'tags', label: '标签' },
]

</script>

<template>
  <div class="cust">
    <CCard>
      <div class="cust__toolbar">
        <CSelect v-model="level" :options="LEVELS" width="140px" @change="load" />
        <CInput v-model="keyword" placeholder="搜索姓名 / 手机号" @input="load" />
        <div class="cust__tools-right">
          <span class="cust__scope">数据域：{{ auth.scope }}</span>
          <CButton v-perm="'customer:create'" variant="primary" size="sm">新建客户</CButton>
        </div>
      </div>

      <CTable :columns="columns" :rows="rows" row-key="id" :loading="loading" empty-text="暂无客户">
        <template #col-name="{ row }">
          <button class="cust__name-link" @click="openProfile(row.id)">
            {{ row.name }}
            <CIcon name="chevron-right" :size="13" />
          </button>
        </template>
        <template #col-level="{ row }">
          <CStatusPill :status="levelPill(row.level).status">{{ levelPill(row.level).text }}</CStatusPill>
        </template>
        <template #col-tags="{ row }">
          <span v-for="t in row.tags" :key="t" class="tag">{{ t }}</span>
        </template>
      </CTable>

      <div v-if="total" class="cust__total">共 {{ total }} 位客户</div>
    </CCard>
  </div>
</template>

<style scoped>
.cust__toolbar { display: flex; align-items: center; gap: var(--s-sm); margin-bottom: var(--s-md); flex-wrap: wrap; }
.cust__toolbar :deep(.c-input) { width: 220px; }
.cust__tools-right { margin-left: auto; display: flex; align-items: center; gap: var(--s-sm); }
.cust__scope { font-size: var(--t-sm); color: var(--c-text-3); }
.cust__name-link { display: inline-flex; align-items: center; gap: 2px; border: none; background: none; padding: 0; font: inherit; color: var(--c-brand); font-weight: 600; cursor: pointer; }
.cust__name-link:hover { text-decoration: underline; }
.tag { display: inline-block; font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-pill); background: var(--c-brand-soft); color: var(--c-brand); margin-right: 4px; }
.cust__total { margin-top: var(--s-sm); font-size: var(--t-sm); color: var(--c-text-3); text-align: right; }
.cust__dup { margin-top: var(--s-md); }
</style>
