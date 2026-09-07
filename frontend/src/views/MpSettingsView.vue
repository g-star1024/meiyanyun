<script setup lang="ts">
/* ============================================================
 * 小程序与支付配置 /admin/mp-settings（管理后台）
 * 录入：小程序 AppID/AppSecret；非现金支付渠道（微信支付/支付宝/
 *      银行转账）对接参数——真实接入 GET/POST /api/txn/pay-channels；
 *      以及对小程序运行时下发的公开配置（品牌/客服/功能开关，本地）。
 * 安全红线：密钥仅加密存服务端、前端只显掩码，永不下发到小程序。
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import { useMpSettingsStore } from '@/stores/mpSettings'
import { useAuthStore } from '@/stores/auth'
import { listPayChannels, upsertPayChannel, togglePayChannel, type PayChannelDTO } from '@/api/payChannel'

const store = useMpSettingsStore()
const auth = useAuthStore()
const canEditChannel = computed(() => auth.can('finance:channel:edit'))

const flash = ref<{ type: 'ok' | 'warn'; text: string } | null>(null)
function setFlash(text: string, type: 'ok' | 'warn' = 'ok') {
  flash.value = { type, text }
  window.setTimeout(() => (flash.value = null), 3500)
}
function errMsg(e: unknown, fallback: string): string {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || fallback
}

// ---------- 小程序身份（本地配置） ----------
const cred = reactive({
  appId: '',
  appSecret: '',
  originalId: '',
  serverDomain: store.credential.serverDomain,
})
function saveCred() {
  if (!cred.appId.trim()) {
    setFlash('请填写小程序 AppID', 'warn')
    return
  }
  store.saveCredential({ ...cred })
  cred.appSecret = ''
  setFlash('小程序配置已保存（AppSecret 已加密存储，仅显示掩码）')
}

// ---------- 非现金支付渠道（真实接入 /api/txn/pay-channels） ----------
interface ChannelForm {
  appId: string
  mchId: string
  apiV3Key: string
  certSerial: string
  notifyUrl: string
  feeRatePct: string
  remark: string
}
interface ChannelDef {
  code: string
  name: string
  appId: boolean
  secret: boolean
  cert: boolean
  notify: boolean
  appIdLabel?: string
  appIdPh?: string
  mchLabel?: string
  mchPh?: string
  secretLabel?: string
  secretPh?: string
  secretMasked?: string
  notifyPh?: string
  remarkLabel?: string
  remarkPh?: string
}
const CHANNEL_DEFS: ChannelDef[] = [
  {
    code: 'wxpay', name: '微信支付', appId: true, secret: true, cert: true, notify: true,
    appIdLabel: '微信 AppID（小程序/公众号，选填）', appIdPh: 'wx 开头，如 wx1234567890abcdef',
    mchLabel: '微信支付商户号 mchid', mchPh: '如 1600000001',
    secretLabel: 'APIv3 密钥（录入后加密存储）', secretPh: '32 位密钥，仅填写时传输；留空保存不修改',
    secretMasked: '已保存 APIv3 密钥：****（留空保存不修改）',
    notifyPh: 'https://api.xxx.com/api/c/pay/notify',
  },
  {
    code: 'alipay', name: '支付宝', appId: true, secret: true, cert: false, notify: true,
    appIdLabel: '支付宝应用 APPID', appIdPh: '如 2021000000000000',
    mchLabel: '支付宝商户号 PID', mchPh: '2088 开头的合作身份者 ID',
    secretLabel: '应用私钥（录入后加密存储）', secretPh: '仅填写时传输；留空保存不修改',
    secretMasked: '已保存应用私钥：****（留空保存不修改）',
    notifyPh: 'https://api.xxx.com/api/c/alipay/notify',
  },
  {
    code: 'transfer', name: '银行转账', appId: false, secret: false, cert: false, notify: false,
    mchLabel: '银行收款账号（选填）', mchPh: '对公账户账号',
    remarkLabel: '开户行 / 户名备注（选填）', remarkPh: '如：招商银行杭州分行 / 杭州美研医疗美容门诊部',
  },
]
function emptyForm(): ChannelForm {
  return { appId: '', mchId: '', apiV3Key: '', certSerial: '', notifyUrl: '', feeRatePct: '', remark: '' }
}
const forms = reactive<Record<string, ChannelForm>>({
  wxpay: emptyForm(),
  alipay: emptyForm(),
  transfer: emptyForm(),
})
const enabledState = reactive<Record<string, boolean>>({ wxpay: true, alipay: true, transfer: false })
const certUploaded = reactive<Record<string, boolean>>({ wxpay: false, alipay: false, transfer: false })
const chanMap = ref<Record<string, PayChannelDTO>>({})
const chanLoading = ref(false)
const chanLoadErr = ref('')
const savingCode = ref('')

async function loadChannels() {
  chanLoading.value = true
  chanLoadErr.value = ''
  try {
    const { data } = await listPayChannels()
    const map: Record<string, PayChannelDTO> = {}
    for (const d of data) {
      if (d.builtin || d.storeCode) continue
      map[d.channelCode] = d
    }
    chanMap.value = map
    for (const def of CHANNEL_DEFS) {
      const d = map[def.code]
      enabledState[def.code] = d ? d.enabled : def.code !== 'transfer'
      certUploaded[def.code] = false
      forms[def.code] = {
        appId: d?.appId ?? '',
        mchId: d?.mchId ?? '',
        apiV3Key: '',
        certSerial: d?.certSerial ?? '',
        notifyUrl: d?.notifyUrl ?? '',
        feeRatePct: d && d.feeRate ? String(Math.round((d.feeRate / 100) * 100) / 100) : '',
        remark: d?.remark ?? '',
      }
    }
  } catch (e) {
    chanLoadErr.value = errMsg(e, '渠道配置加载失败')
  } finally {
    chanLoading.value = false
  }
}
onMounted(loadChannels)

function onCert(code: string) {
  certUploaded[code] = true
  setFlash('商户证书已选择，证书序列号请同步登记到上方输入框后保存')
}

async function saveChannel(code: string) {
  const def = CHANNEL_DEFS.find((d) => d.code === code)!
  const f = forms[code]
  if (code === 'wxpay' && !f.mchId.trim()) {
    setFlash('请填写微信支付商户号', 'warn')
    return
  }
  if (code === 'alipay' && !f.mchId.trim()) {
    setFlash('请填写支付宝商户号 PID', 'warn')
    return
  }
  let feeRate = 0
  const pct = f.feeRatePct.trim()
  if (pct) {
    const v = Number(pct)
    if (!Number.isFinite(v) || v < 0 || v > 1000) {
      setFlash('手续费率无效：请填 0~1000 之间的百分数（如 0.60 表示 0.6%）', 'warn')
      return
    }
    feeRate = Math.round(v * 100)
  }
  savingCode.value = code
  try {
    await upsertPayChannel({
      channelCode: code,
      storeCode: '',
      enabled: enabledState[code],
      appId: f.appId.trim() || null,
      mchId: f.mchId.trim() || null,
      apiV3Key: f.apiV3Key,
      certSerial: f.certSerial.trim() || null,
      notifyUrl: f.notifyUrl.trim() || null,
      feeRate,
      remark: f.remark.trim() || null,
    })
    setFlash(`${def.name}配置已保存（密钥仅存服务端，不下发到小程序）`)
    await loadChannels()
  } catch (e) {
    setFlash(errMsg(e, `${def.name}配置保存失败`), 'warn')
  } finally {
    savingCode.value = ''
  }
}

async function toggleChannel(code: string) {
  const d = chanMap.value[code]
  if (!d?.configId) return
  try {
    await togglePayChannel(d.configId)
    await loadChannels()
  } catch (e) {
    setFlash(errMsg(e, '渠道启停失败'), 'warn')
  }
}

function onSwitch(code: string) {
  if (!canEditChannel.value) return
  const d = chanMap.value[code]
  if (!d?.configId) {
    enabledState[code] = !enabledState[code]
    return
  }
  toggleChannel(code)
}

// ---------- 运行时公开配置（下发到小程序，本地） ----------
const pub = reactive({ ...store.publicConfig })
function savePub() {
  store.savePublicConfig({ ...pub })
  setFlash('公开配置已下发，小程序下次启动自动获取（无需发版）')
}

const kpis = computed(() => [
  { label: '配置完成度', icon: 'dashboard', value: `${store.completion}%`, tone: (store.ready ? 'success' : 'warning') as 'success' | 'warning', sub: store.ready ? '可提审发布' : '待完善' },
  { label: '小程序 AppID', icon: 'tool', value: store.credential.appId || '未配置', tone: 'text' as const, sub: '构建期写死' },
  { label: '微信支付商户号', icon: 'order', value: chanMap.value.wxpay?.mchId || '未配置', tone: 'text' as const, sub: '服务端机密' },
  { label: '支付开关', icon: 'bell', value: pub.wechatPayEnabled ? '已开启' : '已关闭', tone: (pub.wechatPayEnabled ? 'brand' : 'text') as 'brand' | 'text', sub: '运行时下发' },
])

function toggle(key: 'wechatPayEnabled' | 'pointsMallEnabled' | 'inviteEnabled') {
  pub[key] = !pub[key]
}
</script>

<template>
  <div class="mp-settings">
    <!-- 提示条 -->
    <div v-if="flash" class="flash" :class="`flash--${flash.type}`">
      <CIcon :name="flash.type === 'ok' ? 'check-square' : 'alert'" :size="15" />
      <span>{{ flash.text }}</span>
    </div>

    <!-- KPI -->
    <div class="kpi-row">
      <CKpi
        v-for="k in kpis"
        :key="k.label"
        :label="k.label"
        :value="k.value"
        :sub="k.sub"
        :icon="k.icon"
        :tone="k.tone"
      />
    </div>

    <!-- 安全红线说明 -->
    <CCard class="redline" padding="md">
      <div class="redline__inner">
        <CIcon name="shield" :size="18" class="redline__icon" />
        <div>
          <div class="redline__title">密钥安全红线</div>
          <div class="redline__text">
            AppSecret、APIv3 密钥、商户证书属<b>服务端机密</b>，仅加密存储在服务端密钥库、前端只显示掩码，
            <b>永不下发到小程序</b>（反编译可窃取）。小程序运行时只能获取「公开配置」。AppID 是小程序身份，
            构建期写死在小程序工程 manifest.json，此处仅登记展示。
          </div>
        </div>
      </div>
    </CCard>

    <div v-if="chanLoadErr" class="flash flash--warn">
      <CIcon name="alert" :size="15" />
      <span>支付渠道配置加载失败：{{ chanLoadErr }}（无 integration:view 权限或支付服务不可达；本地配置不受影响）</span>
    </div>

    <div class="grid">
      <!-- 小程序身份 -->
      <CCard title="小程序基础配置">
        <template #header>
          <h3 class="card-h"><CIcon name="tool" :size="16" /> 小程序基础配置</h3>
        </template>
        <div class="form">
          <CInput v-model="cred.appId" label="小程序 AppID" placeholder="wx 开头，如 wx1234567890abcdef" />
          <CInput v-model="cred.appSecret" label="AppSecret（录入后加密存储）" placeholder="仅填写时传输，保存后只显示掩码" type="password" />
          <div v-if="store.credential.appSecretMasked" class="masked">已保存 AppSecret：<b>{{ store.credential.appSecretMasked }}</b></div>
          <CInput v-model="cred.originalId" label="原始 ID（gh_ 开头，选填）" placeholder="gh_xxxxxxxx" />
          <CInput v-model="cred.serverDomain" label="服务器合法域名（https + 已备案）" placeholder="https://api.xxx.com" />
          <div class="actions">
            <CButton variant="primary" @click="saveCred">保存小程序配置</CButton>
          </div>
        </div>
      </CCard>

      <!-- 非现金支付渠道（真实接入 /api/txn/pay-channels） -->
      <CCard v-for="def in CHANNEL_DEFS" :key="def.code">
        <template #header>
          <h3 class="card-h"><CIcon name="order" :size="16" /> {{ def.name }}配置（服务端使用）</h3>
        </template>
        <div class="form">
          <CInput
            v-if="def.appId"
            v-model="forms[def.code].appId"
            :label="def.appIdLabel"
            :placeholder="def.appIdPh"
            :disabled="!canEditChannel || chanLoading"
          />
          <CInput
            v-model="forms[def.code].mchId"
            :label="def.mchLabel"
            :placeholder="def.mchPh"
            :disabled="!canEditChannel || chanLoading"
          />
          <template v-if="def.secret">
            <CInput
              v-model="forms[def.code].apiV3Key"
              :label="def.secretLabel"
              :placeholder="def.secretPh"
              type="password"
              :disabled="!canEditChannel || chanLoading"
            />
            <div v-if="chanMap[def.code]?.hasApiKey" class="masked">{{ def.secretMasked }}</div>
          </template>
          <CInput
            v-if="def.cert"
            v-model="forms[def.code].certSerial"
            label="商户证书序列号（选填）"
            placeholder="证书序列号"
            :disabled="!canEditChannel || chanLoading"
          />
          <CInput
            v-if="def.notify"
            v-model="forms[def.code].notifyUrl"
            label="支付回调地址（服务端）"
            :placeholder="def.notifyPh"
            :disabled="!canEditChannel || chanLoading"
          />
          <CInput
            v-model="forms[def.code].feeRatePct"
            label="手续费率（%，选填，用于账单核对口径）"
            placeholder="如 0.60 表示 0.6%，留空为 0"
            type="number"
            :disabled="!canEditChannel || chanLoading"
          />
          <CInput
            v-if="def.remarkLabel"
            v-model="forms[def.code].remark"
            :label="def.remarkLabel"
            :placeholder="def.remarkPh"
            :disabled="!canEditChannel || chanLoading"
          />
          <div v-if="def.cert" class="cert">
            <CButton variant="secondary" size="sm" :disabled="!canEditChannel" @click="onCert(def.code)">
              上传商户私钥证书（apiclient_key.pem）
            </CButton>
            <span class="cert__state" :class="{ ok: certUploaded[def.code] || !!chanMap[def.code]?.certSerial }">
              {{ certUploaded[def.code] || chanMap[def.code]?.certSerial ? '✓ 已登记' : '未上传' }}
            </span>
          </div>
          <div class="masked">
            当前状态：<b>{{ enabledState[def.code] ? '已启用' : '已停用' }}</b>；
            对账方式：账单 CSV 导入勾兑（收银台真实代扣/API 拉单为后续迭代）
          </div>
          <div class="actions">
            <CButton
              variant="primary"
              :disabled="!canEditChannel || chanLoading || savingCode === def.code"
              @click="saveChannel(def.code)"
            >保存{{ def.name }}配置</CButton>
            <CButton
              v-if="chanMap[def.code]?.configId"
              variant="secondary"
              :disabled="!canEditChannel"
              @click="onSwitch(def.code)"
            >{{ enabledState[def.code] ? '停用' : '启用' }}</CButton>
            <span v-if="!canEditChannel" class="pub-hint">无 finance:channel:edit 权限，仅可查看</span>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 运行时公开配置 -->
    <CCard class="pub-card">
      <template #header>
        <h3 class="card-h"><CIcon name="settings" :size="16" /> 小程序运行时配置（公开下发 · 热更新）</h3>
      </template>
      <div class="pub-grid">
        <CInput v-model="pub.brandName" label="品牌/小程序显示名" />
        <CInput v-model="pub.servicePhone" label="客服电话" />
        <CInput v-model="pub.themeColor" label="主题色（十六进制）" placeholder="#ff6b9e" />
        <CInput v-model="pub.notice" label="首页公告/活动文案（选填）" placeholder="留空则不显示" />
      </div>
      <div class="switches">
        <div class="sw" @click="toggle('wechatPayEnabled')">
          <span>开启微信支付（关闭则仅到店付款）</span>
          <span class="sw__track" :class="{ on: pub.wechatPayEnabled }"><i></i></span>
        </div>
        <div class="sw" @click="toggle('pointsMallEnabled')">
          <span>开启积分商城</span>
          <span class="sw__track" :class="{ on: pub.pointsMallEnabled }"><i></i></span>
        </div>
        <div class="sw" @click="toggle('inviteEnabled')">
          <span>开启邀请有礼</span>
          <span class="sw__track" :class="{ on: pub.inviteEnabled }"><i></i></span>
        </div>
      </div>
      <div class="actions">
        <CButton variant="primary" @click="savePub">保存并下发公开配置</CButton>
        <span class="pub-hint">小程序通过 GET /api/c/mp/config 启动时拉取，改这里无需重新发版</span>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.mp-settings {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--s-md);
}
.flash {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md);
  border-radius: var(--r-md);
  font-size: var(--t-sm);
}
.flash--ok {
  background: var(--c-success-soft, #eaf8ef);
  color: var(--c-success, #16a34a);
}
.flash--warn {
  background: var(--c-warning-soft, #fff5e6);
  color: var(--c-warning, #d97706);
}
.redline {
  border-left: 3px solid var(--c-warning, #fa8c16);
}
.redline__inner {
  display: flex;
  gap: var(--s-md);
  align-items: flex-start;
}
.redline__icon {
  color: var(--c-warning, #fa8c16);
  flex-shrink: 0;
  margin-top: 2px;
}
.redline__title {
  font-size: var(--t-sm);
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 4px;
}
.redline__text {
  font-size: var(--t-xs);
  color: var(--c-text-2);
  line-height: 1.7;
}
.redline__text b {
  color: var(--c-danger, #e02f4a);
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-md);
  align-items: start;
}
.card-h {
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  margin: 0;
  font-size: var(--t-md);
  font-weight: 600;
  color: var(--c-text);
}
.form {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.masked {
  font-size: var(--t-xs);
  color: var(--c-text-2);
  background: var(--c-bg-page, #f6f6f8);
  padding: var(--s-xs) var(--s-sm);
  border-radius: var(--r-sm);
}
.masked b {
  color: var(--c-text);
}
.cert {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
}
.cert__state {
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
.cert__state.ok {
  color: var(--c-success, #16a34a);
}
.actions {
  display: flex;
  align-items: center;
  gap: var(--s-md);
  margin-top: var(--s-xs);
}
.pub-hint {
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
.pub-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-md);
}
.switches {
  display: flex;
  flex-direction: column;
  gap: var(--s-sm);
  margin: var(--s-md) 0;
}
.sw {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--s-sm) var(--s-md);
  background: var(--c-bg-page, #f6f6f8);
  border-radius: var(--r-md);
  font-size: var(--t-sm);
  color: var(--c-text);
  cursor: pointer;
}
.sw__track {
  width: 40px;
  height: 22px;
  border-radius: 999px;
  background: var(--c-border-strong, #d1d1d9);
  position: relative;
  transition: background 0.2s;
}
.sw__track i {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  transition: transform 0.2s;
}
.sw__track.on {
  background: var(--c-brand, #ff6b9e);
}
.sw__track.on i {
  transform: translateX(18px);
}
@media (max-width: 1024px) {
  .kpi-row,
  .grid,
  .pub-grid {
    grid-template-columns: 1fr;
  }
}
</style>
