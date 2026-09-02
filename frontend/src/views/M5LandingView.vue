<script setup lang="ts">
/* ============================================================
 * M5-08 落地页搭建 /m5-landing
 * 4 KPI（已发布页面/累计访问/留资数/转化率）
 * 左：落地页列表；右：可视化搭建预览 + A/B 测试卡
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useM5LandingStore } from '@/stores/m5Landing'
import { checkSensitive } from '@/composables/useSensitiveWords'

const store = useM5LandingStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '已发布页面', icon: 'check-square', value: String(store.publishedCount), tone: 'brand' as const },
  { label: '累计访问', icon: 'trend-up', value: fmtNum(store.totalVisits), tone: 'teal' as const },
  { label: '留资数', icon: 'customer', value: fmtNum(store.totalLeads), tone: 'orange' as const },
  { label: '转化率', icon: 'trend-up', value: `${store.conversionRate}%`, tone: store.conversionRate >= 5 ? ('success' as const) : ('warning' as const) },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'OFFLINE', label: '已下线' },
]
const templateOptions = [
  { value: 'NEWBIE', label: '新客体验' },
  { value: 'PROJECT', label: '项目种草' },
  { value: 'FESTIVAL', label: '节日促销' },
  { value: 'MEMBER', label: '会员日' },
  { value: 'BRAND', label: '品牌宣传' },
]

function fmtNum(n: number) { return n >= 10000 ? (n / 10000).toFixed(1) + '万' : n.toLocaleString('zh-CN') }
function convRate(v: number, l: number) { return v ? ((l / v) * 100).toFixed(1) : '0.0' }

// 选中
const selected = computed(() => store.selected)
function select(id: string) { store.select(id) }

// 搭建弹层
const showCreate = ref(false)
const form = ref({
  name: '', template: 'NEWBIE', headline: '', subtitle: '', project: '',
  fields: { name: true, phone: true, intent: false },
})
const formError = ref('')
function openCreate() {
  form.value = { name: '', template: 'NEWBIE', headline: '', subtitle: '', project: '', fields: { name: true, phone: true, intent: false } }
  formError.value = ''
  showCreate.value = true
}
function submitCreate() {
  formError.value = ''
  if (!form.value.name.trim()) { formError.value = '请输入页面名称'; return }
  const chk = checkSensitive(`${form.value.headline} ${form.value.subtitle} ${form.value.project}`)
  if (chk.hit) { formError.value = chk.message; return }
  const fields: string[] = []
  if (form.value.fields.name) fields.push('姓名')
  if (form.value.fields.phone) fields.push('手机')
  if (form.value.fields.intent) fields.push('意向项目')
  store.createPage({
    name: form.value.name.trim(),
    template: form.value.template as 'NEWBIE' | 'PROJECT' | 'FESTIVAL' | 'MEMBER' | 'BRAND',
    headline: form.value.headline.trim(),
    subtitle: form.value.subtitle.trim(),
    project: form.value.project.trim(),
    formFields: fields,
  })
  showCreate.value = false
}
</script>

<template>
  <div class="lp">
    <div class="lp__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard class="lp__toolbar" padding="none">
      <div class="lp__tools">
        <CSelect v-model="store.filterStatus" width="120px" :options="statusOptions" />
        <CButton variant="primary" size="sm" class="lp__tools-btn" v-perm.disable="'landing:edit'" @click="openCreate">
          <CIcon name="plus" :size="14" />搭建落地页
        </CButton>
      </div>
    </CCard>

    <div class="lp__body">
      <!-- 左：列表 -->
      <CCard class="lp__list" padding="none">
        <template #header>
          <div class="lp__card-head">
            <span>落地页列表</span>
          </div>
        </template>
        <div class="lp__rows">
          <button
            v-for="p in store.filteredPages" :key="p.id"
            class="lp__row" :class="{ 'is-active': selected?.id === p.id }"
            @click="select(p.id)"
          >
            <div class="lp__row-main">
              <div class="lp__row-top">
                <span class="lp__row-name">{{ p.name }}</span>
                <CStatusPill :status="store.STATUS_PILL[p.status]" dot>{{ store.STATUS_LABEL[p.status] }}</CStatusPill>
              </div>
              <div class="lp__row-sub">{{ store.TEMPLATE_LABEL[p.template] }} · {{ p.createdAt }}</div>
            </div>
            <div class="lp__row-stats">
              <div><span class="lp__stat-num">{{ fmtNum(p.visits) }}</span><span class="lp__stat-label">访问</span></div>
              <div><span class="lp__stat-num">{{ fmtNum(p.leads) }}</span><span class="lp__stat-label">留资</span></div>
            </div>
          </button>
          <div v-if="store.filteredPages.length === 0" class="lp__empty">
            <CIcon name="profile" :size="28" class="lp__empty-icon" />
            <span>暂无落地页</span>
          </div>
        </div>
      </CCard>

      <!-- 右：预览 + A/B -->
      <div class="lp__right">
        <CCard v-if="selected" class="lp__preview" padding="none">
          <template #header>
            <div class="lp__card-head">
              <span>可视化搭建预览</span>
              <div class="lp__preview-actions">
                <CButton v-if="selected.status !== 'PUBLISHED'" variant="secondary" size="sm" v-perm.disable="'landing:edit'" @click="store.publish(selected.id)">
                  <CIcon name="upload" :size="14" />发布
                </CButton>
                <CButton v-else variant="ghost" size="sm" v-perm.disable="'landing:edit'" @click="store.offline(selected.id)">下线</CButton>
              </div>
            </div>
          </template>
          <div class="lp__canvas">
            <!-- 模拟手机/页面预览 -->
            <div class="lp__phone">
              <div
                v-for="(b, i) in selected.blocks" :key="b.id"
                class="lp__block" :class="`lp__block--${b.type.toLowerCase()}`"
              >
                <div class="lp__block-bar">
                  <span class="lp__block-label"><CIcon :name="store.BLOCK_ICONS[b.type]" :size="12" />{{ store.BLOCK_LABEL[b.type] }}</span>
                  <span class="lp__block-arrows">
                    <button class="lp__arrow" :disabled="i === 0" @click="store.moveBlock(selected.id, b.id, -1)"><CIcon name="chevron-left" :size="12" /></button>
                    <button class="lp__arrow" :disabled="i === selected.blocks.length - 1" @click="store.moveBlock(selected.id, b.id, 1)"><CIcon name="chevron-right" :size="12" /></button>
                  </span>
                </div>
                <!-- 色块预览 -->
                <div v-if="b.type === 'HERO'" class="lp__mock-hero">
                  <span class="lp__mock-hero-title">{{ selected.headline || '页面主标题' }}</span>
                  <span class="lp__mock-hero-sub">{{ selected.subtitle || '页面副标题' }}</span>
                </div>
                <div v-else-if="b.type === 'TITLE'" class="lp__mock-title">{{ selected.headline || '标题区' }}</div>
                <div v-else-if="b.type === 'PROJECT'" class="lp__mock-project">
                  <div class="lp__mock-project-img"><CIcon name="package" :size="20" /></div>
                  <div class="lp__mock-project-info">
                    <div class="lp__mock-project-name">{{ selected.project || '主推项目' }}</div>
                    <div class="lp__mock-project-price">¥ 咨询获取底价</div>
                  </div>
                </div>
                <div v-else-if="b.type === 'FORM'" class="lp__mock-form">
                  <div v-for="f in selected.formFields" :key="f" class="lp__mock-input">{{ f }}</div>
                </div>
                <div v-else-if="b.type === 'BUTTON'" class="lp__mock-btn">立即预约</div>
              </div>
            </div>
          </div>
        </CCard>

        <!-- A/B 测试卡 -->
        <CCard v-if="selected" class="lp__ab" padding="lg">
          <template #header>
            <div class="lp__card-head">
              <span>A/B 测试配置</span>
              <CButton variant="text" size="sm" v-perm.disable="'landing:edit'" @click="store.toggleAb(selected.id)">
                {{ selected.abEnabled ? '关闭测试' : '开启 A/B 测试' }}
              </CButton>
            </div>
          </template>
          <div v-if="!selected.abEnabled" class="lp__ab-off">
            <CIcon name="settings" :size="20" class="lp__ab-icon" />
            <span>为该页配置 A/B 两个版本，流量 50/50 对比转化率</span>
          </div>
          <div v-else class="lp__ab-grid">
            <div v-for="(v, i) in selected.variants" :key="i" class="lp__ab-card">
              <div class="lp__ab-name">
                <span class="lp__ab-dot" :class="`lp__ab-dot--${i === 0 ? 'a' : 'b'}`" />
                {{ v.name }}
              </div>
              <div class="lp__ab-row"><span>访问</span><b>{{ fmtNum(v.visits) }}</b></div>
              <div class="lp__ab-row"><span>留资</span><b>{{ fmtNum(v.leads) }}</b></div>
              <div class="lp__ab-rate">转化率 {{ convRate(v.visits, v.leads) }}%</div>
            </div>
          </div>
        </CCard>
      </div>
    </div>

    <!-- 搭建弹层 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <CCard class="lp__modal" title="搭建落地页" padding="lg">
        <div class="lp__form">
          <label class="lp__form-label">页面名称</label>
          <CInput v-model="form.name" placeholder="如：新客88元体验页" />

          <label class="lp__form-label">选择模板</label>
          <CSelect v-model="form.template" width="100%" :options="templateOptions" />

          <label class="lp__form-label">主标题</label>
          <CInput v-model="form.headline" placeholder="页面主标题（自动校验违禁词）" />

          <label class="lp__form-label">副标题</label>
          <CInput v-model="form.subtitle" placeholder="页面副标题" />

          <label class="lp__form-label">主推项目</label>
          <CInput v-model="form.project" placeholder="如：热玛吉面部" />

          <label class="lp__form-label">表单字段</label>
          <div class="lp__checkboxes">
            <label class="lp__check"><input type="checkbox" v-model="form.fields.name" disabled /> 姓名</label>
            <label class="lp__check"><input type="checkbox" v-model="form.fields.phone" disabled /> 手机</label>
            <label class="lp__check"><input type="checkbox" v-model="form.fields.intent" /> 意向项目</label>
          </div>

          <div v-if="formError" class="lp__form-error">
            <CIcon name="alert" :size="14" />{{ formError }}
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showCreate = false">取消</CButton>
          <CButton variant="primary" @click="submitCreate">创建</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.lp { display: flex; flex-direction: column; gap: var(--s-lg); }
.lp__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .lp__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.lp__card-head { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: var(--s-sm); font-size: var(--t-md); font-weight: 700; flex-wrap: wrap; }
.lp__card-head-right { display: flex; align-items: center; gap: var(--s-sm); }

.lp__body { display: grid; grid-template-columns: 340px 1fr; gap: var(--s-lg); align-items: start; }
.lp__list { min-width: 0; }
.lp__right { display: flex; flex-direction: column; gap: var(--s-lg); min-width: 0; }

.lp__rows { max-height: 560px; overflow-y: auto; }
.lp__toolbar { flex-shrink: 0; }
.lp__tools { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); flex-wrap: nowrap; }
.lp__tools > * { flex-shrink: 0; }
.lp__tools-btn { margin-left: auto; white-space: nowrap; }
.lp__row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); width: 100%; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; text-align: left; font-family: inherit; }
.lp__row:hover { background: var(--c-brand-soft); }
.lp__row.is-active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.lp__row-main { flex: 1; min-width: 0; }
.lp__row-top { display: flex; align-items: center; gap: var(--s-xs); margin-bottom: 4px; }
.lp__row-name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 180px; }
.lp__row-sub { font-size: var(--t-xs); color: var(--c-text-3); }
.lp__row-stats { display: flex; gap: var(--s-md); flex-shrink: 0; }
.lp__row-stats > div { display: flex; flex-direction: column; align-items: center; }
.lp__stat-num { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.lp__stat-label { font-size: 10px; color: var(--c-text-3); }

.lp__empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl); color: var(--c-text-3); font-size: var(--t-sm); }
.lp__empty-icon { color: var(--c-text-4); }

/* preview */
.lp__canvas { padding: var(--s-lg); background: var(--c-bg-right); display: flex; justify-content: center; max-height: 520px; overflow-y: auto; }
.lp__phone { width: 100%; max-width: 360px; background: var(--c-surface); border-radius: var(--r-xl); border: 1px solid var(--c-border); overflow: hidden; box-shadow: var(--shadow-card); display: flex; flex-direction: column; gap: var(--s-xs); padding: var(--s-sm); }
.lp__block { border: 1px dashed var(--c-border); border-radius: var(--r-md); overflow: hidden; }
.lp__block-bar { display: flex; align-items: center; justify-content: space-between; padding: 4px var(--s-xs); background: var(--c-bg-right); }
.lp__block-label { display: inline-flex; align-items: center; gap: 4px; font-size: 10px; color: var(--c-text-3); }
.lp__block-arrows { display: inline-flex; gap: 2px; }
.lp__arrow { width: 20px; height: 20px; display: flex; align-items: center; justify-content: center; border: 1px solid var(--c-border); border-radius: var(--r-sm); background: var(--c-surface); color: var(--c-text-3); cursor: pointer; }
.lp__arrow:disabled { opacity: 0.35; cursor: not-allowed; }
.lp__arrow:not(:disabled):hover { border-color: var(--c-brand); color: var(--c-brand); }

.lp__mock-hero { height: 120px; background: linear-gradient(135deg, var(--c-brand-soft), var(--c-brand)); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; color: #fff; padding: var(--s-sm); text-align: center; }
.lp__mock-hero-title { font-size: var(--t-md); font-weight: 700; }
.lp__mock-hero-sub { font-size: var(--t-xs); opacity: 0.9; }
.lp__mock-title { padding: var(--s-md); text-align: center; font-size: var(--t-base); font-weight: 700; color: var(--c-text); }
.lp__mock-project { display: flex; gap: var(--s-sm); padding: var(--s-sm); }
.lp__mock-project-img { width: 56px; height: 56px; border-radius: var(--r-sm); background: var(--c-brand-soft); color: var(--c-brand); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.lp__mock-project-info { flex: 1; display: flex; flex-direction: column; justify-content: center; gap: 4px; }
.lp__mock-project-name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.lp__mock-project-price { font-size: var(--t-xs); color: var(--c-danger-fg); font-weight: 600; }
.lp__mock-form { display: flex; flex-direction: column; gap: 6px; padding: var(--s-sm); }
.lp__mock-input { height: 32px; border: 1px solid var(--c-border); border-radius: var(--r-sm); background: var(--c-bg-right); display: flex; align-items: center; padding: 0 var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); }
.lp__mock-btn { margin: var(--s-sm); height: 38px; border-radius: var(--r-capsule); background: var(--c-brand); color: #fff; display: flex; align-items: center; justify-content: center; font-size: var(--t-sm); font-weight: 600; }

/* A/B */
.lp__ab-off { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md) 0; color: var(--c-text-3); font-size: var(--t-sm); }
.lp__ab-icon { color: var(--c-text-4); }
.lp__ab-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.lp__ab-card { padding: var(--s-md); border-radius: var(--r-md); background: var(--c-bg-right); display: flex; flex-direction: column; gap: var(--s-xs); }
.lp__ab-name { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-xs); }
.lp__ab-dot { width: 8px; height: 8px; border-radius: 50%; }
.lp__ab-dot--a { background: var(--c-brand); }
.lp__ab-dot--b { background: var(--c-teal-dark); }
.lp__ab-row { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.lp__ab-row b { color: var(--c-text); font-weight: 600; font-variant-numeric: tabular-nums; }
.lp__ab-rate { margin-top: var(--s-xs); font-size: var(--t-lg); font-weight: 700; color: var(--c-brand); text-align: center; }

/* modal */
.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.lp__modal { width: 480px; max-width: 100%; box-shadow: var(--shadow-pop); }
.lp__form { display: flex; flex-direction: column; gap: var(--s-sm); }
.lp__form-label { font-size: var(--t-xs); color: var(--c-text-3); }
.lp__checkboxes { display: flex; gap: var(--s-lg); }
.lp__check { display: inline-flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; }
.lp__form-error { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-danger-fg); background: var(--c-danger-bg); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); }
</style>
