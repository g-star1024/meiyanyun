<script setup lang="ts">
/* ============================================================
 * M5-15 营销设置 /m5-settings
 * 双栏：左侧 4 设置分组（触达频率/合规词库/审批流/默认渠道）
 *       右侧配置表单；保存 dirty 检测 + 二次确认 + 影响范围提示
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import CSelect from '@/components/CSelect.vue'
import CCheckbox from '@/components/CCheckbox.vue'
import {
  useM5SettingsStore,
  PUSH_CHANNEL_LABEL,
  AD_CHANNELS,
  type M5Settings,
  type PushChannel,
} from '@/stores/m5Settings'
import { useSensitiveWords } from '@/composables/useSensitiveWords'

const store = useM5SettingsStore()
const sw = useSensitiveWords()
onMounted(() => store.seed())

type GroupKey = 'FREQ' | 'WORDS' | 'APPROVAL' | 'CHANNEL'
const activeGroup = ref<GroupKey>('FREQ')

const groups: { key: GroupKey; label: string; icon: 'volume' | 'shield' | 'check-square' | 'marketing'; desc: string }[] = [
  { key: 'FREQ', label: '触达频率', icon: 'volume', desc: '周频≤3 / 免打扰时段' },
  { key: 'WORDS', label: '合规词库', icon: 'shield', desc: '违禁词维护与拦截' },
  { key: 'APPROVAL', label: '审批流', icon: 'check-square', desc: '大额券/推送审批' },
  { key: 'CHANNEL', label: '默认渠道', icon: 'marketing', desc: '推送与投放默认渠道' },
]
const activeGroupLabel = computed(() => groups.find((g) => g.key === activeGroup.value)?.label ?? '')

// 本地草稿
const draft = reactive<M5Settings>({ ...store.settings })
const newWord = ref('')
const wordError = ref('')

function syncFromStore() {
  Object.assign(draft, store.settings)
  draft.defaultPushChannels = [...store.settings.defaultPushChannels]
  draft.defaultAdChannels = [...store.settings.defaultAdChannels]
}
syncFromStore()
onMounted(syncFromStore)

const dirty = computed(() => JSON.stringify(draft) !== JSON.stringify(store.settings))

const canEdit = computed(() => store.canEdit)

const customWords = computed(() => sw.customWords.value)
const builtinWords = sw.builtinWords

const kpis = computed(() => [
  { label: '周频上限', icon: 'clock', value: `${draft.weeklyLimit}/周`, tone: 'brand' as const },
  { label: '违禁词数', icon: 'alert', value: String(builtinWords.length + customWords.value.length), tone: 'danger' as const },
  { label: '审批层级', icon: 'check-square', value: `${draft.approvalLevel} 级`, tone: 'warning' as const },
  { label: '默认渠道数', icon: 'marketing', value: String(draft.defaultPushChannels.length + draft.defaultAdChannels.length), tone: 'teal' as const },
])

const levelOptions = [
  { value: '1', label: '1 级审批（店长）' },
  { value: '2', label: '2 级审批（店长 + 区域）' },
]

// 词库
function addWord() {
  const w = newWord.value.trim()
  wordError.value = ''
  if (!w) return
  if (builtinWords.includes(w)) {
    wordError.value = '该词已在系统内置词库中，无需重复添加'
    return
  }
  if (customWords.value.includes(w)) {
    wordError.value = '该词已存在'
    return
  }
  sw.addWord(w)
  newWord.value = ''
}
function removeCustomWord(w: string) {
  sw.removeWord(w)
}

// 默认渠道勾选
function togglePush(ch: PushChannel) {
  const i = draft.defaultPushChannels.indexOf(ch)
  if (i >= 0) draft.defaultPushChannels.splice(i, 1)
  else draft.defaultPushChannels.push(ch)
}
function toggleAd(ch: string) {
  const i = draft.defaultAdChannels.indexOf(ch)
  if (i >= 0) draft.defaultAdChannels.splice(i, 1)
  else draft.defaultAdChannels.push(ch)
}

// 保存二次确认
const showConfirm = ref(false)
const toast = ref('')
function requestSave() {
  if (draft.weeklyLimit > store.WEEKLY_HARD_LIMIT) {
    draft.weeklyLimit = store.WEEKLY_HARD_LIMIT
  }
  showConfirm.value = true
}
function confirmSave() {
  const r = store.save({ ...draft })
  if (!r.ok) return
  syncFromStore()
  showConfirm.value = false
  toast.value = '营销设置已保存'
  setTimeout(() => (toast.value = ''), 2400)
}
function resetDraft() {
  syncFromStore()
}

function onWeeklyLimitInput(v: string) {
  const n = Number(v)
  draft.weeklyLimit = store.clampWeeklyLimit(n)
}

function fmt(iso: string) {
  return iso.replace('T', ' ')
}

const pushEntries = Object.entries(PUSH_CHANNEL_LABEL) as [PushChannel, string][]
</script>

<template>
  <div class="ms5">
    <div class="ms5__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="ms5__layout">
      <!-- 左：分组导航 -->
      <CCard class="ms5__nav" padding="none">
        <button
          v-for="g in groups" :key="g.key"
          class="nav-item" :class="{ 'is-active': activeGroup === g.key }"
          @click="activeGroup = g.key"
        >
          <CIcon :name="g.icon" :size="18" class="nav-item__icon" />
          <span class="nav-item__text">
            <span class="nav-item__label">{{ g.label }}</span>
            <span class="nav-item__desc">{{ g.desc }}</span>
          </span>
          <CIcon name="chevron-right" :size="14" class="nav-item__arrow" />
        </button>
      </CCard>

      <!-- 右：配置表单 -->
      <CCard class="ms5__form" padding="lg">
        <template #header>
          <div class="ms5__form-head">
            <span>{{ activeGroupLabel }}</span>
            <div class="ms5__form-head-right">
              <CButton variant="ghost" size="sm" :disabled="!dirty || !canEdit" @click="resetDraft">撤销修改</CButton>
              <CButton variant="primary" size="sm" :disabled="!dirty || !canEdit" @click="requestSave">
                <CIcon name="check" :size="14" />保存设置
              </CButton>
            </div>
          </div>
        </template>

        <!-- 触达频率 -->
        <template v-if="activeGroup === 'FREQ'">
          <h3 class="group-title"><CIcon name="volume" :size="16" /> 触达频率控制</h3>
          <p class="group-desc">同一客户 7 天内营销推送条数受周频硬约束，不可超过 {{ store.WEEKLY_HARD_LIMIT }} 次；免打扰时段内禁止任何营销推送。</p>

          <div class="form-grid">
            <div class="fld">
              <label class="fld__label">同一客户每周推送上限（条，最大 {{ store.WEEKLY_HARD_LIMIT }}）</label>
              <CInput type="number" :model-value="String(draft.weeklyLimit)" :disabled="!canEdit"
                @update:model-value="onWeeklyLimitInput($event)" />
            </div>
          </div>

          <div class="switches">
            <label class="sw">
              <span class="sw__text">
                <span class="sw__title">营销免打扰时段</span>
                <span class="sw__desc">开启后 {{ draft.quietStart }}–{{ draft.quietEnd }} 不向客户推送任何营销消息</span>
              </span>
              <label class="switch">
                <input type="checkbox" v-model="draft.quietHoursEnabled" :disabled="!canEdit" />
                <span class="slider" />
              </label>
            </label>
            <div class="time-row" v-if="draft.quietHoursEnabled">
              <CInput type="text" :model-value="draft.quietStart" :disabled="!canEdit"
                @update:model-value="draft.quietStart = $event" />
              <span class="time-sep">至</span>
              <CInput type="text" :model-value="draft.quietEnd" :disabled="!canEdit"
                @update:model-value="draft.quietEnd = $event" />
            </div>
            <label class="sw">
              <span class="sw__text">
                <span class="sw__title">节日豁免</span>
                <span class="sw__desc">会员日 / 重大节日当天豁免周频上限（仍受违禁词约束）</span>
              </span>
              <label class="switch">
                <input type="checkbox" v-model="draft.holidayExempt" :disabled="!canEdit" />
                <span class="slider" />
              </label>
            </label>
          </div>

          <div class="redline">
            <CIcon name="shield" :size="16" />
            <span>周频 ≤ {{ store.WEEKLY_HARD_LIMIT }} 为硬合规约束，不可关闭或放宽。</span>
          </div>
        </template>

        <!-- 合规词库 -->
        <template v-else-if="activeGroup === 'WORDS'">
          <h3 class="group-title"><CIcon name="shield" :size="16" /> 合规违禁词库</h3>
          <p class="group-desc">营销活动名称、推送文案、券名、海报与落地页在发布前强制校验，命中即拦截。内置词由系统维护，自定义词可增删。</p>

          <div class="word-add">
            <CInput v-model="newWord" placeholder="输入自定义违禁词，如：特效、零副作用" :disabled="!canEdit"
              @update:model-value="newWord = $event; wordError = ''" />
            <CButton variant="primary" size="md" :disabled="!canEdit || !newWord.trim()" @click="addWord">
              <CIcon name="plus" :size="14" />添加
            </CButton>
          </div>
          <div v-if="wordError" class="word-error"><CIcon name="alert" :size="13" />{{ wordError }}</div>

          <div class="word-section">
            <div class="word-section__title">自定义词（{{ customWords.length }}）<span class="word-section__hint">可删除</span></div>
            <div v-if="!customWords.length" class="word-empty">暂无自定义违禁词</div>
            <div v-else class="word-tags">
              <span v-for="w in customWords" :key="w" class="word-tag word-tag--custom">
                {{ w }}
                <button class="word-tag__x" :disabled="!canEdit" @click="removeCustomWord(w)">
                  <CIcon name="close" :size="12" />
                </button>
              </span>
            </div>
          </div>

          <div class="word-section">
            <div class="word-section__title">系统内置词（{{ builtinWords.length }}）<span class="word-section__hint word-section__hint--lock">不可删</span></div>
            <div class="word-tags">
              <span v-for="w in builtinWords" :key="w" class="word-tag word-tag--builtin">
                <CIcon name="shield" :size="11" />{{ w }}
              </span>
            </div>
          </div>

          <div class="redline">
            <CIcon name="shield" :size="16" />
            <span>违禁词拦截为硬合规约束，所有 M5 文案发布前强制过校验，不可关闭。</span>
          </div>
        </template>

        <!-- 审批流 -->
        <template v-else-if="activeGroup === 'APPROVAL'">
          <h3 class="group-title"><CIcon name="check-square" :size="16" /> 审批流配置</h3>
          <p class="group-desc">大额券与高风险推送需经对应层级审批后方可发出，审批操作全程留痕。</p>

          <div class="form-grid">
            <div class="fld">
              <label class="fld__label">大额券审批阈值（元，单张面额超过需审批）</label>
              <CInput type="number" :model-value="String(draft.largeCouponThreshold)" :disabled="!canEdit"
                @update:model-value="draft.largeCouponThreshold = Math.max(0, Number($event) || 0)" />
            </div>
            <div class="fld">
              <label class="fld__label">活动发布审批层级</label>
              <CSelect :model-value="String(draft.approvalLevel)" :options="levelOptions" width="100%"
                :disabled="!canEdit"
                @update:model-value="draft.approvalLevel = (Number($event) === 1 ? 1 : 2)" />
            </div>
          </div>

          <div class="switches">
            <label class="sw">
              <span class="sw__text">
                <span class="sw__title">营销推送需审批</span>
                <span class="sw__desc">关闭后所有推送免审直接发出（不建议，关闭操作将记入审计）</span>
              </span>
              <label class="switch">
                <input type="checkbox" v-model="draft.pushRequiresApproval" :disabled="!canEdit" />
                <span class="slider" />
              </label>
            </label>
          </div>
        </template>

        <!-- 默认渠道 -->
        <template v-else>
          <h3 class="group-title"><CIcon name="marketing" :size="16" /> 默认渠道</h3>
          <p class="group-desc">新建营销活动 / 推送时默认勾选的渠道，运营可在单次活动中调整。</p>

          <div class="fld">
            <label class="fld__label">默认推送渠道</label>
            <div class="channel-list">
              <label v-for="[key, label] in pushEntries" :key="key" class="channel-chip">
                <CCheckbox :model-value="draft.defaultPushChannels.includes(key)"
                  :disabled="!canEdit"
                  @update:model-value="togglePush(key)" />
                <span>{{ label }}</span>
              </label>
            </div>
          </div>

          <div class="fld">
            <label class="fld__label">默认投放渠道</label>
            <div class="channel-list">
              <label v-for="ch in AD_CHANNELS" :key="ch" class="channel-chip">
                <CCheckbox :model-value="draft.defaultAdChannels.includes(ch)"
                  :disabled="!canEdit"
                  @update:model-value="toggleAd(ch)" />
                <span>{{ ch }}</span>
              </label>
            </div>
          </div>
        </template>
      </CCard>
    </div>

    <!-- 变更审计记录 -->
    <CCard class="ms5__log" padding="lg">
      <template #header><h3 class="card-title"><CIcon name="check" :size="16" /> 变更审计记录</h3></template>
      <div class="log-list">
        <div v-for="l in store.logs" :key="l.id" class="log-row">
          <span class="log-field">{{ l.field }}</span>
          <span class="log-change">
            <span class="log-old">{{ l.oldValue }}</span>
            <CIcon name="chevron-right" :size="12" class="log-arrow" />
            <span class="log-new">{{ l.newValue }}</span>
          </span>
          <span class="log-by">{{ l.by }}</span>
          <span class="log-at">{{ fmt(l.at) }}</span>
        </div>
      </div>
    </CCard>

    <!-- 保存确认弹层 -->
    <div v-if="showConfirm" class="modal-mask" @click.self="showConfirm = false">
      <CCard class="modal modal--sm" title="确认保存营销设置" padding="lg">
        <div class="confirm">
          <div class="confirm__icon"><CIcon name="alert" :size="28" /></div>
          <p class="confirm__text">本次设置将作用于全 M5 营销模块（活动 / 推送 / 券 / 落地页 / 海报）。</p>
          <p class="confirm__hint">周频上限与违禁词为硬合规约束，保存后即时生效，请确认无误。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showConfirm = false">再看看</CButton>
          <CButton variant="primary" @click="confirmSave">确认保存</CButton>
        </template>
      </CCard>
    </div>

    <transition name="toast">
      <div v-if="toast" class="toast"><CIcon name="check" :size="16" />{{ toast }}</div>
    </transition>
  </div>
</template>

<style scoped>
.ms5 { display: flex; flex-direction: column; gap: var(--s-lg); }
.ms5__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ms5__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.ms5__form-head { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: var(--s-sm); font-size: var(--t-md); font-weight: 700; flex-wrap: wrap; }
.ms5__form-head-right { display: flex; align-items: center; gap: var(--s-sm); }
:deep(.ckpi) { min-width: 0; }

.card-title { font-size: var(--t-md); font-weight: 700; margin: 0; display: flex; align-items: center; gap: var(--s-xs); }

.ms5__layout { display: grid; grid-template-columns: 280px 1fr; gap: var(--s-lg); align-items: start; }
.ms5__nav { overflow: hidden; }
.nav-item {
  display: flex; align-items: center; gap: var(--s-sm); width: 100%; text-align: left;
  padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light);
  cursor: pointer; transition: background 0.15s;
}
.nav-item:last-child { border-bottom: none; }
.nav-item:hover { background: var(--c-brand-soft); }
.nav-item.is-active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.nav-item__icon { color: var(--c-text-3); flex-shrink: 0; }
.nav-item.is-active .nav-item__icon { color: var(--c-brand); }
.nav-item__text { flex: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.nav-item__label { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.nav-item__desc { font-size: var(--t-xs); color: var(--c-text-3); }
.nav-item__arrow { color: var(--c-text-4); }

.group-title { font-size: var(--t-md); font-weight: 700; margin: 0 0 var(--s-xs); display: flex; align-items: center; gap: var(--s-xs); }
.group-desc { font-size: var(--t-xs); color: var(--c-text-3); margin: 0 0 var(--s-lg); line-height: 1.6; }

.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.fld { display: flex; flex-direction: column; gap: var(--s-xs); margin-bottom: var(--s-md); }
.fld__label { font-size: var(--t-xs); color: var(--c-text-3); }

/* 开关 */
.switch { position: relative; display: inline-block; width: 42px; height: 22px; flex-shrink: 0; }
.switch input { opacity: 0; width: 0; height: 0; }
.slider { position: absolute; cursor: pointer; inset: 0; background: var(--c-border); border-radius: 22px; transition: .2s; }
.slider::before { content: ''; position: absolute; height: 18px; width: 18px; left: 2px; top: 2px; background: #fff; border-radius: 50%; transition: .2s; }
.switch input:checked + .slider { background: var(--c-brand); }
.switch input:checked + .slider::before { transform: translateX(20px); }
.switch input:disabled + .slider { opacity: .5; cursor: not-allowed; }

.switches { display: flex; flex-direction: column; gap: var(--s-sm); }
.sw { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); padding: var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-md); }
.sw__text { display: flex; flex-direction: column; gap: 2px; }
.sw__title { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.sw__desc { font-size: var(--t-xs); color: var(--c-text-3); }

.time-row { display: flex; align-items: center; gap: var(--s-sm); padding: 0 var(--s-md); }
.time-row :deep(.cinput) { flex: 1; }
.time-sep { font-size: var(--t-xs); color: var(--c-text-3); }

.redline {
  display: flex; align-items: center; gap: var(--s-xs); margin-top: var(--s-lg);
  padding: var(--s-sm) var(--s-md); background: var(--c-warning-bg); color: var(--c-warning-fg);
  border-radius: var(--r-md); font-size: var(--t-xs); font-weight: 600;
}

/* 词库 */
.word-add { display: flex; gap: var(--s-sm); align-items: flex-end; margin-bottom: var(--s-sm); }
.word-add :deep(.cinput) { flex: 1; }
.word-error {
  display: inline-flex; align-items: center; gap: var(--s-xxs);
  color: var(--c-danger-fg); font-size: var(--t-xs); margin-bottom: var(--s-md);
}
.word-section { margin-top: var(--s-md); }
.word-section__title {
  font-size: var(--t-xs); font-weight: 600; color: var(--c-text-2);
  display: flex; align-items: center; gap: var(--s-sm); margin-bottom: var(--s-sm);
}
.word-section__hint { font-weight: 400; color: var(--c-text-4); }
.word-section__hint--lock { color: var(--c-danger-fg); }
.word-empty { font-size: var(--t-xs); color: var(--c-text-4); padding: var(--s-sm) 0; }
.word-tags { display: flex; flex-wrap: wrap; gap: var(--s-xs); }
.word-tag {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 3px 8px; border-radius: var(--r-sm);
  font-size: var(--t-xs); line-height: 1.4;
}
.word-tag--custom { background: var(--c-brand-soft); color: var(--c-brand); }
.word-tag--builtin { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.word-tag__x {
  display: inline-flex; align-items: center; justify-content: center;
  width: 16px; height: 16px; border: none; background: transparent; cursor: pointer;
  color: inherit; padding: 0; border-radius: 50%;
}
.word-tag__x:hover { background: rgba(0, 0, 0, .08); }
.word-tag__x:disabled { cursor: not-allowed; opacity: .5; }

/* 渠道 */
.channel-list { display: flex; flex-wrap: wrap; gap: var(--s-sm); }
.channel-chip {
  display: inline-flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-xs) var(--s-sm);
  background: var(--c-bg-right, #f7f7fb); border: 1px solid var(--c-border-light);
  border-radius: var(--r-capsule); font-size: var(--t-xs); color: var(--c-text-2); cursor: pointer;
}

/* 审计记录 */
.log-list { display: flex; flex-direction: column; }
.log-row { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); }
.log-row:last-child { border-bottom: none; }
.log-field { flex: 1; color: var(--c-text); font-weight: 500; }
.log-change { display: inline-flex; align-items: center; gap: var(--s-xs); color: var(--c-text-3); flex: 2; }
.log-old { color: var(--c-text-4); text-decoration: line-through; }
.log-arrow { color: var(--c-text-4); }
.log-new { color: var(--c-brand); font-weight: 600; }
.log-by { color: var(--c-text-3); font-size: var(--t-xs); }
.log-at { color: var(--c-text-4); font-size: var(--t-xs); min-width: 120px; text-align: right; }

/* 弹层 */
.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 400px; max-width: 100%; box-shadow: var(--shadow-pop); }
.confirm { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); text-align: center; padding: var(--s-sm) 0; }
.confirm__icon { width: 52px; height: 52px; border-radius: 50%; background: var(--c-warning-bg); color: var(--c-warning-fg); display: flex; align-items: center; justify-content: center; }
.confirm__text { font-size: var(--t-sm); color: var(--c-text-2); margin: 0; line-height: 1.6; }
.confirm__hint { font-size: var(--t-xs); color: var(--c-text-3); margin: 0; line-height: 1.6; }

.toast {
  position: fixed; bottom: var(--s-xl); left: 50%; transform: translateX(-50%);
  display: inline-flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-sm) var(--s-lg); background: var(--c-success-fg); color: #fff;
  border-radius: var(--r-capsule); font-size: var(--t-sm); font-weight: 600;
  box-shadow: var(--shadow-pop); z-index: 300;
}
.toast-enter-active, .toast-leave-active { transition: opacity 0.2s, transform 0.2s; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translate(-50%, 10px); }

@media (max-width: 1024px) {
  .ms5__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .ms5__layout { grid-template-columns: 1fr; }
  .ms5__nav { display: grid; grid-template-columns: 1fr 1fr; }
  .nav-item { border-bottom: 1px solid var(--c-border-light); }
  .nav-item:nth-child(odd) { border-right: 1px solid var(--c-border-light); }
  .nav-item__arrow { display: none; }
  .form-grid { grid-template-columns: 1fr; }
  .log-at { min-width: 0; }
}
</style>
