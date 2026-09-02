<script setup lang="ts">
/* ============================================================
 * G-06 消息设置（/notif-settings）
 * 1. 通知渠道开关（站内/企微/短信/邮件）
 * 2. 通知类型偏好（5 类 × 4 渠道）
 * 3. 免打扰时段
 * 联动 T3-03 消息通道
 * ============================================================ */
import { reactive } from 'vue'
import CCard from '@/components/CCard.vue'
import CInput from '@/components/CInput.vue'
import CButton from '@/components/CButton.vue'

const channels = reactive({
  inbox: { label: '站内消息', on: true, desc: '在站内通知中心接收，实时弹出' },
  wecom: { label: '企业微信', on: true, desc: '通过企微应用推送，需已绑定企微' },
  sms: { label: '短信', on: false, desc: '重要提醒以短信发送，可能产生通讯费用' },
  email: { label: '邮件', on: false, desc: '汇总 / 周报类消息以邮件发送' },
})

type ChanKey = keyof typeof channels
const chanKeys = Object.keys(channels) as ChanKey[]
const chanLabels: Record<ChanKey, string> = { inbox: '站内', wecom: '企微', sms: '短信', email: '邮件' }

interface Pref { key: string; label: string; desc: string; ch: Record<ChanKey, boolean> }
const prefs = reactive<Pref[]>([
  { key: 'approval', label: '审批待办', desc: '退款 / 调价 / 权限申请等待你审批', ch: { inbox: true, wecom: true, sms: false, email: false } },
  { key: 'workorder', label: '工单提醒', desc: '客诉、设备维修、反馈工单进度', ch: { inbox: true, wecom: true, sms: false, email: false } },
  { key: 'alert', label: '告警', desc: '库存不足、异常登录、双签拦截等高优先级', ch: { inbox: true, wecom: true, sms: true, email: false } },
  { key: 'marketing', label: '营销活动', desc: '新玩法上线、节日营销模板推荐', ch: { inbox: true, wecom: false, sms: false, email: false } },
  { key: 'system', label: '系统公告', desc: '版本更新、停机维护、合规通知', ch: { inbox: true, wecom: true, sms: false, email: true } },
])

const dnd = reactive({
  enabled: false,
  start: '22:00',
  end: '08:00',
})

function toggleChan(k: ChanKey) {
  channels[k].on = !channels[k].on
  if (!channels[k].on) {
    prefs.forEach((p) => (p.ch[k] = false))
  }
}
function togglePref(p: Pref, k: ChanKey) {
  if (!channels[k].on) return
  p.ch[k] = !p.ch[k]
}

function save() {
  alert('消息设置已保存（已同步 T3-03 消息通道）')
}
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
            <span class="chan__label">{{ channels[k].label }}</span>
            <span class="chan__desc">{{ channels[k].desc }}</span>
          </div>
          <button
            type="button"
            class="toggle"
            :class="{ 'is-on': channels[k].on }"
            :aria-pressed="channels[k].on"
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
              :disabled="!channels[k].on"
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
      <CButton variant="secondary" size="md">恢复默认</CButton>
      <CButton variant="primary" size="md" @click="save">保存设置</CButton>
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
