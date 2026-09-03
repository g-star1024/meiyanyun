<script setup lang="ts">
/* T1 权限中台 · 员工管理 /admin/staff
 * 对接 org-service：员工花名册（/org/staff）+ 管理写端点（/org/admin/staff/*）
 * 写操作权限：建/停/重置密码/调店/主角色 = rbac:edit；兼岗授予/摘除 = role:assign
 * 角色选项仅「启用」状态；停用角色后端 assertRoleUsable 拦截（400 中文提示）。
 * 错误一律走 toast（成功）/ 原生 alert（删除类确认），无假交互。 */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import CDrawer from '@/components/CDrawer.vue'
import CCheckbox from '@/components/CCheckbox.vue'
import {
  listStaff, listRoles, listStores, getStaffRoles,
  createStaff, disableStaff, resetStaffPassword, transferStaff,
  setPrimaryRole, addStaffRole, removeStaffRole,
  type Staff, type RoleDef, type Store as OrgStore,
} from '@/api/org'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const toast = useToast()

// ---------------- 数据 ----------------
const staff = ref<Staff[]>([])
const roles = ref<RoleDef[]>([])
const stores = ref<OrgStore[]>([])
const loading = ref(false)

// ---------------- 过滤 ----------------
const filterStore = ref('ALL')
const filterStatus = ref('ALL')
const keyword = ref('')

// ---------------- 权限（与 @RequirePerm 对齐） ----------------
const canManage = computed(() => auth.can('rbac:edit'))
const canAssign = computed(() => auth.can('role:assign'))

// ---------------- 抽屉 ----------------
type DrawerKind = '' | 'create' | 'transfer' | 'roles'
const drawerKind = ref<DrawerKind>('')
const drawerOpen = computed({
  get: () => drawerKind.value !== '',
  set: (v: boolean) => { if (!v) drawerKind.value = '' },
})
const drawerTitle = computed(() => {
  if (drawerKind.value === 'create') return '新建员工'
  if (drawerKind.value === 'transfer') return `调店 / 调区 · ${activeStaff.value?.staffName ?? ''}`
  if (drawerKind.value === 'roles') return `角色管理 · ${activeStaff.value?.staffName ?? ''}`
  return ''
})
const activeStaff = ref<Staff | null>(null)
const submitting = ref(false)

// 新建员工表单
const createForm = reactive({
  staffId: '', staffName: '', roleCode: '', storeCode: '', region: '', medicalLicensed: false,
})

// 调店/调区表单（空值保持 null，提交时区分门店/区域岗）
const transferForm = reactive({ storeCode: '', region: '' })

// 角色抽屉状态
const staffRoles = ref<string[]>([])
const staffPrimary = ref('')
const rolesLoading = ref(false)
const pickRoleCode = ref('')

// ---------------- 派生 ----------------
const roleName = (code: string | null | undefined) =>
  roles.value.find((r) => r.roleCode === code)?.roleName ?? code ?? '—'
const storeName = (code: string | null | undefined) =>
  stores.value.find((s) => s.storeCode === code)?.storeName ?? code ?? '—'

const activeRoles = computed(() =>
  roles.value
    .filter((r) => !r.status || r.status === '启用')
    .sort((a, b) => Number.parseInt(a.roleSequence || '90', 10) - Number.parseInt(b.roleSequence || '90', 10)),
)
const roleOptions = computed(() => [
  { value: '', label: '请选择角色' },
  ...activeRoles.value.map((r) => ({ value: r.roleCode, label: `${r.roleName}（${r.roleCode}）` })),
])
const storeOptions = computed(() => [
  { value: '', label: '请选择门店' },
  ...stores.value.map((s) => ({ value: s.storeCode, label: s.storeName })),
])
const filterStoreOptions = computed(() => [
  { value: 'ALL', label: '全部门店' },
  ...stores.value.map((s) => ({ value: s.storeCode, label: s.storeName })),
])

const filtered = computed(() => staff.value.filter((s) => {
  if (filterStatus.value !== 'ALL' && s.status !== filterStatus.value) return false
  if (filterStore.value !== 'ALL' && s.storeCode !== filterStore.value) return false
  if (keyword.value.trim()) {
    const kw = keyword.value.trim().toLowerCase()
    if (!s.staffId.toLowerCase().includes(kw) && !s.staffName.toLowerCase().includes(kw)) return false
  }
  return true
}))

const kpis = computed(() => [
  { label: '员工总数', icon: 'customer', value: String(staff.value.length), tone: 'brand' as const },
  { label: '在职', icon: 'user-check', value: String(staff.value.filter((s) => s.status === '在职').length), tone: 'success' as const },
  { label: '离职', icon: 'logout', value: String(staff.value.filter((s) => s.status === '离职').length), tone: 'danger' as const },
  { label: '启用角色', icon: 'shield', value: String(activeRoles.value.length), tone: 'orange' as const },
])

function fmtTime(iso?: string) {
  if (!iso) return '—'
  return iso.replace('T', ' ').slice(0, 16)
}

function errMsg(e: unknown, fallback: string): string {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || fallback
}

// ---------------- 数据装载 ----------------
async function seed() {
  loading.value = true
  try {
    const [staffRes, rolesRes, storesRes] = await Promise.all([listStaff(), listRoles(), listStores()])
    staff.value = staffRes.data
    roles.value = rolesRes.data
    stores.value = storesRes.data
  } catch (e) {
    toast.error(errMsg(e, '员工数据载入失败，请稍后重试'))
  } finally {
    loading.value = false
  }
}

// ---------------- 新建员工 ----------------
function openCreate() {
  createForm.staffId = ''
  createForm.staffName = ''
  createForm.roleCode = ''
  createForm.storeCode = ''
  createForm.region = ''
  createForm.medicalLicensed = false
  drawerKind.value = 'create'
}

async function submitCreate() {
  if (!createForm.staffId.trim() || !createForm.staffName.trim() || !createForm.roleCode) {
    toast.warning('请填写工号、姓名并选择主角色')
    return
  }
  submitting.value = true
  try {
    await createStaff({
      staffId: createForm.staffId.trim(),
      staffName: createForm.staffName.trim(),
      roleCode: createForm.roleCode,
      storeCode: createForm.storeCode || null,
      region: createForm.region.trim() || null,
      medicalLicensed: createForm.medicalLicensed,
    })
    toast.success(`员工 ${createForm.staffName.trim()} 已创建，初始密码为 meiyun123，请提示本人尽快修改`)
    drawerKind.value = ''
    await seed()
  } catch (e) {
    toast.error(errMsg(e, '员工创建失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
}

// ---------------- 停用 ----------------
async function onDisable(s: Staff) {
  if (!window.confirm(`确认将员工 ${s.staffName}（${s.staffId}）置为离职？离职后该账号无法登录，操作不可逆。`)) return
  try {
    await disableStaff(s.staffId)
    toast.success(`员工 ${s.staffName} 已离职`)
    await seed()
  } catch (e) {
    toast.error(errMsg(e, '停用失败，请稍后重试'))
  }
}

// ---------------- 重置密码 ----------------
async function onResetPwd(s: Staff) {
  if (!window.confirm(`确认为 ${s.staffName}（${s.staffId}）重置登录密码？重置后为系统默认密码。`)) return
  try {
    const res = await resetStaffPassword(s.staffId)
    toast.success(`密码已重置，新默认密码：${res.data.defaultPassword}，请提示本人登录后尽快修改`)
  } catch (e) {
    toast.error(errMsg(e, '密码重置失败，请稍后重试'))
  }
}

// ---------------- 调店 / 调区 ----------------
function openTransfer(s: Staff) {
  activeStaff.value = s
  transferForm.storeCode = s.storeCode ?? ''
  transferForm.region = s.region ?? ''
  drawerKind.value = 'transfer'
}

async function submitTransfer() {
  const s = activeStaff.value
  if (!s) return
  if (!transferForm.storeCode && !transferForm.region.trim()) {
    toast.warning('请选择调入门店或填写调入区域')
    return
  }
  submitting.value = true
  try {
    await transferStaff(s.staffId, {
      storeCode: transferForm.storeCode || null,
      region: transferForm.storeCode ? null : transferForm.region.trim() || null,
    })
    toast.success(`${s.staffName} 的归属已调整`)
    drawerKind.value = ''
    await seed()
  } catch (e) {
    toast.error(errMsg(e, '调店失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
}

// ---------------- 角色管理（主角色 + 兼岗） ----------------
async function openRoles(s: Staff) {
  activeStaff.value = s
  pickRoleCode.value = ''
  staffRoles.value = []
  staffPrimary.value = ''
  drawerKind.value = 'roles'
  rolesLoading.value = true
  try {
    const res = await getStaffRoles(s.staffId)
    staffPrimary.value = res.data.primaryRole
    staffRoles.value = res.data.roles
  } catch (e) {
    toast.error(errMsg(e, '角色信息载入失败，请稍后重试'))
  } finally {
    rolesLoading.value = false
  }
}

const assignableRoles = computed(() =>
  activeRoles.value.filter((r) => !staffRoles.value.includes(r.roleCode)),
)
const assignRoleOptions = computed(() => [
  { value: '', label: '选择要授予的兼岗角色' },
  ...assignableRoles.value.map((r) => ({ value: r.roleCode, label: `${r.roleName}（${r.roleCode}）` })),
])

async function onAddRole() {
  const s = activeStaff.value
  if (!s || !pickRoleCode.value) return
  try {
    await addStaffRole(s.staffId, pickRoleCode.value)
    toast.success(`已授予兼岗：${roleName(pickRoleCode.value)}`)
    staffRoles.value = [...staffRoles.value, pickRoleCode.value]
    pickRoleCode.value = ''
    await seed()
  } catch (e) {
    toast.error(errMsg(e, '角色授予失败，请稍后重试'))
  }
}

async function onRemoveRole(roleCode: string) {
  const s = activeStaff.value
  if (!s) return
  if (roleCode === staffPrimary.value) {
    toast.warning('主角色不可直接摘除，请先通过「调整主角色」切换')
    return
  }
  if (!window.confirm(`确认摘除 ${s.staffName} 的兼岗「${roleName(roleCode)}」？`)) return
  try {
    await removeStaffRole(s.staffId, roleCode)
    toast.success(`兼岗 ${roleName(roleCode)} 已摘除`)
    staffRoles.value = staffRoles.value.filter((c) => c !== roleCode)
    await seed()
  } catch (e) {
    toast.error(errMsg(e, '角色摘除失败，请稍后重试'))
  }
}

async function onSetPrimary(roleCode: string) {
  const s = activeStaff.value
  if (!s || roleCode === staffPrimary.value) return
  if (!window.confirm(`确认将 ${s.staffName} 的主角色切换为「${roleName(roleCode)}」？原主角色将保留为兼岗。`)) return
  try {
    await setPrimaryRole(s.staffId, roleCode)
    toast.success(`主角色已切换为 ${roleName(roleCode)}`)
    staffPrimary.value = roleCode
    if (!staffRoles.value.includes(roleCode)) staffRoles.value = [...staffRoles.value, roleCode]
    await seed()
  } catch (e) {
    toast.error(errMsg(e, '主角色调整失败，请稍后重试'))
  }
}

onMounted(() => { seed() })
</script>

<template>
  <div class="vw">
    <div class="vw__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="vw__body">
      <CCard padding="none">
        <div class="list-head">
          <div class="list-head__left">
            <span class="list-head__title">员工花名册</span>
            <span class="list-head__hint">{{ filtered.length }} 人</span>
          </div>
          <div class="list-head__filters">
            <CInput v-model="keyword" placeholder="搜索工号 / 姓名" />
            <CSelect v-model="filterStatus" width="120px" :options="[
              { value: 'ALL', label: '全部状态' },
              { value: '在职', label: '在职' },
              { value: '离职', label: '离职' },
            ]" />
            <CSelect v-model="filterStore" width="160px" :options="filterStoreOptions" />
            <CButton v-if="canManage" variant="primary" size="sm" @click="openCreate">
              <CIcon name="plus" :size="14" />新建员工
            </CButton>
          </div>
        </div>

        <div v-if="loading" class="state-row">
          <CIcon name="loading" :size="16" />员工数据加载中…
        </div>
        <div v-else-if="filtered.length === 0" class="state-row">
          <CIcon name="customer" :size="16" />暂无符合条件的员工
        </div>

        <div v-else class="grid">
          <div class="grid__head">
            <span>工号</span>
            <span>姓名</span>
            <span>主角色</span>
            <span>归属门店 / 区域</span>
            <span>医疗岗</span>
            <span>状态</span>
            <span class="col-ops">操作</span>
          </div>
          <div v-for="s in filtered" :key="s.staffId" class="grid__row">
            <span class="cell-id">{{ s.staffId }}</span>
            <span class="cell-name">
              {{ s.staffName }}
              <small class="cell-sub">{{ fmtTime(s.createdAt) }} 入职</small>
            </span>
            <span class="cell-role">{{ roleName(s.roleCode) }}</span>
            <span class="cell-store">
              <template v-if="s.storeCode">{{ storeName(s.storeCode) }}</template>
              <template v-else-if="s.region">{{ s.region }}（区域岗）</template>
              <template v-else>—</template>
            </span>
            <span>
              <CStatusPill v-if="s.medicalLicensed" status="info" dot>持证</CStatusPill>
              <span v-else class="cell-muted">—</span>
            </span>
            <span>
              <CStatusPill :status="s.status === '在职' ? 'success' : 'disabled'" dot>{{ s.status }}</CStatusPill>
            </span>
            <span class="col-ops">
              <CButton variant="text" size="sm" @click="openRoles(s)">角色</CButton>
              <CButton v-if="canManage && s.status === '在职'" variant="text" size="sm" @click="openTransfer(s)">调店</CButton>
              <CButton v-if="canManage && s.status === '在职'" variant="text" size="sm" @click="onResetPwd(s)">重置密码</CButton>
              <CButton v-if="canManage && s.status === '在职'" variant="text" size="sm" @click="onDisable(s)">离职</CButton>
            </span>
          </div>
          <div class="grid__foot">
            <span class="cell-muted">入职时间以账号创建时间为准；停用角色不可授予员工（后端强校验）。</span>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 抽屉：新建员工 -->
    <CDrawer v-if="drawerKind === 'create'" v-model:show="drawerOpen" title="新建员工" size="md">
      <div class="form">
        <label class="field">
          <span class="field__label">工号 <i>*</i></span>
          <CInput v-model="createForm.staffId" placeholder="如 E101（大写字母+数字，全局唯一）" />
        </label>
        <label class="field">
          <span class="field__label">姓名 <i>*</i></span>
          <CInput v-model="createForm.staffName" placeholder="员工真实姓名" />
        </label>
        <label class="field">
          <span class="field__label">主角色 <i>*</i></span>
          <CSelect v-model="createForm.roleCode" width="100%" :options="roleOptions" />
        </label>
        <label class="field">
          <span class="field__label">归属门店（门店岗必填）</span>
          <CSelect v-model="createForm.storeCode" width="100%" :options="storeOptions" />
        </label>
        <label class="field">
          <span class="field__label">归属区域（区域/集团岗填写，如 华东大区）</span>
          <CInput v-model="createForm.region" placeholder="门店岗留空" />
        </label>
        <label class="field field--row">
          <CCheckbox v-model="createForm.medicalLicensed">
            <span class="field__label field__label--inline">医疗执业持证人员（医生/护士等）</span>
          </CCheckbox>
        </label>
        <div class="form-tip">
          <CIcon name="info" :size="13" />新建账号初始密码为 <b>meiyun123</b>，登录名为工号；请提示员工首次登录后尽快修改密码。
        </div>
      </div>
      <template #footer>
        <CButton variant="secondary" @click="drawerKind = ''">取消</CButton>
        <CButton variant="primary" :disabled="submitting" @click="submitCreate">创建员工</CButton>
      </template>
    </CDrawer>

    <!-- 抽屉：调店 / 调区 -->
    <CDrawer v-else-if="drawerKind === 'transfer'" v-model:show="drawerOpen" :title="drawerTitle" size="md">
      <div class="form">
        <label class="field">
          <span class="field__label">调入门店</span>
          <CSelect v-model="transferForm.storeCode" width="100%" :options="storeOptions" />
        </label>
        <label class="field">
          <span class="field__label">调入区域（区域/集团岗填写，与门店二选一）</span>
          <CInput v-model="transferForm.region" placeholder="如 华东大区" />
        </label>
        <div class="form-tip">
          <CIcon name="info" :size="13" />选择门店后将按门店归属；仅填区域则为区域岗。调店操作记入审计日志。
        </div>
      </div>
      <template #footer>
        <CButton variant="secondary" @click="drawerKind = ''">取消</CButton>
        <CButton variant="primary" :disabled="submitting" @click="submitTransfer">确认调整</CButton>
      </template>
    </CDrawer>

    <!-- 抽屉：角色管理 -->
    <CDrawer v-else-if="drawerKind === 'roles'" v-model:show="drawerOpen" :title="drawerTitle" size="md">
      <div v-if="rolesLoading" class="state-row">
        <CIcon name="loading" :size="16" />角色信息加载中…
      </div>
      <div v-else class="form">
        <div class="field">
          <span class="field__label">已授予角色（{{ staffRoles.length }}）</span>
          <div class="role-list">
            <div v-for="code in staffRoles" :key="code" class="role-item">
              <div class="role-item__info">
                <span class="role-item__name">{{ roleName(code) }}</span>
                <span class="role-item__code">{{ code }}</span>
              </div>
              <CStatusPill v-if="code === staffPrimary" status="primary" dot>主角色</CStatusPill>
              <CButton
                v-else-if="canAssign"
                variant="text" size="sm"
                @click="onSetPrimary(code)"
              >设为主角色</CButton>
              <CButton
                v-if="canAssign && code !== staffPrimary"
                variant="text" size="sm"
                @click="onRemoveRole(code)"
              >摘除</CButton>
            </div>
            <div v-if="staffRoles.length === 0" class="cell-muted">暂无角色</div>
          </div>
        </div>

        <div v-if="canAssign" class="field">
          <span class="field__label">授予兼岗角色</span>
          <div class="role-add">
            <CSelect v-model="pickRoleCode" width="100%" :options="assignRoleOptions" />
            <CButton variant="secondary" :disabled="!pickRoleCode" @click="onAddRole">授予</CButton>
          </div>
          <div class="form-tip">
            <CIcon name="info" :size="13" />仅可授予「启用」状态角色；主角色切换后原主角色自动保留为兼岗；主角色不可直接摘除。
          </div>
        </div>
        <div v-else class="form-tip">
          <CIcon name="shield" :size="13" />当前账号无角色分配权限（role:assign），仅可查看。
        </div>
      </div>
    </CDrawer>
  </div>
</template>

<style scoped>
.vw { display: flex; flex-direction: column; gap: var(--s-lg); }
.vw__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .vw__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.vw__body { display: block; }

.list-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.list-head__left { display: flex; align-items: center; gap: var(--s-sm); }
.list-head__title { font-size: var(--t-sm); font-weight: 700; }
.list-head__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.list-head__filters { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.list-head__filters :deep(.cinput) { width: 180px; }

.state-row { display: flex; align-items: center; justify-content: center; gap: var(--s-sm); padding: var(--s-xl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }

.grid { padding: 0 var(--s-lg) var(--s-md); }
.grid__head, .grid__row {
  display: grid;
  grid-template-columns: 90px 100px minmax(130px, 1.2fr) minmax(180px, 1.4fr) 70px 80px minmax(240px, 2fr);
  gap: var(--s-sm);
  align-items: center;
  padding: var(--s-sm) 0;
  font-size: var(--t-sm);
}
.grid__head { border-bottom: 1px solid var(--c-border-light); color: var(--c-text-3); font-size: var(--t-xs); font-weight: 600; }
.grid__row { border-bottom: 1px solid var(--c-border-light); min-height: 44px; }
.grid__foot { padding-top: var(--s-sm); }
.cell-id { font-family: monospace; color: var(--c-text-2); }
.cell-name { font-weight: 600; display: flex; flex-direction: column; gap: 2px; }
.cell-sub { font-weight: 400; font-size: var(--t-xs); color: var(--c-text-3); }
.cell-role { color: var(--c-brand); font-weight: 600; }
.cell-store { color: var(--c-text-2); }
.cell-muted { color: var(--c-text-3); font-size: var(--t-xs); }
.col-ops { display: flex; gap: 2px; justify-content: flex-end; flex-wrap: wrap; }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.field { display: flex; flex-direction: column; gap: 6px; }
.field--row { flex-direction: row; align-items: center; }
.field__label { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.field__label--inline { font-weight: 500; }
.field__label i { color: var(--c-danger-fg); font-style: normal; }
.form-tip { display: flex; align-items: flex-start; gap: 6px; font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6; }
.form-tip b { color: var(--c-text-2); }

.role-list { display: flex; flex-direction: column; gap: var(--s-sm); }
.role-item { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-sm); }
.role-item__info { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.role-item__name { font-size: var(--t-sm); font-weight: 600; }
.role-item__code { font-size: var(--t-xs); color: var(--c-text-3); font-family: monospace; }
.role-add { display: flex; gap: var(--s-sm); align-items: center; }

@media (max-width: 1200px) {
  .grid__head, .grid__row { grid-template-columns: 80px 90px 1fr 1fr; }
  .grid__head span:nth-child(5), .grid__row > span:nth-child(5),
  .grid__head span:nth-child(6), .grid__row > span:nth-child(6) { display: none; }
  .col-ops { grid-column: 1 / -1; justify-content: flex-start; }
}
</style>
