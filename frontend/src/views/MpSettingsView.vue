<script setup lang="ts">
/* ============================================================
 * 小程序与支付配置 /admin/mp-settings（管理后台）
 * 录入：小程序 AppID/AppSecret、微信支付商户号/APIv3 密钥/证书；
 *      以及对小程序运行时下发的公开配置（品牌/客服/功能开关）。
 * 安全红线：密钥仅加密存服务端、前端只显掩码，永不下发到小程序。
 * ============================================================ */
import { computed, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import { useMpSettingsStore } from '@/stores/mpSettings'

const store = useMpSettingsStore()

const flash = ref<{ type: 'ok' | 'warn'; text: string } | null>(null)
function setFlash(text: string, type: 'ok' | 'warn' = 'ok') {
  flash.value = { type, text }
  window.setTimeout(() => (flash.value = null), 3500)
}

// ---------- 小程序身份 ----------
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

// ---------- 微信支付 ----------
const payForm = reactive({
  mchId: '',
  apiV3Key: '',
  certSerial: '',
  notifyUrl: store.pay.notifyUrl,
})
const certUploaded = ref(false)
function onCert() {
  // 真实实现调文件上传接口；此处仅标记
  certUploaded.value = true
  setFlash('商户证书已选择，保存后将上传至服务端密钥库')
}
function savePay() {
  if (!payForm.mchId.trim()) {
    setFlash('请填写微信支付商户号', 'warn')
    return
  }
  store.savePay({ ...payForm, certUploaded: certUploaded.value || store.pay.certHasUploaded })
  payForm.apiV3Key = ''
  setFlash('微信支付配置已保存（密钥/证书仅存服务端，不下发到小程序）')
}

// ---------- 运行时公开配置（下发到小程序） ----------
const pub = reactive({ ...store.publicConfig })
function savePub() {
  store.savePublicConfig({ ...pub })
  setFlash('公开配置已下发，小程序下次启动自动获取（无需发版）')
}

const kpis = computed(() => [
  { label: '配置完成度', icon: 'dashboard', value: `${store.completion}%`, tone: (store.ready ? 'success' : 'warning') as 'success' | 'warning', sub: store.ready ? '可提审发布' : '待完善' },
  { label: '小程序 AppID', icon: 'tool', value: store.credential.appId || '未配置', tone: 'text' as const, sub: '构建期写死' },
  { label: '支付商户号', icon: 'order', value: store.pay.mchId || '未配置', tone: 'text' as const, sub: '服务端机密' },
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

      <!-- 微信支付 -->
      <CCard>
        <template #header>
          <h3 class="card-h"><CIcon name="order" :size="16" /> 微信支付配置（服务端使用）</h3>
        </template>
        <div class="form">
          <CInput v-model="payForm.mchId" label="微信支付商户号 mchid" placeholder="如 1600000001" />
          <CInput v-model="payForm.apiV3Key" label="APIv3 密钥（录入后加密存储）" placeholder="32 位密钥，仅填写时传输" type="password" />
          <div v-if="store.pay.apiV3KeyMasked" class="masked">已保存 APIv3 密钥：<b>{{ store.pay.apiV3KeyMasked }}</b></div>
          <CInput v-model="payForm.certSerial" label="商户证书序列号（选填）" placeholder="证书序列号" />
          <CInput v-model="payForm.notifyUrl" label="支付回调地址（服务端）" placeholder="https://api.xxx.com/api/c/pay/notify" />
          <div class="cert">
            <CButton variant="secondary" size="sm" @click="onCert">上传商户私钥证书（apiclient_key.pem）</CButton>
            <span class="cert__state" :class="{ ok: certUploaded || store.pay.certHasUploaded }">
              {{ certUploaded || store.pay.certHasUploaded ? '✓ 已上传' : '未上传' }}
            </span>
          </div>
          <div class="actions">
            <CButton variant="primary" @click="savePay">保存支付配置</CButton>
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
