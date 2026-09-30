<script setup lang="ts">
// M1 集团管控 · 区域管理（B49 卡3 读侧接真；棒②卡1 写侧接真 B87）。
// 数据源：GET /api/org/tree（六区组织框架）+ GET /api/stores/regions/dist（六区门店统计，集团聚合视角）。
// 层级事实源定案（棒②卡1）：区域主数据 = org_unit 区域节点（prod 23/23 门店挂载六区与 store.region
//   中文短名 100% 一致已实证），不建独立 region 表。
// 写侧（B87 已在产，本页复用 m1Org store 命令收口）：区域新建（父=集团）/编辑/停用启用（停用必填原因）；
//   任何越权/校验以网关返回的中文 message 为准；无 org:edit 权限时写入口隐藏。
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CDrawer from '@/components/CDrawer.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { useM1RegionStore, type RegionStatus, type Region } from '@/stores/m1Region'
import { useM1OrgStore } from '@/stores/m1Org'
import { useAuthStore } from '@/stores/auth'

const region = useM1RegionStore()
const org = useM1OrgStore()
const auth = useAuthStore()
onMounted(() => {
  region.fetchAll()
  org.load().catch(() => { /* 组织树仅服务写侧表单，加载失败不影响只读浏览 */ })
})

const keyword = ref('')
const fStatus = ref('')

const filtered = computed(() => {
  const kw = keyword.value.trim()
  return region.withStats.filter((r) => {
    if (fStatus.value && r.status !== fStatus.value) return false
    if (kw && !`${r.code} ${r.name} ${r.orgName} ${r.managerName}`.includes(kw)) return false
    return true
  })
})

const kpis = computed(() => {
  const list = region.withStats
  return {
    active: list.filter((r) => r.status === 'ACTIVE').length,
    total: list.length,
    stores: list.reduce((s, r) => s + r.storeCount, 0),
    operating: list.reduce((s, r) => s + r.operatingCount, 0),
  }
})

const emptyText = computed(() => {
  if (region.loading) return '加载中…'
  if (region.error) return region.error
  return '暂无符合条件的区域'
})

function statusTone(s: RegionStatus) { return s === 'ACTIVE' ? 'success' : 'disabled' }

function errMsg(e: any, fallback: string) {
  return e?.response?.data?.message || e?.message || fallback
}

const canEdit = computed(() => auth.can('org:edit'))

// ---------- 新建 / 编辑抽屉 ----------
const drawerOpen = ref(false)
const editingId = ref<string | null>(null)
const formErr = ref('')
const saving = ref(false)
const form = reactive({ name: '', code: '', leaderName: '', sort: 0, remark: '' })

function onSortInput(e: Event) {
  const v = Number((e.target as HTMLInputElement).value)
  form.sort = Number.isFinite(v) ? Math.trunc(v) : 0
}

function openCreate() {
  editingId.value = null
  form.name = ''
  form.code = ''
  form.leaderName = ''
  form.sort = (Math.max(0, ...org.nodes.filter((n) => n.type === 'REGION').map((n) => n.sort)) || 0) + 1
  form.remark = ''
  formErr.value = ''
  drawerOpen.value = true
}

function openEdit(r: Region) {
  const node = org.get(r.id)
  editingId.value = r.id
  form.name = node?.name ?? r.orgName
  form.code = r.code
  form.leaderName = node?.leaderName ?? r.managerName
  form.sort = node?.sort ?? r.sortNo
  form.remark = node?.remark ?? ''
  formErr.value = ''
  drawerOpen.value = true
}

async function submitForm() {
  if (saving.value) return
  if (!form.name.trim()) { formErr.value = '请填写区域名称'; return }
  if (!editingId.value && !form.code.trim()) { formErr.value = '请填写区域编码'; return }
  const rootId = org.roots[0]?.id
  if (!editingId.value && !rootId) { formErr.value = '集团根节点未加载，请稍后重试'; return }
  saving.value = true
  formErr.value = ''
  try {
    if (editingId.value) {
      const cur = org.get(editingId.value)
      await org.update(editingId.value, {
        name: form.name.trim(),
        leaderName: form.leaderName.trim(),
        headcount: cur?.headcount ?? 0,
        sort: form.sort,
        remark: form.remark.trim(),
      })
    } else {
      await org.create({
        code: form.code.trim(),
        name: form.name.trim(),
        type: 'REGION',
        parentId: rootId!,
        leaderName: form.leaderName.trim(),
        headcount: 0,
        sort: form.sort,
        remark: form.remark.trim(),
      })
    }
    drawerOpen.value = false
    await region.fetchAll(true)
  } catch (e: any) {
    formErr.value = errMsg(e, editingId.value ? '保存失败' : '创建失败')
  } finally {
    saving.value = false
  }
}

// ---------- 停用 / 启用确认 ----------
const statusConfirmOpen = ref(false)
const confirmTarget = ref<Region | null>(null)
const confirmTo = ref<RegionStatus>('ACTIVE')
const confirmReason = ref('')
const confirmErr = ref('')
const confirmSaving = ref(false)

function onToggleStatus(r: Region) {
  confirmTarget.value = r
  confirmTo.value = r.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  confirmReason.value = ''
  confirmErr.value = ''
  statusConfirmOpen.value = true
}

async function confirmStatus() {
  if (!confirmTarget.value || confirmSaving.value) return
  const reason = confirmReason.value.trim()
  if (confirmTo.value === 'INACTIVE' && !reason) {
    confirmErr.value = '请填写停用原因'
    return
  }
  confirmSaving.value = true
  confirmErr.value = ''
  try {
    await org.setStatus(confirmTarget.value.id, confirmTo.value, reason || undefined)
    statusConfirmOpen.value = false
    await region.fetchAll(true)
  } catch (e: any) {
    confirmErr.value = errMsg(e, '操作失败')
  } finally {
    confirmSaving.value = false
  }
}
</script>

<template>
  <div class="mr-page">
    <div class="mr-kpis">
      <div class="kpi kpi--brand">
        <div class="kpi__icon"><CIcon name="org" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">运营中区域</div><div class="kpi__value">{{ kpis.active }}</div></div>
      </div>
      <div class="kpi kpi--neutral">
        <div class="kpi__icon"><CIcon name="box" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">区域总数</div><div class="kpi__value">{{ kpis.total }}</div></div>
      </div>
      <div class="kpi kpi--info">
        <div class="kpi__icon"><CIcon name="store" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">门店总数</div><div class="kpi__value">{{ kpis.stores }}</div></div>
      </div>
      <div class="kpi kpi--success">
        <div class="kpi__icon"><CIcon name="pos" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">营业中门店</div><div class="kpi__value">{{ kpis.operating }}</div></div>
      </div>
    </div>

    <CCard padding="md">
      <div class="mr-toolbar">
        <div class="filters">
          <select v-model="fStatus" class="sel">
            <option value="">全部状态</option>
            <option value="ACTIVE">运营中</option>
            <option value="INACTIVE">已停用</option>
          </select>
          <CInput v-model="keyword" placeholder="搜索编码/名称/经理" />
        </div>
        <CButton v-if="canEdit" variant="primary" @click="openCreate">
          <CIcon name="plus" :size="16" /> 新建区域
        </CButton>
        <CButton v-if="region.error" variant="secondary" @click="region.fetchAll(true)">
          <CIcon name="refresh" :size="16" /> 重试
        </CButton>
      </div>
    </CCard>

    <div class="mr-grid">
      <CCard v-for="r in filtered" :key="r.id" padding="none" class="region-card" :class="{ 'region-card--inactive': r.status === 'INACTIVE' }">
        <div class="region-card__head">
          <div class="region-card__title">
            <span class="region-card__code">{{ r.code }}</span>
            <span class="region-card__name">{{ r.orgName }}</span>
          </div>
          <CStatusPill :status="statusTone(r.status)" dot>{{ r.statusText }}</CStatusPill>
        </div>
        <div class="region-card__body">
          <div class="region-stat">
            <div class="region-stat__label">区域经理</div>
            <div class="region-stat__value">{{ r.managerName }}</div>
          </div>
          <div class="region-stat">
            <div class="region-stat__label">门店数</div>
            <div class="region-stat__value">{{ r.storeCount }}<span class="region-stat__sub">（营业 {{ r.operatingCount }}）</span></div>
          </div>
          <div class="region-stat">
            <div class="region-stat__label">经营性质</div>
            <div class="region-stat__value region-stat__value--sm">直营 {{ r.ownCnt }} · 联营 {{ r.jointCnt }}</div>
          </div>
          <div class="region-stat">
            <div class="region-stat__label">筹建 / 关店</div>
            <div class="region-stat__value region-stat__value--sm">{{ r.buildingCnt }} / {{ r.closedCnt }}</div>
          </div>
        </div>
        <div v-if="canEdit" class="region-card__foot">
          <CButton variant="text" size="sm" @click="openEdit(r)">
            <CIcon name="edit" :size="14" /> 编辑
          </CButton>
          <CButton
            variant="text"
            size="sm"
            :class="{ 'op--danger': r.status === 'ACTIVE' }"
            @click="onToggleStatus(r)"
          >
            <CIcon :name="r.status === 'ACTIVE' ? 'close' : 'refresh'" :size="14" />
            {{ r.status === 'ACTIVE' ? '停用' : '启用' }}
          </CButton>
        </div>
      </CCard>
      <div v-if="filtered.length === 0" class="empty">{{ emptyText }}</div>
    </div>

    <p class="mr-footnote">
      数据源：GET /api/org/tree + GET /api/stores/regions/dist（集团聚合视角，不受登录人数据域收窄影响）。
      区域主数据 = org_unit 区域节点（棒②卡1 定案，门店归属以组织树挂载为准）；写侧经 B87 接口
      （新建父=集团 / 编辑 / 停用启用留痕），需 org:edit 权限，后端校验失败中文 message 原样展示。
    </p>

    <!-- 抽屉：新建区域 / 编辑区域 -->
    <CDrawer
      v-model:show="drawerOpen"
      :title="editingId ? '编辑区域' : '新建区域'"
      size="md"
    >
      <div class="form">
        <div class="form-grid">
          <label class="field field--full">
            <span class="field__label">区域名称 <i>*</i></span>
            <CInput v-model="form.name" placeholder="如 东北事业部" />
          </label>
          <label class="field">
            <span class="field__label">区域编码 <i>*</i></span>
            <CInput
              v-model="form.code"
              placeholder="如 ORG-NE"
              :disabled="!!editingId"
            />
          </label>
          <label class="field">
            <span class="field__label">排序</span>
            <input
              class="native-input"
              type="number"
              :value="form.sort"
              @input="onSortInput"
            />
          </label>
          <label class="field field--full">
            <span class="field__label">区域经理</span>
            <CInput v-model="form.leaderName" placeholder="如 林微" />
          </label>
          <label class="field field--full">
            <span class="field__label">备注</span>
            <CTextarea v-model="form.remark" :rows="3" placeholder="可选备注信息" />
          </label>
        </div>
        <div v-if="formErr" class="form-err">
          <CIcon name="alert" :size="14" /> {{ formErr }}
        </div>
      </div>
      <template #footer>
        <CButton variant="secondary" :disabled="saving" @click="drawerOpen = false">取消</CButton>
        <CButton variant="primary" :disabled="!canEdit || saving" @click="submitForm">
          {{ saving ? '提交中…' : (editingId ? '保存修改' : '创建') }}
        </CButton>
      </template>
    </CDrawer>

    <!-- 停用/启用确认 -->
    <div v-if="statusConfirmOpen" class="modal-mask" @click.self="statusConfirmOpen = false">
      <div class="modal modal--sm">
        <div class="modal__head">
          <h3>{{ confirmTo === 'INACTIVE' ? '停用区域' : '启用区域' }}</h3>
          <button class="modal__close" @click="statusConfirmOpen = false"><CIcon name="close" :size="18" /></button>
        </div>
        <div class="modal__body">
          <p class="confirm-txt">
            确认将「<b>{{ confirmTarget?.orgName }}</b>」{{ confirmTo === 'INACTIVE' ? '停用' : '启用' }}？
            <template v-if="confirmTo === 'INACTIVE'">停用后该区域及其门店在层级选择器中不再出现。</template>
          </p>
          <label v-if="confirmTo === 'INACTIVE'" class="field">
            <span class="field__label">停用原因 <i>*</i></span>
            <CTextarea v-model="confirmReason" :rows="3" placeholder="必填，随启停记录留痕审计" />
          </label>
          <div v-if="confirmErr" class="form-err">
            <CIcon name="alert" :size="14" /> {{ confirmErr }}
          </div>
        </div>
        <div class="modal__foot">
          <CButton variant="secondary" :disabled="confirmSaving" @click="statusConfirmOpen = false">取消</CButton>
          <CButton
            :variant="confirmTo === 'INACTIVE' ? 'danger' : 'primary'"
            :disabled="confirmSaving"
            @click="confirmStatus"
          >
            {{ confirmSaving ? '提交中…' : '确认' }}
          </CButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mr-page { display: flex; flex-direction: column; gap: var(--s-md); }

.mr-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--neutral .kpi__icon { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--success .kpi__icon { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }

.mr-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); flex-wrap: nowrap; }
.mr-toolbar > .cbtn { flex-shrink: 0; white-space: nowrap; }
.filters { display: flex; gap: var(--s-sm); flex: 1; min-width: 0; flex-wrap: nowrap; overflow-x: auto; align-items: center; }
.filters .sel { flex-shrink: 0; }
.filters > :deep(.cinput) { flex: 1; min-width: 200px; max-width: 280px; }
.sel { height: 36px; padding: 0 12px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); }

.mr-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.region-card { display: flex; flex-direction: column; transition: box-shadow .15s; }
.region-card:hover { box-shadow: var(--shadow-pop, 0 8px 24px rgba(0,0,0,.08)); }
.region-card--inactive { opacity: .65; }
.region-card__head { display: flex; align-items: center; justify-content: space-between; padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.region-card__title { display: flex; align-items: center; gap: var(--s-sm); }
.region-card__code { font-size: var(--t-xs); color: var(--c-text-3); font-family: var(--t-number, monospace); background: var(--c-surface, #f7f8fa); padding: 2px 8px; border-radius: var(--r-sm); }
.region-card__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.region-card__body { padding: var(--s-md); display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); flex: 1; }
.region-card__foot { display: flex; align-items: center; justify-content: flex-end; gap: var(--s-xs); padding: var(--s-xs) var(--s-md); border-top: 1px solid var(--c-border-light); }
.op--danger { color: var(--c-danger-fg); }
.region-stat { display: flex; flex-direction: column; gap: 4px; }
.region-stat__label { font-size: var(--t-xs); color: var(--c-text-3); }
.region-stat__value { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.region-stat__value--sm { font-size: var(--t-md); }
.region-stat__sub { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); }
.empty { grid-column: 1 / -1; text-align: center; padding: var(--s-xl); color: var(--c-text-3); font-size: var(--t-sm); }

.mr-footnote { margin: 0; font-size: var(--t-xs); color: var(--c-text-4, var(--c-text-3)); line-height: 1.6; }

/* ---- 抽屉表单 ---- */
.form { display: flex; flex-direction: column; gap: var(--s-lg); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.field { display: flex; flex-direction: column; gap: 6px; }
.field--full { grid-column: 1 / -1; }
.field__label { font-size: var(--t-sm); color: var(--c-text-2); font-weight: 500; }
.field__label i { color: var(--c-danger-fg); font-style: normal; margin-left: 2px; }

.native-input {
  width: 100%;
  height: 36px;
  padding: 0 var(--s-sm);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  font-size: var(--t-sm);
  color: var(--c-text);
  font-family: inherit;
}
.native-input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: 0 0 0 2px rgba(255, 107, 158, 0.12);
}

.form-err {
  display: flex;
  align-items: center;
  gap: var(--s-xxs);
  padding: var(--s-sm) var(--s-md);
  background: var(--c-danger-bg);
  color: var(--c-danger-fg);
  border-radius: var(--r-md);
  font-size: var(--t-sm);
}

/* ---- 停用/启用确认弹窗 ---- */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.modal {
  background: var(--c-surface);
  border-radius: var(--r-xl);
  width: 480px;
  max-width: calc(100vw - 48px);
  display: flex;
  flex-direction: column;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
}
.modal--sm { width: 420px; }
.modal__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--s-md) var(--s-lg);
  border-bottom: 1px solid var(--c-border-light);
}
.modal__head h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.modal__close {
  border: none;
  background: none;
  cursor: pointer;
  color: var(--c-text-3);
  padding: 4px;
  display: flex;
  border-radius: var(--r-sm);
}
.modal__close:hover { color: var(--c-text); }
.modal__body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-md); }
.modal__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--s-sm);
  padding: var(--s-md) var(--s-lg);
  border-top: 1px solid var(--c-border-light);
}
.confirm-txt { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-base); }
.confirm-txt b { color: var(--c-text); }
</style>
