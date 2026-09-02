// ============================================================
// 小程序与支付配置 store（管理后台）
// 运营在此录入小程序 AppID / AppSecret、微信支付商户号 / APIv3 密钥 / 证书，
// 以及对小程序「运行时下发」的公开配置（品牌名、客服电话、功能开关等）。
//
// 安全红线：
//  - AppSecret / APIv3 密钥 / 商户证书属【服务端机密】，只加密存服务端，
//    前端仅展示掩码（如 wx****abcd），永不下发到小程序端。
//  - 小程序运行时只能拿到 publicConfig（公开配置），见 /c/mp/config 接口。
//  - AppID 是小程序身份，构建期写死在 mp 工程 manifest.json；后台此处仅登记/展示。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/** 小程序基础配置（AppID 等身份信息） */
export interface WeappCredential {
  appId: string          // 小程序 AppID（构建期写死 manifest，此处登记）
  appSecretMasked: string // AppSecret 掩码（真实值仅存服务端密钥库）
  originalId: string     // 原始 ID（gh_ 开头）
  serverDomain: string   // request 合法域名（需 https + 备案）
}

/** 微信支付配置（机密，仅服务端使用） */
export interface WxPayConfig {
  mchId: string            // 微信支付商户号
  apiV3KeyMasked: string   // APIv3 密钥掩码
  certSerial: string       // 商户证书序列号
  certHasUploaded: boolean // 商户私钥证书是否已上传
  notifyUrl: string        // 支付回调地址（服务端）
}

/** 对小程序运行时下发的【公开】配置（不敏感，可热更新） */
export interface MpPublicConfig {
  brandName: string
  servicePhone: string
  wechatPayEnabled: boolean
  pointsMallEnabled: boolean
  inviteEnabled: boolean
  themeColor: string
  notice: string
}

function mask(secret: string) {
  if (!secret) return ''
  if (secret.length <= 6) return '******'
  return `${secret.slice(0, 2)}****${secret.slice(-4)}`
}

export const useMpSettingsStore = defineStore('mpSettings', () => {
  const credential = ref<WeappCredential>({
    appId: '',
    appSecretMasked: '',
    originalId: '',
    serverDomain: 'https://api.meiyun.example.com',
  })
  const pay = ref<WxPayConfig>({
    mchId: '',
    apiV3KeyMasked: '',
    certSerial: '',
    certHasUploaded: false,
    notifyUrl: 'https://api.meiyun.example.com/api/c/pay/notify',
  })
  const publicConfig = ref<MpPublicConfig>({
    brandName: '美研云',
    servicePhone: '400-000-0000',
    wechatPayEnabled: true,
    pointsMallEnabled: true,
    inviteEnabled: true,
    themeColor: '#ff6b9e',
    notice: '',
  })

  /** 配置完成度（0-100），用于 KPI 提示 */
  const completion = computed(() => {
    let done = 0
    const total = 6
    if (credential.value.appId) done++
    if (credential.value.appSecretMasked) done++
    if (pay.value.mchId) done++
    if (pay.value.apiV3KeyMasked) done++
    if (pay.value.certHasUploaded) done++
    if (credential.value.serverDomain) done++
    return Math.round((done / total) * 100)
  })
  const ready = computed(() => completion.value === 100)

  /** 保存小程序身份信息（AppSecret 只在录入时传一次，服务端加密后回掩码） */
  function saveCredential(input: { appId: string; appSecret?: string; originalId: string; serverDomain: string }) {
    credential.value.appId = input.appId.trim()
    credential.value.originalId = input.originalId.trim()
    credential.value.serverDomain = input.serverDomain.trim()
    if (input.appSecret) credential.value.appSecretMasked = mask(input.appSecret.trim())
  }

  /** 保存微信支付配置（APIv3 密钥/证书只在录入时上传） */
  function savePay(input: { mchId: string; apiV3Key?: string; certSerial?: string; certUploaded?: boolean; notifyUrl: string }) {
    pay.value.mchId = input.mchId.trim()
    pay.value.notifyUrl = input.notifyUrl.trim()
    if (input.apiV3Key) pay.value.apiV3KeyMasked = mask(input.apiV3Key.trim())
    if (input.certSerial !== undefined) pay.value.certSerial = input.certSerial.trim()
    if (input.certUploaded !== undefined) pay.value.certHasUploaded = input.certUploaded
  }

  /** 保存对小程序下发的公开配置（热更新，无需发版） */
  function savePublicConfig(input: Partial<MpPublicConfig>) {
    publicConfig.value = { ...publicConfig.value, ...input }
  }

  return {
    credential,
    pay,
    publicConfig,
    completion,
    ready,
    saveCredential,
    savePay,
    savePublicConfig,
  }
})
