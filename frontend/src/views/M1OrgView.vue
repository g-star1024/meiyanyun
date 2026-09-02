<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTextarea from '@/components/CTextarea.vue'
import CIcon from '@/components/CIcon.vue'
import { useM1OrgStore, type OrgNode, type OrgType, type OrgStatus } from '@/stores/m1Org'
import { useAuthStore } from '@/stores/auth'

const org = useM1OrgStore()
const auth = useAuthStore()
onMounted(() => org.seed())

const canEdit = computed(() => auth.can('org:edit'))

const selectedId = ref<string | null>(null)
const expanded = ref<Set<string>>(new Set())

// 默认展开集团和第一个大区
onMounted(() => {
  const group = org.roots[0]
  if (group) {
    expanded.value.add(group.id)
    selectedId.value = group.id
    const firstRegion = org.children(group.id)[0]
    if (firstRegion) expanded.value.add(firstRegion.id)
  }
})

const selected = computed(() => (selectedId.value ? org.get(selectedId.value) : null))

const kpis = computed(() => {
  const all = org.nodes
  return {
    units: all.length,
    headcount: all.reduce((s, n) => s + n.headcount, 0),
    regions: all.filter((n) => n.type === 'REGION' && n.status === 'ACTIVE').length,
    stores: all.filter((n) => n.type === 'STORE' && n.status === 'ACTIVE').length,
  }
})

function typeIcon(t: OrgType) {
  return t === 'GROUP' ? 'org' : t === 'REGION' ? 'box' : t === 'STORE' ? 'store' : 'customer'
}
function typeTone(t: OrgType) {
  return t === 'GROUP' ? 'primary' : t === 'REGION' ? 'info' : t === 'STORE' ? 'success' : 'default'
}
function statusTone(s: OrgStatus) { return s === 'ACTIVE' ? 'success' : 'disabled' }

function toggle(id: string) {
  if (expanded.value.has(id)) expanded.value.delete(id)
  else expanded.value.add(id)
  // 触发响应式
  expanded.value = new Set(expanded.value)
}

function select(id: string) { selectedId.value = id }

// ---- 弹层 ----
const showModal = ref(false)
const editing = ref<OrgNode | null>(null)
const parentForNew = ref<string | null>(null)
const formErr = ref('')
const form = reactive({
  code: '', name: '', type: 'STORE' as OrgType, leaderName: '', headcount: '',
  status: 'ACTIVE' as OrgStatus, remark: '',
})

function resetForm() {
  Object.assign(form, { code: '', name: '', type: 'STORE', leaderName: '', headcount: '', status: 'ACTIVE', remark: '' })
  formErr.value = ''
}

function openCreate(parentId: string | null) {
  editing.value = null
  parentForNew.value = parentId
  resetForm()
  // 根据父节点类型推断子节点类型
  if (parentId) {
    const p = org.get(parentId)
    if (p?.type === 'GROUP') form.type = 'REGION'
    else if (p?.type === 'REGION') form.type = 'STORE'
    else if (p?.type === 'STORE') form.type = 'DEPT'
  } else {
    form.type = 'GROUP'
  }
  showModal.value = true
}

function openEdit(n: OrgNode) {
  editing.value = n
  parentForNew.value = n.parentId
  Object.assign(form, {
    code: n.code, name: n.name, type: n.type, leaderName: n.leaderName,
    headcount: String(n.headcount), status: n.status, remark: n.remark ?? '',
  })
  formErr.value = ''
  showModal.value = true
}

function submit() {
  if (!form.name.trim()) { formErr.value = '请填写组织名称'; return }
  if (!form.code.trim()) { formErr.value = '请填写组织编码'; return }
  if (!form.leaderName.trim()) { formErr.value = '请填写负责人'; return }
  formErr.value = ''
  const payload = {
    code: form.code.trim(), name: form.name.trim(), type: form.type,
    parentId: parentForNew.value, leaderName: form.leaderName.trim(),
    headcount: Number(form.headcount) || 0, status: form.status,
    remark: form.remark.trim() || undefined, sort: 0,
  }
  if (editing.value) {
    org.update(editing.value.id, payload)
  } else {
    const created = org.create(payload)
    selectedId.value = created.id
    if (parentForNew.value) expanded.value.add(parentForNew.value)
  }
  showModal.value = false
}

// ---- 停用/启用 ----
const showConfirm = ref(false)
const confirmTarget = ref<OrgNode | null>(null)
const confirmTo = ref<OrgStatus>('ACTIVE')
const confirmReason = ref('')
function openStatus(n: OrgNode, to: OrgStatus) {
  confirmTarget.value = n; confirmTo.value = to; confirmReason.value = ''
  showConfirm.value = true
}
function confirmStatus() {
  if (!confirmTarget.value) return
  if (confirmTo.value === 'INACTIVE' && !confirmReason.value.trim()) return
  org.setStatus(confirmTarget.value.id, confirmTo.value, confirmReason.value.trim() || undefined)
  showConfirm.value = false
}

// 允许新增下级的类型
function canAddChild(n: OrgNode) { return n.type !== 'DEPT' && n.status === 'ACTIVE' }
</script>

<template>
  <div class="mo-page">
    <div class="mo-kpis">
      <div class="kpi kpi--brand">
        <div class="kpi__icon"><CIcon name="org" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">组织单元</div><div class="kpi__value">{{ kpis.units }}</div></div>
      </div>
      <div class="kpi kpi--success">
        <div class="kpi__icon"><CIcon name="user-check" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">在职人数</div><div class="kpi__value">{{ kpis.headcount }}</div></div>
      </div>
      <div class="kpi kpi--info">
        <div class="kpi__icon"><CIcon name="box" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">运营区域</div><div class="kpi__value">{{ kpis.regions }}</div></div>
      </div>
      <div class="kpi kpi--neutral">
        <div class="kpi__icon"><CIcon name="store" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">营业门店</div><div class="kpi__value">{{ kpis.stores }}</div></div>
      </div>
    </div>

    <div class="mo-body">
      <!-- 左：组织树 -->
      <CCard padding="none" class="mo-tree-card">
        <div class="tree-head">
          <span>组织架构</span>
          <CButton v-if="canEdit && org.roots.length === 0" variant="text" size="sm" @click="openCreate(null)">
            <CIcon name="plus" :size="14" /> 新建集团
          </CButton>
        </div>
        <div class="tree-body">
          <template v-for="root in org.roots" :key="root.id">
            <div class="tree-row" :class="{ 'is-selected': selectedId === root.id, 'is-inactive': root.status === 'INACTIVE' }" @click="select(root.id)">
              <button class="tree-toggle" @click.stop="toggle(root.id)">
                <CIcon :name="expanded.has(root.id) ? 'chevron-down' : 'chevron-right'" :size="14" />
              </button>
              <CIcon :name="typeIcon(root.type)" :size="16" class="tree-icon" />
              <span class="tree-name">{{ root.name }}</span>
              <CStatusPill :status="typeTone(root.type)">{{ org.ORG_TYPE_LABEL[root.type] }}</CStatusPill>
            </div>
            <div v-if="expanded.has(root.id)" class="tree-children">
              <template v-for="r1 in org.children(root.id)" :key="r1.id">
                <div class="tree-row tree-row--l1" :class="{ 'is-selected': selectedId === r1.id, 'is-inactive': r1.status === 'INACTIVE' }" @click="select(r1.id)">
                  <button class="tree-toggle" @click.stop="toggle(r1.id)">
                    <CIcon :name="expanded.has(r1.id) ? 'chevron-down' : 'chevron-right'" :size="14" />
                  </button>
                  <CIcon :name="typeIcon(r1.type)" :size="16" class="tree-icon" />
                  <span class="tree-name">{{ r1.name }}</span>
                  <span class="tree-meta">{{ r1.leaderName }}</span>
                </div>
                <div v-if="expanded.has(r1.id)" class="tree-children">
                  <template v-for="r2 in org.children(r1.id)" :key="r2.id">
                    <div class="tree-row tree-row--l2" :class="{ 'is-selected': selectedId === r2.id, 'is-inactive': r2.status === 'INACTIVE' }" @click="select(r2.id)">
                      <button class="tree-toggle" @click.stop="toggle(r2.id)">
                        <CIcon :name="expanded.has(r2.id) ? 'chevron-down' : 'chevron-right'" :size="14" />
                      </button>
                      <CIcon :name="typeIcon(r2.type)" :size="16" class="tree-icon" />
                      <span class="tree-name">{{ r2.name }}</span>
                      <span class="tree-meta">{{ r1.leaderName ? '' : '' }}{{ r2.headcount }}人</span>
                      <CStatusPill v-if="r2.status === 'INACTIVE'" status="disabled">停用</CStatusPill>
                    </div>
                    <div v-if="expanded.has(r2.id)" class="tree-children">
                      <div
                        v-for="r3 in org.children(r2.id)" :key="r3.id"
                        class="tree-row tree-row--l3" :class="{ 'is-selected': selectedId === r3.id, 'is-inactive': r3.status === 'INACTIVE' }"
                        @click="select(r3.id)"
                      >
                        <span class="tree-toggle tree-toggle--leaf"></span>
                        <CIcon :name="typeIcon(r3.type)" :size="15" class="tree-icon" />
                        <span class="tree-name">{{ r3.name }}</span>
                        <span class="tree-meta">{{ r3.headcount }}人</span>
                      </div>
                    </div>
                  </template>
                </div>
              </template>
            </div>
          </template>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" padding="none" class="mo-detail-card">
        <div class="detail-head">
          <div class="detail-head__left">
            <CIcon :name="typeIcon(selected.type)" :size="22" class="detail-head__icon" />
            <div>
              <div class="detail-head__name">{{ selected.name }}</div>
              <div class="detail-head__sub">
                <span class="code-tag">{{ selected.code }}</span>
                <CStatusPill :status="typeTone(selected.type)">{{ org.ORG_TYPE_LABEL[selected.type] }}</CStatusPill>
                <CStatusPill :status="statusTone(selected.status)" dot>{{ org.ORG_STATUS_LABEL[selected.status] }}</CStatusPill>
              </div>
            </div>
          </div>
          <div class="detail-head__ops" v-if="canEdit">
            <CButton v-if="canAddChild(selected)" variant="secondary" size="sm" @click="openCreate(selected.id)">
              <CIcon name="plus" :size="14" /> 新增下级
            </CButton>
            <CButton variant="text" size="sm" @click="openEdit(selected)">编辑</CButton>
            <CButton v-if="selected.status === 'ACTIVE'" variant="text" size="sm" @click="openStatus(selected, 'INACTIVE')">停用</CButton>
            <CButton v-else variant="text" size="sm" @click="openStatus(selected, 'ACTIVE')">启用</CButton>
          </div>
        </div>
        <div class="detail-body">
          <div class="info-grid">
            <div class="info"><span class="info__label">负责人</span><span class="info__value">{{ selected.leaderName }}</span></div>
            <div class="info"><span class="info__label">直属人数</span><span class="info__value">{{ selected.headcount }} 人</span></div>
            <div class="info"><span class="info__label">编制总人数</span><span class="info__value info__value--brand">{{ org.totalHeadcount(selected.id) }} 人</span></div>
            <div class="info"><span class="info__label">组织编码</span><span class="info__value info__value--mono">{{ selected.code }}</span></div>
          </div>
          <div v-if="selected.remark" class="info-remark">{{ selected.remark }}</div>

          <!-- 直属下级 -->
          <div class="sub-section">
            <h4>直属下级（{{ org.children(selected.id).length }}）</h4>
            <div v-if="org.children(selected.id).length" class="sub-list">
              <div
                v-for="c in org.children(selected.id)" :key="c.id"
                class="sub-row" :class="{ 'is-inactive': c.status === 'INACTIVE' }"
                @click="select(c.id)"
              >
                <CIcon :name="typeIcon(c.type)" :size="16" />
                <span class="sub-row__name">{{ c.name }}</span>
                <CStatusPill :status="typeTone(c.type)">{{ org.ORG_TYPE_LABEL[c.type] }}</CStatusPill>
                <span class="sub-row__meta">{{ c.leaderName }} · {{ c.headcount }}人</span>
                <CIcon name="chevron-right" :size="14" class="sub-row__arrow" />
              </div>
            </div>
            <div v-else class="sub-empty">暂无下级组织</div>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 新建/编辑弹层 -->
    <div v-if="showModal" class="modal-mask" @click.self="showModal = false">
      <div class="modal">
        <div class="modal__head">
          <h3>{{ editing ? '编辑组织单元' : '新建组织单元' }}</h3>
          <button class="modal__close" @click="showModal = false"><CIcon name="close" :size="18" /></button>
        </div>
        <div class="modal__body">
          <div class="form-grid">
            <label class="field"><span class="field__label">组织编码 <i>*</i></span><CInput v-model="form.code" placeholder="如 R-EAST" /></label>
            <label class="field"><span class="field__label">组织名称 <i>*</i></span><CInput v-model="form.name" placeholder="如 华东大区" /></label>
            <label class="field">
              <span class="field__label">组织类型</span>
              <select v-model="form.type" class="sel" :disabled="!!editing">
                <option value="GROUP">集团</option>
                <option value="REGION">大区</option>
                <option value="STORE">门店</option>
                <option value="DEPT">部门</option>
              </select>
            </label>
            <label class="field">
              <span class="field__label">状态</span>
              <select v-model="form.status" class="sel">
                <option value="ACTIVE">正常</option>
                <option value="INACTIVE">已停用</option>
              </select>
            </label>
            <label class="field"><span class="field__label">负责人 <i>*</i></span><CInput v-model="form.leaderName" placeholder="如 陈野" /></label>
            <label class="field"><span class="field__label">直属人数</span><CInput v-model="form.headcount" placeholder="如 28" /></label>
            <label class="field field--full"><span class="field__label">备注</span><CTextarea v-model="form.remark" placeholder="组织备注（可选）" :rows="2" /></label>
          </div>
          <div v-if="formErr" class="form-err">{{ formErr }}</div>
        </div>
        <div class="modal__foot">
          <CButton variant="secondary" @click="showModal = false">取消</CButton>
          <CButton variant="primary" @click="submit">{{ editing ? '保存' : '创建' }}</CButton>
        </div>
      </div>
    </div>

    <!-- 停用/启用确认 -->
    <div v-if="showConfirm" class="modal-mask" @click.self="showConfirm = false">
      <div class="modal modal--sm">
        <div class="modal__head">
          <h3>{{ confirmTo === 'INACTIVE' ? '停用组织' : '启用组织' }}</h3>
          <button class="modal__close" @click="showConfirm = false"><CIcon name="close" :size="18" /></button>
        </div>
        <div class="modal__body">
          <p class="confirm-txt">确认将「<b>{{ confirmTarget?.name }}</b>」{{ confirmTo === 'INACTIVE' ? '停用' : '启用' }}？</p>
          <label v-if="confirmTo === 'INACTIVE'" class="field">
            <span class="field__label">停用原因 <i>*</i></span>
            <CTextarea v-model="confirmReason" placeholder="请说明停用原因，将记入审计日志" :rows="3" />
          </label>
        </div>
        <div class="modal__foot">
          <CButton variant="secondary" @click="showConfirm = false">取消</CButton>
          <CButton variant="primary" :disabled="confirmTo === 'INACTIVE' && !confirmReason.trim()" @click="confirmStatus">确认</CButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mo-page { display: flex; flex-direction: column; gap: var(--s-md); }
.mo-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--success .kpi__icon { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--neutral .kpi__icon { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }

.mo-body { display: grid; grid-template-columns: 320px 1fr; gap: var(--s-md); align-items: start; }

/* tree */
.mo-tree-card { overflow: hidden; }
.tree-head { display: flex; align-items: center; justify-content: space-between; padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); font-weight: 700; font-size: var(--t-md); }
.tree-body { max-height: 640px; overflow-y: auto; padding: var(--s-xs) 0; }
.tree-row { display: flex; align-items: center; gap: 6px; padding: 8px 12px; cursor: pointer; font-size: var(--t-sm); color: var(--c-text-2); transition: background .12s; }
.tree-row:hover { background: var(--c-surface, #f7f8fa); }
.tree-row.is-selected { background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }
.tree-row.is-inactive { opacity: .5; }
.tree-row--l1 { padding-left: 28px; }
.tree-row--l2 { padding-left: 48px; }
.tree-row--l3 { padding-left: 68px; }
.tree-toggle { width: 18px; height: 18px; border: none; background: none; cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--c-text-3); padding: 0; flex: none; border-radius: var(--r-sm); }
.tree-toggle--leaf { display: inline-block; }
.tree-icon { flex: none; color: var(--c-text-3); }
.is-selected .tree-icon { color: var(--c-brand); }
.tree-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-meta { font-size: var(--t-xs); color: var(--c-text-3); flex: none; }

/* detail */
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; padding: var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.detail-head__left { display: flex; gap: var(--s-md); align-items: center; }
.detail-head__icon { width: 44px; height: 44px; border-radius: var(--r-lg); background: var(--c-brand-soft); color: var(--c-brand); display: flex; align-items: center; justify-content: center; flex: none; }
.detail-head__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail-head__sub { display: flex; gap: 6px; align-items: center; margin-top: 4px; }
.code-tag { font-size: var(--t-xs); color: var(--c-text-3); font-family: var(--t-number, monospace); background: var(--c-surface, #f7f8fa); padding: 2px 8px; border-radius: var(--r-sm); }
.detail-head__ops { display: flex; gap: var(--s-xs); }
.detail-body { padding: var(--s-lg); }
.info-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.info { display: flex; flex-direction: column; gap: 4px; }
.info__label { font-size: var(--t-xs); color: var(--c-text-3); }
.info__value { font-size: var(--t-md); font-weight: 600; color: var(--c-text); }
.info__value--brand { color: var(--c-brand); }
.info__value--mono { font-family: var(--t-number, monospace); }
.info-remark { margin-top: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-surface, #f7f8fa); border-radius: var(--r-sm); font-size: var(--t-xs); color: var(--c-text-2); border-left: 3px solid var(--c-warning-fg); }

.sub-section { margin-top: var(--s-lg); }
.sub-section h4 { margin: 0 0 var(--s-sm); font-size: var(--t-sm); font-weight: 700; color: var(--c-text-2); }
.sub-list { display: flex; flex-direction: column; gap: 4px; }
.sub-row { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-md); cursor: pointer; transition: all .12s; }
.sub-row:hover { border-color: var(--c-brand); background: var(--c-brand-soft); }
.sub-row.is-inactive { opacity: .5; }
.sub-row__name { flex: 1; font-weight: 600; font-size: var(--t-sm); }
.sub-row__meta { font-size: var(--t-xs); color: var(--c-text-3); }
.sub-row__arrow { color: var(--c-text-3); flex: none; }
.sub-empty { padding: var(--s-lg); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); }

/* modal */
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--c-surface); border-radius: var(--r-xl); width: 560px; max-width: calc(100vw - 48px); max-height: 86vh; display: flex; flex-direction: column; box-shadow: var(--shadow-pop, 0 12px 40px rgba(0,0,0,.18)); }
.modal--sm { width: 420px; }
.modal__head { display: flex; align-items: center; justify-content: space-between; padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.modal__head h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.modal__close { border: none; background: none; cursor: pointer; color: var(--c-text-3); padding: 4px; display: flex; border-radius: var(--r-sm); }
.modal__close:hover { background: var(--c-surface, #f7f8fa); color: var(--c-text); }
.modal__body { padding: var(--s-lg); overflow-y: auto; }
.modal__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-top: 1px solid var(--c-border-light); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.field { display: flex; flex-direction: column; gap: 6px; }
.field--full { grid-column: 1 / -1; }
.field__label { font-size: var(--t-xs); color: var(--c-text-2); font-weight: 500; }
.field__label i { color: var(--c-danger-fg); font-style: normal; }
.sel { height: 36px; padding: 0 12px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); width: 100%; box-sizing: border-box; }
.sel:disabled { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); }
.form-err { margin-top: var(--s-sm); color: var(--c-danger-fg); font-size: var(--t-xs); }
.confirm-txt { margin: 0 0 var(--s-md); font-size: var(--t-sm); color: var(--c-text-2); }

@media (max-width: 1024px) {
  .mo-body { grid-template-columns: 1fr; }
  .info-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
