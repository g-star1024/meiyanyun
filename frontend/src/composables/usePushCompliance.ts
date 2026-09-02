import type { PushQuota } from '@/api/marketing'

// ============================================================
// M5-03 客户触达合规闸门（纯逻辑，可单测）。
// 周频规则与后端 MarketingService 一致：同一客户 7 天内触达 ≤ weeklyLimit 条；
// 文案违禁词以发送时后端强校验为准，前端预检仅作实时提示。
// ============================================================

/** 推送文案长度上限（与后端 push content varchar(256) 一致） */
export const PUSH_CONTENT_MAX = 256

/** 周频用量百分比：sentLast7Days / weeklyLimit，封顶 100；无配额配置时为 0 */
export function quotaUsagePct(q: PushQuota | null): number {
  if (!q || q.weeklyLimit <= 0) return 0
  return Math.min(100, Math.round((q.sentLast7Days / q.weeklyLimit) * 100))
}

/** 周频进度条色调：>=100 危险红、>=70 预警黄、否则正常绿 */
export function quotaTone(pct: number): 'danger' | 'warn' | 'ok' {
  if (pct >= 100) return 'danger'
  if (pct >= 70) return 'warn'
  return 'ok'
}

export interface PushFormInput {
  hasCustomer: boolean
  content: string
  wordHits: string[]
  quota: PushQuota | null
}

export interface PushGateResult {
  ok: boolean
  error: string
}

/**
 * 发送前置校验（前端软闸门，后端 /push 仍做强校验）：
 * 选客 → 文案非空 → 长度 ≤ 256 → 无违禁词命中 → 周频有余量。
 * 返回第一条不通过的原因；全部通过返回 ok。
 */
export function checkPushReady(input: PushFormInput): PushGateResult {
  if (!input.hasCustomer) return { ok: false, error: '请先选择客户' }
  const text = input.content.trim()
  if (!text) return { ok: false, error: '请输入推送内容' }
  if (text.length > PUSH_CONTENT_MAX) return { ok: false, error: `推送内容最多 ${PUSH_CONTENT_MAX} 字` }
  if (input.wordHits.length) {
    return { ok: false, error: `文案命中违禁词：${input.wordHits.join('、')}，请修改后再发` }
  }
  if (input.quota && input.quota.remaining <= 0) {
    return { ok: false, error: '该客户本周触达已达周频上限，请下周再发' }
  }
  return { ok: true, error: '' }
}
