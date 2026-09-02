<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CCheckbox from '@/components/CCheckbox.vue'
import CSegmented from '@/components/CSegmented.vue'
import {
  useM1RbacStore, type RbacRole, type FieldAccess, FIELD_ACCESS_ORDER,
} from '@/stores/m1Rbac'
import { useAuthStore } from '@/stores/auth'
import type { DataScope } from '@/types/domain'

const rbac = useM1RbacStore()
const auth = useAuthStore()
onMounted(() => rbac.seed())

const canEdit = computed(() => auth.can('rbac:edit'))

// 选中角色
const selectedId = ref('')
const selected = computed<RbacRole | undefined>(() => {
  if (selectedId.value) {
    const r = rbac.get(selectedId.value)
    if (r) return r
  }
  return rbac.roles[0]
})
watch(() => rbac.roles.length, () => {
  if (!selectedId.value && rbac.roles[0]) selectedId.value = rbac.roles[0].id
}, { immediate: true })

function select(r: RbacRole) { selectedId.value = r.id }

const keyword = ref('')
const filteredBuiltin = computed(() => filterRoles(rbac.builtinRoles))
const filteredCustom = computed(() => filterRoles(rbac.customRoles))
function filterRoles(list: RbacRole[]) {
  const kw = keyword.value.trim()
  if (!kw) return list
  return list.filter((r) => `${r.key} ${r.label} ${r.desc ?? ''}`.includes(kw))
}

// 功能权限分组（可勾选）
const PERM_GROUPS: { group: string; perms: { key: string; label: string }[] }[] = [
  {
    group: '门店运营',
    perms: [
      { key: 'appointment:create', label: '预约登记' },
      { key: 'reception:edit', label: '前台接待' },
      { key: 'cashier:sign', label: '收银签字' },
      { key: 'writeoff:create', label: '核销执行' },
      { key: 'queue:edit', label: '叫号分诊' },
    ],
  },
  {
    group: '客户与医疗',
    perms: [
      { key: 'customer:edit', label: '客户资料编辑' },
      { key: 'customer:phone:decrypt', label: '手机号解密' },
      { key: 'emr:edit', label: '病历编辑' },
      { key: 'consult:edit', label: '咨询记录' },
      { key: 'followup:edit', label: '回访编辑' },
    ],
  },
  {
    group: '审批与管控',
    perms: [
      { key: 'refund:approve', label: '退款审批' },
      { key: 'transfer:approve', label: '资产转移审批' },
      { key: 'complaint:approve', label: '投诉结案' },
      { key: 'finance:margin:view', label: '成本毛利查看' },
      { key: 'settings:edit', label: '系统设置' },
    ],
  },
]
function isFieldAccess(acc: FieldAccess | undefined): FieldAccess { return acc ?? 'HIDE' }

function countFieldLevel(level: FieldAccess) {
  if (!selected.value) return 0
  return rbac.ALL_FIELDS.filter((f) => isFieldAccess(selected.value!.fields[f.key]) === level).length
}

// ---- 新建弹层 ----
const showModal = ref(false)
const formErr = ref('')
const form = reactive({ key: '', label: '', scope: 'STORE' as DataScope, desc: '' })
function openCreate() {
  Object.assign(form, { key: '', label: '', scope: 'STORE', desc: '' })
  formErr.value = ''
  showModal.value = true
}
function submit() {
  if (!form.label.trim()) { formErr.value = '请填写角色名称'; return }
  if (!form.key.trim()) { formErr.value = '请填写角色编码'; return }
  if (!/^[A-Z][A-Z0-9_]*$/.test(form.key.trim().toUpperCase())) { formErr.value = '编码仅支持大写字母/数字/下划线'; return }
  if (rbac.roles.some((r) => r.key === form.key.trim().toUpperCase())) { formErr.value = '角色编码已存在'; return }
  formErr.value = ''
  const r = rbac.create({ key: form.key, label: form.label, scope: form.scope, desc: form.desc })
  showModal.value = false
  selectedId.value = r.id
}

// 删除确认
const showDel = ref(false)
function openDel() { if (selected.value && !selected.value.builtin) showDel.value = true }
function confirmDel() {
  if (selected.value && !selected.value.builtin) {
    rbac.remove(selected.value.id)
    selectedId.value = rbac.roles[0]?.id ?? ''
  }
  showDel.value = false
}

const scopeOptions: { label: string; value: DataScope }[] = [
  { label: '仅本人', value: 'SELF' }, { label: '本门店', value: 'STORE' },
  { label: '本品牌', value: 'BRAND' }, { label: '本区域', value: 'REGION' },
  { label: '集团', value: 'GROUP' },
]
const accessSegments = FIELD_ACCESS_ORDER.map((v) => ({ label: rbac.FIELD_ACCESS_LABEL[v], value: v }))

function fmtDate(iso: string) {
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<template>
  <div class="rb-page">
    <!-- KPI -->
    <div class="rb-kpis">
      <div class="kpi kpi--brand">
        <div class="kpi__icon"><CIcon name="sign" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">角色总数</div><div class="kpi__value">{{ rbac.stats.total }}</div></div>
      </div>
      <div class="kpi kpi--info">
        <div class="kpi__icon"><CIcon name="plus" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">自定义角色</div><div class="kpi__value">{{ rbac.stats.custom }}</div></div>
      </div>
      <div class="kpi kpi--warning">
        <div class="kpi__icon"><CIcon name="org" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">授权人数</div><div class="kpi__value">{{ rbac.stats.headcount }}</div></div>
      </div>
      <div class="kpi kpi--success">
        <div class="kpi__icon"><CIcon name="settings" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">管控字段</div><div class="kpi__value">{{ rbac.stats.fields }}</div></div>
      </div>
    </div>

    <div class="rb-layout">
      <!-- 左：角色列表 -->
      <CCard class="rb-list" padding="none">
        <div class="rb-list__head">
          <CInput v-model="keyword" placeholder="搜索角色编码/名称" />
          <CButton variant="primary" size="sm" :disabled="!canEdit" v-perm="'rbac:edit'" @click="openCreate">
            <CIcon name="plus" :size="14" /> 新建
          </CButton>
        </div>
        <div class="rb-list__body">
          <div class="rb-group-label">内置角色（{{ filteredBuiltin.length }}）</div>
          <div
            v-for="r in filteredBuiltin" :key="r.id"
            class="role-item" :class="{ 'role-item--active': selected?.id === r.id }"
            @click="select(r)"
          >
            <div class="role-item__main">
              <span class="role-item__label">{{ r.label }}</span>
              <span class="role-item__key">{{ r.key }}</span>
            </div>
            <CStatusPill status="disabled" dot>系统</CStatusPill>
          </div>
          <div v-if="filteredCustom.length" class="rb-group-label">自定义角色（{{ filteredCustom.length }}）</div>
          <div
            v-for="r in filteredCustom" :key="r.id"
            class="role-item" :class="{ 'role-item--active': selected?.id === r.id }"
            @click="select(r)"
          >
            <div class="role-item__main">
              <span class="role-item__label">{{ r.label }}</span>
              <span class="role-item__key">{{ r.key }} · {{ r.headcount }}人 · {{ rbac.SCOPE_LABEL[r.scope] }}</span>
            </div>
            <CStatusPill status="success" dot>自定义</CStatusPill>
          </div>
          <div v-if="!filteredBuiltin.length && !filteredCustom.length" class="rb-empty">无匹配角色</div>
        </div>
      </CCard>

      <!-- 右：角色详情 -->
      <CCard v-if="selected" class="rb-detail" padding="none">
        <div class="rb-detail__head">
          <div>
            <div class="rb-detail__title-row">
              <h3>{{ selected.label }}</h3>
              <CStatusPill :status="selected.builtin ? 'disabled' : 'success'" dot>
                {{ selected.builtin ? '内置角色' : '自定义角色' }}
              </CStatusPill>
            </div>
            <div class="rb-detail__meta">
              <span class="code">{{ selected.key }}</span>
              <span>数据域：<b>{{ rbac.SCOPE_LABEL[selected.scope] }}</b></span>
              <span>授权 {{ selected.headcount ?? 0 }} 人</span>
              <span class="muted">更新于 {{ fmtDate(selected.updatedAt) }}</span>
            </div>
            <p v-if="selected.desc" class="rb-detail__desc">{{ selected.desc }}</p>
          </div>
          <div class="rb-detail__ops" v-if="!selected.builtin">
            <CButton variant="secondary" size="sm" :disabled="!canEdit" v-perm="'rbac:edit'" @click="openDel">删除角色</CButton>
          </div>
        </div>

        <div class="rb-detail__body">
          <!-- 数据域 -->
          <section class="panel">
            <div class="panel__head">
              <h4>数据范围</h4>
              <span class="panel__hint">决定该角色可查看哪些门店/区域的数据</span>
            </div>
            <div class="scope-row">
              <CSegmented
                :model-value="selected.scope"
                :options="scopeOptions"
                :disabled="selected.builtin || !canEdit"
                @update:model-value="(v) => canEdit && rbac.setScope(selected!.id, v as DataScope)"
              />
            </div>
          </section>

          <!-- 字段级权限矩阵 -->
          <section class="panel">
            <div class="panel__head">
              <h4>字段级权限</h4>
              <div class="panel__legend">
                <span class="lg lg--disabled">隐藏 {{ countFieldLevel('HIDE') }}</span>
                <span class="lg lg--warning">脱敏 {{ countFieldLevel('MASK') }}</span>
                <span class="lg lg--info">只读 {{ countFieldLevel('READ') }}</span>
                <span class="lg lg--success">可编辑 {{ countFieldLevel('EDIT') }}</span>
              </div>
            </div>
            <div v-if="selected.builtin" class="builtin-tip">
              <CIcon name="alert" :size="14" /> 内置角色字段策略由系统统一管控，不可修改；如需差异化请新建自定义角色。
            </div>
            <div class="field-matrix">
              <div class="fm-row fm-row--head">
                <div class="fm-cell fm-cell--name">字段</div>
                <div class="fm-cell fm-cell--access">访问级别</div>
              </div>
              <template v-for="g in rbac.FIELD_GROUPS" :key="g.module">
                <div class="fm-module">{{ g.module }}</div>
                <div v-for="f in g.fields" :key="f.key" class="fm-row">
                  <div class="fm-cell fm-cell--name">
                    <span class="fm-label">{{ f.label }}</span>
                    <span v-if="f.desc" class="fm-desc">{{ f.desc }}</span>
                  </div>
                  <div class="fm-cell fm-cell--access">
                    <CSegmented
                      size="sm"
                      :model-value="isFieldAccess(selected.fields[f.key])"
                      :options="accessSegments"
                      :disabled="selected.builtin || !canEdit"
                      @update:model-value="(v) => canEdit && rbac.setField(selected!.id, f.key, v as FieldAccess)"
                    />
                  </div>
                </div>
              </template>
            </div>
          </section>

          <!-- 功能权限 -->
          <section class="panel">
            <div class="panel__head">
              <h4>功能权限</h4>
              <span class="panel__hint">{{ selected.permissions.includes('*') ? '超级管理员：全部权限（通配）' : `已选 ${selected.permissions.length} 项` }}</span>
            </div>
            <div v-if="!selected.permissions.includes('*')" class="perm-grid">
              <div v-for="g in PERM_GROUPS" :key="g.group" class="perm-group">
                <div class="perm-group__title">{{ g.group }}</div>
                <CCheckbox
                  v-for="p in g.perms" :key="p.key"
                  :model-value="selected.permissions.includes(p.key)"
                  :disabled="selected.builtin || !canEdit"
                  @update:model-value="() => canEdit && rbac.togglePermission(selected!.id, p.key)"
                >{{ p.label }}<span class="perm-key">{{ p.key }}</span></CCheckbox>
              </div>
            </div>
            <div v-else class="builtin-tip builtin-tip--brand">
              <CIcon name="sign" :size="14" /> 超级管理员拥有全部功能权限（通配 *），不可裁剪。
            </div>
          </section>
        </div>
      </CCard>
    </div>

    <!-- 新建角色弹层 -->
    <div v-if="showModal" class="modal-mask" @click.self="showModal = false">
      <div class="modal">
        <div class="modal__head">
          <h3>新建自定义角色</h3>
          <button class="modal__close" @click="showModal = false"><CIcon name="close" :size="18" /></button>
        </div>
        <div class="modal__body">
          <div class="form-grid">
            <label class="field field--full"><span class="field__label">角色名称 <i>*</i></span><CInput v-model="form.label" placeholder="如 皮肤科护士" /></label>
            <label class="field"><span class="field__label">角色编码 <i>*</i></span><CInput v-model="form.key" placeholder="如 CLINIC_NURSE" /></label>
            <label class="field">
              <span class="field__label">数据范围</span>
              <select v-model="form.scope" class="sel sel--full">
                <option v-for="o in scopeOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </label>
            <label class="field field--full"><span class="field__label">角色说明</span><CTextarea v-model="form.desc" placeholder="描述该角色的职责范围（可选）" :rows="2" /></label>
          </div>
          <div v-if="formErr" class="form-err">{{ formErr }}</div>
          <p class="modal-tip">创建后可在右侧面板配置字段级访问与功能权限，保存即时生效。</p>
        </div>
        <div class="modal__foot">
          <CButton variant="secondary" @click="showModal = false">取消</CButton>
          <CButton variant="primary" @click="submit">创建</CButton>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <div v-if="showDel" class="modal-mask" @click.self="showDel = false">
      <div class="modal modal--sm">
        <div class="modal__head">
          <h3>删除角色</h3>
          <button class="modal__close" @click="showDel = false"><CIcon name="close" :size="18" /></button>
        </div>
        <div class="modal__body">
          <p class="confirm-txt">确认删除自定义角色「<b>{{ selected?.label }}</b>」？该角色下 {{ selected?.headcount ?? 0 }} 名人员将失去相应权限，操作记入审计日志。</p>
        </div>
        <div class="modal__foot">
          <CButton variant="secondary" @click="showDel = false">取消</CButton>
          <CButton variant="primary" @click="confirmDel">确认删除</CButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rb-page { display: flex; flex-direction: column; gap: var(--s-md); }

.rb-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--warning .kpi__icon { background: var(--c-warning-bg, #FFF5E6); color: var(--c-warning-fg); }
.kpi--success .kpi__icon { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }

.rb-layout { display: grid; grid-template-columns: 320px 1fr; gap: var(--s-md); align-items: start; }

.rb-list { max-height: calc(100vh - 220px); display: flex; flex-direction: column; }
.rb-list__head { display: flex; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.rb-list__head > :deep(.cinput) { flex: 1; }
.rb-list__body { overflow-y: auto; padding: var(--s-xs); }
.rb-group-label { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; padding: var(--s-sm) var(--s-sm) 4px; }
.role-item { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); border-radius: var(--r-md); cursor: pointer; transition: background .12s; }
.role-item:hover { background: var(--c-surface, #f7f8fa); }
.role-item--active { background: var(--c-brand-soft); }
.role-item--active:hover { background: var(--c-brand-soft); }
.role-item__main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.role-item__label { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.role-item__key { font-size: var(--t-xs); color: var(--c-text-3); }
.rb-empty { padding: var(--s-lg); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); }

.rb-detail { display: flex; flex-direction: column; }
.rb-detail__head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-md); padding: var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.rb-detail__title-row { display: flex; align-items: center; gap: var(--s-sm); }
.rb-detail__title-row h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.rb-detail__meta { display: flex; flex-wrap: wrap; gap: var(--s-md); margin-top: 6px; font-size: var(--t-xs); color: var(--c-text-3); }
.rb-detail__meta .code { font-family: var(--t-number, monospace); background: var(--c-surface, #f7f8fa); padding: 1px 8px; border-radius: var(--r-sm); color: var(--c-text-2); }
.rb-detail__meta b { color: var(--c-text); font-weight: 600; }
.rb-detail__meta .muted { color: var(--c-text-3); }
.rb-detail__desc { margin: 8px 0 0; font-size: var(--t-xs); color: var(--c-text-2); }
.rb-detail__body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }

.panel { display: flex; flex-direction: column; gap: var(--s-md); }
.panel__head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); flex-wrap: wrap; }
.panel__head h4 { margin: 0; font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.panel__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.scope-row { max-width: 520px; }

.builtin-tip { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-text-2); background: var(--c-surface, #f7f8fa); padding: var(--s-sm) var(--s-md); border-radius: var(--r-md); border-left: 3px solid var(--c-text-3); }
.builtin-tip--brand { border-left-color: var(--c-brand); background: var(--c-brand-soft); color: var(--c-brand); }

.panel__legend { display: flex; gap: var(--s-sm); flex-wrap: wrap; }
.lg { font-size: var(--t-xs); padding: 2px 10px; border-radius: var(--r-capsule); font-weight: 500; }
.lg--disabled { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); }
.lg--warning { background: var(--c-warning-bg, #FFF5E6); color: var(--c-warning-fg); }
.lg--info { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.lg--success { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }

.field-matrix { border: 1px solid var(--c-border-light); border-radius: var(--r-lg); overflow: hidden; }
.fm-row { display: grid; grid-template-columns: 1fr 360px; align-items: center; }
.fm-row--head { background: var(--c-surface, #f7f8fa); }
.fm-row--head .fm-cell { font-size: var(--t-xs); font-weight: 600; color: var(--c-text-3); }
.fm-module { grid-column: 1 / -1; padding: 6px var(--s-md); font-size: var(--t-xs); font-weight: 600; color: var(--c-text-2); background: var(--c-surface, #f7f8fa); border-top: 1px solid var(--c-border-light); border-bottom: 1px solid var(--c-border-light); }
.fm-row:not(.fm-row--head) { border-top: 1px solid var(--c-border-light); }
.fm-cell { padding: var(--s-sm) var(--s-md); display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.fm-cell--access { align-items: stretch; }
.fm-label { font-size: var(--t-sm); color: var(--c-text); font-weight: 500; }
.fm-desc { font-size: var(--t-xs); color: var(--c-text-3); }

.perm-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-lg); }
.perm-group { display: flex; flex-direction: column; gap: var(--s-sm); }
.perm-group__title { font-size: var(--t-xs); font-weight: 600; color: var(--c-text-2); padding-bottom: 4px; border-bottom: 1px solid var(--c-border-light); }
.perm-key { display: block; font-size: 11px; color: var(--c-text-3); font-family: var(--t-number, monospace); margin-top: 2px; }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--c-surface); border-radius: var(--r-xl); width: 520px; max-width: calc(100vw - 48px); max-height: 86vh; display: flex; flex-direction: column; box-shadow: var(--shadow-pop, 0 12px 40px rgba(0,0,0,.18)); }
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
.sel { height: 36px; padding: 0 12px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); }
.sel--full { width: 100%; }
.form-err { margin-top: var(--s-sm); color: var(--c-danger-fg); font-size: var(--t-xs); }
.modal-tip { margin: var(--s-md) 0 0; font-size: var(--t-xs); color: var(--c-text-3); }
.confirm-txt { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); }

@media (max-width: 1024px) {
  .rb-kpis { grid-template-columns: repeat(2, 1fr); }
  .rb-layout { grid-template-columns: 1fr; }
  .rb-list { max-height: none; }
  .perm-grid { grid-template-columns: 1fr; }
  .fm-row { grid-template-columns: 1fr; }
  .fm-cell--access { padding-top: 0; }
}
</style>
