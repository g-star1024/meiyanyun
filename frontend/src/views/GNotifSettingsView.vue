<script setup lang="ts">
/* ============================================================
 * G-06 消息设置（/notif-settings）B60 卡2 起接 txn 真实偏好 API
 * 1. 通知渠道开关（站内/企微/短信/邮件）——按五类偏好聚合派生，开关逐类落库
 * 2. 通知类型偏好（APPROVAL/CUSTOMER/INVENTORY/MARKETING/SYSTEM × 4 渠道）
 * 3. 个人免打扰时段（服务端按类别持久化，页面一个开关统管五类；URGENT 恒豁免）
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CInput from '@/components/CInput.vue'
import CButton from '@/components/CButton.vue'
import {
  useNotificationStore,
  type NotifyCategory, type NotifyChannel,
} from '@/stores/notification'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'

const ns = useNotificationStore()
const toast = useToast()

type ChanKey = 'inbox' | 'wecom' | 'sms' | 'email'
const CHAN_OF: Record<ChanKey, NotifyChannel> = {
  inbox: 'INBOX', wecom: 'WECHAT', sms: 'SMS', email: 'EMAIL',
}
const chanKeys: ChanKey[] = ['inbox', 'wecom', 'sms', 'email']
const chanLabels: Record<ChanKey, string> = { inbox: '站内', wecom: '企微', sms: '短信', email: '邮件' }
const chanMeta: Record<ChanKey, { label: string; desc: string }> = {
  inbox: { label: '站内消息', desc: '在站内通知中心接收，实时弹出（必达渠道，不可关闭）' },
  wecom: { label: '企业微信', desc: '通过企微应用推送，需已绑定企微' },
  sms: { label: '短信', desc: '重要提醒以短信发送，可能产生通讯费用' },
  email: { label: '邮件', desc: '汇总 / 周报类消息以邮件发送' },
}

const CATEGORY_DESC: Record<NotifyCategory, string> = {
  APPROVAL: '退款 / 调价 / 权限申请等待你审批',
  CUSTOMER: '回访、随访、生日关怀等客户运营提醒',
  INVENTORY: '库存不足、耗材预警、双签拦截等高优先级',
  MARKETING: '新玩法上线、节日营销模板推荐',
  SYSTEM: '版本更新、停机维护、合规通知',
}

interface PrefRow {
  key: NotifyCategory
  label: string
  desc: string
  enabled: boolean
  ch: Record<ChanKey, boolean>
}

const prefs = computed<PrefRow[]>(() =>
  ns.preferences.map((p) => ({
    key: p.category,
    label: ns.CATEGORY_LABEL[p.category],
    desc: CATEGORY_DESC[p.category],
    enabled: p.enabled,
    ch: {
      inbox: p.channels.includes('INBOX'),
      wecom: p.channels.includes('WECHAT'),
      sms: p.channels.includes('SMS'),
      email: p.channels.includes('EMAIL'),
    },
  })),
)

/** 渠道总开关：INBOX 必达恒开；其余渠道=任一已订阅类别勾选了该渠道 */
function channelOn(k: ChanKey): boolean {
  if (k === 'inbox') return true
  const ch = CHAN_OF[k]
  return ns.preferences.some((p) => p.enabled && p.channels.includes(ch))
}

/** 渠道总开关：开→所有已订阅类别补该渠道；关→移除；逐类串行 PUT 避免全量回写竞态 */
async function toggleChan(k: ChanKey) {
  if (k === 'inbox') {
    toast.info('站内消息为必达渠道，不可关闭')
    return
  }
  const ch = CHAN_OF[k]
  const turnOn = !channelOn(k)
  const targets = ns.preferences.filter(
    (p) => p.enabled && (turnOn ? !p.channels.includes(ch) : p.channels.includes(ch)),
  )
  for (const p of targets) {
    // 串行：每次 PUT 返回服务端全量偏好并整体替换，并行会互相覆盖
    // eslint-disable-next-line no-await-in-loop
    await ns.toggleChannel(p.category, ch)
  }
}

function togglePref(row: PrefRow, k: ChanKey) {
  if (!channelOn(k) || !row.enabled) return
  ns.toggleChannel(row.key, CHAN_OF[k])
}

const dnd = reactive({
  enabled: false,
  start: '22:00',
  end: '08:00',
})

function syncDndFromPrefs() {
  const ps = ns.preferences
  dnd.enabled = ps.length > 0 && ps.every((p) => p.quietEnabled)
  dnd.start = ps[0]?.quietStart || '22:00'
  dnd.end = ps[0]?.quietEnd || '08:00'
}

const saving = ref(false)
const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/

/** 保存：渠道/类型勾选已在点击时即时落库，此处统一持久化五类个人免打扰 */
async function save() {
  if (saving.value) return
  let start = dnd.start.trim()
  const end = dnd.end.trim()
  if (dnd.enabled) {
    if (!HHMM.test(start) || !HHMM.test(end)) {
      toast.error('免打扰时刻格式非法，应为 HH:mm（00:00–23:59）')
      return
    }
    if (start === end) {
      toast.error('免打扰开始与结束时刻不能相同（至少相差 1 分钟，跨午夜请用如 22:00–08:00）')
      return
    }
  } else if (start === end) {
    // 关闭态下后端仍校验起止相等，回落到系统默认时段避免 400
    start = '22:00'
    dnd.end = '08:00'
  }
  saving.value = true
  try {
    for (const p of ns.preferences) {
      // eslint-disable-next-line no-await-in-loop
      const ok = await ns.saveQuiet(p.category, {
        quietEnabled: dnd.enabled,
        quietStart: start,
        quietEnd: dnd.end.trim(),
      })
      if (!ok) break
    }
    toast.success('消息设置已保存')
  } catch (e) {
    toast.error(errMsg(e, '消息设置保存失败'))
  } finally {
    saving.value = false
  }
}

function resetDefault() {
  dnd.enabled = false
  dnd.start = '22:00'
  dnd.end = '08:00'
  toast.info('已恢复默认免打扰（22:00–次日 08:00，关闭），点击保存设置生效')
}

onMounted(async () => {
  await ns.fetchPreferences()
  syncDndFromPrefs()
})
</script>

<template>
  <div class="g-notif">
    <CCard title="通知渠道" padding="lg">
      <template #header>
        <h3 class="sec-title">通知渠道</h3>
        <span class="sec-sub">关闭渠道后，该渠道的所有类型都会停用</span>
      </template>
      <div class="chan-list">
        <div v-for="k in chanKeys" :key="k" class="chan">
          <div class="chan__text">
            <span class="chan__label">{{ chanMeta[k].label }}</span>
            <span class="chan__desc">{{ chanMeta[k].desc }}</span>
          </div>
          <button
            type="button"
            class="toggle"
            :class="{ 'is-on': channelOn(k) }"
            :aria-pressed="channelOn(k)"
            @click="toggleChan(k)"
          >
            <span class="toggle__dot" />
          </button>
        </div>
      </div>
    </CCard>

    <CCard title="通知类型偏好" padding="lg">
      <template #header>
        <h3 class="sec-title">通知类型偏好</h3>
        <span class="sec-sub">按业务类型选择接收渠道</span>
      </template>
      <div class="pref">
        <div class="pref__head">
          <span class="pref__name">类型</span>
          <span v-for="k in chanKeys" :key="k" class="pref__col">{{ chanLabels[k] }}</span>
        </div>
        <div v-for="p in prefs" :key="p.key" class="pref__row">
          <div class="pref__name">
            <span class="pref__label">{{ p.label }}</span>
            <span class="pref__desc">{{ p.desc }}</span>
          </div>
          <span v-for="k in chanKeys" :key="k" class="pref__col">
            <input
              type="checkbox"
              class="chk"
              :checked="p.ch[k]"
              :disabled="k === 'inbox' || !channelOn(k) || !p.enabled"
              @change="togglePref(p, k)"
            />
          </span>
        </div>
      </div>
    </CCard>

    <CCard title="免打扰时段" padding="lg">
      <template #header>
        <h3 class="sec-title">免打扰时段</h3>
        <span class="sec-sub">开启后非紧急消息将延迟到时段结束推送</span>
      </template>
      <div class="dnd">
        <div class="dnd__row">
          <div class="dnd__text">
            <span class="dnd__label">启用免打扰</span>
            <span class="dnd__desc">告警类消息不受免打扰影响</span>
          </div>
          <button
            type="button"
            class="toggle"
            :class="{ 'is-on': dnd.enabled }"
            :aria-pressed="dnd.enabled"
            @click="dnd.enabled = !dnd.enabled"
          >
            <span class="toggle__dot" />
          </button>
        </div>
        <div v-if="dnd.enabled" class="dnd__time">
          <CInput v-model="dnd.start" type="text" label="开始时间" placeholder="22:00" />
          <span class="dnd__sep">—</span>
          <CInput v-model="dnd.end" type="text" label="结束时间" placeholder="08:00" />
        </div>
      </div>
    </CCard>

    <div class="g-notif__foot">
      <CButton variant="secondary" size="md" @click="resetDefault">恢复默认</CButton>
      <CButton variant="primary" size="md" :disabled="saving" @click="save">
        {{ saving ? '保存中…' : '保存设置' }}
      </CButton>
    </div>
  </div>
</template>

<style scoped>
.g-notif { display: flex; flex-direction: column; gap: var(--s-lg); }
.sec-title { font-size: var(--t-md); font-weight: 700; color: var(--c-text); margin: 0; }
.sec-sub { font-size: var(--t-xs); color: var(--c-text-3); }

/* Toggle */
.toggle {
  position: relative; width: 40px; height: 22px;
  border-radius: var(--r-capsule);
  background: var(--c-border);
  border: none; cursor: pointer; padding: 0;
  transition: background .2s;
  flex-shrink: 0;
}
.toggle__dot {
  position: absolute; top: 2px; left: 2px;
  width: 18px; height: 18px; border-radius: 50%;
  background: var(--c-surface);
  box-shadow: 0 1px 3px rgba(20,21,43,.2);
  transition: transform .2s;
}
.toggle.is-on { background: var(--c-brand); }
.toggle.is-on .toggle__dot { transform: translateX(18px); }

/* Channels */
.chan-list { display: flex; flex-direction: column; }
.chan {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-md) 0;
  border-bottom: 1px solid var(--c-border-light);
}
.chan:last-child { border-bottom: none; }
.chan__text { display: flex; flex-direction: column; gap: 2px; }
.chan__label { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.chan__desc { font-size: var(--t-xs); color: var(--c-text-3); }

/* Pref matrix */
.pref { display: flex; flex-direction: column; }
.pref__head, .pref__row {
  display: grid; grid-template-columns: 1fr repeat(4, 64px);
  align-items: center; gap: var(--s-md);
  padding: var(--s-sm) 0;
}
.pref__head { border-bottom: 1px solid var(--c-border); font-size: var(--t-xs); color: var(--c-text-3); }
.pref__row { border-bottom: 1px solid var(--c-border-light); }
.pref__row:last-child { border-bottom: none; }
.pref__col { display: inline-flex; align-items: center; justify-content: center; }
.pref__name { display: flex; flex-direction: column; gap: 2px; }
.pref__label { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.pref__desc { font-size: var(--t-xs); color: var(--c-text-3); }
.chk {
  width: 16px; height: 16px; accent-color: var(--c-brand); cursor: pointer;
}
.chk:disabled { cursor: not-allowed; opacity: .4; }

/* DND */
.dnd { display: flex; flex-direction: column; gap: var(--s-md); }
.dnd__row { display: flex; align-items: center; justify-content: space-between; }
.dnd__text { display: flex; flex-direction: column; gap: 2px; }
.dnd__label { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.dnd__desc { font-size: var(--t-xs); color: var(--c-text-3); }
.dnd__time { display: flex; align-items: flex-end; gap: var(--s-sm); }
.dnd__time :deep(.cinput) { width: 140px; }
.dnd__sep { padding-bottom: var(--s-sm); color: var(--c-text-3); }

.g-notif__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); }
</style>
