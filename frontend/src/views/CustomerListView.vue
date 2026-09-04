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
import { listCustomers, createCustomer, type CustomerDTO } from '@/api/customer'
import { useToast } from '@/composables/useToast'
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
const toast = useToast()

function errMsg(e: any, fallback: string) {
  return e?.response?.data?.message || e?.message || fallback
}

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

// ---- 新建客户弹层（照抄字典管理弹层范式；归属门店/归属人由后端按登录上下文注入） ----
const showForm = ref(false)
const saving = ref(false)
const form = ref({
  name: '',
  phone: '',
  gender: '女',
  level: '普通',
  channel: 'WALK_IN',
  birthDate: '',
})

const GENDER_OPTIONS = [
  { label: '女', value: '女' },
  { label: '男', value: '男' },
  { label: '其他', value: '其他' },
]
// 等级为全站中文契约（与真实库 customer.level 一致）
const LEVEL_OPTIONS = [
  { label: '普通会员', value: '普通' },
  { label: '银卡会员', value: '银卡' },
  { label: '金卡会员', value: '金卡' },
  { label: '钻石会员', value: '钻石' },
  { label: '黑卡会员', value: '黑卡' },
]
// 渠道选项对齐 CUSTOMER_SOURCE 字典（7 码）
const CHANNEL_OPTIONS = Object.values(CUSTOMER_SOURCE).map(s => ({ label: s.label, value: s.value }))

function openCreateForm() {
  form.value = { name: '', phone: '', gender: '女', level: '普通', channel: 'WALK_IN', birthDate: '' }
  showForm.value = true
}

async function saveForm() {
  const f = form.value
  if (!f.name.trim()) { toast.error('请填写客户姓名'); return }
  if (!/^1[3-9]\d{9}$/.test(f.phone.trim())) { toast.error('请填写正确的 11 位手机号'); return }
  saving.value = true
  try {
    await createCustomer({
      name: f.name.trim(),
      phone: f.phone.trim(),
      gender: f.gender,
      level: f.level,
      channel: f.channel,
      birthDate: f.birthDate || null,
      storeCode: null,
    })
    toast.success('客户已新建')
    showForm.value = false
    await load()
  } catch (e) {
    toast.error('新建失败：' + errMsg(e, '网络异常'))
  } finally {
    saving.value = false
  }
}

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
          <CButton v-perm="'customer:create'" variant="primary" size="sm" @click="openCreateForm">
            <CIcon name="plus" :size="14" />
            新建客户
          </CButton>
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

    <!-- 新建客户弹窗（标记照抄字典管理；归属门店/归属人由后端按登录人自动填充） -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard title="新建客户" class="modal">
        <div class="form">
          <div class="form-row">
            <CInput v-model="form.name" label="客户姓名" placeholder="请输入姓名" />
          </div>
          <div class="form-row">
            <CInput v-model="form.phone" label="手机号" placeholder="11 位大陆手机号" />
          </div>
          <div class="form-row">
            <label class="date-label">性别</label>
            <CSelect v-model="form.gender" :options="GENDER_OPTIONS" width="100%" />
          </div>
          <div class="form-row">
            <label class="date-label">会员等级</label>
            <CSelect v-model="form.level" :options="LEVEL_OPTIONS" width="100%" />
          </div>
          <div class="form-row">
            <label class="date-label">获客渠道</label>
            <CSelect v-model="form.channel" :options="CHANNEL_OPTIONS" width="100%" />
          </div>
          <div class="form-row">
            <label class="date-label">生日（可选）</label>
            <input v-model="form.birthDate" type="date" class="date-field" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="saving" @click="saveForm">
            {{ saving ? '保存中…' : '保存' }}
          </CButton>
        </template>
      </CCard>
    </div>
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

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}
.modal {
  width: 500px;
  max-width: 90vw;
  max-height: 90vh;
  overflow-y: auto;
}
.form {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.form-row {
  display: flex;
  flex-direction: column;
  gap: var(--s-xs);
}
/* 生日原生日期控件：样式对齐 CInput（label 13px / padding 10 / 圆角 6 / 边框 #D1D1D9） */
.date-label {
  font-size: 13px;
  font-weight: 400;
  color: var(--c-text);
  line-height: 18px;
}
.date-field {
  width: 100%;
  padding: 10px;
  border: 1px solid #D1D1D9;
  border-radius: var(--r-sm);
  background: var(--c-surface);
  font-size: 13px;
  color: var(--c-text);
  line-height: 20px;
}
.date-field:focus {
  outline: none;
  border-color: #4D5AD9;
  box-shadow: 0 0 0 2px rgba(77, 90, 217, 0.12);
}
</style>
