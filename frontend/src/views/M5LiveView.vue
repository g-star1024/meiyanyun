<script setup lang="ts">
/* ============================================================
 * M5-05 直播/短视频 /m5-live
 * 4 KPI（进行中直播/本月场次/累计观看/挂链成交）
 * 上：直播场次列表；下左：选中直播漏斗+成交对比；下右：短视频库
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import CTextarea from '@/components/CTextarea.vue'
import CBarChart from '@/components/CBarChart.vue'
import { useM5LiveStore } from '@/stores/m5Live'
import { useM1MarketingStore } from '@/stores/m1Marketing'
import { checkSensitive } from '@/composables/useSensitiveWords'

const store = useM5LiveStore()
const m1 = useM1MarketingStore()
onMounted(() => { store.seed(); m1.seed() })

const selectedId = ref<string | null>(null)
const selected = computed(() => {
  if (selectedId.value) return store.get(selectedId.value)
  return store.sessions[0] ?? null
})
function select(id: string) { selectedId.value = id }

const kpis = computed(() => [
  { label: '进行中直播', icon: 'marketing', value: String(store.liveCount), tone: 'danger' as const },
  { label: '本月场次', icon: 'marketing', value: String(store.monthSessions), tone: 'brand' as const },
  { label: '累计观看', icon: 'marketing', value: fmtNum(store.totalViews), tone: 'teal' as const },
  { label: '挂链成交', icon: 'marketing', value: money(store.totalDealAmount), tone: 'orange' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'NOT_STARTED', label: '未开始' },
  { value: 'LIVE', label: '直播中' },
  { value: 'ENDED', label: '已结束' },
]
const platformOptions = [
  { value: 'DOUYIN', label: '抖音' },
  { value: 'WECHAT_CHANNEL', label: '视频号' },
]

function money(n: number) { return `¥${n.toLocaleString('zh-CN')}` }
function fmtNum(n: number) { return n >= 10000 ? (n / 10000).toFixed(1) + '万' : n.toLocaleString('zh-CN') }

// 漏斗
const funnel = computed(() => {
  if (!selected.value) return null
  const s = selected.value
  const max = Math.max(s.viewers, 1)
  return [
    { label: '观看人数', value: s.viewers, pct: 100, color: 'var(--c-brand)' },
    { label: '挂链点击', value: s.linkClicks, pct: Math.round((s.linkClicks / max) * 100), color: 'var(--c-teal-dark)' },
    { label: '成交单数', value: s.dealCount, pct: Math.round((s.dealCount / max) * 100), color: 'var(--c-orange-dark)' },
  ]
})

// 创建直播弹层
const showCreate = ref(false)
const form = ref({ title: '', platform: 'DOUYIN', startTime: '', couponId: '', intro: '' })
const formError = ref('')
function openCreate() {
  form.value = { title: '', platform: 'DOUYIN', startTime: '', couponId: '', intro: '' }
  formError.value = ''
  showCreate.value = true
}
function submitCreate() {
  formError.value = ''
  if (!form.value.title.trim()) { formError.value = '请输入直播标题'; return }
  if (!form.value.startTime) { formError.value = '请选择开播时间'; return }
  const chk = checkSensitive(form.value.intro)
  if (chk.hit) { formError.value = chk.message; return }
  store.createSession({
    title: form.value.title.trim(),
    platform: form.value.platform as 'DOUYIN' | 'WECHAT_CHANNEL',
    startTime: form.value.startTime.replace('T', ' '),
    mountedCouponIds: form.value.couponId ? [form.value.couponId] : [],
    intro: form.value.intro.trim(),
  })
  showCreate.value = false
}

function couponName(id: string) {
  return m1.coupons.find((c) => c.id === id)?.name ?? '—'
}
</script>

<template>
  <div class="lv">
    <div class="lv__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- 直播场次列表 -->
    <CCard class="lv__sessions" padding="none">
      <template #header>
        <div class="lv__card-head">
          <span>直播场次</span>
          <div class="lv__card-head-right">
            <CSelect v-model="store.filterStatus" width="140px" :options="statusOptions" />
            <CButton variant="primary" size="sm" v-perm.disable="'live:edit'" @click="openCreate">
              <CIcon name="plus" :size="14" />创建直播
            </CButton>
          </div>
        </div>
      </template>
      <div class="lv__table">
        <div class="lv__tr lv__tr--head">
          <div class="lv__th lv__th--title">直播标题</div>
          <div class="lv__th">平台</div>
          <div class="lv__th">状态</div>
          <div class="lv__th lv__th--num">观看人数</div>
          <div class="lv__th lv__th--num">挂链点击</div>
          <div class="lv__th lv__th--num">成交</div>
          <div class="lv__th lv__th--num">成交额</div>
          <div class="lv__th lv__th--act">操作</div>
        </div>
        <button
          v-for="s in store.filteredSessions" :key="s.id"
          class="lv__tr lv__tr--row" :class="{ 'is-active': selected?.id === s.id }"
          @click="select(s.id)"
        >
          <div class="lv__td lv__td--title">
            <span class="lv__title-text">{{ s.title }}</span>
            <span class="lv__sub">{{ s.startTime }} · {{ s.host }}</span>
          </div>
          <div class="lv__td">{{ store.PLATFORM_LABEL[s.platform] }}</div>
          <div class="lv__td">
            <CStatusPill :status="store.LIVE_STATUS_PILL[s.status]" dot>{{ store.LIVE_STATUS_LABEL[s.status] }}</CStatusPill>
          </div>
          <div class="lv__td lv__td--num">{{ fmtNum(s.viewers) }}</div>
          <div class="lv__td lv__td--num">{{ fmtNum(s.linkClicks) }}</div>
          <div class="lv__td lv__td--num">{{ s.dealCount }}</div>
          <div class="lv__td lv__td--num lv__amount">{{ money(s.dealAmount) }}</div>
          <div class="lv__td lv__td--act" @click.stop>
            <CButton v-if="s.status === 'NOT_STARTED'" variant="text" size="sm" @click="store.startLive(s.id)">开播</CButton>
            <CButton v-else-if="s.status === 'LIVE'" variant="text" size="sm" @click="store.endLive(s.id)">结束</CButton>
            <span v-else class="lv__muted">—</span>
          </div>
        </button>
        <div v-if="store.filteredSessions.length === 0" class="lv__empty">
          <CIcon name="volume" :size="28" class="lv__empty-icon" />
          <span>暂无直播场次</span>
        </div>
      </div>
    </CCard>

    <div class="lv__bottom">
      <!-- 左：详情/漏斗/图表 -->
      <CCard v-if="selected" class="lv__detail" padding="lg">
        <template #header>
          <div class="lv__detail-head">
            <div>
              <h3 class="lv__detail-title">{{ selected.title }}</h3>
              <div class="lv__detail-sub">
                {{ store.PLATFORM_LABEL[selected.platform] }} · {{ selected.startTime }} · 主播 {{ selected.host }}
              </div>
            </div>
            <CStatusPill :status="store.LIVE_STATUS_PILL[selected.status]" dot>{{ store.LIVE_STATUS_LABEL[selected.status] }}</CStatusPill>
          </div>
        </template>

        <div class="lv__funnel">
          <div class="lv__block-title">观看 → 点击 → 成交漏斗</div>
          <div v-for="f in funnel" :key="f.label" class="lv__funnel-row">
            <div class="lv__funnel-label">{{ f.label }}</div>
            <div class="lv__funnel-bar">
              <CProgressBar :value="f.pct" :max="100" :color="f.color" :height="14" :show-label="false" />
            </div>
            <div class="lv__funnel-val">{{ fmtNum(f.value) }}</div>
          </div>
          <div v-if="selected.mountedCouponIds.length" class="lv__coupon">
            <span class="lv__coupon-label">挂载团购/券：</span>
            <span v-for="cid in selected.mountedCouponIds" :key="cid" class="lv__coupon-tag">{{ couponName(cid) }}</span>
          </div>
        </div>

        <div class="lv__chart">
          <div class="lv__block-title">各场成交对比</div>
          <CBarChart :items="store.dealChartItems" :height="220" unit="元" :show-value="false" />
        </div>
      </CCard>

      <CCard v-else class="lv__detail" padding="lg">
        <div class="lv__detail-empty">
          <CIcon name="volume" :size="36" class="lv__empty-icon" />
          <p>请选择一场直播</p>
        </div>
      </CCard>

      <!-- 右：短视频库 -->
      <CCard class="lv__videos" padding="none">
        <template #header><span>短视频库</span></template>
        <div class="lv__video-list">
          <div v-for="v in store.videos" :key="v.id" class="lv__video">
            <div class="lv__video-thumb">
              <CIcon name="volume" :size="24" />
              <span class="lv__video-duration">0:30</span>
            </div>
            <div class="lv__video-info">
              <div class="lv__video-title">{{ v.title }}</div>
              <div class="lv__video-meta">
                <CStatusPill status="info">{{ store.VIDEO_PLATFORM_LABEL[v.platform] }}</CStatusPill>
                <span><CIcon name="customer" :size="12" />{{ fmtNum(v.plays) }}</span>
                <span><CIcon name="check" :size="12" />{{ fmtNum(v.likes) }}</span>
              </div>
              <div class="lv__video-deal">挂链成交 {{ v.dealCount }} 单 · {{ money(v.dealAmount) }}</div>
            </div>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 创建直播弹层 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <CCard class="lv__modal" title="创建直播" padding="lg">
        <div class="lv__form">
          <label class="lv__form-label">直播标题</label>
          <CInput v-model="form.title" placeholder="如：暑期水光自由卡专场" />

          <label class="lv__form-label">开播平台</label>
          <CSelect v-model="form.platform" width="100%" :options="platformOptions" />

          <label class="lv__form-label">开播时间</label>
          <input v-model="form.startTime" type="datetime-local" class="lv__datetime" />

          <label class="lv__form-label">挂载团购/券</label>
          <CSelect v-model="form.couponId" width="100%" :options="[{ value: '', label: '不挂载' }, ...m1.coupons.map(c => ({ value: c.id, label: c.name }))]" />

          <label class="lv__form-label">直播简介</label>
          <CTextarea v-model="form.intro" :rows="3" placeholder="直播亮点、优惠说明…（提交前自动校验违禁词）" />

          <div v-if="formError" class="lv__form-error">
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
.lv { display: flex; flex-direction: column; gap: var(--s-lg); }
.lv__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .lv__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.lv__card-head { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: var(--s-sm); font-size: var(--t-md); font-weight: 700; flex-wrap: wrap; }
.lv__card-head-right { display: flex; align-items: center; gap: var(--s-sm); }

/* table */
.lv__table { width: 100%; }
.lv__tr { display: grid; grid-template-columns: 2fr 1fr 1fr 1fr 1fr 0.8fr 1.2fr 0.8fr; align-items: center; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); }
.lv__tr--head { font-size: var(--t-xs); color: var(--c-text-3); border-bottom: 1px solid var(--c-border-light); padding-top: var(--s-sm); padding-bottom: var(--s-sm); }
.lv__tr--row { width: 100%; background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; text-align: left; font-family: inherit; }
.lv__tr--row:hover { background: var(--c-brand-soft); }
.lv__tr--row.is-active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.lv__th { font-weight: 500; }
.lv__th--num, .lv__td--num { text-align: right; font-variant-numeric: tabular-nums; }
.lv__th--act, .lv__td--act { text-align: center; }
.lv__td--title { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.lv__title-text { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.lv__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.lv__amount { font-weight: 700; color: var(--c-orange-dark); }
.lv__muted { color: var(--c-text-4); font-size: var(--t-xs); }

.lv__empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl); color: var(--c-text-3); font-size: var(--t-sm); }
.lv__empty-icon { color: var(--c-text-4); }

/* bottom */
.lv__bottom { display: grid; grid-template-columns: 1fr 360px; gap: var(--s-lg); align-items: start; }
.lv__detail { min-width: 0; }
.lv__videos { min-width: 0; }

.lv__detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-md); width: 100%; }
.lv__detail-title { font-size: var(--t-lg); font-weight: 700; margin: 0; }
.lv__detail-sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.lv__funnel { display: flex; flex-direction: column; gap: var(--s-md); margin-bottom: var(--s-lg); }
.lv__block-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-xs); }
.lv__funnel-row { display: grid; grid-template-columns: 80px 1fr 80px; align-items: center; gap: var(--s-md); }
.lv__funnel-label { font-size: var(--t-xs); color: var(--c-text-2); }
.lv__funnel-val { font-size: var(--t-sm); font-weight: 600; text-align: right; font-variant-numeric: tabular-nums; }
.lv__coupon { display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; margin-top: var(--s-xs); }
.lv__coupon-label { font-size: var(--t-xs); color: var(--c-text-3); }
.lv__coupon-tag { font-size: var(--t-xs); color: var(--c-brand); background: var(--c-brand-soft); padding: 2px 8px; border-radius: var(--r-pill); }

.lv__chart { margin-top: var(--s-md); }

.lv__detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl); color: var(--c-text-3); }

/* videos */
.lv__video-list { display: flex; flex-direction: column; gap: var(--s-sm); padding: var(--s-md); max-height: 520px; overflow-y: auto; }
.lv__video { display: flex; gap: var(--s-sm); padding: var(--s-sm); border-radius: var(--r-md); background: var(--c-bg-right); }
.lv__video-thumb { width: 96px; height: 72px; border-radius: var(--r-sm); background: linear-gradient(135deg, var(--c-brand-soft), var(--c-brand)); display: flex; align-items: center; justify-content: center; color: #fff; flex-shrink: 0; position: relative; }
.lv__video-duration { position: absolute; right: 4px; bottom: 4px; font-size: 10px; background: rgba(0,0,0,.5); color: #fff; padding: 0 4px; border-radius: 2px; }
.lv__video-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.lv__video-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.lv__video-meta { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); }
.lv__video-meta span { display: inline-flex; align-items: center; gap: 2px; }
.lv__video-deal { font-size: var(--t-xs); color: var(--c-orange-dark); font-weight: 600; }

/* modal */
.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.lv__modal { width: 480px; max-width: 100%; box-shadow: var(--shadow-pop); }
.lv__form { display: flex; flex-direction: column; gap: var(--s-sm); }
.lv__form-label { font-size: var(--t-xs); color: var(--c-text-3); }
.lv__datetime { width: 100%; padding: 10px; border: 1px solid #D1D1D9; border-radius: var(--r-sm); font-size: var(--t-sm); font-family: inherit; color: var(--c-text); background: var(--c-surface); }
.lv__datetime:focus { outline: none; border-color: var(--c-brand); box-shadow: 0 0 0 2px rgba(255,107,158,.12); }
.lv__form-error { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-danger-fg); background: var(--c-danger-bg); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); }
</style>
