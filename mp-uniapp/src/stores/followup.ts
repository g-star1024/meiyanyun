/**
 * 术后回访 store（C 端）
 * 会员查看待回访项目、提交满意度/反馈（同步 B 端 M4-11）。
 * 后端就绪后：列表 GET /c/followups，提交 POST /c/followups/:id/submit。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface Followup {
  id: string
  customerName: string
  project: string
  planDate: string
  status: 'PENDING' | 'DONE' | 'SKIPPED'
  satisfaction?: number
  note?: string
  doneAt?: string
}

let seq = 0
function nextId() {
  seq += 1
  return `fu-${seq}`
}

export const useFollowupStore = defineStore('mp-followup', () => {
  const followups = ref<Followup[]>([])

  function submitByCustomer(
    id: string,
    payload: { satisfaction: number; note?: string; adverseReaction?: boolean; adverseNote?: string },
  ): boolean {
    const f = followups.value.find((x) => x.id === id)
    if (!f || f.status !== 'PENDING') return false
    f.status = 'DONE'
    f.satisfaction = payload.satisfaction
    f.note = payload.adverseReaction && payload.adverseNote
      ? `${payload.note || ''}【不良反应】${payload.adverseNote}`.trim()
      : payload.note
    f.doneAt = new Date().toISOString()
    return true
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    followups.value.push(
      { id: nextId(), customerName: '陈美玲', project: '光子嫩肤（M22）', planDate: '2026-08-28T10:00:00', status: 'PENDING' },
      { id: nextId(), customerName: '陈美玲', project: '水光针（基础）', planDate: '2026-08-30T15:00:00', status: 'PENDING' },
      { id: nextId(), customerName: '陈美玲', project: 'VISIA 皮肤检测', planDate: '2026-08-20T11:00:00', status: 'DONE', satisfaction: 5, note: '恢复良好，无不适', doneAt: '2026-08-21T09:30:00' },
    )
  }

  return { followups, submitByCustomer, seed }
})
