<script setup lang="ts">
/* ============================================================
 * 公海池 /customer-pool（Desktop 优先）
 * 未分配门店的客户池：认领（写回当前门店）/ 分配（指定门店与负责人）。
 * 数据源：customer-service /api/customer/m3/pool；手机掩码展示。
 * 认领冲突（409「客户已被认领」）自动刷新列表。
 * ============================================================ */
import { onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CIcon from '@/components/CIcon.vue'
import {
  listPool,
  claimPoolCustomer,
  assignPoolCustomer,
  type PoolItemDTO,
} from '@/api/customer'
import { useAuthStore } from '@/stores/auth'
import { useActivityStore } from '@/stores/activity'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'

const auth = useAuthStore()
const activity = useActivityStore()
const toast = useToast()

const list = ref<PoolItemDTO[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    list.value = await listPool()
  } catch (e) {
    toast.error(errMsg(e, '公海池加载失败'))
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function doClaim(item: PoolItemDTO) {
  try {
    await claimPoolCustomer(item.id)
    list.value = list.value.filter((x) => x.id !== item.id)
    activity.log(auth.user.name, `公海认领客户 ${item.name}`, item.id)
    toast.success(`已认领客户 ${item.name}`)
  } catch (e) {
    toast.error(errMsg(e, '认领失败'))
    await load()
  }
}

const assignTarget = ref<PoolItemDTO | null>(null)
const assignForm = ref({ storeCode: '', ownerStaffId: '' })

function openAssign(item: PoolItemDTO) {
  assignTarget.value = item
  assignForm.value = { storeCode: '', ownerStaffId: '' }
}

async function doAssign() {
  const t = assignTarget.value
  if (!t) return
  const storeCode = assignForm.value.storeCode.trim()
  const ownerStaffId = assignForm.value.ownerStaffId.trim()
  if (!storeCode || !ownerStaffId) {
    toast.error('门店编码与负责人必填')
    return
  }
  try {
    await assignPoolCustomer(t.id, storeCode, ownerStaffId)
    list.value = list.value.filter((x) => x.id !== t.id)
    activity.log(auth.user.name, `公海分配客户 ${t.name} → ${storeCode}`, t.id)
    toast.success(`已分配客户 ${t.name}`)
    assignTarget.value = null
  } catch (e) {
    toast.error(errMsg(e, '分配失败'))
    await load()
  }
}
</script>

<template>
  <div class="pool">
    <CCard class="pool__head" padding="none">
      <div class="pool__head-row">
        <div class="pool__title">
          <CIcon name="customer" :size="20" />
          <div>
            <div class="pool__title-main">客户公海池</div>
            <div class="pool__title-sub">未分配门店的客户 · 认领或分配后从池中移除 · 当前 {{ list.length }} 人</div>
          </div>
        </div>
        <CButton variant="ghost" :disabled="loading" @click="load">
          <CIcon name="refresh" :size="16" />刷新
        </CButton>
      </div>
    </CCard>

    <CCard class="pool__body" padding="none">
      <div v-if="loading && list.length === 0" class="empty">
        <CIcon name="refresh" :size="28" class="empty__icon" />
        <div>加载中…</div>
      </div>
      <div v-else-if="list.length === 0" class="empty">
        <CIcon name="check" :size="28" class="empty__icon" />
        <div>公海池暂无客户</div>
      </div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>客户姓名</th>
            <th>手机号</th>
            <th>会员等级</th>
            <th class="tbl__ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="item in list" :key="item.id">
            <tr>
              <td class="tbl__name">{{ item.name }}</td>
              <td>{{ item.phone }}</td>
              <td>{{ item.level || '—' }}</td>
              <td class="tbl__ops">
                <CButton variant="ghost" size="sm" v-perm.disable="'customer:edit'" @click="openAssign(item)">
                  分配
                </CButton>
                <CButton variant="primary" size="sm" v-perm.disable="'customer:edit'" @click="doClaim(item)">
                  <CIcon name="check" :size="14" />认领
                </CButton>
              </td>
            </tr>
            <tr v-if="assignTarget?.id === item.id" class="assign-row">
              <td colspan="4">
                <div class="assign-box">
                  <span class="assign-box__label">分配 {{ item.name }} 至：</span>
                  <CInput v-model="assignForm.storeCode" placeholder="门店编码（如 store-jingan）" />
                  <CInput v-model="assignForm.ownerStaffId" placeholder="负责人员工编号" />
                  <CButton variant="primary" size="sm" @click="doAssign">确认分配</CButton>
                  <CButton variant="ghost" size="sm" @click="assignTarget = null">取消</CButton>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </CCard>
  </div>
</template>

<style scoped>
.pool {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px 24px;
}
.pool__head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
}
.pool__title {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--c-text);
}
.pool__title-main {
  font-size: 16px;
  font-weight: 600;
}
.pool__title-sub {
  font-size: 12px;
  color: var(--c-text-3);
  margin-top: 2px;
}
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 56px 0;
  color: var(--c-text-3);
  font-size: 13px;
}
.empty__icon {
  opacity: 0.5;
}
.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.tbl th {
  text-align: left;
  padding: 12px 16px;
  color: var(--c-text-3);
  font-weight: 500;
  border-bottom: 1px solid var(--c-border);
  background: var(--c-bg-soft);
}
.tbl td {
  padding: 12px 16px;
  border-bottom: 1px solid var(--c-border);
  color: var(--c-text);
}
.tbl__name {
  font-weight: 600;
}
.tbl__ops {
  text-align: right;
  white-space: nowrap;
}
.tbl__ops :deep(.c-button + .c-button) {
  margin-left: 8px;
}
.assign-row td {
  background: var(--c-bg-soft);
}
.assign-box {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.assign-box__label {
  font-size: 13px;
  color: var(--c-text-2);
}
.assign-box :deep(.c-input) {
  width: 220px;
}
</style>
