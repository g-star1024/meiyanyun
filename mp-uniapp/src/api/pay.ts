/**
 * 微信支付（小程序端）
 * ---------------------------------------------------------------
 * 资金安全链路（密钥永不下发到小程序）：
 *   1. 小程序下单 → 调后端 POST /api/c/orders （带登录 token）
 *   2. 后端用【保存在服务端的】商户号/APIv3密钥/证书调用微信支付「统一下单」，
 *      拿到 prepay_id，并按小程序支付参数规则签名后返回给小程序
 *   3. 小程序拿到后端返回的 5 个参数，调 uni.requestPayment 唤起微信收银台
 *   4. 支付结果以后端「微信支付回调」为准，小程序端结果只做 UI 引导
 *
 * 所以：AppSecret、mchid、APIv3 key、商户证书 —— 全部只在后端，
 *      B 端「小程序与支付配置」页录入后加密存储，小程序永远接触不到。
 */
import { http } from '@/utils/request'

/** 后端统一下单返回的小程序支付参数（由后端签名生成） */
export interface WxPayParams {
  timeStamp: string
  nonceStr: string
  /** 后端下单返回的 prepay_id 包装成的 package 字段：prepay_id=xxx */
  package: string
  signType: 'MD5' | 'RSA' | 'HMAC-SHA256'
  paySign: string
}

export interface CreateOrderPayload {
  /** 项目/商品 id */
  itemId: string
  /** 数量 */
  qty: number
  /** 预约信息（可选） */
  bookingId?: string
  remark?: string
}

/**
 * 下单并唤起支付。
 * @returns 成功时返回订单号；用户取消或失败抛出异常
 */
export async function createOrderAndPay(payload: CreateOrderPayload): Promise<{ orderNo: string }> {
  // 1) 后端创建订单 + 统一下单，返回支付参数
  const res = await http.post<{ orderNo: string; payParams: WxPayParams }>('/c/orders', {
    ...payload,
    payChannel: 'WECHAT_MINIAPP',
  })

  // 2) 若后台关闭了线上支付（如仅到店付），后端不返回 payParams
  if (!res.payParams) {
    return { orderNo: res.orderNo }
  }

  // 3) 唤起微信收银台
  await new Promise<void>((resolve, reject) => {
    uni.requestPayment({
      provider: 'wxpay',
      timeStamp: res.payParams.timeStamp,
      nonceStr: res.payParams.nonceStr,
      package: res.payParams.package,
      signType: res.payParams.signType,
      paySign: res.payParams.paySign,
      success: () => resolve(),
      fail: (err) => reject(new Error(err.errMsg === 'requestPayment:fail cancel' ? 'CANCEL' : 'PAY_FAIL')),
    })
  })

  return { orderNo: res.orderNo }
}
