<script setup lang="ts">
/* ============================================================
 * 门店设置 /m2-settings（M2-21）
 * 表单页：基础信息 / 预约规则 / 库存与采购 / 通知偏好，底部保存。
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useM2SettingsStore, type M2Settings } from '@/stores/m2settings'

const store = useM2SettingsStore()
onMounted(() => store.seed())

// 本地草稿（编辑态）
const draft = reactive<M2Settings>({ ...store.settings })
const dirty = ref(false)
const toast = ref('')

function sync<K extends keyof M2Settings>(key: K, value: M2Settings[K]) {
  draft[key] = value
  dirty.value = true
}
function isDirty(key: keyof M2Settings) {
  return draft[key] !== store.settings[key]
}
function save() {
  if (store.saveAll({ ...draft })) {
    dirty.value = false
    toast.value = '设置已保存'
    setTimeout(() => (toast.value = ''), 2400)
  }
}
function reset() {
  Object.assign(draft, store.settings)
  dirty.value = false
}

const kpis = computed(() => [
  { label: '营业时长', icon: 'clock', value: `${store.businessHoursLen}h`, tone: 'brand' as const },
  { label: '在岗员工', icon: 'profile', value: String(store.staffOnDuty), tone: 'teal' as const },
  { label: '库存预警项', icon: 'alert', value: String(store.warnItemCount), tone: 'warning' as const },
  { label: '本月修改', icon: 'settings', value: String(store.monthModified), tone: 'text' as const },
])

const yesNo = [
  { value: 'true', label: '是' },
  { value: 'false', label: '否' },
]
const warnThresholdOptions = [
  { value: '10', label: '10%' },
  { value: '15', label: '15%' },
  { value: '20', label: '20%' },
  { value: '25', label: '25%' },
  { value: '30', label: '30%' },
]
const leadDayOptions = [
  { value: '3', label: '3 天' },
  { value: '5', label: '5 天' },
  { value: '7', label: '7 天' },
  { value: '10', label: '10 天' },
  { value: '14', label: '14 天' },
]

function toNum(v: string) { return Number(v) }
function fromNum(v: number) { return String(v) }
function fromBool(v: boolean) { return v ? 'true' : 'false' }
function toBool(v: string) { return v === 'true' }
</script>

<template>
  <div class="ms">
    <div class="ms__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="ms__body">
      <CCard class="ms__card" title="基础信息" padding="lg">
        <div class="grid grid--2">
          <CInput label="门店名称" :model-value="draft.storeName" @update:model-value="sync('storeName', $event)" />
          <CInput label="门店编码" :model-value="draft.storeCode" @update:model-value="sync('storeCode', $event)" />
          <CInput label="工作日营业时间" :model-value="draft.businessHoursWeekday" placeholder="如 10:00 - 21:00" @update:model-value="sync('businessHoursWeekday', $event)" />
          <CInput label="周末营业时间" :model-value="draft.businessHoursWeekend" placeholder="如 09:30 - 21:30" @update:model-value="sync('businessHoursWeekend', $event)" />
        </div>
      </CCard>

      <CCard class="ms__card" title="预约规则" padding="lg">
        <div class="grid grid--2">
          <div class="fld">
            <label class="fld__label">最早提前预约（小时）</label>
            <CInput type="number" :model-value="fromNum(draft.minAdvanceHours)" @update:model-value="sync('minAdvanceHours', toNum($event))" />
          </div>
          <div class="fld">
            <label class="fld__label">最晚提前预约（天）</label>
            <CInput type="number" :model-value="fromNum(draft.maxAdvanceDays)" @update:model-value="sync('maxAdvanceDays', toNum($event))" />
          </div>
          <div class="fld">
            <label class="fld__label">迟到/取消缓冲（分钟）</label>
            <CInput type="number" :model-value="fromNum(draft.lateCancelMinutes)" @update:model-value="sync('lateCancelMinutes', toNum($event))" />
          </div>
          <div class="fld">
            <label class="fld__label">爽约扣费（元）</label>
            <CInput type="number" :model-value="fromNum(draft.noShowFee)" @update:model-value="sync('noShowFee', toNum($event))" />
          </div>
        </div>
      </CCard>

      <CCard class="ms__card" title="库存与采购" padding="lg">
        <div class="grid grid--2">
          <div class="fld">
            <label class="fld__label">库存预警阈值</label>
            <CSelect
              :model-value="fromNum(draft.inventoryWarnThreshold)"
              :options="warnThresholdOptions"
              width="100%"
              @update:model-value="sync('inventoryWarnThreshold', toNum($event))"
            />
          </div>
          <div class="fld">
            <label class="fld__label">采购到货周期</label>
            <CSelect
              :model-value="fromNum(draft.purchaseLeadDays)"
              :options="leadDayOptions"
              width="100%"
              @update:model-value="sync('purchaseLeadDays', toNum($event))"
            />
          </div>
          <div class="fld">
            <label class="fld__label">启用自动补货建议</label>
            <CSelect
              :model-value="fromBool(draft.autoReorder)"
              :options="yesNo"
              width="100%"
              @update:model-value="sync('autoReorder', toBool($event))"
            />
          </div>
        </div>
      </CCard>

      <CCard class="ms__card" title="收银 / 退款" padding="lg">
        <div class="grid grid--2">
          <div class="fld">
            <label class="fld__label">店长退款上限（元）</label>
            <CInput type="number" :model-value="fromNum(draft.refundMaxAmount)" @update:model-value="sync('refundMaxAmount', toNum($event))" />
          </div>
          <div class="fld">
            <label class="fld__label">超额退款需审批</label>
            <CSelect
              :model-value="fromBool(draft.refundRequireApproval)"
              :options="yesNo"
              width="100%"
              @update:model-value="sync('refundRequireApproval', toBool($event))"
            />
          </div>
          <div class="fld fld--full">
            <label class="fld__label">发票抬头</label>
            <CInput :model-value="draft.invoiceTitle" @update:model-value="sync('invoiceTitle', $event)" />
          </div>
        </div>
      </CCard>

      <CCard class="ms__card" title="通知偏好" padding="lg">
        <div class="switches">
          <label class="sw" :class="{ 'sw--dirty': isDirty('notifySmsBooking') }">
            <span class="sw__text">
              <span class="sw__title">预约成功短信</span>
              <span class="sw__desc">客户下单 / 预约后自动发送确认短信</span>
            </span>
            <input type="checkbox" :checked="draft.notifySmsBooking" @change="sync('notifySmsBooking', ($event.target as HTMLInputElement).checked)" />
          </label>
          <label class="sw" :class="{ 'sw--dirty': isDirty('notifySmsArrival') }">
            <span class="sw__text">
              <span class="sw__title">到店提醒短信</span>
              <span class="sw__desc">预约前 2 小时发送到店提醒与导航</span>
            </span>
            <input type="checkbox" :checked="draft.notifySmsArrival" @change="sync('notifySmsArrival', ($event.target as HTMLInputElement).checked)" />
          </label>
          <label class="sw" :class="{ 'sw--dirty': isDirty('notifyWxFollowup') }">
            <span class="sw__text">
              <span class="sw__title">企微回访提醒</span>
              <span class="sw__desc">项目完成后按 SOP 节奏推送给咨询师</span>
            </span>
            <input type="checkbox" :checked="draft.notifyWxFollowup" @change="sync('notifyWxFollowup', ($event.target as HTMLInputElement).checked)" />
          </label>
          <label class="sw" :class="{ 'sw--dirty': isDirty('notifyEmailReport') }">
            <span class="sw__text">
              <span class="sw__title">周报邮件推送</span>
              <span class="sw__desc">每周一 9:00 向店长邮箱推送经营周报</span>
            </span>
            <input type="checkbox" :checked="draft.notifyEmailReport" @change="sync('notifyEmailReport', ($event.target as HTMLInputElement).checked)" />
          </label>
        </div>
      </CCard>

      <div class="ms__footer">
        <span v-if="dirty" class="ms__hint">有未保存的修改</span>
        <span v-else class="ms__hint ms__hint--ok"><CIcon name="check" :size="14" />所有修改已保存</span>
        <CButton variant="ghost" @click="reset">重置</CButton>
        <CButton variant="primary" v-perm.disable="'m2settings:edit'" :disabled="!dirty" @click="save">
          <CIcon name="check" :size="16" />保存设置
        </CButton>
      </div>

      <transition name="toast">
        <div v-if="toast" class="toast">
          <CIcon name="check" :size="16" />{{ toast }}
        </div>
      </transition>
    </div>
  </div>
</template>

<style scoped>
.ms { display: flex; flex-direction: column; gap: var(--s-lg); }
.ms__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ms__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.ms__body { display: flex; flex-direction: column; gap: var(--s-lg); }
.ms__card { }

.grid { display: grid; gap: var(--s-md); }
.grid--2 { grid-template-columns: 1fr 1fr; }
.fld { display: flex; flex-direction: column; gap: var(--s-xs); }
.fld--full { grid-column: 1 / -1; }
.fld__label { font-size: var(--t-xs); color: var(--c-text-3); }

.switches { display: flex; flex-direction: column; gap: var(--s-sm); }
.sw {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-md);
  padding: var(--s-md) var(--s-lg);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-md);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.sw:hover { border-color: var(--c-brand-border); background: var(--c-brand-soft); }
.sw--dirty { border-color: var(--c-brand); background: var(--c-brand-soft); }
.sw__text { display: flex; flex-direction: column; gap: 2px; }
.sw__title { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.sw__desc { font-size: var(--t-xs); color: var(--c-text-3); }
.sw input {
  appearance: none; width: 40px; height: 22px; border-radius: var(--r-capsule);
  background: var(--c-border); position: relative; cursor: pointer; transition: background 0.15s;
}
.sw input::after {
  content: ''; position: absolute; top: 2px; left: 2px; width: 18px; height: 18px;
  border-radius: 50%; background: var(--c-surface); box-shadow: 0 1px 2px rgba(0,0,0,.2);
  transition: transform 0.15s;
}
.sw input:checked { background: var(--c-brand); }
.sw input:checked::after { transform: translateX(18px); }

.ms__footer {
  position: sticky; bottom: 0;
  display: flex; align-items: center; justify-content: flex-end; gap: var(--s-sm);
  padding: var(--s-md) var(--s-lg);
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-card);
}
.ms__hint { font-size: var(--t-sm); color: var(--c-text-3); margin-right: auto; display: inline-flex; align-items: center; gap: 4px; }
.ms__hint--ok { color: var(--c-success-fg); }

.toast {
  position: fixed; bottom: var(--s-xl); left: 50%; transform: translateX(-50%);
  display: inline-flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-sm) var(--s-lg);
  background: var(--c-success-fg); color: #fff;
  border-radius: var(--r-capsule);
  font-size: var(--t-sm); font-weight: 600;
  box-shadow: var(--shadow-pop);
  z-index: 300;
}
.toast-enter-active, .toast-leave-active { transition: opacity 0.2s, transform 0.2s; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translate(-50%, 10px); }

@media (max-width: 1024px) {
  .ms__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .grid--2 { grid-template-columns: 1fr; }
}
</style>
