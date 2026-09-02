<script setup lang="ts">
// T1 权限中台 · 角色管理（/admin/roles）
// 左树右详情 + 抽屉表单（新建/编辑）+ 成员管理
import { computed, defineComponent, h, onMounted, reactive, ref, watch, type PropType, type VNode } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CDrawer from '@/components/CDrawer.vue'
import CCheckbox from '@/components/CCheckbox.vue'
import { useT1RbacStore, MATRIX_MODULES, type T1Role, type DataScope } from '@/stores/t1Rbac'
import { useAuthStore } from '@/stores/auth'

const rbac = useT1RbacStore()
const auth = useAuthStore()
onMounted(() => rbac.seed())

// ---------- KPI ----------
const kpiTotal = computed(() => rbac.roles.length)
const kpiActive = computed(() => rbac.roles.filter((r) => r.status === 'ACTIVE').length)
const kpiBuiltin = computed(() => rbac.roles.filter((r) => r.builtin).length)
const kpiMembers = computed(() => rbac.roles.reduce((sum, r) => sum + rbac.memberCount(r.id), 0))

// ---------- 左侧树 ----------
const expanded = ref<Set<string>>(new Set())
const selectedId = ref<string>('')

function ensureDefaultSelected() {
  if (!selectedId.value && rbac.roles.length) {
    selectedId.value = rbac.roles[0].id
  }
}
watch(() => rbac.roles.length, ensureDefaultSelected, { immediate: true })

function toggleExpand(id: string) {
  if (expanded.value.has(id)) expanded.value.delete(id)
  else expanded.value.add(id)
}
function selectRole(id: string) {
  selectedId.value = id
}

const rootRoles = computed(() => rbac.children(null))
const selected = computed<T1Role | undefined>(() =>
  selectedId.value ? rbac.get(selectedId.value) : undefined,
)
const selectedMembers = computed(() =>
  selected.value ? rbac.getMembers(selected.value.id) : [],
)
const selectedEff = computed(() =>
  selected.value ? rbac.effectivePermissions(selected.value.id) : { actions: [] as string[], scope: 'SELF' as DataScope },
)

// ---------- 抽屉表单（新建/编辑）----------
const drawerOpen = ref(false)
const editingId = ref<string | null>(null)
const formErr = ref('')
const form = reactive({
  name: '',
  code: '',
  description: '',
  parentId: '' as string | '',
  scope: 'STORE' as DataScope,
  actions: [] as string[],
})

const scopeOptions = [
  { label: '仅本人 (SELF)', value: 'SELF' },
  { label: '门店 (STORE)', value: 'STORE' },
  { label: '品牌 (BRAND)', value: 'BRAND' },
  { label: '区域 (REGION)', value: 'REGION' },
  { label: '集团 (GROUP)', value: 'GROUP' },
]

const parentOptions = computed(() => {
  const opts = [{ label: '无（顶级角色）', value: '' }]
  for (const r of rbac.roles) {
    if (editingId.value && r.id === editingId.value) continue
    opts.push({ label: `${r.name}（${r.code}）`, value: r.id })
  }
  return opts
})

function resetForm() {
  form.name = ''
  form.code = ''
  form.description = ''
  form.parentId = ''
  form.scope = 'STORE'
  form.actions = []
  formErr.value = ''
}

function openCreate() {
  editingId.value = null
  resetForm()
  drawerOpen.value = true
}

function openEdit(role: T1Role) {
  editingId.value = role.id
  form.name = role.name
  form.code = role.code
  form.description = role.description
  form.parentId = role.parentId ?? ''
  form.scope = role.permissions.scope
  form.actions = [...role.permissions.actions]
  formErr.value = ''
  drawerOpen.value = true
}

function isActionChecked(perm: string): boolean {
  return form.actions.includes('*') || form.actions.includes(perm)
}
function toggleAction(perm: string, checked: boolean) {
  if (form.actions.includes('*')) return
  const idx = form.actions.indexOf(perm)
  if (checked && idx < 0) form.actions.push(perm)
  else if (!checked && idx >= 0) form.actions.splice(idx, 1)
}
function moduleAllChecked(perms: readonly string[]): boolean {
  if (form.actions.includes('*')) return true
  return perms.every((p) => form.actions.includes(p))
}
function toggleModuleAll(perms: readonly string[]) {
  if (form.actions.includes('*')) return
  if (moduleAllChecked(perms)) {
    form.actions = form.actions.filter((a) => !perms.includes(a))
  } else {
    perms.forEach((p) => { if (!form.actions.includes(p)) form.actions.push(p) })
  }
}

function submitForm() {
  if (!form.name.trim()) { formErr.value = '请填写角色名称'; return }
  if (!form.code.trim()) { formErr.value = '请填写角色编码'; return }
  if (!/^[A-Z][A-Z0-9_]*$/.test(form.code.trim().toUpperCase())) {
    formErr.value = '编码仅支持大写字母/数字/下划线，且以字母开头'
    return
  }
  const code = form.code.trim().toUpperCase()
  const dup = rbac.roles.some((r) => r.code === code && r.id !== editingId.value)
  if (dup) { formErr.value = '角色编码已存在'; return }

  const payload = {
    code,
    name: form.name.trim(),
    description: form.description.trim(),
    parentId: form.parentId || null,
    permissions: { actions: [...form.actions], scope: form.scope },
  }

  if (editingId.value) {
    rbac.updateRole(editingId.value, {
      name: payload.name,
      description: payload.description,
      parentId: payload.parentId,
      permissions: payload.permissions,
    })
  } else {
    const r = rbac.createRole(payload)
    selectedId.value = r.id
  }
  drawerOpen.value = false
}

// ---------- 行操作 ----------
function onToggleStatus(role: T1Role) {
  if (role.builtin) return
  rbac.toggleStatus(role.id)
}
function onDelete(role: T1Role) {
  if (role.builtin) return
  const kids = rbac.children(role.id)
  const memCount = rbac.memberCount(role.id)
  if (kids.length > 0) {
    window.alert(`角色「${role.name}」下存在 ${kids.length} 个子角色，请先解除继承关系后再删除。`)
    return
  }
  if (memCount > 0) {
    window.alert(`角色「${role.name}」下仍有 ${memCount} 名成员，请先移除成员后再删除。`)
    return
  }
  if (!window.confirm(`确认删除角色「${role.name}」？该操作不可恢复。`)) return
  rbac.deleteRole(role.id)
  if (selectedId.value === role.id) selectedId.value = rbac.roles[0]?.id ?? ''
}

function onRemoveMember(roleId: string, staffId: string) {
  rbac.removeMember(roleId, staffId)
}

// ---------- 工具 ----------
function fmtDate(iso: string) {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function scopeLabel(s: DataScope) {
  return rbac.SCOPE_LABEL[s]
}

// 内联递归节点：通过 getChildren 回调避免与 store 强耦合
interface TreeNodeProps {
  role: T1Role
  level: number
  selectedId: string
  expandedSet: Set<string>
  getChildren: (id: string) => T1Role[]
}
const RoleTreeNode = defineComponent({
  name: 'RoleTreeNode',
  props: {
    role: { type: Object as PropType<TreeNodeProps['role']>, required: true },
    level: { type: Number, default: 0 },
    selectedId: { type: String, default: '' },
    expandedSet: { type: Object as PropType<TreeNodeProps['expandedSet']>, required: true },
    getChildren: { type: Function as PropType<TreeNodeProps['getChildren']>, required: true },
  },
  emits: ['toggle', 'select'],
  setup(props, { emit }): () => VNode {
    return () => {
      const role = props.role
      const kids = props.getChildren(role.id)
      const hasChildren = kids.length > 0
      const isOpen = props.expandedSet.has(role.id)
      const isActive = props.selectedId === role.id

      const caret = h(
        'span',
        {
          class: ['node__caret', { 'node__caret--leaf': !hasChildren }],
          onClick: (e: Event) => { e.stopPropagation(); if (hasChildren) emit('toggle', role.id) },
        },
        hasChildren ? [h(CIcon, { name: isOpen ? 'chevron-down' : 'chevron-right', size: 14 })] : [],
      )
      const label = h('span', { class: 'node__label' }, role.name)
      const code = h('span', { class: 'node__code' }, role.code)

      const row = h(
        'div',
        {
          class: ['node__row', { 'node__row--active': isActive }],
          style: { paddingLeft: `calc(var(--s-sm) + ${props.level * 14}px)` },
          onClick: () => emit('select', role.id),
        },
        [caret, label, code],
      )

      const childrenVnodes: VNode | null = isOpen && hasChildren
        ? h(
            'div',
            { class: 'node__children' },
            kids.map((k) =>
              h(RoleTreeNode, {
                role: k,
                level: props.level + 1,
                selectedId: props.selectedId,
                expandedSet: props.expandedSet,
                getChildren: props.getChildren,
                onToggle: (id: string) => emit('toggle', id),
                onSelect: (id: string) => emit('select', id),
              }),
            ),
          )
        : null

      return h('div', { class: 'node' }, [row, childrenVnodes])
    }
  },
})
</script>

<template>
  <div class="t1-roles">
    <!-- 头部：KPI + 主按钮区 -->
    <div class="t1-roles__head">
      <div class="rb-kpis">
        <div class="kpi kpi--brand">
          <div class="kpi__icon"><CIcon name="shield" :size="20" /></div>
          <div class="kpi__body"><div class="kpi__label">角色总数</div><div class="kpi__value">{{ kpiTotal }}</div></div>
        </div>
        <div class="kpi kpi--success">
          <div class="kpi__icon"><CIcon name="check-square" :size="20" /></div>
          <div class="kpi__body"><div class="kpi__label">启用角色</div><div class="kpi__value">{{ kpiActive }}</div></div>
        </div>
        <div class="kpi kpi--warning">
          <div class="kpi__icon"><CIcon name="sign" :size="20" /></div>
          <div class="kpi__body"><div class="kpi__label">内置角色</div><div class="kpi__value">{{ kpiBuiltin }}</div></div>
        </div>
        <div class="kpi kpi--neutral">
          <div class="kpi__icon"><CIcon name="user-check" :size="20" /></div>
          <div class="kpi__body"><div class="kpi__label">成员关联数</div><div class="kpi__value">{{ kpiMembers }}</div></div>
        </div>
      </div>
      <div class="action-row">
        <CButton
          v-if="auth.can('role:create')"
          variant="primary"
          @click="openCreate"
        >
          <CIcon name="plus" :size="16" /> 新建角色
        </CButton>
      </div>
    </div>

    <div class="t1-roles__layout">
      <!-- 左：角色树 -->
      <CCard class="tree-card" padding="none">
        <div class="tree-head">
          <span class="tree-title">角色继承树</span>
          <span class="tree-sub">{{ rbac.roles.length }} 个角色</span>
        </div>
        <div class="tree-body">
          <RoleTreeNode
            v-for="r in rootRoles"
            :key="r.id"
            :role="r"
            :level="0"
            :selected-id="selectedId"
            :expanded-set="expanded"
            :get-children="(id: string) => rbac.children(id)"
            @toggle="toggleExpand"
            @select="selectRole"
          />
          <div v-if="!rootRoles.length" class="tree-empty">暂无角色</div>
        </div>
      </CCard>

      <!-- 右：角色详情 -->
      <CCard v-if="selected" class="detail-card" padding="none">
        <template #header>
          <div class="detail-head">
            <div class="detail-head__main">
              <div class="detail-title-row">
                <span class="detail-title">{{ selected.name }}</span>
                <CStatusPill :status="selected.status === 'ACTIVE' ? 'success' : 'disabled'" dot>
                  {{ selected.status === 'ACTIVE' ? '启用中' : '已停用' }}
                </CStatusPill>
                <CStatusPill v-if="selected.builtin" status="info" dot>系统内置</CStatusPill>
                <CStatusPill v-else status="primary">自定义</CStatusPill>
              </div>
              <div class="detail-meta">
                <span class="code-pill">{{ selected.code }}</span>
                <span class="meta-item">数据范围：<b>{{ scopeLabel(selected.permissions.scope) }}</b></span>
                <span class="meta-item">成员：<b>{{ rbac.memberCount(selected.id) }}</b> 人</span>
                <span class="meta-item muted">更新于 {{ fmtDate(selected.updatedAt) }}</span>
              </div>
            </div>
            <div class="detail-head__ops">
              <CButton
                v-if="auth.can('role:edit')"
                variant="secondary"
                size="sm"
                :disabled="selected.builtin"
                @click="openEdit(selected)"
              >
                <CIcon name="edit" :size="14" /> 编辑
              </CButton>
              <CButton
                v-if="auth.can('role:edit')"
                variant="secondary"
                size="sm"
                :disabled="selected.builtin"
                @click="onToggleStatus(selected)"
              >
                <CIcon name="check-square" :size="14" />
                {{ selected.status === 'ACTIVE' ? '停用' : '启用' }}
              </CButton>
              <CButton
                v-if="auth.can('role:delete')"
                variant="danger"
                size="sm"
                :disabled="selected.builtin"
                @click="onDelete(selected)"
              >
                <CIcon name="delete" :size="14" /> 删除
              </CButton>
            </div>
          </div>
        </template>

        <div class="detail-body">
          <!-- 基本信息 -->
          <section class="panel">
            <div class="panel__head">
              <h4 class="panel__title">基本信息</h4>
            </div>
            <div class="info-grid">
              <div class="info-cell">
                <div class="info-label">角色编码</div>
                <div class="info-value mono">{{ selected.code }}</div>
              </div>
              <div class="info-cell">
                <div class="info-label">角色名称</div>
                <div class="info-value">{{ selected.name }}</div>
              </div>
              <div class="info-cell">
                <div class="info-label">状态</div>
                <div class="info-value">
                  <CStatusPill :status="selected.status === 'ACTIVE' ? 'success' : 'disabled'" dot>
                    {{ selected.status === 'ACTIVE' ? '启用' : '停用' }}
                  </CStatusPill>
                </div>
              </div>
              <div class="info-cell">
                <div class="info-label">角色类型</div>
                <div class="info-value">
                  <CStatusPill :status="selected.builtin ? 'info' : 'primary'">
                    {{ selected.builtin ? '系统内置' : '自定义' }}
                  </CStatusPill>
                </div>
              </div>
              <div class="info-cell info-cell--full">
                <div class="info-label">角色描述</div>
                <div class="info-value">{{ selected.description || '—' }}</div>
              </div>
            </div>
          </section>

          <!-- 权限包 -->
          <section class="panel">
            <div class="panel__head">
              <h4 class="panel__title">权限包</h4>
              <span class="panel__hint">
                含继承父角色权限 · 数据范围
                <CStatusPill status="primary">{{ scopeLabel(selectedEff.scope) }}</CStatusPill>
              </span>
            </div>
            <div v-if="selectedEff.actions.includes('*')" class="perm-wildcard">
              <CIcon name="shield" :size="16" />
              该角色为超级管理员，拥有全部模块的功能权限（通配 *）。
            </div>
            <div v-else class="perm-groups">
              <div
                v-for="mod in MATRIX_MODULES"
                :key="mod.key"
                class="perm-group"
                :class="{ 'is-empty': !mod.perms.some((p) => selectedEff.actions.includes(p)) }"
              >
                <div class="perm-group__title">{{ mod.label }}</div>
                <div class="perm-chips">
                  <CStatusPill
                    v-for="p in mod.perms"
                    :key="p"
                    :status="selectedEff.actions.includes(p) ? 'success' : 'disabled'"
                  >
                    {{ p }}
                  </CStatusPill>
                </div>
              </div>
            </div>
          </section>

          <!-- 成员列表 -->
          <section class="panel">
            <div class="panel__head">
              <h4 class="panel__title">成员列表</h4>
              <span class="panel__hint">共 {{ selectedMembers.length }} 人</span>
            </div>
            <div v-if="selectedMembers.length" class="member-table">
              <div class="mt-row mt-row--head">
                <div class="mt-cell">姓名</div>
                <div class="mt-cell">职位</div>
                <div class="mt-cell">门店</div>
                <div class="mt-cell">加入时间</div>
                <div class="mt-cell mt-cell--ops">操作</div>
              </div>
              <div
                v-for="m in selectedMembers"
                :key="m.staffId"
                class="mt-row"
              >
                <div class="mt-cell mt-cell--name">
                  <span class="avatar">{{ m.name.slice(0, 1) }}</span>
                  <span>{{ m.name }}</span>
                </div>
                <div class="mt-cell">{{ m.jobTitle }}</div>
                <div class="mt-cell">{{ m.storeName }}</div>
                <div class="mt-cell muted">{{ fmtDate(m.addedAt) }}</div>
                <div class="mt-cell mt-cell--ops">
                  <CButton
                    v-if="auth.can('role:assign')"
                    variant="text"
                    size="sm"
                    @click="onRemoveMember(selected.id, m.staffId)"
                  >
                    移除
                  </CButton>
                </div>
              </div>
            </div>
            <div v-else class="empty-hint">
              <CIcon name="user" :size="18" />
              该角色暂无成员，可通过"成员分配"功能添加。
            </div>
          </section>
        </div>
      </CCard>

      <CCard v-else class="detail-card detail-card--placeholder" padding="lg">
        <div class="placeholder">
          <CIcon name="user-check" :size="36" />
          <p>请在左侧选择一个角色查看详情</p>
        </div>
      </CCard>
    </div>

    <!-- 抽屉：新建/编辑角色 -->
    <CDrawer
      v-model:show="drawerOpen"
      :title="editingId ? '编辑角色' : '新建角色'"
      size="lg"
    >
      <div class="form">
        <div class="form-grid">
          <label class="field">
            <span class="field__label">角色名称 <i>*</i></span>
            <CInput v-model="form.name" placeholder="如 皮肤科护士" />
          </label>
          <label class="field">
            <span class="field__label">角色编码 <i>*</i></span>
            <CInput v-model="form.code" placeholder="如 CLINIC_NURSE" />
          </label>
          <label class="field">
            <span class="field__label">父角色（继承权限）</span>
            <CSelect v-model="form.parentId" :options="parentOptions" width="100%" />
          </label>
          <label class="field">
            <span class="field__label">数据范围</span>
            <CSelect v-model="form.scope" :options="scopeOptions" width="100%" />
          </label>
          <label class="field field--full">
            <span class="field__label">角色描述</span>
            <CTextarea v-model="form.description" :rows="2" placeholder="描述该角色的职责范围" />
          </label>
        </div>

        <div class="perm-editor">
          <div class="perm-editor__head">
            <span class="field__label">功能权限</span>
            <span class="panel__hint">
              已选 {{ form.actions.includes('*') ? '全部（通配）' : form.actions.length + ' 项' }}
            </span>
          </div>
          <div class="perm-editor__body">
            <div v-for="mod in MATRIX_MODULES" :key="mod.key" class="pe-group">
              <CCheckbox
                :model-value="moduleAllChecked(mod.perms)"
                :disabled="form.actions.includes('*')"
                @update:model-value="() => toggleModuleAll(mod.perms)"
              >
                <span class="pe-group__title">{{ mod.label }}</span>
              </CCheckbox>
              <div class="pe-perms">
                <CCheckbox
                  v-for="p in mod.perms"
                  :key="p"
                  :model-value="isActionChecked(p)"
                  :disabled="form.actions.includes('*')"
                  @update:model-value="(v) => toggleAction(p, v)"
                >
                  <span class="pe-perm">{{ p }}</span>
                </CCheckbox>
              </div>
            </div>
          </div>
        </div>

        <div v-if="formErr" class="form-err">
          <CIcon name="alert" :size="14" /> {{ formErr }}
        </div>
      </div>

      <template #footer>
        <CButton variant="secondary" @click="drawerOpen = false">取消</CButton>
        <CButton
          variant="primary"
          :disabled="!auth.can(editingId ? 'role:edit' : 'role:create')"
          @click="submitForm"
        >
          {{ editingId ? '保存修改' : '创建角色' }}
        </CButton>
      </template>
    </CDrawer>
  </div>
</template>

<style scoped>
.t1-roles {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
  min-width: 1280px;
}
.t1-roles__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-md);
  flex-wrap: wrap;
}
.rb-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); flex: 1; }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--warning .kpi__icon { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.kpi--success .kpi__icon { background: var(--c-success-bg); color: var(--c-success-fg); }
.kpi--neutral .kpi__icon { background: var(--c-bg-page); color: var(--c-text-3); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }
.action-row { display: flex; gap: var(--s-sm); flex-shrink: 0; }

.t1-roles__layout {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: var(--s-md);
  align-items: start;
}

/* ---- 树 ---- */
.tree-card { max-height: calc(100vh - 220px); display: flex; flex-direction: column; overflow: hidden; }
.tree-card :deep(.card__body) {
  padding: 0;
  overflow-y: auto;
  flex: 1;
}
.tree-head { display: flex; align-items: baseline; justify-content: space-between; padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.tree-title { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.tree-sub { font-size: var(--t-xs); color: var(--c-text-3); }
.tree-body { padding: var(--s-xs) 0; }
.tree-empty { padding: var(--s-lg); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); }

/* render 函数内联的 RoleTreeNode 是子组件，scoped CSS 必须 :deep 穿透 */
.tree-body :deep(.node) { user-select: none; }
.tree-body :deep(.node__row) {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px var(--s-md);
  cursor: pointer;
  font-size: var(--t-sm);
  color: var(--c-text-2);
  transition: background .12s;
}
.tree-body :deep(.node__row):hover { background: var(--c-bg-page); }
.tree-body :deep(.node__row--active) { background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }
.tree-body :deep(.node__row--active):hover { background: var(--c-brand-soft); }
.tree-body :deep(.node__caret) {
  width: 18px; height: 18px;
  display: inline-flex; align-items: center; justify-content: center;
  color: var(--c-text-3);
  flex-shrink: 0;
}
.tree-body :deep(.node__caret--leaf) { visibility: hidden; }
.tree-body :deep(.node__label) {
  font-size: var(--t-sm);
  color: var(--c-text);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.tree-body :deep(.node__code) { font-size: var(--t-xs); color: var(--c-text-3); margin-left: var(--s-xxs); flex-shrink: 0; }
.tree-body :deep(.node__children) { padding-left: var(--s-md); }
.tree-body :deep(.node__row--active) .node__label { color: var(--c-brand); }
.tree-body :deep(.node__row--active) .node__code { color: var(--c-brand); opacity: .8; }

/* ---- 详情 ---- */
.detail-card { display: flex; flex-direction: column; }
.detail-card :deep(.card__body) { padding: 0; }
.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-md);
  padding: var(--s-lg);
  border-bottom: 1px solid var(--c-border-light);
}
.detail-head__main { display: flex; flex-direction: column; gap: var(--s-xs); min-width: 0; }
.detail-head__ops { display: flex; gap: var(--s-xs); flex-shrink: 0; }
.detail-title-row { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.detail-title { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail-meta { display: flex; align-items: center; gap: var(--s-md); flex-wrap: wrap; font-size: var(--t-xs); color: var(--c-text-3); }
.detail-meta .code-pill {
  font-family: var(--f-latin);
  background: var(--c-bg-page);
  padding: 2px 10px;
  border-radius: var(--r-sm);
  color: var(--c-text-2);
  font-weight: 600;
}
.detail-meta b { color: var(--c-text); font-weight: 600; }
.detail-meta .muted { color: var(--c-text-3); }

.detail-body {
  padding: var(--s-lg);
  display: flex;
  flex-direction: column;
  gap: var(--s-lg);
}
.panel { display: flex; flex-direction: column; gap: var(--s-md); }
.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-md);
  flex-wrap: wrap;
}
.panel__title { margin: 0; font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.panel__hint { font-size: var(--t-xs); color: var(--c-text-3); display: inline-flex; align-items: center; gap: var(--s-xs); }

.info-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--s-md);
  padding: var(--s-md);
  background: var(--c-bg-page);
  border-radius: var(--r-lg);
}
.info-cell { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.info-cell--full { grid-column: 1 / -1; }
.info-label { font-size: var(--t-xs); color: var(--c-text-3); }
.info-value { font-size: var(--t-sm); color: var(--c-text); font-weight: 500; word-break: break-all; }
.info-value.mono { font-family: var(--f-latin); }

.perm-wildcard {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-md);
  background: var(--c-brand-soft);
  color: var(--c-brand);
  border-radius: var(--r-lg);
  font-size: var(--t-sm);
  font-weight: 500;
}
.perm-groups {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--s-md);
}
.perm-group {
  display: flex;
  flex-direction: column;
  gap: var(--s-sm);
  padding: var(--s-md);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}
.perm-group.is-empty { opacity: 0.65; }
.perm-group__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.perm-chips { display: flex; flex-wrap: wrap; gap: var(--s-xxs); }

.member-table {
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
  overflow: hidden;
}
.mt-row {
  display: grid;
  grid-template-columns: 1.2fr 1.2fr 1fr 1fr 80px;
  align-items: center;
  padding: var(--s-sm) var(--s-md);
  border-top: 1px solid var(--c-border-light);
  font-size: var(--t-sm);
  color: var(--c-text-2);
}
.mt-row:first-child { border-top: none; }
.mt-row--head {
  background: var(--c-bg-page);
  color: var(--c-text-3);
  font-size: var(--t-xs);
  font-weight: 600;
}
.mt-cell--name { display: flex; align-items: center; gap: var(--s-sm); color: var(--c-text); font-weight: 600; }
.avatar {
  width: 26px; height: 26px;
  border-radius: 50%;
  background: var(--c-brand-soft);
  color: var(--c-brand);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: var(--t-xs); font-weight: 700;
}
.mt-cell--ops { display: flex; justify-content: flex-end; }
.muted { color: var(--c-text-3); }

.empty-hint {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-lg);
  justify-content: center;
  color: var(--c-text-3);
  font-size: var(--t-sm);
  background: var(--c-bg-page);
  border-radius: var(--r-lg);
}

.detail-card--placeholder { min-height: 420px; }
.detail-card--placeholder :deep(.card__body) {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
.placeholder { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); color: var(--c-text-3); }

/* ---- 抽屉表单 ---- */
.form { display: flex; flex-direction: column; gap: var(--s-lg); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.field { display: flex; flex-direction: column; gap: 6px; }
.field--full { grid-column: 1 / -1; }
.field__label { font-size: var(--t-sm); color: var(--c-text-2); font-weight: 500; }
.field__label i { color: var(--c-danger-fg); font-style: normal; margin-left: 2px; }

.perm-editor { display: flex; flex-direction: column; gap: var(--s-sm); }
.perm-editor__head {
  display: flex; align-items: center; justify-content: space-between;
}
.perm-editor__body {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-md);
  max-height: 360px;
  overflow-y: auto;
  padding: var(--s-md);
  background: var(--c-bg-page);
  border-radius: var(--r-lg);
}
.pe-group { display: flex; flex-direction: column; gap: var(--s-xs); }
.pe-group__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.pe-perms { display: flex; flex-direction: column; gap: var(--s-xxs); padding-left: var(--s-lg); }
.pe-perm { font-family: var(--f-latin); font-size: var(--t-xs); color: var(--c-text-2); }

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
</style>
