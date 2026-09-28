/**
 * 术后回访 store（C 端）
 * 会员查看待回访项目、提交满意度/反馈（同步 B 端 M4-11）。
 * C-B5 切真（DESIGN-C §四端点 #8）：列表 GET /c/followups（行级隔离由后端按登录态
 * 绑定档案过滤，前端不再按 customerName 匹配），提交 POST /c/followups/:id/submit
 * （满意度 1-5 前置校验；非 PENDING 幂等直返；中文错误原码原话由 http 层弹出）。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  fetchFollowups,
  submitFollowup,
  type CFollowupItem,
  type FollowupSubmitPayload,
} from '@/api/followup'

export type Followup = CFollowupItem

export const useFollowupStore = defineStore('mp-followup', () => {
  const followups = ref<Followup[]>([])

  /** 客户自评提交（async）：成功同步本地行（响应无 note 字段，不良反应附注按原语义本地拼装） */
  async function submitByCustomer(id: string, payload: FollowupSubmitPayload): Promise<boolean> {
    try {
      const r = await submitFollowup(id, payload)
      const f = followups.value.find((x) => x.id === id)
      if (f) {
        f.status = 'DONE'
        f.satisfaction = r?.satisfaction ?? payload.satisfaction
        f.note = payload.adverseReaction && payload.adverseNote
          ? `${payload.note || ''}【不良反应】${payload.adverseNote}`.trim()
          : payload.note || null
        f.doneAt = r?.doneAt || new Date().toISOString()
      }
      return true
    } catch {
      return false
    }
  }

  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const list = await fetchFollowups()
      followups.value = list
    } catch {
      /* 未登录/后端未就绪：回滚 seeded 允许登录后 onShow 重拉（本地无假数据兜底） */
      seeded = false
    }
  }

  return { followups, submitByCustomer, seed }
})
