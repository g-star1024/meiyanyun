/**
 * 会员互动 store（C 端只读）
 * C-B6 切真（DESIGN-C §四端点 #9）：专属顾问 GET /c/advisor（customer.owner_staff_id
 * → staff 在职投影；未分配/档案不可读 data=null，页面空态「专属顾问待分配」；
 * years/tags/served/rating 系无源字段如实空值 §七），邀请 GET /c/invite
 * （code=本人会员编号，invited/visited/points 三统计全源 referral/referral_reward；
 * 三档奖励文案留前端常量 §七——referral_campaign 无奖励规则字段）。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { http } from '@/utils/request'

export interface Advisor {
  name: string
  title: string
  avatar: string
  years?: string | null
  tags?: string[] | null
  served?: string | null
  rating?: string | null
}

export interface InviteInfo {
  code: string
  invited: number
  visited: number
  points: number
}

export const useEngageStore = defineStore('mp-engage', () => {
  const advisor = ref<Advisor | null>(null)
  const invite = ref<InviteInfo | null>(null)

  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const [a, i] = await Promise.all([
        http.get<Advisor | null>('/c/advisor', { silent: true }),
        http.get<InviteInfo | null>('/c/invite', { silent: true }),
      ])
      advisor.value = a && a.name
        ? { ...a, tags: a.tags ?? [], years: a.years ?? '', served: a.served ?? '', rating: a.rating ?? '' }
        : null
      invite.value = i && i.code ? i : null
    } catch {
      /* 未登录/后端未就绪：回滚 seeded 允许登录后 onShow 重拉（空态如实呈现） */
      seeded = false
    }
  }

  return { advisor, invite, seed }
})
