<script setup lang="ts">
/* ============================================================
 * M5-13 素材库 /m5-assets
 * 4 KPI（素材总数/图片数/视频数/已授权门店）
 * 主体：素材网格 + 标签/类型筛选；选中详情 + 分发到店
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CTextarea from '@/components/CTextarea.vue'
import { useM5AssetsStore } from '@/stores/m5Assets'
import { checkSensitive } from '@/composables/useSensitiveWords'

const store = useM5AssetsStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '素材总数', icon: 'marketing', value: String(store.totalCount), tone: 'brand' as const },
  { label: '图片数', icon: 'marketing', value: String(store.imageCount), tone: 'teal' as const },
  { label: '视频数', icon: 'marketing', value: String(store.videoCount), tone: 'orange' as const },
  { label: '已授权门店', icon: 'store', value: String(store.authorizedStores), tone: 'success' as const },
])

const selected = computed(() => store.selected)
function select(id: string) { store.select(id) }

const accentClass: Record<string, string> = {
  brand: 'as__thumb--brand', teal: 'as__thumb--teal', orange: 'as__thumb--orange',
  purple: 'as__thumb--purple', blue: 'as__thumb--blue', gold: 'as__thumb--gold',
}

// 上传弹层
const showUpload = ref(false)
const form = ref({
  name: '', type: 'IMAGE', tags: '', scope: 'ALL',
  storeNames: [] as string[], expireAt: '', content: '',
})
const formError = ref('')
function openUpload() {
  form.value = { name: '', type: 'IMAGE', tags: '', scope: 'ALL', storeNames: [], expireAt: '', content: '' }
  formError.value = ''
  showUpload.value = true
}
const uploadTypeOptions = [
  { value: 'IMAGE', label: '图片（海报）' },
  { value: 'VIDEO', label: '视频' },
  { value: 'COPY', label: '文案' },
  { value: 'LOGO', label: 'Logo' },
]
function submitUpload() {
  formError.value = ''
  if (!form.value.name.trim()) { formError.value = '请输入素材名称'; return }
  if (!form.value.expireAt) { formError.value = '请选择有效期'; return }
  if (form.value.type === 'COPY') {
    const chk = checkSensitive(form.value.content)
    if (chk.hit) { formError.value = chk.message; return }
  }
  const tags = form.value.tags.split(/[,，]/).map((t) => t.trim()).filter(Boolean)
  store.upload({
    name: form.value.name.trim(),
    type: form.value.type as 'IMAGE' | 'VIDEO' | 'COPY' | 'LOGO',
    tags,
    scope: form.value.scope as 'ALL' | 'SPECIFIED',
    storeNames: form.value.storeNames,
    expireAt: form.value.expireAt,
    content: form.value.content.trim() || undefined,
  })
  showUpload.value = false
}

// 标签管理
const newTag = ref('')
function addTag() {
  if (selected.value && newTag.value.trim()) {
    store.addTag(selected.value.id, newTag.value.trim())
    newTag.value = ''
  }
}

// 分发到店弹层
const showDistribute = ref(false)
const distStores = ref<string[]>([])
function openDistribute() {
  distStores.value = []
  showDistribute.value = true
}
function toggleStore(name: string) {
  const i = distStores.value.indexOf(name)
  if (i >= 0) distStores.value.splice(i, 1)
  else distStores.value.push(name)
}
function confirmDistribute() {
  if (selected.value && distStores.value.length) {
    store.distribute(selected.value.id, distStores.value)
  }
  showDistribute.value = false
}
</script>

<template>
  <div class="as">
    <div class="as__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- 筛选栏 -->
    <CCard class="as__filters" padding="sm">
      <div class="as__filter-bar">
        <CSelect v-model="store.filterType" width="140px" :options="store.typeOptions" />
        <CSelect v-model="store.filterTag" width="140px" :options="store.tagOptions" />
        <span class="as__count">共 {{ store.filtered.length }} 个素材</span>
        <CButton variant="primary" size="sm" class="as__filter-btn" v-perm.disable="'asset:upload'" @click="openUpload">
          <CIcon name="upload" :size="14" />上传素材
        </CButton>
      </div>
    </CCard>

    <div class="as__body">
      <!-- 素材网格 -->
      <div class="as__grid">
        <button
          v-for="a in store.filtered" :key="a.id"
          class="as__card" :class="{ 'is-active': selected?.id === a.id }"
          @click="select(a.id)"
        >
          <div class="as__thumb" :class="accentClass[a.accent]">
            <CIcon :name="store.TYPE_ICON[a.type]" :size="28" />
            <span class="as__type-badge">{{ store.TYPE_LABEL[a.type] }}</span>
          </div>
          <div class="as__info">
            <div class="as__name">{{ a.name }}</div>
            <div class="as__tags">
              <span v-for="t in a.tags.slice(0, 3)" :key="t" class="as__tag">{{ t }}</span>
            </div>
            <div class="as__meta">
              <span>{{ store.SCOPE_LABEL[a.scope] }}</span>
              <span class="as__ref" v-if="a.refCount > 0">被引 {{ a.refCount }}</span>
            </div>
          </div>
        </button>
        <div v-if="store.filtered.length === 0" class="as__empty">
          <CIcon name="package" :size="32" class="as__empty-icon" />
          <span>暂无符合条件的素材</span>
        </div>
      </div>

      <!-- 详情面板 -->
      <CCard v-if="selected" class="as__detail" padding="lg">
        <template #header>
          <span>素材详情</span>
        </template>
        <div class="as__detail-body">
          <!-- 预览 -->
          <div class="as__preview" :class="accentClass[selected.accent]">
            <CIcon :name="store.TYPE_ICON[selected.type]" :size="48" />
            <p v-if="selected.type === 'COPY' && selected.content" class="as__preview-text">{{ selected.content }}</p>
          </div>

          <div class="as__detail-name">{{ selected.name }}</div>
          <div class="as__detail-row">
            <span class="as__detail-label">类型</span>
            <CStatusPill status="info">{{ store.TYPE_LABEL[selected.type] }}</CStatusPill>
          </div>
          <div class="as__detail-row">
            <span class="as__detail-label">有效期至</span>
            <span>{{ selected.expireAt }}</span>
          </div>
          <div class="as__detail-row">
            <span class="as__detail-label">授权范围</span>
            <span>{{ store.SCOPE_LABEL[selected.scope] }}</span>
          </div>
          <div class="as__detail-row">
            <span class="as__detail-label">被引用</span>
            <span>{{ selected.refCount }} 次（活动/直播/落地页）</span>
          </div>

          <!-- 标签管理 -->
          <div class="as__tag-mgr">
            <div class="as__detail-label">标签</div>
            <div class="as__tag-list">
              <span v-for="t in selected.tags" :key="t" class="as__tag as__tag--removable">
                {{ t }}
                <button class="as__tag-x" @click="store.removeTag(selected.id, t)"><CIcon name="close" :size="10" /></button>
              </span>
            </div>
            <div class="as__tag-add">
              <CInput v-model="newTag" placeholder="新增标签" />
              <CButton variant="secondary" size="sm" @click="addTag">添加</CButton>
            </div>
          </div>

          <!-- 授权门店 -->
          <div class="as__stores">
            <div class="as__detail-label">授权门店</div>
            <div class="as__store-list">
              <span v-for="s in selected.storeNames" :key="s" class="as__store-item">
                <CIcon name="store" :size="12" />{{ s }}
              </span>
            </div>
          </div>

          <CButton variant="primary" block v-perm.disable="'asset:upload'" @click="openDistribute">
            <CIcon name="plus" :size="14" />分发到店
          </CButton>
        </div>
      </CCard>
    </div>

    <!-- 上传弹层 -->
    <div v-if="showUpload" class="modal-mask" @click.self="showUpload = false">
      <CCard class="as__modal" title="上传素材" padding="lg">
        <div class="as__form">
          <label class="as__form-label">素材名称</label>
          <CInput v-model="form.name" placeholder="如：暑期水光主海报" />

          <label class="as__form-label">素材类型</label>
          <CSelect v-model="form.type" width="100%" :options="uploadTypeOptions" />

          <label class="as__form-label">标签（逗号分隔）</label>
          <CInput v-model="form.tags" placeholder="如：暑期,水光,促销" />

          <template v-if="form.type === 'COPY'">
            <label class="as__form-label">文案内容</label>
            <CTextarea v-model="form.content" :rows="3" placeholder="文案内容（自动校验违禁词）" />
          </template>

          <label class="as__form-label">授权范围</label>
          <div class="as__radio-row">
            <label class="as__radio"><input type="radio" v-model="form.scope" value="ALL" /> 全部门店</label>
            <label class="as__radio"><input type="radio" v-model="form.scope" value="SPECIFIED" /> 指定门店</label>
          </div>
          <div v-if="form.scope === 'SPECIFIED'" class="as__store-checks">
            <label v-for="s in store.storeOptions" :key="s.value" class="as__check">
              <input type="checkbox" :value="s.value" v-model="form.storeNames" /> {{ s.label }}
            </label>
          </div>

          <label class="as__form-label">有效期至</label>
          <input v-model="form.expireAt" type="date" class="as__date" />

          <div v-if="formError" class="as__form-error">
            <CIcon name="alert" :size="14" />{{ formError }}
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showUpload = false">取消</CButton>
          <CButton variant="primary" @click="submitUpload">上传</CButton>
        </template>
      </CCard>
    </div>

    <!-- 分发到店弹层 -->
    <div v-if="showDistribute" class="modal-mask" @click.self="showDistribute = false">
      <CCard class="as__modal as__modal--sm" title="分发到店" padding="lg">
        <div class="as__form">
          <p class="as__dist-hint">选择要分发的门店（已授权的门店不会重复添加）：</p>
          <div class="as__store-checks">
            <label v-for="s in store.storeOptions" :key="s.value" class="as__check">
              <input type="checkbox" :checked="distStores.includes(s.value)" @change="toggleStore(s.value)" />
              {{ s.label }}
            </label>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showDistribute = false">取消</CButton>
          <CButton variant="primary" :disabled="distStores.length === 0" @click="confirmDistribute">确认分发（{{ distStores.length }}）</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.as { display: flex; flex-direction: column; gap: var(--s-lg); }
.as__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .as__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.as__filters :deep(.card__body) { padding: var(--s-sm) var(--s-md); }
.as__filter-bar { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.as__count { font-size: var(--t-xs); color: var(--c-text-3); margin-left: auto; }
.as__filter-btn { flex-shrink: 0; }

.as__body { display: grid; grid-template-columns: 1fr 340px; gap: var(--s-lg); align-items: start; }

/* grid */
.as__grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: var(--s-md); align-content: start; }
.as__card { background: var(--c-surface); border: 1px solid var(--c-border); border-radius: var(--r-xl); overflow: hidden; cursor: pointer; text-align: left; font-family: inherit; padding: 0; transition: border-color .15s, box-shadow .15s; }
.as__card:hover { border-color: var(--c-brand-border); }
.as__card.is-active { border-color: var(--c-brand); box-shadow: 0 0 0 2px var(--c-brand-soft); }
.as__thumb { height: 120px; display: flex; align-items: center; justify-content: center; color: #fff; position: relative; }
.as__thumb--brand { background: linear-gradient(135deg, var(--c-brand-soft), var(--c-brand)); }
.as__thumb--teal { background: linear-gradient(135deg, rgba(0,180,180,.2), var(--c-teal-dark)); }
.as__thumb--orange { background: linear-gradient(135deg, rgba(255,165,0,.2), var(--c-orange-dark)); }
.as__thumb--purple { background: linear-gradient(135deg, rgba(128,80,200,.2), #7c5cc8); }
.as__thumb--blue { background: linear-gradient(135deg, rgba(77,90,217,.2), #4d5ad9); }
.as__thumb--gold { background: linear-gradient(135deg, rgba(200,160,40,.2), #b89020); }
.as__type-badge { position: absolute; top: var(--s-xs); left: var(--s-xs); font-size: 10px; background: rgba(0,0,0,.35); color: #fff; padding: 2px 8px; border-radius: var(--r-pill); }
.as__info { padding: var(--s-sm); display: flex; flex-direction: column; gap: 6px; }
.as__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.as__tags { display: flex; flex-wrap: wrap; gap: 4px; }
.as__tag { font-size: 10px; color: var(--c-brand); background: var(--c-brand-soft); padding: 1px 6px; border-radius: var(--r-pill); white-space: nowrap; }
.as__meta { display: flex; justify-content: space-between; font-size: 10px; color: var(--c-text-3); }
.as__ref { color: var(--c-orange-dark); font-weight: 600; }

.as__empty { grid-column: 1 / -1; display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl); color: var(--c-text-3); font-size: var(--t-sm); }
.as__empty-icon { color: var(--c-text-4); }

/* detail */
.as__detail { min-width: 0; position: sticky; top: var(--s-lg); }
.as__detail-body { display: flex; flex-direction: column; gap: var(--s-sm); }
.as__preview { height: 160px; border-radius: var(--r-md); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: var(--s-sm); color: #fff; margin-bottom: var(--s-xs); padding: var(--s-md); text-align: center; }
.as__preview-text { font-size: var(--t-sm); line-height: 1.6; max-width: 100%; }
.as__detail-name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.as__detail-row { display: flex; justify-content: space-between; align-items: center; font-size: var(--t-sm); color: var(--c-text-2); }
.as__detail-label { font-size: var(--t-xs); color: var(--c-text-3); }
.as__tag-mgr { display: flex; flex-direction: column; gap: var(--s-xs); margin-top: var(--s-xs); }
.as__tag-list { display: flex; flex-wrap: wrap; gap: 4px; }
.as__tag--removable { display: inline-flex; align-items: center; gap: 2px; }
.as__tag-x { display: inline-flex; border: none; background: none; color: inherit; cursor: pointer; padding: 0; }
.as__tag-x:hover { color: var(--c-danger-fg); }
.as__tag-add { display: flex; gap: var(--s-xs); }
.as__stores { display: flex; flex-direction: column; gap: var(--s-xs); }
.as__store-list { display: flex; flex-direction: column; gap: 4px; max-height: 120px; overflow-y: auto; }
.as__store-item { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-2); }

/* modal */
.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.as__modal { width: 480px; max-width: 100%; box-shadow: var(--shadow-pop); }
.as__modal--sm { width: 400px; }
.as__form { display: flex; flex-direction: column; gap: var(--s-sm); }
.as__form-label { font-size: var(--t-xs); color: var(--c-text-3); }
.as__radio-row { display: flex; gap: var(--s-lg); }
.as__radio { display: inline-flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; }
.as__store-checks { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-xs); padding: var(--s-sm); background: var(--c-bg-right); border-radius: var(--r-md); }
.as__check { display: inline-flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; }
.as__date { width: 100%; padding: 10px; border: 1px solid #D1D1D9; border-radius: var(--r-sm); font-size: var(--t-sm); font-family: inherit; color: var(--c-text); background: var(--c-surface); }
.as__date:focus { outline: none; border-color: var(--c-brand); box-shadow: 0 0 0 2px rgba(255,107,158,.12); }
.as__form-error { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-danger-fg); background: var(--c-danger-bg); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); }
.as__dist-hint { font-size: var(--t-sm); color: var(--c-text-2); margin: 0; }
</style>
