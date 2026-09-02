<script setup lang="ts">
// T1 权限中台 · 权限矩阵（/admin/permissions）
// 三标签：矩阵视图 / 冲突检测 / 差异对比；只读 + 导出矩阵
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CSelect from '@/components/CSelect.vue'
import CSegmented from '@/components/CSegmented.vue'
import { useT1RbacStore, MATRIX_MODULES, type PermissionConflict } from '@/stores/t1Rbac'
import { useAuthStore } from '@/stores/auth'

const rbac = useT1RbacStore()
const auth = useAuthStore()
onMounted(() => rbac.seed())

// ---------- KPI ----------
const kpiRoles = computed(() => rbac.activeRoles.length)
const kpiModules = computed(() => MATRIX_MODULES.length)
const kpiPerms = computed(() => MATRIX_MODULES.reduce((s, m) => s + m.perms.length, 0))
const conflicts = computed<PermissionConflict[]>(() => rbac.detectConflicts())
const kpiConflicts = computed(() => conflicts.value.length)

// ---------- 标签 ----------
type Tab = 'matrix' | 'conflict' | 'diff'
const tab = ref<Tab>('matrix')
const tabOptions = [
  { label: '矩阵视图', value: 'matrix' },
  { label: '冲突检测', value: 'conflict' },
  { label: '差异对比', value: 'diff' },
]

// ---------- 矩阵 ----------
const matrix = computed(() => rbac.matrix())
function modulePerms(roleId: string, modKey: string): string[] {
  const mod = MATRIX_MODULES.find((m) => m.key === modKey)
  if (!mod) return []
  const eff = rbac.effectivePermissions(roleId)
  if (eff.actions.includes('*')) return [...mod.perms]
  return mod.perms.filter((p) => eff.actions.includes(p))
}
function scopeOf(roleId: string) {
  return rbac.effectivePermissions(roleId).scope
}

// ---------- 差异对比 ----------
const roleOptions = computed(() =>
  rbac.activeRoles.map((r) => ({ label: `${r.name}（${r.code}）`, value: r.id })),
)
const diffA = ref('')
const diffB = ref('')
// 默认选前两个启用角色
if (rbac.activeRoles[0]) diffA.value = rbac.activeRoles[0].id
if (rbac.activeRoles[1]) diffB.value = rbac.activeRoles[1].id

const diff = computed(() => {
  if (!diffA.value || !diffB.value || diffA.value === diffB.value) return null
  return rbac.diffRoles(diffA.value, diffB.value)
})
const nameOf = (id: string) => rbac.get(id)?.name ?? '—'

// ---------- 导出矩阵 ----------
function exportMatrix() {
  const rows = matrix.value
  const header = ['角色编码', '角色名称', '数据范围', ...MATRIX_MODULES.map((m) => m.label)]
  const lines = [header.join(',')]
  for (const r of rows) {
    const line = [
      r.role.code,
      r.role.name,
      rbac.SCOPE_LABEL[r.eff.scope],
      ...MATRIX_MODULES.map((m) => {
        const perms = modulePerms(r.role.id, m.key)
        return perms.length ? perms.join('|') : '-'
      }),
    ]
    lines.push(line.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(','))
  }
  const csv = '\uFEFF' + lines.join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `权限矩阵_${new Date().toISOString().slice(0, 10)}.csv`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="t1-perm">
    <!-- 头部 -->
    <div class="t1-perm__head">
      <div class="kpi-row">
        <CKpi label="启用角色数" :value="String(kpiRoles)" tone="brand" icon="org" />
        <CKpi label="模块数" :value="String(kpiModules)" tone="teal" icon="org" />
        <CKpi label="权限点总数" :value="String(kpiPerms)" tone="text" icon="org" />
        <CKpi
          label="检测到冲突"
          :value="String(kpiConflicts)"
          :tone="kpiConflicts > 0 ? 'danger' : 'success'" icon="alert" />
      </div>
    </div>

    <CCard class="tab-card" padding="none">
      <template #header>
        <div class="tab-bar">
          <CSegmented v-model="tab" :options="tabOptions" />
          <span class="tab-tip" v-if="tab === 'matrix'">
            行 = 角色，列 = 模块；单元格显示该角色在该模块下拥有的权限点数量，点击查看具体权限码
          </span>
          <span class="tab-tip" v-else-if="tab === 'conflict'">
            基于职责分离（SoD）与数据范围差异规则，自动检测高风险权限组合
          </span>
          <span class="tab-tip" v-else>
            对比两个启用角色的有效权限（含父角色继承），展示独有/共有权限码
          </span>
          <CButton
            v-if="auth.can('role:view')"
            variant="secondary" size="sm"
            class="tab-bar__action"
            @click="exportMatrix"
          >
            <CIcon name="export" :size="14" /> 导出矩阵
          </CButton>
        </div>
      </template>

      <!-- 矩阵视图 -->
      <div v-if="tab === 'matrix'" class="matrix-wrap">
        <table class="matrix">
          <thead>
            <tr>
              <th class="matrix__role-col">角色 / 模块</th>
              <th v-for="m in MATRIX_MODULES" :key="m.key" :title="m.perms.join(', ')">
                {{ m.label }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in matrix" :key="row.role.id">
              <td class="matrix__role-col">
                <div class="role-cell">
                  <span class="role-name">{{ row.role.name }}</span>
                  <span class="role-meta">
                    <CStatusPill status="primary">{{ rbac.SCOPE_LABEL[scopeOf(row.role.id)] }}</CStatusPill>
                    <span class="role-code">{{ row.role.code }}</span>
                  </span>
                </div>
              </td>
              <td
                v-for="m in MATRIX_MODULES"
                :key="m.key"
                class="matrix__cell"
                :class="{
                  'is-on': modulePerms(row.role.id, m.key).length > 0,
                  'is-wild': row.eff.actions.includes('*'),
                }"
              >
                <template v-if="row.eff.actions.includes('*')">
                  <span class="cell-wild" title="全部权限（通配）">
                    <CIcon name="check-square" :size="16" />
                  </span>
                </template>
                <template v-else-if="modulePerms(row.role.id, m.key).length > 0">
                  <button
                    class="cell-btn"
                    :title="modulePerms(row.role.id, m.key).join('\n')"
                  >
                    <CIcon name="check" :size="14" />
                    <span>{{ modulePerms(row.role.id, m.key).length }}</span>
                  </button>
                </template>
                <template v-else>
                  <span class="cell-off">—</span>
                </template>
              </td>
            </tr>
            <tr v-if="!matrix.length">
              <td :colspan="MATRIX_MODULES.length + 1" class="matrix-empty">暂无启用角色</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 冲突检测 -->
      <div v-else-if="tab === 'conflict'" class="conflict-wrap">
        <div v-if="conflicts.length" class="conflict-list">
          <div
            v-for="(c, i) in conflicts"
            :key="i"
            class="conflict-item"
            :class="`conflict-item--${c.severity.toLowerCase()}`"
          >
            <div class="conflict-item__icon">
              <CIcon name="alert" :size="20" />
            </div>
            <div class="conflict-item__body">
              <div class="conflict-item__head">
                <div class="conflict-roles">
                  <CStatusPill status="primary">{{ c.roleA }}</CStatusPill>
                  <CIcon name="handover" :size="14" class="conflict-arrow" />
                  <CStatusPill status="primary">{{ c.roleB }}</CStatusPill>
                </div>
                <div class="conflict-tags">
                  <CStatusPill status="info">{{ c.module }}</CStatusPill>
                  <CStatusPill :status="c.severity === 'HIGH' ? 'danger' : 'warning'" dot>
                    {{ c.severity === 'HIGH' ? '高风险' : '中风险' }}
                  </CStatusPill>
                </div>
              </div>
              <p class="conflict-reason">{{ c.reason }}</p>
            </div>
          </div>
        </div>
        <div v-else class="conflict-empty">
          <CIcon name="check-square" :size="36" />
          <p>未检测到权限冲突，当前角色配置符合职责分离（SoD）基线。</p>
        </div>
      </div>

      <!-- 差异对比 -->
      <div v-else class="diff-wrap">
        <div class="diff-pickers">
          <div class="picker">
            <span class="picker__label">角色 A</span>
            <CSelect v-model="diffA" :options="roleOptions" width="240px" />
          </div>
          <CIcon name="handover" :size="18" class="diff-arrow" />
          <div class="picker">
            <span class="picker__label">角色 B</span>
            <CSelect v-model="diffB" :options="roleOptions" width="240px" />
          </div>
        </div>

        <div v-if="diff" class="diff-result">
          <div class="diff-scope">
            <div class="scope-chip">
              <span class="scope-chip__label">{{ nameOf(diffA) }} · 数据范围</span>
              <CStatusPill status="primary">{{ rbac.SCOPE_LABEL[diff.scopeA] }}</CStatusPill>
            </div>
            <div class="scope-chip">
              <span class="scope-chip__label">{{ nameOf(diffB) }} · 数据范围</span>
              <CStatusPill status="primary">{{ rbac.SCOPE_LABEL[diff.scopeB] }}</CStatusPill>
            </div>
          </div>
          <div class="diff-cols">
            <div class="diff-col diff-col--a">
              <div class="diff-col__head">
                <CIcon name="user" :size="14" />
                仅 {{ nameOf(diffA) }} 拥有
                <span class="diff-count">{{ diff.onlyA.length }}</span>
              </div>
              <div class="diff-col__body">
                <div v-for="p in diff.onlyA" :key="p" class="perm-chip perm-chip--a">
                  {{ p }}
                </div>
                <div v-if="!diff.onlyA.length" class="diff-empty">—</div>
              </div>
            </div>
            <div class="diff-col diff-col--common">
              <div class="diff-col__head">
                <CIcon name="check" :size="14" />
                共同拥有
                <span class="diff-count">{{ diff.common.length }}</span>
              </div>
              <div class="diff-col__body">
                <div v-for="p in diff.common" :key="p" class="perm-chip perm-chip--common">
                  {{ p }}
                </div>
                <div v-if="!diff.common.length" class="diff-empty">—</div>
              </div>
            </div>
            <div class="diff-col diff-col--b">
              <div class="diff-col__head">
                <CIcon name="user" :size="14" />
                仅 {{ nameOf(diffB) }} 拥有
                <span class="diff-count">{{ diff.onlyB.length }}</span>
              </div>
              <div class="diff-col__body">
                <div v-for="p in diff.onlyB" :key="p" class="perm-chip perm-chip--b">
                  {{ p }}
                </div>
                <div v-if="!diff.onlyB.length" class="diff-empty">—</div>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="diff-hint">
          <CIcon name="alert" :size="20" />
          请选择两个不同的启用角色进行对比。
        </div>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.t1-perm {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
  min-width: 1280px;
}
.t1-perm__head {
  display: block;
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s-md);
}

.tab-card { display: flex; flex-direction: column; }
.tab-card :deep(.card__body) { padding: 0; }

.tab-bar {
  display: flex;
  align-items: center;
  gap: var(--s-md);
  width: 100%;
  flex-wrap: wrap;
}
.tab-bar__action { margin-left: auto; flex-shrink: 0; }
.tab-tip {
  font-size: var(--t-xs);
  color: var(--c-text-3);
}

/* ---- 矩阵 ---- */
.matrix-wrap { overflow-x: auto; }
.matrix {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--t-sm);
}
.matrix thead th {
  position: sticky;
  top: 0;
  background: var(--c-bg-page);
  padding: var(--s-sm) var(--s-md);
  text-align: left;
  font-weight: 600;
  color: var(--c-text-2);
  font-size: var(--t-xs);
  white-space: nowrap;
  border-bottom: 1px solid var(--c-border);
  z-index: 1;
}
.matrix tbody td {
  padding: 0;
  border-bottom: 1px solid var(--c-border-light);
  border-right: 1px solid var(--c-border-light);
  text-align: center;
  vertical-align: middle;
  height: 52px;
}
.matrix__role-col {
  position: sticky;
  left: 0;
  background: var(--c-surface);
  text-align: left !important;
  padding: var(--s-sm) var(--s-md) !important;
  min-width: 200px;
  z-index: 2;
  border-right: 1px solid var(--c-border) !important;
}
.matrix thead .matrix__role-col { background: var(--c-bg-page); z-index: 3; }
.role-cell { display: flex; flex-direction: column; gap: 4px; }
.role-name { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.role-meta { display: flex; align-items: center; gap: var(--s-xxs); }
.role-code {
  font-family: var(--f-latin);
  font-size: var(--t-xs);
  color: var(--c-text-3);
}

.matrix__cell.is-on { background: var(--c-brand-soft); }
.matrix__cell.is-wild { background: var(--c-info-bg); }
.cell-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: transparent;
  border: none;
  color: var(--c-brand);
  font-weight: 600;
  cursor: help;
  padding: 4px 8px;
  border-radius: var(--r-sm);
  font-family: inherit;
  font-size: var(--t-sm);
}
.cell-btn:hover { background: rgba(255, 107, 158, 0.12); }
.cell-wild { color: var(--c-info-fg); display: inline-flex; }
.cell-off { color: var(--c-text-4); }
.matrix-empty {
  text-align: center !important;
  padding: var(--s-xl) !important;
  color: var(--c-text-3);
}

/* ---- 冲突 ---- */
.conflict-wrap { padding: var(--s-lg); }
.conflict-list { display: flex; flex-direction: column; gap: var(--s-md); }
.conflict-item {
  display: flex;
  gap: var(--s-md);
  padding: var(--s-md);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  border-left: 3px solid transparent;
}
.conflict-item--high { border-left-color: var(--c-danger-fg); background: var(--c-danger-bg); }
.conflict-item--medium { border-left-color: var(--c-warning-fg); background: var(--c-warning-bg); }
.conflict-item__icon {
  width: 36px; height: 36px;
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.conflict-item--high .conflict-item__icon { background: var(--c-surface); color: var(--c-danger-fg); }
.conflict-item--medium .conflict-item__icon { background: var(--c-surface); color: var(--c-warning-fg); }
.conflict-item__body { display: flex; flex-direction: column; gap: var(--s-xs); flex: 1; min-width: 0; }
.conflict-item__head {
  display: flex; align-items: center; justify-content: space-between;
  gap: var(--s-md); flex-wrap: wrap;
}
.conflict-roles { display: flex; align-items: center; gap: var(--s-xs); }
.conflict-arrow { color: var(--c-text-3); }
.conflict-tags { display: flex; gap: var(--s-xxs); }
.conflict-reason { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-base); }

.conflict-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-xxl);
  color: var(--c-success-fg);
}
.conflict-empty p { margin: 0; color: var(--c-text-2); font-size: var(--t-sm); }

/* ---- 差异对比 ---- */
.diff-wrap { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }
.diff-pickers { display: flex; align-items: flex-end; gap: var(--s-md); }
.picker { display: flex; flex-direction: column; gap: 6px; }
.picker__label { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 500; }
.diff-arrow { color: var(--c-text-3); margin-bottom: var(--s-sm); }

.diff-result { display: flex; flex-direction: column; gap: var(--s-md); }
.diff-scope { display: flex; gap: var(--s-md); flex-wrap: wrap; }
.scope-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--s-xs);
  padding: var(--s-xs) var(--s-md);
  background: var(--c-bg-page);
  border-radius: var(--r-lg);
  font-size: var(--t-sm);
}
.scope-chip__label { color: var(--c-text-3); }

.diff-cols {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--s-md);
}
.diff-col {
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 320px;
}
.diff-col__head {
  display: flex;
  align-items: center;
  gap: var(--s-xxs);
  padding: var(--s-sm) var(--s-md);
  font-size: var(--t-sm);
  font-weight: 600;
  border-bottom: 1px solid var(--c-border-light);
  background: var(--c-bg-page);
}
.diff-col--a .diff-col__head { color: var(--c-info-fg); }
.diff-col--common .diff-col__head { color: var(--c-success-fg); }
.diff-col--b .diff-col__head { color: var(--c-brand); }
.diff-count {
  margin-left: auto;
  font-family: var(--f-latin);
  background: var(--c-surface);
  padding: 0 8px;
  border-radius: var(--r-capsule);
  font-size: var(--t-xs);
  font-weight: 600;
  color: var(--c-text-2);
}
.diff-col__body {
  padding: var(--s-sm);
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-xxs);
  align-content: flex-start;
}
.perm-chip {
  font-family: var(--f-latin);
  font-size: var(--t-xs);
  padding: 4px 10px;
  border-radius: var(--r-pill);
  background: var(--c-info-bg);
  color: var(--c-info-fg);
}
.perm-chip--a { background: var(--c-info-bg); color: var(--c-info-fg); }
.perm-chip--b { background: var(--c-brand-soft); color: var(--c-brand); }
.perm-chip--common { background: var(--c-success-bg); color: var(--c-success-fg); }
.diff-empty { color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-md); }

.diff-hint {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-lg);
  background: var(--c-warning-bg);
  color: var(--c-warning-fg);
  border-radius: var(--r-lg);
  font-size: var(--t-sm);
}
</style>
