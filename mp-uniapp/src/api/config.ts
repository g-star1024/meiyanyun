/**
 * 远程运行时配置（小程序从后台拉取）
 * ---------------------------------------------------------------
 * 回答「AppID / 支付密钥能不能配在后台、小程序自动获取」：
 *
 * 1) AppID：不能靠运行时下发。它是小程序的身份，在微信框架加载前就必须确定，
 *    只能写死在构建产物里（src/manifest.json → mp-weixin.appid）。
 *    后台可以「展示/管理」AppID 供运营查看，但不能在运行时改变它。
 *
 * 2) 支付密钥（AppSecret / 商户号 mchid / APIv3 密钥 / 商户证书）：
 *    【绝对不能下发到小程序】。这些是服务端机密，小程序端不可持有，
 *    否则反编译即可窃取，造成资金损失。它们只保存在 B 端后台/服务端密钥库，
 *    由服务端调用微信支付时使用。
 *
 * 3) 小程序运行时真正需要、且适合后台下发的是「公开配置」——下方 RemoteConfig：
 *    品牌名、客服电话、支付开关、功能开关、主题色、活动文案等。
 *    这些不敏感，可热更新，无需重新发版。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { http } from '@/utils/request'

export interface RemoteConfig {
  /** 小程序/品牌显示名 */
  brandName: string
  /** 客服电话 */
  servicePhone: string
  /** 是否开启微信支付（后台控制，关闭则只展示到店付款） */
  wechatPayEnabled: boolean
  /** 是否开启积分商城 */
  pointsMallEnabled: boolean
  /** 是否开启邀请有礼 */
  inviteEnabled: boolean
  /** 主题色（十六进制） */
  themeColor: string
  /** 首页公告/活动文案 */
  notice: string
  /** 后台展示用的 AppID（仅展示，不参与框架逻辑） */
  weappAppIdMasked: string
}

/** 内置默认值：后台未配置或拉取失败时兜底 */
const FALLBACK: RemoteConfig = {
  brandName: '美研云',
  servicePhone: '400-000-0000',
  wechatPayEnabled: true,
  pointsMallEnabled: true,
  inviteEnabled: true,
  themeColor: '#ff6b9e',
  notice: '',
  weappAppIdMasked: '',
}

export const useRemoteConfig = defineStore('remoteConfig', () => {
  const config = ref<RemoteConfig>({ ...FALLBACK })
  const loaded = ref(false)

  async function load(): Promise<RemoteConfig> {
    try {
      // 对应后端：GET /api/c/mp/config （公开接口，无需登录）
      const data = await http.get<Partial<RemoteConfig>>('/c/mp/config', { auth: false, silent: true })
      config.value = { ...FALLBACK, ...(data || {}) }
    } catch {
      config.value = { ...FALLBACK }
    }
    loaded.value = true
    return config.value
  }

  return { config, loaded, load }
})
