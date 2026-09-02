<script setup lang="ts">
/* ============================================================
 * T2-03 标签工厂 /data/tags
 * SQL/规则/ML 标签加工 → 发布 → 敏感标签需 T3 审批
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CDrawer from '@/components/CDrawer.vue'
import { useT2TagFactoryStore, type FactoryTag, type TagFactoryType, type TagFactoryStatus, type TagSensitivity, type ValueType } from '@/stores/t2TagFactory'
import { useAuthStore } from '@/stores/auth'

const store = useT2TagFactoryStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '标签总数', icon: 'customer', value: String(store.kpi.total), tone: 'brand' as const },
  { label: '已发布', icon: 'check-square', value: String(store.kpi.published), tone: 'success' as const },
  { label: '草稿', icon: 'dashboard', value: String(store.kpi.draft), tone: 'text' as const },
  { label: '待审批', icon: 'check-square', value: String(store.kpi.pending), tone: store.kpi.pending > 0 ? 'warning' as const : 'text' as const },
])

// pill 映射
function statusPill(s: TagFactoryStatus) {
  return s === 'PUBLISHED' ? 'success'
    : s === 'DRAFT' ? 'disabled'
    : s === 'PENDING_APPROVAL' ? 'warning'
    : s === 'OFFLINE' ? 'info'
    : 'primary'
}
function sensPill(s: TagSensitivity) {
  return s === 'PUBLIC' ? 'success' : s === 'INTERNAL' ? 'info' : 'danger'
}
function typePill(t: TagFactoryType) {
  return t === 'SQL' ? 'primary' : t === 'RULE' ? 'info' : 'warning'
}

function fmtTime(iso: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function fmtNum(n: number) { return n.toLocaleString() }

// ---- 筛选 ----
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'PENDING_APPROVAL', label: '待审批' },
  { value: 'OFFLINE', label: '已下线' },
  { value: 'PROCESSING', label: '计算中' },
]
const typeOptions = [
  { value: 'ALL', label: '全部类型' },
  { value: 'SQL', label: 'SQL 加工' },
  { value: 'RULE', label: '规则加工' },
  { value: 'ML', label: 'ML 模型' },
]

// ---- 展开详情 ----
const expandedId = ref<string | null>(null)
function toggleExpand(id: string) { expandedId.value = expandedId.value === id ? null : id }

// ---- 新建/编辑 ----
const showForm = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({
  code: '', name: '', category: '', type: 'SQL' as TagFactoryType,
  sensitivity: 'PUBLIC' as TagSensitivity, valueType: 'ENUM' as ValueType,
  description: '', sql: '', refreshCron: '每日 02:00', tagsText: '',
})
const typeFormOpts = [
  { value: 'SQL', label: 'SQL 加工' },
  { value: 'RULE', label: '规则加工' },
  { value: 'ML', label: 'ML 模型' },
]
const sensOpts = [
  { value: 'PUBLIC', label: '公开' },
  { value: 'INTERNAL', label: '内部' },
  { value: 'SENSITIVE', label: '敏感（需审批）' },
]
const valueTypeOpts = [
  { value: 'ENUM', label: '枚举' },
  { value: 'NUMBER', label: '数值' },
  { value: 'BOOLEAN', label: '布尔' },
  { value: 'DATE', label: '日期' },
]
const canSubmit = computed(() => form.code.trim() && form.name.trim() && form.sql.trim())
function resetForm() {
  Object.assign(form, {
    code: '', name: '', category: '', type: 'SQL',
    sensitivity: 'PUBLIC', valueType: 'ENUM',
    description: '', sql: '', refreshCron: '每日 02:00', tagsText: '',
  })
  editingId.value = null
}
function openCreate() { resetForm(); showForm.value = true }
function openEdit(t: FactoryTag) {
  Object.assign(form, {
    code: t.code, name: t.name, category: t.category, type: t.type,
    sensitivity: t.sensitivity, valueType: t.valueType,
    description: t.description, sql: t.sql, refreshCron: t.refreshCron,
    tagsText: t.tags.join(', '),
  })
  editingId.value = t.id
  showForm.value = true
}
function submitForm() {
  if (!canSubmit.value) return
  const payload = {
    code: form.code, name: form.name, category: form.category || '未分类',
    type: form.type, sensitivity: form.sensitivity, valueType: form.valueType,
    description: form.description, sql: form.sql, refreshCron: form.refreshCron,
    tags: form.tagsText.split(',').map((s) => s.trim()).filter(Boolean),
  }
  if (editingId.value) {
    store.updateTag(editingId.value, {
      name: payload.name, description: payload.description, sql: payload.sql,
      sensitivity: payload.sensitivity, refreshCron: payload.refreshCron,
      category: payload.category, tags: payload.tags,
    })
  } else {
    store.createTag(payload)
  }
  showForm.value = false
  resetForm()
}

// ---- 操作反馈 ----
const previewMsg = ref<Record<string, string>>({})
function doPreview(t: FactoryTag) {
  const n = store.previewCompute(t.id)
  previewMsg.value[t.id] = `试算完成，预估覆盖 ${n.toLocaleString()} 人`
  setTimeout(() => delete previewMsg.value[t.id], 3000)
}
function doPublish(t: FactoryTag) {
  store.publishTag(t.id)
}
function doApprove(t: FactoryTag) { store.approvePublish(t.id) }
function doOffline(t: FactoryTag) { store.offlineTag(t.id) }
function doDelete(t: FactoryTag) {
  if (confirm(`确认删除标签「${t.name}」？`)) store.deleteTag(t.id)
}
</script>

<template>
  <div class="tagf">
    <div class="tagf__head">
      <div class="tagf__kpis">
        <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
      </div>
    </div>

    <CCard class="tagf__main" padding="none">
      <div class="tagf__toolbar">
        <div class="tagf__filters">
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="140px" />
          <CSelect v-model="store.filterType" :options="typeOptions" width="140px" />
          <CInput v-model="store.keyword" placeholder="搜索标签名或编码" style="width: 240px" />
        </div>
        <CButton v-if="auth.can('tagFactory:create')" variant="primary" size="sm" class="tagf__create" @click="openCreate">
          <CIcon name="plus" :size="16" />新建标签
        </CButton>
      </div>

      <table class="ctable">
        <thead>
          <tr>
            <th style="width:32px"></th>
            <th>标签</th>
            <th style="width:110px">类型</th>
            <th style="width:100px">敏感度</th>
            <th style="width:100px">状态</th>
            <th style="width:120px" class="num">覆盖人数</th>
            <th style="width:100px" class="num">消费方</th>
            <th style="width:150px">最后计算</th>
            <th style="width:320px">操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="t in store.filtered" :key="t.id">
            <tr :class="{ 'row--expand': expandedId === t.id }">
              <td>
                <button class="expand-btn" @click="toggleExpand(t.id)">
                  <CIcon :name="expandedId === t.id ? 'chevron-down' : 'chevron-right'" :size="14" />
                </button>
              </td>
              <td>
                <div class="tg-name">{{ t.name }}</div>
                <div class="tg-sub"><code>{{ t.code }}</code> · {{ t.category }}</div>
              </td>
              <td><CStatusPill :status="typePill(t.type)">{{ store.TYPE_LABEL[t.type] }}</CStatusPill></td>
              <td><CStatusPill :status="sensPill(t.sensitivity)">{{ store.SENSITIVITY_LABEL[t.sensitivity] }}</CStatusPill></td>
              <td><CStatusPill :status="statusPill(t.status)" dot>{{ store.STATUS_LABEL[t.status] }}</CStatusPill></td>
              <td class="num">{{ fmtNum(t.coverCount) }}</td>
              <td class="num">{{ t.consumers.length }}</td>
              <td>{{ fmtTime(t.lastComputeAt) }}</td>
              <td>
                <div class="ops">
                  <CButton size="sm" variant="text" :disabled="!auth.can('tagFactory:edit')" @click="doPreview(t)">
                    <CIcon name="loading" :size="13" />试算
                  </CButton>
                  <CButton v-if="t.status === 'DRAFT' || t.status === 'OFFLINE'" size="sm" variant="text"
                    :disabled="!auth.can('tagFactory:publish')" @click="doPublish(t)">
                    <CIcon name="upload" :size="13" />{{ t.sensitivity === 'SENSITIVE' ? '提交审批' : '发布' }}
                  </CButton>
                  <CButton v-if="t.status === 'PENDING_APPROVAL' && auth.can('tagFactory:approve')" size="sm" variant="text" @click="doApprove(t)">
                    <CIcon name="check" :size="13" />审批通过
                  </CButton>
                  <CButton v-if="t.status === 'PUBLISHED'" size="sm" variant="text" :disabled="!auth.can('tagFactory:edit')" @click="doOffline(t)">
                    <CIcon name="export" :size="13" />下线
                  </CButton>
                  <CButton v-if="t.status !== 'PUBLISHED'" size="sm" variant="text" :disabled="!auth.can('tagFactory:edit')" @click="openEdit(t)">
                    <CIcon name="edit" :size="13" />编辑
                  </CButton>
                  <CButton v-if="t.status !== 'PUBLISHED'" size="sm" variant="text" :disabled="!auth.can('tagFactory:edit')" @click="doDelete(t)">
                    <CIcon name="delete" :size="13" />删除
                  </CButton>
                </div>
                <div v-if="previewMsg[t.id]" class="ops__msg">
                  <CIcon name="check" :size="12" />{{ previewMsg[t.id] }}
                </div>
              </td>
            </tr>
            <tr v-if="expandedId === t.id" class="row-detail">
              <td colspan="9">
                <div class="detail">
                  <div class="detail__col">
                    <div class="detail__title">标签说明</div>
                    <p class="detail__desc">{{ t.description || '—' }}</p>
                    <div class="detail__title">SQL / 规则表达式</div>
                    <pre class="detail__sql">{{ t.sql }}</pre>
                    <div class="detail__meta">
                      <span>刷新：{{ t.refreshCron }}</span>
                      <span>负责人：{{ t.owner }}</span>
                      <span>创建：{{ fmtTime(t.createdAt) }}</span>
                    </div>
                  </div>
                  <div class="detail__col">
                    <div class="detail__title">版本历史（{{ t.versions.length }}）</div>
                    <div v-if="!t.versions.length" class="muted">暂无已发布版本</div>
                    <ul v-else class="ver-list">
                      <li v-for="v in t.versions" :key="v.version">
                        <div class="ver-head">
                          <span class="ver-tag">{{ v.version }}</span>
                          <span class="muted">{{ fmtTime(v.publishedAt) }} · {{ v.publishedBy }}</span>
                        </div>
                        <div class="ver-cov">覆盖 {{ fmtNum(v.coverCount) }} 人</div>
                      </li>
                    </ul>
                    <div class="detail__title">消费方（{{ t.consumers.length }}）</div>
                    <div v-if="!t.consumers.length" class="muted">暂无消费方</div>
                    <ul v-else class="con-list">
                      <li v-for="(c, i) in t.consumers" :key="i">
                        <span class="con-mod">{{ c.module }}</span>
                        <span class="muted">{{ c.scene }} · {{ fmtTime(c.usedAt) }}</span>
                      </li>
                    </ul>
                  </div>
                </div>
              </td>
            </tr>
          </template>
          <tr v-if="store.filtered.length === 0">
            <td colspan="9" class="empty">没有符合条件的标签</td>
          </tr>
        </tbody>
      </table>
    </CCard>

    <!-- 新建/编辑 Drawer -->
    <CDrawer :show="showForm" :title="editingId ? '编辑标签' : '新建标签'" size="lg" @update:show="showForm = $event">
      <div class="form">
        <div class="form__row">
          <CInput v-model="form.code" label="标签编码" placeholder="TAG_XXX（英文大写下划线）" />
          <CInput v-model="form.name" label="标签名称" placeholder="例如：高价值客户" />
        </div>
        <CInput v-model="form.category" label="分类" placeholder="客户价值 / 风险预警 / 消费偏好 ..." />
        <div class="form__row">
          <div class="form__field">
            <label class="fld-label">加工类型</label>
            <CSelect v-model="form.type" :options="typeFormOpts" width="100%" />
          </div>
          <div class="form__field">
            <label class="fld-label">值类型</label>
            <CSelect v-model="form.valueType" :options="valueTypeOpts" width="100%" />
          </div>
        </div>
        <div class="form__row">
          <div class="form__field">
            <label class="fld-label">敏感度</label>
            <CSelect v-model="form.sensitivity" :options="sensOpts" width="100%" />
          </div>
          <CInput v-model="form.refreshCron" label="刷新频率" placeholder="每日 02:00" />
        </div>
        <CTextarea v-model="form.description" :rows="2" label="标签描述" placeholder="标签的业务含义和使用场景" />
        <CTextarea v-model="form.sql" :rows="4" label="SQL / 规则表达式" placeholder="SELECT customer_id FROM ..." />
        <CInput v-model="form.tagsText" label="附加标签（逗号分隔）" placeholder="高价值, 核心客群" />
      </div>
      <template #footer>
        <CButton variant="ghost" @click="showForm = false">取消</CButton>
        <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">{{ editingId ? '保存' : '创建' }}</CButton>
      </template>
    </CDrawer>
  </div>
</template>

<style scoped>
.tagf { display: flex; flex-direction: column; gap: var(--s-md); }
.tagf__head { display: flex; align-items: stretch; gap: var(--s-md); }
.tagf__kpis { flex: 1; display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.tagf__main :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); padding: 0; }
.tagf__toolbar { padding: var(--s-md) var(--s-lg) 0; display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); flex-wrap: nowrap; }
.tagf__filters { display: flex; gap: var(--s-sm); align-items: center; flex-wrap: nowrap; overflow-x: auto; }
.tagf__filters > :deep(.csel) { flex-shrink: 0; }
.tagf__create { flex-shrink: 0; white-space: nowrap; margin-left: auto; }

.ctable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.ctable thead th { padding: 12px var(--s-lg); background: var(--c-bg-page); color: var(--c-text); font-weight: 600; font-size: var(--t-xs); text-align: left; border-bottom: 1px solid var(--c-border); white-space: nowrap; }
.ctable tbody td { padding: 12px var(--s-lg); color: var(--c-text-2); border-bottom: 1px solid var(--c-border); vertical-align: middle; }
.ctable tbody tr:last-child td { border-bottom: none; }
.ctable tbody tr:hover { background: var(--c-brand-soft); }
.ctable .num { text-align: right; font-variant-numeric: tabular-nums; }
.ctable th.num { text-align: right; }
.ctable .muted { color: var(--c-text-3); font-size: var(--t-xs); }

.expand-btn { border: none; background: transparent; color: var(--c-text-3); cursor: pointer; width: 24px; height: 24px; border-radius: var(--r-sm); display: inline-flex; align-items: center; justify-content: center; }
.expand-btn:hover { background: var(--c-bg-page); color: var(--c-text); }
.row--expand { background: var(--c-brand-soft); }
.row-detail td { background: var(--c-bg-page); padding: 0 !important; }
.row-detail:hover { background: var(--c-bg-page) !important; }

.tg-name { font-weight: 600; color: var(--c-text); }
.tg-sub { font-size: 11px; color: var(--c-text-3); margin-top: 2px; display: flex; gap: 6px; align-items: center; }
.tg-sub code { font-family: ui-monospace, Menlo, monospace; background: var(--c-surface); padding: 1px 5px; border-radius: 3px; }

.ops { display: flex; flex-wrap: wrap; gap: 2px; }
.ops :deep(.cbtn--text) { padding: 2px 6px; font-size: var(--t-xs); height: 24px; }
.ops__msg { margin-top: 4px; font-size: 11px; color: var(--c-success-fg); display: flex; align-items: center; gap: 4px; }

.detail { display: grid; grid-template-columns: 1.2fr 1fr; gap: var(--s-lg); padding: var(--s-md) var(--s-lg); }
.detail__col { display: flex; flex-direction: column; gap: var(--s-xs); min-width: 0; }
.detail__title { font-size: var(--t-xs); font-weight: 600; color: var(--c-text-3); text-transform: uppercase; letter-spacing: 0.5px; margin-top: var(--s-xs); }
.detail__desc { font-size: var(--t-sm); color: var(--c-text-2); margin: 0; line-height: 1.6; }
.detail__sql { font-family: ui-monospace, Menlo, monospace; font-size: 11px; background: var(--c-surface); padding: var(--s-sm); border-radius: var(--r-sm); border: 1px solid var(--c-border-light); color: var(--c-text); white-space: pre-wrap; word-break: break-all; margin: 0; }
.detail__meta { display: flex; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-xs); flex-wrap: wrap; }
.ver-list, .con-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 6px; }
.ver-list li { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--r-sm); padding: 6px 10px; }
.ver-head { display: flex; justify-content: space-between; align-items: center; }
.ver-tag { font-size: 11px; font-weight: 700; color: var(--c-brand); background: var(--c-brand-soft); padding: 1px 6px; border-radius: var(--r-sm); }
.ver-cov { font-size: var(--t-xs); color: var(--c-text-2); margin-top: 2px; }
.con-list li { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); padding: 4px 0; border-bottom: 1px dashed var(--c-border-light); }
.con-list li:last-child { border-bottom: none; }
.con-mod { color: var(--c-text); font-weight: 500; }

.empty { text-align: center; color: var(--c-text-3); padding: var(--s-xl) !important; }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.fld-label { font-size: 13px; color: var(--c-text); line-height: 18px; }
</style>
