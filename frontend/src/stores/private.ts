// ============================================================
// Private 私域运营 store（M3-13）
// 企微客户资产 / 社群 / SOP 编排。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type FriendStatus = 'FRIEND' | 'NOT_FRIEND' | 'PENDING'
export type SopChannel = 'WECHAT' | 'GROUP' | 'MOMENTS' | 'SMS'
export type SopTrigger = 'NEW_FRIEND' | 'BIRTHDAY' | 'DORMANT_30D' | 'AFTER_SERVICE' | 'MANUAL'

export interface PrivateCustomer {
  id: string
  name: string
  staff: string // 归属员工
  friendStatus: FriendStatus
  groupCount: number
  sopTasks: number
  lastInteraction: string
  conversion: number // 转化率 0-100
  tags: string[]
}

export interface Community {
  id: string
  name: string
  members: number
  activePct: number // 活跃度 0-100
  operator: string
  tags: string[]
}

export interface SopStep {
  id: string
  order: number
  channel: SopChannel
  content: string
  delay: string // 如 "1小时后"、"次日 10:00"
}

export interface SopRule {
  id: string
  name: string
  trigger: SopTrigger
  enabled: boolean
  steps: SopStep[]
  targetCount: number
  executed: number
}

const FRIEND_LABEL: Record<FriendStatus, string> = {
  FRIEND: '已添加',
  NOT_FRIEND: '未添加',
  PENDING: '待通过',
}

const CHANNEL_LABEL: Record<SopChannel, string> = {
  WECHAT: '企微单聊',
  GROUP: '群发',
  MOMENTS: '朋友圈',
  SMS: '短信',
}

const TRIGGER_LABEL: Record<SopTrigger, string> = {
  NEW_FRIEND: '新好友添加',
  BIRTHDAY: '客户生日',
  DORMANT_30D: '沉睡 30 天',
  AFTER_SERVICE: '到店后',
  MANUAL: '手动触发',
}

export const usePrivateStore = defineStore('private', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const customers = ref<PrivateCustomer[]>([])
  const communities = ref<Community[]>([])
  const sops = ref<SopRule[]>([])

  const friendCount = computed(() => customers.value.filter((c) => c.friendStatus === 'FRIEND').length)
  const activeGroups = computed(() => communities.value.filter((g) => g.activePct >= 40).length)
  const runningSops = computed(() => sops.value.filter((s) => s.enabled).length)
  const avgConversion = computed(() => {
    if (!customers.value.length) return 0
    return Math.round(customers.value.reduce((s, c) => s + c.conversion, 0) / customers.value.length)
  })

  const filterStaff = ref('ALL')
  const staffList = computed(() => ['ALL', ...Array.from(new Set(customers.value.map((c) => c.staff)))])

  const filteredCustomers = computed(() => {
    if (filterStaff.value === 'ALL') return customers.value
    return customers.value.filter((c) => c.staff === filterStaff.value)
  })

  function getCustomer(id: string) { return customers.value.find((c) => c.id === id) }
  function getSop(id: string) { return sops.value.find((s) => s.id === id) }

  function toggleSop(id: string): boolean {
    const s = sops.value.find((x) => x.id === id)
    if (!s || !auth.can('private:edit')) return false
    s.enabled = !s.enabled
    activity.log(auth.user.name, `${s.enabled ? '启用' : '停用'} SOP：${s.name}`, s.id)
    return true
  }

  function createSop(input: { name: string; trigger: SopTrigger; steps: Array<{ channel: SopChannel; content: string; delay: string }> }): SopRule | null {
    if (!auth.can('private:edit')) return null
    const sop: SopRule = {
      id: nextId('sop'),
      name: input.name,
      trigger: input.trigger,
      enabled: false,
      steps: input.steps.map((s, i) => ({ ...s, id: nextId('step'), order: i + 1 })),
      targetCount: 0,
      executed: 0,
    }
    sops.value.unshift(sop)
    activity.log(auth.user.name, `创建 SOP：${sop.name}`, sop.id)
    return sop
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = Date.now()
    const ago = (d: number) => new Date(now - d * 86400_000).toISOString()

    const custData: Array<Omit<PrivateCustomer, 'id'>> = [
      { name: '陈美玲', staff: '周敏', friendStatus: 'FRIEND', groupCount: 3, sopTasks: 2, lastInteraction: ago(1), conversion: 72, tags: ['高价值', '热玛吉'] },
      { name: '赵雨晴', staff: '周敏', friendStatus: 'FRIEND', groupCount: 2, sopTasks: 1, lastInteraction: ago(3), conversion: 58, tags: ['水光客'] },
      { name: '孙佳宁', staff: '李娜', friendStatus: 'FRIEND', groupCount: 1, sopTasks: 0, lastInteraction: ago(7), conversion: 35, tags: ['沉睡'] },
      { name: '林婉清', staff: '李娜', friendStatus: 'PENDING', groupCount: 0, sopTasks: 0, lastInteraction: ago(2), conversion: 20, tags: ['新客'] },
      { name: '周雅琴', staff: '吴桐', friendStatus: 'NOT_FRIEND', groupCount: 0, sopTasks: 0, lastInteraction: ago(30), conversion: 12, tags: ['流失'] },
      { name: '吴思涵', staff: '吴桐', friendStatus: 'FRIEND', groupCount: 2, sopTasks: 3, lastInteraction: ago(0.2), conversion: 80, tags: ['高价值', '玻尿酸'] },
      { name: '郑晓彤', staff: '周敏', friendStatus: 'FRIEND', groupCount: 4, sopTasks: 2, lastInteraction: ago(2), conversion: 65, tags: ['超声刀'] },
      { name: '黄丽萍', staff: '李娜', friendStatus: 'FRIEND', groupCount: 1, sopTasks: 1, lastInteraction: ago(5), conversion: 42, tags: ['皮秒'] },
    ]
    custData.forEach((c) => customers.value.push({ ...c, id: nextId('pc') }))

    const commData: Array<Omit<Community, 'id'>> = [
      { name: '✨ 美研VIP福利群①', members: 326, activePct: 68, operator: '周敏', tags: ['VIP', '福利'] },
      { name: '💆 水光打卡群', members: 218, activePct: 52, operator: '李娜', tags: ['项目群', '打卡'] },
      { name: '🎂 生日福利通知群', members: 412, activePct: 35, operator: '吴桐', tags: ['生日', '通知'] },
      { name: '🌿 抗衰交流群', members: 189, activePct: 74, operator: '周敏', tags: ['抗衰', '高价值'] },
      { name: '🔥 限时秒杀群', members: 502, activePct: 45, operator: '吴桐', tags: ['促销'] },
      { name: '💬 新客答疑群', members: 156, activePct: 28, operator: '李娜', tags: ['新客'] },
    ]
    commData.forEach((g) => communities.value.push({ ...g, id: nextId('grp') }))

    sops.value = [
      {
        id: nextId('sop'),
        name: '新客 3 天欢迎 SOP',
        trigger: 'NEW_FRIEND',
        enabled: true,
        targetCount: 128,
        executed: 112,
        steps: [
          { id: nextId('step'), order: 1, channel: 'WECHAT', content: '欢迎添加，发送新人 99 元体验券', delay: '立即' },
          { id: nextId('step'), order: 2, channel: 'WECHAT', content: '发送门店环境介绍与医生简介', delay: '1 小时后' },
          { id: nextId('step'), order: 3, channel: 'MOMENTS', content: '点赞首条朋友圈并互动', delay: '次日 10:00' },
        ],
      },
      {
        id: nextId('sop'),
        name: '到店后 7 天跟进',
        trigger: 'AFTER_SERVICE',
        enabled: true,
        targetCount: 86,
        executed: 80,
        steps: [
          { id: nextId('step'), order: 1, channel: 'WECHAT', content: '询问术后恢复情况，叮嘱护理', delay: '次日 10:00' },
          { id: nextId('step'), order: 2, channel: 'WECHAT', content: '发送术后护理小贴士', delay: '3 天后' },
          { id: nextId('step'), order: 3, channel: 'GROUP', content: '邀请进入项目专属群', delay: '7 天后' },
        ],
      },
      {
        id: nextId('sop'),
        name: '沉睡 30 天唤醒',
        trigger: 'DORMANT_30D',
        enabled: false,
        targetCount: 42,
        executed: 0,
        steps: [
          { id: nextId('step'), order: 1, channel: 'WECHAT', content: '专属优惠券 + 近期新项目推荐', delay: '立即' },
          { id: nextId('step'), order: 2, channel: 'SMS', content: '短信提醒券即将到期', delay: '5 天后' },
        ],
      },
      {
        id: nextId('sop'),
        name: '生日月关怀',
        trigger: 'BIRTHDAY',
        enabled: true,
        targetCount: 24,
        executed: 18,
        steps: [
          { id: nextId('step'), order: 1, channel: 'WECHAT', content: '生日祝福 + 生日礼券', delay: '生日当天 09:00' },
          { id: nextId('step'), order: 2, channel: 'MOMENTS', content: '送上生日祝福朋友圈互动', delay: '当日' },
        ],
      },
    ]
  }

  return {
    customers, communities, sops, filterStaff, staffList,
    friendCount, activeGroups, runningSops, avgConversion,
    filteredCustomers,
    getCustomer, getSop, toggleSop, createSop, seed,
    FRIEND_LABEL, CHANNEL_LABEL, TRIGGER_LABEL,
  }
})
