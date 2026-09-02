<script setup lang="ts">
/* ============================================================
 * AI 客户分群 /m3-segment（M3-14）
 * 4 KPI + 分群卡片网格 + 详情（群内客户表 + 操作）+ 新建规则分群弹层。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import { useSegmentStore, type Segment, type SegmentType } from '@/stores/segment'

const store = useSegmentStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<Segment | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.segments[0] ?? null
})

const kpis = computed(() => [
  { label: '分群总数', icon: 'customer', value: String(store.totalSegments), tone: 'brand' as const },
  { label: '覆盖客户', icon: 'customer', value: String(store.totalCovered), tone: 'teal' as const },
  { label: '最大群客户数', icon: 'customer', value: store.largest ? String(store.largest.customerCount) : '0', tone: 'orange' as const },
  { label: 'AI 建议数', icon: 'dashboard', value: String(store.aiCount), tone: 'success' as const },
])

const typeOptions = [
  { value: 'ALL', label: '全部类型' },
  { value: 'HIGH_POTENTIAL', label: '高潜客户' },
  { value: 'DORMANT', label: '沉睡客户' },
  { value: 'PRICE_SENSITIVE', label: '价格敏感' },
  { value: 'HIGH_VALUE', label: '高价值' },
  { value: 'CHURN_RISK', label: '流失风险' },
  { value: 'NEW', label: '新客' },
]

function fmtDate(iso: string) {
  const d = new Date(iso)
  const diff = Date.now() - d.getTime()
  const h = Math.floor(diff / 3600_000)
  if (h < 1) return `${Math.floor(diff / 60000)} 分钟前`
  if (h < 24) return `${h} 小时前`
  return `${Math.floor(h / 24)} 天前`
}

// 新建规则分群
const showForm = ref(false)
// 可配置规则条件：每条可开关 + 自定义阈值/取值，提交时把开启项拼成条件文案
interface RuleState {
  key: string
  on: boolean
  label: string
  days: string
  times: string
  spendMin: string
  spendMax: string
  level: string
  tag: string
}
function defaultRules(): RuleState[] {
  return [
    { key: 'dormant', on: false, label: '天未到店（沉睡）', days: '60', times: '', spendMin: '', spendMax: '', level: '', tag: '' },
    { key: 'visit', on: false, label: '天内到店 ≥', days: '30', times: '2', spendMin: '', spendMax: '', level: '', tag: '' },
    { key: 'spend', on: false, label: '累计消费区间（元）', days: '', times: '', spendMin: '1000', spendMax: '5000', level: '', tag: '' },
    { key: 'level', on: false, label: '会员等级 ≥', days: '', times: '', spendMin: '', spendMax: '', level: '银卡', tag: '' },
    { key: 'tag', on: false, label: '带客户标签', days: '', times: '', spendMin: '', spendMax: '', level: '', tag: '高意向' },
  ]
}
const levelOptions = [
  { value: '银卡', label: '银卡' },
  { value: '金卡', label: '金卡' },
  { value: '钻石', label: '钻石' },
]
const form = ref<{ name: string; type: SegmentType; rules: RuleState[] }>({
  name: '',
  type: 'HIGH_POTENTIAL',
  rules: defaultRules(),
})
const activeRules = computed(() => form.value.rules.filter((r) => r.on))
const canSubmit = computed(() => form.value.name.trim() && activeRules.value.length > 0)

function openForm() {
  form.value = { name: '', type: 'HIGH_POTENTIAL', rules: defaultRules() }
  showForm.value = true
}
function buildConditions(rules: RuleState[]): string[] {
  const out: string[] = []
  for (const r of rules) {
    if (!r.on) continue
    if (r.key === 'dormant') out.push(`${Number(r.days) || 60} 天未到店`)
    else if (r.key === 'visit') out.push(`近 ${Number(r.days) || 30} 天到店 ≥ ${Number(r.times) || 1} 次`)
    else if (r.key === 'spend') out.push(`累计消费 ${Number(r.spendMin) || 0}-${Number(r.spendMax) || 0} 元`)
    else if (r.key === 'level') out.push(`等级 ≥ ${r.level}`)
    else if (r.key === 'tag') out.push(`带「${r.tag?.trim() || '未命名'}」标签`)
  }
  return out
}
function submitForm() {
  if (!canSubmit.value) return
  const conds = buildConditions(form.value.rules)
  const seg = store.createSegment({ name: form.value.name, type: form.value.type, conditions: conds })
  if (seg) {
    showForm.value = false
    selectedId.value = seg.id
  }
}
</script>

<template>
  <div class="sg">
    <div class="sg__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard padding="none">
      <div class="filter-bar">
        <div class="chips">
          <button
            v-for="t in typeOptions" :key="t.value"
            class="chip" :class="{ 'chip--active': store.filterType === t.value }"
            @click="store.filterType = t.value as any"
          >{{ t.label }}</button>
        </div>
        <CButton variant="primary" size="sm" v-perm.disable="'segment:edit'" @click="openForm">
          <CIcon name="plus" :size="14" />新建规则分群
        </CButton>
      </div>
    </CCard>

    <div class="sg__body">
      <!-- 卡片网格 -->
      <div class="grid">
        <button
          v-for="s in store.filtered" :key="s.id"
          class="seg-card"
          :class="{ 'seg-card--active': selected?.id === s.id }"
          @click="selectedId = s.id"
        >
          <div class="seg-card__head">
            <div class="seg-card__icon" :style="{ background: store.TYPE_COLOR[s.type].bg, color: store.TYPE_COLOR[s.type].fg }">
              <CIcon :name="(store.TYPE_ICON[s.type]) as any" :size="18" />
            </div>
            <CStatusPill v-if="s.aiStatus === 'AI'" status="primary">AI 建议</CStatusPill>
            <CStatusPill v-else status="draft">规则</CStatusPill>
          </div>
          <div class="seg-card__name">{{ s.name }}</div>
          <div class="seg-card__type" :style="{ color: store.TYPE_COLOR[s.type].fg }">{{ store.TYPE_LABEL[s.type] }}</div>
          <div class="seg-card__num">
            <span class="num">{{ s.customerCount }}</span>
            <span class="unit">人</span>
            <span class="pct">占 {{ s.sharePct }}%</span>
          </div>
          <div class="seg-card__rule">{{ s.ruleSummary }}</div>
          <div v-if="s.aiSuggestion" class="seg-card__ai">
            <CIcon name="trend-up" :size="12" />
            <span>{{ s.aiSuggestion }}</span>
          </div>
          <div class="seg-card__foot">
            <span>更新 {{ fmtDate(s.updatedAt) }}</span>
          </div>
        </button>
      </div>

      <!-- 详情 -->
      <CCard v-if="selected" class="sg__detail" padding="lg">
        <template #header>
          <div class="det-head">
            <div class="det-head__icon" :style="{ background: store.TYPE_COLOR[selected.type].bg, color: store.TYPE_COLOR[selected.type].fg }">
              <CIcon :name="(store.TYPE_ICON[selected.type]) as any" :size="20" />
            </div>
            <div>
              <h3 class="card-title">{{ selected.name }}</h3>
              <div class="det-head__sub">
                <span :style="{ color: store.TYPE_COLOR[selected.type].fg }">{{ store.TYPE_LABEL[selected.type] }}</span>
                <span>·</span>
                <CStatusPill v-if="selected.aiStatus === 'AI'" status="primary">AI 建议</CStatusPill>
                <CStatusPill v-else status="draft">规则</CStatusPill>
                <span>·</span>
                <span>更新 {{ fmtDate(selected.updatedAt) }}</span>
              </div>
            </div>
          </div>
        </template>

        <div class="det-kpis">
          <div class="dk">
            <div class="dk__val">{{ selected.customerCount }}</div>
            <div class="dk__lbl">客户数</div>
          </div>
          <div class="dk">
            <div class="dk__val">{{ selected.sharePct }}%</div>
            <div class="dk__lbl">占比</div>
          </div>
          <div class="dk">
            <div class="dk__val">{{ selected.conditions.length }}</div>
            <div class="dk__lbl">规则条件</div>
          </div>
        </div>

        <div class="det-conds">
          <div class="sec-title">命中条件</div>
          <div class="conds">
            <span v-for="c in selected.conditions" :key="c" class="cond">
              <CIcon name="check" :size="12" />{{ c }}
            </span>
          </div>
        </div>

        <div v-if="selected.aiSuggestion" class="det-ai">
          <div class="sec-title"><CIcon name="trend-up" :size="14" /> AI 运营建议</div>
          <p>{{ selected.aiSuggestion }}</p>
        </div>

        <div class="det-members">
          <div class="sec-title">群内客户（{{ selected.members.length }}）</div>
          <div class="members-table">
            <div class="mt-row mt-row--head">
              <span>客户</span><span>等级</span><span>最后到店</span><span>命中点</span>
            </div>
            <div v-for="m in selected.members" :key="m.id" class="mt-row">
              <span class="mt-name">{{ m.name }}</span>
              <span class="mt-lv">{{ m.level }}</span>
              <span>{{ fmtDate(m.lastVisit) }}</span>
              <span class="mt-matched">
                <i v-for="mm in m.matched" :key="mm">{{ mm }}</i>
              </span>
            </div>
          </div>
        </div>

        <div class="det-ops">
          <CButton variant="ghost" v-perm.disable="'segment:edit'" @click="store.refresh(selected.id)">
            <CIcon name="scan" :size="16" />刷新计算
          </CButton>
          <CButton variant="ghost" v-perm.disable="'segment:edit'" @click="store.exportMembers(selected.id)">
            <CIcon name="export" :size="16" />导出名单
          </CButton>
          <CButton variant="primary" v-perm.disable="'segment:edit'" @click="store.createFollowTask(selected.id)">
            <CIcon name="plus" :size="16" />一键建跟进任务
          </CButton>
        </div>
      </CCard>
    </div>

    <!-- 新建规则分群弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建规则分群" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">分群名称 <span class="req">*</span></label>
            <CInput v-model="form.name" placeholder="如：金卡沉睡客户" />
          </div>
          <div class="form__row">
            <label class="form__label">分群类型</label>
            <CSelect v-model="form.type" :options="typeOptions.filter(t => t.value !== 'ALL')" width="100%" />
          </div>
          <div class="form__row">
            <label class="form__label">规则条件（至少启用 1 项，阈值可自定义）</label>
            <div class="rules">
              <label v-for="r in form.rules" :key="r.key" class="rule" :class="{ 'rule--on': r.on }">
                <input type="checkbox" v-model="r.on" />
                <template v-if="r.key === 'dormant'">
                  <CInput type="number" v-model="r.days" class="rule__num" :disabled="!r.on" />
                  <span class="rule__txt">{{ r.label }}</span>
                </template>
                <template v-else-if="r.key === 'visit'">
                  <span class="rule__txt">近</span>
                  <CInput type="number" v-model="r.days" class="rule__num" :disabled="!r.on" />
                  <span class="rule__txt">{{ r.label }}</span>
                  <CInput type="number" v-model="r.times" class="rule__num" :disabled="!r.on" />
                  <span class="rule__txt">次</span>
                </template>
                <template v-else-if="r.key === 'spend'">
                  <span class="rule__txt">{{ r.label }}</span>
                  <CInput type="number" v-model="r.spendMin" class="rule__num" :disabled="!r.on" />
                  <span class="rule__txt">-</span>
                  <CInput type="number" v-model="r.spendMax" class="rule__num" :disabled="!r.on" />
                </template>
                <template v-else-if="r.key === 'level'">
                  <span class="rule__txt">{{ r.label }}</span>
                  <CSelect v-model="r.level" :options="levelOptions" width="110px" />
                </template>
                <template v-else-if="r.key === 'tag'">
                  <span class="rule__txt">{{ r.label }}</span>
                  <CInput v-model="r.tag" class="rule__tag" placeholder="如：高意向" :disabled="!r.on" />
                </template>
              </label>
            </div>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">创建</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.sg { display: flex; flex-direction: column; gap: var(--s-lg); }
.sg__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .sg__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.card-title { font-size: var(--t-md); font-weight: 700; margin: 0; }

.filter-bar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); flex-wrap: nowrap; }
.filter-bar > .cbtn { flex-shrink: 0; }
.chips { display: flex; gap: var(--s-xs); flex-wrap: nowrap; overflow-x: auto; }
.chip { padding: 6px 12px; font-size: var(--t-xs); color: var(--c-text-2); background: var(--c-disabled-bg); border: none; border-radius: var(--r-pill); cursor: pointer; }
.chip:hover { color: var(--c-text); }
.chip--active { background: var(--c-brand); color: #fff; }

.sg__body { display: grid; grid-template-columns: 1fr; gap: var(--s-lg); align-items: start; }

.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: var(--s-md); }
.seg-card {
  text-align: left; padding: var(--s-lg); background: var(--c-surface);
  border: 1px solid var(--c-border-light); border-radius: var(--r-xl); cursor: pointer;
  display: flex; flex-direction: column; gap: var(--s-xs); transition: border-color 0.2s, box-shadow 0.2s;
}
.seg-card:hover { border-color: var(--c-brand); }
.seg-card--active { border-color: var(--c-brand); box-shadow: 0 0 0 2px var(--c-brand-soft); }
.seg-card__head { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.seg-card__icon { width: 36px; height: 36px; border-radius: var(--r-md); display: flex; align-items: center; justify-content: center; }
.seg-card__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.seg-card__type { font-size: var(--t-xs); font-weight: 600; }
.seg-card__num { display: flex; align-items: baseline; gap: 4px; margin: var(--s-xs) 0; }
.seg-card__num .num { font-size: 28px; font-weight: 800; color: var(--c-text); line-height: 1; font-variant-numeric: tabular-nums; }
.seg-card__num .unit { font-size: var(--t-xs); color: var(--c-text-3); }
.seg-card__num .pct { font-size: var(--t-xs); color: var(--c-text-3); margin-left: auto; }
.seg-card__rule { font-size: var(--t-xs); color: var(--c-text-3); line-height: var(--lh-md); }
.seg-card__ai {
  display: flex; gap: 6px; padding: var(--s-sm); background: var(--c-brand-soft);
  border-radius: var(--r-md); font-size: var(--t-xs); color: var(--c-brand); line-height: var(--lh-md);
  margin-top: var(--s-xs);
}
.seg-card__foot { font-size: 10px; color: var(--c-text-4); margin-top: auto; padding-top: var(--s-xs); }

/* 详情 */
.det-head { display: flex; gap: var(--s-md); align-items: center; }
.det-head__icon { width: 44px; height: 44px; border-radius: var(--r-md); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.det-head__sub { display: flex; gap: 6px; align-items: center; font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; flex-wrap: wrap; }

.det-kpis { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); padding: var(--s-md) 0; border-bottom: 1px solid var(--c-border-light); margin-bottom: var(--s-md); }
.dk { text-align: center; }
.dk__val { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.dk__lbl { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); display: flex; align-items: center; gap: 6px; }
.det-conds, .det-ai, .det-members { margin-bottom: var(--s-md); }
.conds { display: flex; flex-wrap: wrap; gap: var(--s-xs); }
.cond { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); padding: 4px 10px; background: var(--c-success-bg); color: var(--c-success-fg); border-radius: var(--r-sm); }

.det-ai { background: var(--c-brand-soft); border-radius: var(--r-md); padding: var(--s-md); }
.det-ai .sec-title { color: var(--c-brand); margin-bottom: 4px; }
.det-ai p { margin: 0; font-size: var(--t-sm); color: var(--c-text); line-height: var(--lh-lg); }

.members-table { border: 1px solid var(--c-border-light); border-radius: var(--r-md); overflow: hidden; }
.mt-row { display: grid; grid-template-columns: 1fr 80px 100px 1.4fr; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); font-size: var(--t-sm); color: var(--c-text-2); align-items: center; border-bottom: 1px solid var(--c-border-light); }
.mt-row:last-child { border-bottom: none; }
.mt-row--head { background: var(--c-disabled-bg); font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; }
.mt-name { font-weight: 600; color: var(--c-text); }
.mt-lv { color: var(--c-brand); }
.mt-matched { display: flex; flex-wrap: wrap; gap: 4px; }
.mt-matched i { font-style: normal; font-size: 10px; padding: 1px 6px; background: var(--c-disabled-bg); border-radius: var(--r-sm); color: var(--c-text-2); }

.det-ops { display: flex; justify-content: flex-end; gap: var(--s-sm); padding-top: var(--s-md); border-top: 1px solid var(--c-border-light); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.rules { display: flex; flex-direction: column; gap: var(--s-xs); }
.rule { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-md); background: var(--c-surface); opacity: .55; transition: all .15s; }
.rule--on { opacity: 1; border-color: var(--c-brand); background: var(--c-brand-soft); }
.rule input[type="checkbox"] { accent-color: var(--c-brand); }
.rule__txt { color: var(--c-text-2); }
.rule :deep(.cinput) { width: 64px; }
.rule__tag { width: 120px; }
.rule :deep(.cinput.is-disabled) { opacity: .5; }

@media (max-width: 1024px) {
  .sg__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .mt-row { grid-template-columns: 1fr 60px 80px 1fr; font-size: var(--t-xs); }
}
</style>
