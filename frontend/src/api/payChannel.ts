// ============================================================
// PayChannel API（对接 txn-service，B12 非现金渠道账实接入）
// 渠道随支付方式走：wxpay/alipay/transfer 需后台对接参数；cash/balance 系统内置只读。
// 密钥红线：apiV3Key 写后不可读回——列表仅 hasApiKey 标记，不回显明文；
//          保存时密钥留空 = 不修改。审计不记明文。
// 权限：读 integration:view；写 finance:channel:edit。
// ============================================================
import client from './client'

export type PayChannelCode = 'wxpay' | 'alipay' | 'transfer' | 'cash' | 'balance'
export type ReconcileMode = 'IMPORT' | 'API' | 'BUILTIN'

/** 渠道配置读模型（对齐 PayChannelService.ChannelView；内置现金/余额行无对接字段） */
export interface PayChannelDTO {
  /** 配置单号 PCC+yyyyMMdd-6位；内置行为 null */
  configId: string | null
  channelCode: PayChannelCode | string
  channelName: string
  /** 门店码；空串 = 集团默认模板 */
  storeCode: string
  /** 系统内置渠道（cash/balance）只读，不可编辑/启停 */
  builtin: boolean
  enabled: boolean
  appId: string | null
  mchId: string | null
  /** 是否已设置 APIv3 密钥（明文绝不下发） */
  hasApiKey: boolean
  certSerial: string | null
  notifyUrl: string | null
  /** 对账方式：IMPORT 账单导入（本期唯一）/ API 自动拉取（预留）/ BUILTIN 内置 */
  reconcileMode: ReconcileMode | string
  /** 渠道手续费率（万分位，60 = 0.6%），勾兑估算手续费参考 */
  feeRate: number
  remark: string | null
}

/** upsert 渠道配置入参（对齐 PayChannelService.UpsertCmd；apiV3Key 空串/缺省 = 不修改已有密钥） */
export interface UpsertPayChannelCmd {
  channelCode: string
  /** 门店码；空串 = 集团默认模板 */
  storeCode?: string
  enabled?: boolean
  appId?: string | null
  mchId?: string | null
  /** APIv3 密钥；仅写入，留空不改 */
  apiV3Key?: string | null
  certSerial?: string | null
  notifyUrl?: string | null
  feeRate?: number
  remark?: string | null
}

/** 渠道列表（含 cash/balance 内置只读行） */
export const listPayChannels = () =>
  client.get<PayChannelDTO[]>('/txn/pay-channels')

/** 新建/更新渠道配置（upsert by channel+store；密钥空串 = 不改） */
export const upsertPayChannel = (cmd: UpsertPayChannelCmd) =>
  client.post<PayChannelDTO>('/txn/pay-channels', cmd)

/** 启用/停用切换（服务端翻转当前状态；停用不抹配置、不影响历史账） */
export const togglePayChannel = (configId: string) =>
  client.post<PayChannelDTO>(`/txn/pay-channels/${configId}/toggle`)
