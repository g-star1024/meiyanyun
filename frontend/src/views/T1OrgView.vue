<script setup lang="ts">
// T1 权限中台 · 组织架构（/admin/org）
// 左树右详情 + 抽屉表单（新建/编辑）+ 状态切换
import { computed, defineComponent, h, onMounted, reactive, ref, watch, type PropType, type VNode } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CDrawer from '@/components/CDrawer.vue'
import { useM1OrgStore, type OrgNode, type OrgType, type OrgStatus } from '@/stores/m1Org'
import { useAuthStore } from '@/stores/auth'

const org = useM1OrgStore()
const auth = useAuthStore()
onMounted(() => org.seed())

// ---------- KPI ----------
const kpiTotal = computed(() => org.nodes.length)
const kpiRegions = computed(() => org.nodes.filter((n) => n.type === 'REGION').length)
const kpiStores = computed(() => org.nodes.filter((n) => n.type === 'STORE').length)
const rootId = computed(() => org.roots[0]?.id ?? '')
const kpiHeadcount = computed(() => (rootId.value ? org.totalHeadcount(rootId.value) : 0))

// ---------- 选中与展开 ----------
const selectedId = ref<string>('')
const expanded = ref<Set<string>>(new Set())

function ensureDefault() {
  if (!selectedId.value && org.roots[0]) {
    selectedId.value = org.roots[0].id
    expanded.value.add(org.roots[0].id)
  }
}
watch(() => org.nodes.length, ensureDefault, { immediate: true })

function toggleExpand(id: string) {
  if (expanded.value.has(id)) expanded.value.delete(id)
  else expanded.value.add(id)
}
function selectNode(id: string) {
  selectedId.value = id
}

const selected = computed<OrgNode | undefined>(() =>
  selectedId.value ? org.get(selectedId.value) : undefined,
)
const selectedChildren = computed(() =>
  selected.value ? org.children(selected.value.id) : [],
)
const selectedChildStats = computed(() =>
  selected.value ? org.childTypeCount(selected.value.id) : { regions: 0, stores: 0, depts: 0 },
)
const selectedTotalHc = computed(() =>
  selected.value ? org.totalHeadcount(selected.value.id) : 0,
)

// ---------- 图标映射 ----------
const TYPE_ICON: Record<OrgType, 'org' | 'box' | 'store' | 'user'> = {
  GROUP: 'org',
  REGION: 'box',
  STORE: 'store',
  DEPT: 'user',
}
const typeOptions = [
  { label: '集团 (GROUP)', value: 'GROUP' },
  { label: '大区 (REGION)', value: 'REGION' },
  { label: '门店 (STORE)', value: 'STORE' },
  { label: '部门 (DEPT)', value: 'DEPT' },
]

const parentOptions = computed(() => {
  const opts = [{ label: '无（顶级节点）', value: '' }]
  for (const n of org.nodes) {
    if (editingId.value && n.id === editingId.value) continue
    // 不能选自己的后代作为父节点（简单实现：排除自己的后代）
    if (editingId.value && org.descendantIds(editingId.value).includes(n.id)) continue
    opts.push({
      label: `${org.ORG_TYPE_LABEL[n.type]} · ${n.name}（${n.code}）`,
      value: n.id,
    })
  }
  return opts
})

// ---------- 抽屉表单 ----------
const drawerOpen = ref(false)
const editingId = ref<string | null>(null)
const formErr = ref('')
const form = reactive({
  name: '',
  code: '',
  type: 'STORE' as OrgType,
  parentId: '' as string | '',
  leaderName: '',
  headcount: 0,
  sort: 0,
  remark: '',
  status: 'ACTIVE' as OrgStatus,
})

function resetForm() {
  form.name = ''
  form.code = ''
  form.type = 'STORE'
  form.parentId = ''
  form.leaderName = ''
  form.headcount = 0
  form.sort = 0
  form.remark = ''
  form.status = 'ACTIVE'
  formErr.value = ''
}

function openCreate() {
  editingId.value = null
  resetForm()
  // 默认父节点为当前选中节点
  if (selected.value) form.parentId = selected.value.id
  drawerOpen.value = true
}

function openEdit(n: OrgNode) {
  editingId.value = n.id
  form.name = n.name
  form.code = n.code
  form.type = n.type
  form.parentId = n.parentId ?? ''
  form.leaderName = n.leaderName
  form.headcount = n.headcount
  form.sort = n.sort
  form.remark = n.remark ?? ''
  form.status = n.status
  formErr.value = ''
  drawerOpen.value = true
}

function onHeadcountInput(e: Event) {
  const v = Number((e.target as HTMLInputElement).value)
  form.headcount = Number.isFinite(v) && v >= 0 ? Math.floor(v) : 0
}
function onSortInput(e: Event) {
  const v = Number((e.target as HTMLInputElement).value)
  form.sort = Number.isFinite(v) ? Math.floor(v) : 0
}

function submitForm() {
  if (!form.name.trim()) { formErr.value = '请填写组织单元名称'; return }
  if (!form.code.trim()) { formErr.value = '请填写编码'; return }
  if (!/^[A-Z0-9][A-Z0-9-_]*$/.test(form.code.trim().toUpperCase())) {
    formErr.value = '编码仅支持大写字母/数字/下划线/连字符'
    return
  }
  const code = form.code.trim().toUpperCase()
  const dup = org.nodes.some((n) => n.code === code && n.id !== editingId.value)
  if (dup) { formErr.value = '编码已存在'; return }

  if (editingId.value) {
    org.update(editingId.value, {
      name: form.name.trim(),
      code,
      type: form.type,
      parentId: form.parentId || null,
      leaderName: form.leaderName.trim(),
      headcount: form.headcount,
      sort: form.sort,
      remark: form.remark.trim(),
      status: form.status,
    })
  } else {
    org.create({
      name: form.name.trim(),
      code,
      type: form.type,
      parentId: form.parentId || null,
      leaderName: form.leaderName.trim(),
      headcount: form.headcount,
      sort: form.sort,
      remark: form.remark.trim(),
      status: form.status,
    })
  }
  drawerOpen.value = false
}

// ---------- 停用/启用确认 ----------
const statusConfirmOpen = ref(false)
const confirmTarget = ref<OrgNode | null>(null)
const confirmTo = ref<OrgStatus>('ACTIVE')
const confirmReason = ref('')
const confirmErr = ref('')

function onToggleStatus(n: OrgNode) {
  const to: OrgStatus = n.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  confirmTarget.value = n
  confirmTo.value = to
  confirmReason.value = ''
  confirmErr.value = ''
  statusConfirmOpen.value = true
}

function confirmStatus() {
  if (!confirmTarget.value) return
  const reason = confirmReason.value.trim()
  if (confirmTo.value === 'INACTIVE' && !reason) {
    confirmErr.value = '请填写停用原因'
    return
  }
  try {
    org.setStatus(confirmTarget.value.id, confirmTo.value, reason || undefined)
    statusConfirmOpen.value = false
  } catch (e) {
    confirmErr.value = e instanceof Error ? e.message : '操作失败'
  }
}

// ---------- 工具 ----------
function fmtDate(iso: string) {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function typeLabel(t: OrgType) {
  return org.ORG_TYPE_LABEL[t]
}

// 递归树节点（内联，避免拆文件）
const OrgTreeNode = defineComponent({
  name: 'OrgTreeNode',
  props: {
    node: { type: Object as PropType<OrgNode>, required: true },
    level: { type: Number, default: 0 },
    selectedId: { type: String, default: '' },
    expandedSet: { type: Object as PropType<Set<string>>, required: true },
    getChildren: { type: Function as PropType<(id: string) => OrgNode[]>, required: true },
    typeIcon: { type: Object as PropType<Record<OrgType, string>>, required: true },
    typeLabel: { type: Function as PropType<(t: OrgType) => string>, required: true },
  },
  emits: ['toggle', 'select'],
  setup(props, { emit }): () => VNode {
    return () => {
      const n = props.node
      const kids = props.getChildren(n.id)
      const hasChildren = kids.length > 0
      const isOpen = props.expandedSet.has(n.id)
      const isActive = props.selectedId === n.id

      const caret = h(
        'span',
        {
          class: ['org-node__caret', { 'org-node__caret--leaf': !hasChildren }],
          onClick: (e: Event) => {
            e.stopPropagation()
            if (hasChildren) emit('toggle', n.id)
          },
        },
        hasChildren ? [h(CIcon, { name: isOpen ? 'chevron-down' : 'chevron-right', size: 14 })] : [],
      )
      const icon = h(
        'span',
        { class: ['org-node__type-icon', `type-icon--${n.type.toLowerCase()}`] },
        [h(CIcon, { name: props.typeIcon[n.type] as 'org' | 'box' | 'store' | 'user', size: 14 })],
      )
      const label = h('span', { class: 'org-node__label' }, n.name)
      const code = h('span', { class: 'org-node__code' }, n.code)
      const statusDot = n.status === 'INACTIVE'
        ? h('span', { class: 'org-node__status', title: '已停用' })
        : null

      const row = h(
        'div',
        {
          class: ['org-node__row', { 'is-active': isActive, 'is-inactive': n.status === 'INACTIVE' }],
          style: { paddingLeft: `calc(var(--s-sm) + ${props.level * 14}px)` },
          onClick: () => emit('select', n.id),
        },
        [caret, icon, label, code, statusDot],
      )

      const childrenVnodes: VNode | null = isOpen && hasChildren
        ? h(
            'div',
            { class: 'org-node__children' },
            kids.map((k) =>
              h(OrgTreeNode, {
                node: k,
                level: props.level + 1,
                selectedId: props.selectedId,
                expandedSet: props.expandedSet,
                getChildren: props.getChildren,
                typeIcon: props.typeIcon,
                typeLabel: props.typeLabel,
                onToggle: (id: string) => emit('toggle', id),
                onSelect: (id: string) => emit('select', id),
              }),
            ),
          )
        : null

      return h('div', { class: 'org-node' }, [row, childrenVnodes])
    }
  },
})
</script>

<template>
  <div class="t1-org">
    <!-- 头部 -->
    <div class="t1-org__head">
      <div class="mo-kpis">
        <div class="kpi kpi--brand">
          <div class="kpi__icon"><CIcon name="org" :size="20" /></div>
          <div class="kpi__body"><div class="kpi__label">组织单元总数</div><div class="kpi__value">{{ kpiTotal }}</div></div>
        </div>
        <div class="kpi kpi--info">
          <div class="kpi__icon"><CIcon name="box" :size="20" /></div>
          <div class="kpi__body"><div class="kpi__label">大区数</div><div class="kpi__value">{{ kpiRegions }}</div></div>
        </div>
        <div class="kpi kpi--success">
          <div class="kpi__icon"><CIcon name="store" :size="20" /></div>
          <div class="kpi__body"><div class="kpi__label">门店数</div><div class="kpi__value">{{ kpiStores }}</div></div>
        </div>
        <div class="kpi kpi--neutral">
          <div class="kpi__icon"><CIcon name="user-check" :size="20" /></div>
          <div class="kpi__body"><div class="kpi__label">集团总人数</div><div class="kpi__value">{{ kpiHeadcount }}</div></div>
        </div>
      </div>
    </div>

    <div class="t1-org__layout">
      <!-- 左：组织树 -->
      <CCard class="tree-card" padding="none">
        <div class="tree-head">
          <span class="tree-head__title">组织架构 <span class="tree-sub">{{ org.nodes.length }} 个节点</span></span>
          <CButton
            v-if="auth.can('org:edit')"
            variant="primary" size="sm"
            @click="openCreate"
          >
            <CIcon name="plus" :size="14" /> 新建组织单元
          </CButton>
        </div>
        <div class="tree-body">
          <OrgTreeNode
            v-for="r in org.roots"
            :key="r.id"
            :node="r"
            :level="0"
            :selected-id="selectedId"
            :expanded-set="expanded"
            :get-children="(id: string) => org.children(id)"
            :type-icon="TYPE_ICON"
            :type-label="typeLabel"
            @toggle="toggleExpand"
            @select="selectNode"
          />
          <div v-if="!org.roots.length" class="tree-empty">暂无组织数据</div>
        </div>
      </CCard>

      <!-- 右：节点详情 -->
      <CCard v-if="selected" class="detail-card" padding="none">
        <template #header>
          <div class="detail-head">
            <div class="detail-head__main">
              <span class="type-icon" :class="`type-icon--${selected.type.toLowerCase()}`">
                <CIcon :name="TYPE_ICON[selected.type]" :size="22" />
              </span>
              <div>
                <div class="detail-title">{{ selected.name }}</div>
                <div class="detail-meta">
                  <span class="code-pill">{{ selected.code }}</span>
                  <CStatusPill status="info">{{ typeLabel(selected.type) }}</CStatusPill>
                  <CStatusPill :status="selected.status === 'ACTIVE' ? 'success' : 'disabled'" dot>
                    {{ selected.status === 'ACTIVE' ? '正常' : '已停用' }}
                  </CStatusPill>
                  <span class="muted">创建于 {{ fmtDate(selected.createdAt) }}</span>
                </div>
              </div>
            </div>
            <div class="detail-head__ops">
              <CButton
                v-if="auth.can('org:edit')"
                variant="secondary"
                size="sm"
                @click="openEdit(selected)"
              >
                <CIcon name="edit" :size="14" /> 编辑
              </CButton>
              <CButton
                v-if="auth.can('org:edit')"
                variant="secondary"
                size="sm"
                @click="onToggleStatus(selected)"
              >
                <CIcon name="check-square" :size="14" />
                {{ selected.status === 'ACTIVE' ? '停用' : '启用' }}
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
                <div class="info-label">编码</div>
                <div class="info-value mono">{{ selected.code }}</div>
              </div>
              <div class="info-cell">
                <div class="info-label">名称</div>
                <div class="info-value">{{ selected.name }}</div>
              </div>
              <div class="info-cell">
                <div class="info-label">类型</div>
                <div class="info-value">{{ typeLabel(selected.type) }}</div>
              </div>
              <div class="info-cell">
                <div class="info-label">状态</div>
                <div class="info-value">
                  <CStatusPill :status="selected.status === 'ACTIVE' ? 'success' : 'disabled'" dot>
                    {{ selected.status === 'ACTIVE' ? '正常' : '已停用' }}
                  </CStatusPill>
                </div>
              </div>
              <div class="info-cell">
                <div class="info-label">负责人</div>
                <div class="info-value">{{ selected.leaderName || '—' }}</div>
              </div>
              <div class="info-cell">
                <div class="info-label">本节点编制</div>
                <div class="info-value">{{ selected.headcount }} 人</div>
              </div>
              <div class="info-cell">
                <div class="info-label">总人数（含下级）</div>
                <div class="info-value">
                  <b class="hc-strong">{{ selectedTotalHc }}</b> 人
                </div>
              </div>
              <div class="info-cell">
                <div class="info-label">排序</div>
                <div class="info-value">{{ selected.sort }}</div>
              </div>
              <div class="info-cell info-cell--full" v-if="selected.status === 'INACTIVE'">
                <div class="info-label">停用原因</div>
                <div class="info-value">{{ selected.inactiveReason || '—' }}</div>
              </div>
              <div class="info-cell info-cell--full" v-if="selected.remark">
                <div class="info-label">备注</div>
                <div class="info-value">{{ selected.remark }}</div>
              </div>
            </div>
          </section>

          <!-- 子节点统计 -->
          <section class="panel">
            <div class="panel__head">
              <h4 class="panel__title">直属子节点统计</h4>
            </div>
            <div class="stat-row">
              <div class="stat-chip">
                <span class="stat-chip__icon stat-chip__icon--region"><CIcon name="box" :size="16" /></span>
                <div>
                  <div class="stat-chip__label">大区</div>
                  <div class="stat-chip__value">{{ selectedChildStats.regions }}</div>
                </div>
              </div>
              <div class="stat-chip">
                <span class="stat-chip__icon stat-chip__icon--store"><CIcon name="store" :size="16" /></span>
                <div>
                  <div class="stat-chip__label">门店</div>
                  <div class="stat-chip__value">{{ selectedChildStats.stores }}</div>
                </div>
              </div>
              <div class="stat-chip">
                <span class="stat-chip__icon stat-chip__icon--dept"><CIcon name="user" :size="16" /></span>
                <div>
                  <div class="stat-chip__label">部门</div>
                  <div class="stat-chip__value">{{ selectedChildStats.depts }}</div>
                </div>
              </div>
              <div class="stat-chip stat-chip--total">
                <span class="stat-chip__icon"><CIcon name="org" :size="16" /></span>
                <div>
                  <div class="stat-chip__label">直属合计</div>
                  <div class="stat-chip__value">{{ selectedChildren.length }}</div>
                </div>
              </div>
            </div>
          </section>

          <!-- 直属子节点列表 -->
          <section class="panel">
            <div class="panel__head">
              <h4 class="panel__title">直属子节点</h4>
              <span class="panel__hint">共 {{ selectedChildren.length }} 个</span>
            </div>
            <div v-if="selectedChildren.length" class="child-list">
              <div
                v-for="c in selectedChildren"
                :key="c.id"
                class="child-item"
                @click="selectNode(c.id)"
              >
                <span class="child-item__icon" :class="`type-icon--${c.type.toLowerCase()}`">
                  <CIcon :name="TYPE_ICON[c.type]" :size="16" />
                </span>
                <div class="child-item__main">
                  <div class="child-item__name">{{ c.name }}</div>
                  <div class="child-item__meta">
                    <span class="mono">{{ c.code }}</span>
                    <span>负责人：{{ c.leaderName || '待任命' }}</span>
                    <span>{{ c.headcount }} 人</span>
                  </div>
                </div>
                <CStatusPill :status="c.status === 'ACTIVE' ? 'success' : 'disabled'" dot>
                  {{ c.status === 'ACTIVE' ? '正常' : '停用' }}
                </CStatusPill>
                <CIcon name="chevron-right" :size="16" class="child-item__arrow" />
              </div>
            </div>
            <div v-else class="empty-hint">
              <CIcon name="box" :size="18" />
              该节点暂无直属子单元。
            </div>
          </section>
        </div>
      </CCard>

      <CCard v-else class="detail-card detail-card--placeholder" padding="lg">
        <div class="placeholder">
          <CIcon name="org" :size="36" />
          <p>请在左侧选择一个组织单元查看详情</p>
        </div>
      </CCard>
    </div>

    <!-- 抽屉：新建/编辑组织单元 -->
    <CDrawer
      v-model:show="drawerOpen"
      :title="editingId ? '编辑组织单元' : '新建组织单元'"
      size="md"
    >
      <div class="form">
        <div class="form-grid">
          <label class="field field--full">
            <span class="field__label">名称 <i>*</i></span>
            <CInput v-model="form.name" placeholder="如 静安旗舰店" />
          </label>
          <label class="field">
            <span class="field__label">编码 <i>*</i></span>
            <CInput v-model="form.code" placeholder="如 M001" />
          </label>
          <label class="field">
            <span class="field__label">类型</span>
            <CSelect v-model="form.type" :options="typeOptions" width="100%" />
          </label>
          <label class="field field--full">
            <span class="field__label">上级节点</span>
            <CSelect v-model="form.parentId" :options="parentOptions" width="100%" />
          </label>
          <label class="field">
            <span class="field__label">负责人</span>
            <CInput v-model="form.leaderName" placeholder="如 苏晴" />
          </label>
          <label class="field">
            <span class="field__label">编制人数</span>
            <input
              class="native-input"
              type="number"
              min="0"
              :value="form.headcount"
              @input="onHeadcountInput"
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
          <label class="field">
            <span class="field__label">状态</span>
            <CSelect
              v-model="form.status"
              :options="[
                { label: '正常 (ACTIVE)', value: 'ACTIVE' },
                { label: '停用 (INACTIVE)', value: 'INACTIVE' },
              ]"
              width="100%"
            />
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
        <CButton variant="secondary" @click="drawerOpen = false">取消</CButton>
        <CButton
          variant="primary"
          :disabled="!auth.can('org:edit')"
          @click="submitForm"
        >
          {{ editingId ? '保存修改' : '创建' }}
        </CButton>
      </template>
    </CDrawer>

    <!-- 停用/启用确认 -->
    <div v-if="statusConfirmOpen" class="modal-mask" @click.self="statusConfirmOpen = false">
      <div class="modal modal--sm">
        <div class="modal__head">
          <h3>{{ confirmTo === 'INACTIVE' ? '停用组织单元' : '启用组织单元' }}</h3>
          <button class="modal__close" @click="statusConfirmOpen = false"><CIcon name="close" :size="18" /></button>
        </div>
        <div class="modal__body">
          <p class="confirm-txt">
            确认将「<b>{{ confirmTarget?.name }}</b>」{{ confirmTo === 'INACTIVE' ? '停用' : '启用' }}？
            {{ confirmTo === 'INACTIVE' ? '停用后该组织单元及其下级统计将标记为不可用。' : '启用后该组织单元恢复可用。' }}
          </p>
          <label v-if="confirmTo === 'INACTIVE'" class="field">
            <span class="field__label">停用原因 <i>*</i></span>
            <CTextarea v-model="confirmReason" placeholder="请说明停用原因，将记入审计日志" :rows="3" />
          </label>
          <div v-if="confirmErr" class="form-err">
            <CIcon name="alert" :size="14" /> {{ confirmErr }}
          </div>
        </div>
        <div class="modal__foot">
          <CButton variant="secondary" @click="statusConfirmOpen = false">取消</CButton>
          <CButton
            variant="primary"
            :disabled="confirmTo === 'INACTIVE' && !confirmReason.trim()"
            @click="confirmStatus"
          >
            确认{{ confirmTo === 'INACTIVE' ? '停用' : '启用' }}
          </CButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.t1-org {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
  min-width: 1280px;
}
.t1-org__head {
  display: block;
}
.mo-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); min-width: 0; }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--success .kpi__icon { background: var(--c-success-bg); color: var(--c-success-fg); }
.kpi--info .kpi__icon { background: var(--c-info-bg); color: var(--c-info-fg); }
.kpi--neutral .kpi__icon { background: var(--c-bg-page); color: var(--c-text-3); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }

.t1-org__layout {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: var(--s-md);
  align-items: start;
}

/* ---- 树 ---- */
.tree-card { max-height: calc(100vh - 220px); display: flex; flex-direction: column; overflow: hidden; }
.tree-card :deep(.card__body) { padding: 0; overflow-y: auto; flex: 1; }
.tree-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); font-weight: 700; font-size: var(--t-md); color: var(--c-text); flex-wrap: wrap; }
.tree-head__title { display: flex; align-items: baseline; gap: var(--s-xs); }
.tree-head .tree-sub { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 500; }
.tree-body { padding: var(--s-xs) 0; }
.tree-empty { padding: var(--s-lg); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); }

/* render 函数内联的 OrgTreeNode 是子组件，scoped CSS 必须 :deep 穿透 */
.tree-body :deep(.org-node) { user-select: none; }
.tree-body :deep(.org-node__row) {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px var(--s-md);
  cursor: pointer;
  font-size: var(--t-sm);
  color: var(--c-text-2);
  transition: background .12s;
}
.tree-body :deep(.org-node__row):hover { background: var(--c-bg-page); }
.tree-body :deep(.org-node__row.is-active) { background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }
.tree-body :deep(.org-node__row.is-active):hover { background: var(--c-brand-soft); }
.tree-body :deep(.org-node__row.is-inactive) { opacity: .5; }
.tree-body :deep(.org-node__caret) {
  width: 18px; height: 18px;
  display: inline-flex; align-items: center; justify-content: center;
  color: var(--c-text-3);
  flex-shrink: 0;
}
.tree-body :deep(.org-node__caret--leaf) { visibility: hidden; }
.tree-body :deep(.org-node__type-icon) {
  width: 22px; height: 22px;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: var(--r-sm);
  flex-shrink: 0;
}
.tree-body :deep(.type-icon--group) { background: var(--c-brand-soft); color: var(--c-brand); }
.tree-body :deep(.type-icon--region) { background: var(--c-info-bg); color: var(--c-info-fg); }
.tree-body :deep(.type-icon--store) { background: var(--c-success-bg); color: var(--c-success-fg); }
.tree-body :deep(.type-icon--dept) { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.tree-body :deep(.org-node__label) { font-size: var(--t-sm); color: var(--c-text); font-weight: 500; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-body :deep(.org-node__code) {
  font-family: var(--f-latin);
  font-size: var(--t-xs);
  color: var(--c-text-3);
  margin-left: var(--s-xxs);
  flex-shrink: 0;
}
.tree-body :deep(.org-node__status) {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--c-text-4);
  margin-left: auto;
  flex-shrink: 0;
}
.tree-body :deep(.org-node__row.is-active) .org-node__label { color: var(--c-brand); }
.tree-body :deep(.org-node__row.is-active) .org-node__code { color: var(--c-brand); opacity: .8; }
.tree-body :deep(.org-node__row.is-active) .org-node__type-icon { background: rgba(255, 107, 158, 0.18); color: var(--c-brand); }

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
.detail-head__main { display: flex; gap: var(--s-md); align-items: center; min-width: 0; }
.type-icon {
  width: 44px; height: 44px;
  border-radius: var(--r-lg);
  display: inline-flex; align-items: center; justify-content: center;
  flex: none;
}
.detail-title { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail-meta { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; margin-top: 4px; font-size: var(--t-xs); color: var(--c-text-3); }
.code-pill {
  font-family: var(--f-latin);
  background: var(--c-bg-page);
  padding: 2px 10px;
  border-radius: var(--r-sm);
  color: var(--c-text-2);
  font-weight: 600;
}
.detail-meta b { color: var(--c-text); font-weight: 600; }
.detail-meta .muted { color: var(--c-text-3); }
.detail-head__ops { display: flex; gap: var(--s-xs); flex-shrink: 0; }

.detail-body {
  padding: var(--s-lg);
  display: flex;
  flex-direction: column;
  gap: var(--s-lg);
}
.panel { display: flex; flex-direction: column; gap: var(--s-md); }
.panel__head {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); flex-wrap: wrap;
}
.panel__title { margin: 0; font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.panel__hint { font-size: var(--t-xs); color: var(--c-text-3); }

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
.info-value { font-size: var(--t-md); font-weight: 600; color: var(--c-text); word-break: break-all; }
.info-value.mono { font-family: var(--f-latin); font-size: var(--t-sm); }
.hc-strong { color: var(--c-brand); font-size: var(--t-lg); }

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--s-md);
}
.stat-chip {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-md);
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
}
.stat-chip--total { background: var(--c-brand-soft); border-color: var(--c-brand-border); }
.stat-chip__icon {
  width: 36px; height: 36px;
  border-radius: var(--r-md);
  display: flex; align-items: center; justify-content: center;
  background: var(--c-bg-page);
  color: var(--c-text-2);
  flex-shrink: 0;
}
.stat-chip__icon--region { background: var(--c-info-bg); color: var(--c-info-fg); }
.stat-chip__icon--store { background: var(--c-success-bg); color: var(--c-success-fg); }
.stat-chip__icon--dept { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.stat-chip__label { font-size: var(--t-xs); color: var(--c-text-3); }
.stat-chip__value { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); line-height: 1.2; }

.child-list {
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
  overflow: hidden;
}
.child-item {
  display: grid;
  grid-template-columns: 32px 1fr auto 20px;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md);
  border-top: 1px solid var(--c-border-light);
  cursor: pointer;
  transition: background .12s;
}
.child-item:first-child { border-top: none; }
.child-item:hover { background: var(--c-brand-soft); }
.child-item__icon {
  width: 28px; height: 28px;
  border-radius: var(--r-md);
  display: inline-flex; align-items: center; justify-content: center;
}
.child-item__main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.child-item__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.child-item__meta {
  display: flex; align-items: center; gap: var(--s-md);
  font-size: var(--t-xs); color: var(--c-text-3); flex-wrap: wrap;
}
.child-item__meta .mono { font-family: var(--f-latin); }
.child-item__arrow { color: var(--c-text-3); }

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
  display: flex; align-items: center; justify-content: center; height: 100%;
}
.placeholder { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); color: var(--c-text-3); }
.mono { font-family: var(--f-latin); }

/* ---- 表单 ---- */
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
