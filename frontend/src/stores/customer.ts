// ============================================================
// Customer 聚合 store
// 职责：客户档案、归属（ownerStaffId，SELF 数据域依据）、转介绍、撞单关联/合并。
// 对齐 docs/domain-model.md §2.2、business-flows §2.9/§2.10。
// 撞单：系统只产生疑似关联(CustomerLink)，不自动合并；合并需 customer:merge 权限人工审批。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type {
  Customer, CustomerLink, CustomerMerge,
  CustomerTransaction, ServiceTrackItem, CustomerCard,
  CustomerPhoto, SkinReport,
} from '@/types/domain'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useSettingsStore } from './settings'

const STORE_ID = 'store-jingan'

export const useCustomerStore = defineStore('customer', () => {
  const auth = useAuthStore()
  const settings = useSettingsStore()
  const activity = useActivityStore()

  const customers = ref<Customer[]>([
    {
      id: 'C-201', name: '王小姐', avatarLetter: '王', phoneMask: '138****2046', phone: '13812342046',
      channel: 'WALK_IN', level: 'KA', tags: ['高价值客户', '撞单归属·李咨询师', '人脉客已合并'],
      storeId: STORE_ID, ownerStaffId: 'staff-lin',
      memberNo: 'M20210308', registerDate: '2021-03-08',
      totalSpend: 86400, ltv: 152000, visitCount: 42, dormantDays: 96,
      cardBalance: 12600, points: 8640, valueScore: 92,
      rfm: { r: 85, f: 92, m: 78, loyalty: 88, active: 76 },
      lifecycleStage: '维持+企微服务', lifecycleStep: 4,
      preferences: [
        { label: '抗衰', weight: 85 },
        { label: '光电', weight: 8 },
        { label: '注射', weight: 5 },
        { label: '皮肤护理', weight: 2 },
      ],
      allergies: ['利多卡因过敏史 — 禁用含该成分产品'],
      rights: ['专属咨询师 1对1服务', '项目折扣 8.5 折', '生日当月双倍积分'],
      nextAppointment: { date: '2026-09-15', project: '超声炮复购' },
      comparePhotos: [
        { label: '治疗前 Before', date: '2025-11', tone: 'before' },
        { label: '治疗后 After', date: '2026-07', tone: 'after' },
      ],
      reminders: [
        { level: 'danger', text: '撞单冲突消解：待李咨询师确认归属' },
        { level: 'warning', text: '沉睡唤醒：96 天未到店，触达进行中' },
        { level: 'success', text: '复购窗口：超声炮 30 天内可复购' },
      ],
      lastVisitAt: '2026-05-21',
    },
    {
      id: 'C-202', name: '李女士', avatarLetter: '李', phoneMask: '139****8821', phone: '13987658821',
      channel: 'REFERRAL', level: 'A', tags: ['转介绍', '抗衰'],
      storeId: STORE_ID, ownerStaffId: 'staff-lin',
      memberNo: 'M20220412', registerDate: '2022-04-12',
      totalSpend: 45600, ltv: 88000, visitCount: 28, dormantDays: 32,
      cardBalance: 6800, points: 4560, valueScore: 78,
      rfm: { r: 72, f: 68, m: 65, loyalty: 80, active: 70 },
      lifecycleStage: '胶原水光复购', lifecycleStep: 3,
      preferences: [
        { label: '抗衰', weight: 70 },
        { label: '水光', weight: 20 },
        { label: '皮肤护理', weight: 10 },
      ],
      allergies: [],
      rights: ['专属咨询师 1对1服务', '项目折扣 9 折'],
      nextAppointment: { date: '2026-09-22', project: '胶原水光' },
      reminders: [
        { level: 'warning', text: '复购窗口：胶原水光疗程剩余 2 次' },
      ],
      lastVisitAt: '2026-07-24',
    },
    {
      id: 'C-203', name: '张同学', avatarLetter: '张', phoneMask: '137****5510', phone: '13700005510',
      channel: 'ONLINE_APPT', level: 'C', tags: ['学生', '痤疮'],
      storeId: STORE_ID,
      memberNo: 'M20250901', registerDate: '2025-09-01',
      totalSpend: 3200, ltv: 12000, visitCount: 6, dormantDays: 12,
      cardBalance: 0, points: 320, valueScore: 42,
      rfm: { r: 88, f: 35, m: 20, loyalty: 30, active: 65 },
      lifecycleStage: '生美体验', lifecycleStep: 0,
      preferences: [
        { label: '祛痘', weight: 90 },
        { label: '皮肤护理', weight: 10 },
      ],
      allergies: [],
      rights: ['新客体验价'],
      nextAppointment: { date: '2026-09-05', project: '祛痘护理' },
      reminders: [
        { level: 'success', text: '新客体验：第二次到店享体验价' },
      ],
      lastVisitAt: '2026-08-13',
    },
    {
      id: 'C-204', name: '赵女士', avatarLetter: '赵', phoneMask: '136****7742', phone: '13611117742',
      channel: 'ONLINE_APPT', level: 'B', tags: ['抗衰', '热玛吉意向'],
      storeId: STORE_ID, ownerStaffId: 'staff-lin',
      memberNo: 'M20230618', registerDate: '2023-06-18',
      totalSpend: 21800, ltv: 46000, visitCount: 15, dormantDays: 45,
      cardBalance: 0, points: 2180, valueScore: 66,
      rfm: { r: 58, f: 50, m: 60, loyalty: 62, active: 55 },
      lifecycleStage: '抗衰项目评估', lifecycleStep: 2,
      preferences: [
        { label: '抗衰', weight: 80 },
        { label: '光电', weight: 15 },
        { label: '皮肤护理', weight: 5 },
      ],
      allergies: [],
      rights: ['专属咨询师 1对1服务'],
      reminders: [
        { level: 'warning', text: '面部活动性痤疮：热玛吉需先抗炎评估' },
      ],
      lastVisitAt: '2026-07-10',
    },
    {
      id: 'C-205', name: '林薇', avatarLetter: '林', phoneMask: '135****3318', phone: '13500003318',
      channel: 'ONLINE_APPT', level: 'NEW', tags: ['新客', '补水', '屏障受损'],
      storeId: STORE_ID, ownerStaffId: 'staff-lin',
      memberNo: 'M20260820', registerDate: '2026-08-20',
      totalSpend: 0, ltv: 2000, visitCount: 1, dormantDays: 0,
      cardBalance: 0, points: 0, valueScore: 38,
      rfm: { r: 90, f: 10, m: 15, loyalty: 20, active: 80 },
      lifecycleStage: '生美体验', lifecycleStep: 0,
      preferences: [
        { label: '皮肤护理', weight: 70 },
        { label: '水光', weight: 30 },
      ],
      allergies: [],
      rights: ['新客体验价'],
      reminders: [
        { level: 'success', text: '新客首单：基础水光体验 ¥780' },
      ],
      lastVisitAt: '2026-08-28',
    },
    {
      id: 'C-206', name: '陈雨', avatarLetter: '陈', phoneMask: '138****6620', phone: '13800006620',
      channel: 'WALK_IN', level: 'C', tags: ['痤疮', '毛孔', '果酸焕肤'],
      storeId: STORE_ID, ownerStaffId: 'staff-lin',
      memberNo: 'M20260615', registerDate: '2026-06-15',
      totalSpend: 680, ltv: 6000, visitCount: 3, dormantDays: 5,
      cardBalance: 0, points: 68, valueScore: 45,
      rfm: { r: 80, f: 30, m: 25, loyalty: 40, active: 70 },
      lifecycleStage: '生美体验', lifecycleStep: 1,
      preferences: [
        { label: '祛痘', weight: 75 },
        { label: '光电', weight: 25 },
      ],
      allergies: [],
      rights: ['新客体验价'],
      reminders: [
        { level: 'warning', text: '果酸焕肤后注意保湿防晒' },
      ],
      lastVisitAt: '2026-08-23',
    },
    {
      id: 'C-207', name: '周婷', avatarLetter: '周', phoneMask: '139****4471', phone: '13900004471',
      channel: 'MARKETING', level: 'B', tags: ['光老化', '光子嫩肤', '暗沉'],
      storeId: STORE_ID, ownerStaffId: 'staff-lin',
      memberNo: 'M20250322', registerDate: '2025-03-22',
      totalSpend: 12800, ltv: 32000, visitCount: 12, dormantDays: 18,
      cardBalance: 0, points: 1280, valueScore: 62,
      rfm: { r: 70, f: 55, m: 58, loyalty: 55, active: 65 },
      lifecycleStage: '皮肤维养', lifecycleStep: 2,
      preferences: [
        { label: '光电', weight: 70 },
        { label: '皮肤护理', weight: 30 },
      ],
      allergies: [],
      rights: ['项目折扣 9.5 折'],
      reminders: [
        { level: 'success', text: '光子嫩肤疗程进行中，已支付待治疗' },
      ],
      lastVisitAt: '2026-08-10',
    },
    {
      id: 'C-208', name: '吴静', avatarLetter: '吴', phoneMask: '137****9056', phone: '13700009056',
      channel: 'REFERRAL', level: 'A', tags: ['转介绍', '水光疗程', '补水'],
      storeId: STORE_ID, ownerStaffId: 'staff-lin',
      memberNo: 'M20241108', registerDate: '2024-11-08',
      totalSpend: 32600, ltv: 58000, visitCount: 21, dormantDays: 2,
      cardBalance: 3200, points: 3260, valueScore: 74,
      rfm: { r: 88, f: 65, m: 62, loyalty: 72, active: 78 },
      lifecycleStage: '胶原水光复购', lifecycleStep: 3,
      preferences: [
        { label: '水光', weight: 80 },
        { label: '皮肤护理', weight: 20 },
      ],
      allergies: [],
      rights: ['专属咨询师 1对1服务', '项目折扣 9 折'],
      reminders: [
        { level: 'success', text: '水光 3 次套餐治疗中（第 2/3 次）' },
      ],
      lastVisitAt: '2026-08-28',
    },
  ])

  // M3-03 360 画像：消费记录 / 服务轨迹 / 卡项
  const transactions = ref<CustomerTransaction[]>([])
  const serviceTrack = ref<ServiceTrackItem[]>([])
  const cards = ref<CustomerCard[]>([])
  // 档案照 / 对比照 + 结构化面诊报告
  const photos = ref<CustomerPhoto[]>([])
  const skinReports = ref<SkinReport[]>([])

  const links = ref<CustomerLink[]>([])
  const merges = ref<CustomerMerge[]>([])

  function nameOf(id: string) {
    if (id === 'WALKIN') return '散客'
    return customers.value.find((c) => c.id === id)?.name || id
  }
  function phoneOf(id: string) {
    return customers.value.find((c) => c.id === id)?.phone || ''
  }
  function get(id: string) {
    return customers.value.find((c) => c.id === id && !c.masterId)
  }

  /** SELF 数据域：本人客户 = ownerStaffId 命中，或在有效期内经审核的转介绍客户 */
  const mine = computed(() => {
    const me = auth.user.staffId
    const now = Date.now()
    return customers.value.filter((c) => {
      if (c.masterId) return false
      if (c.ownerStaffId === me) return true
      if (c.referralByCustomerId && c.referralExpiresAt) {
        return new Date(c.referralExpiresAt).getTime() > now
      }
      return false
    })
  })

  function create(input: Omit<Customer, 'id' | 'storeId' | 'avatarLetter'> & { id?: string }) {
    const id = input.id || nextId('C')
    const c: Customer = {
      ...input,
      id,
      storeId: STORE_ID,
      avatarLetter: input.name?.charAt(0) || '客',
    }
    customers.value.unshift(c)
    detectDuplicate(c)
    activity.log(auth.user.name, `新建客户 ${c.name}`, c.id)
    return c
  }

  /** 极简撞单识别：同手机号视为疑似重复（真实场景加设备/证件/姓名生日） */
  function detectDuplicate(c: Customer) {
    const dup = customers.value.find((x) => x.id !== c.id && !x.masterId && x.phone && c.phone && x.phone === c.phone)
    if (dup) {
      links.value.unshift({
        id: nextId('link'), customerIdA: c.id, customerIdB: dup.id,
        matchReason: ['PHONE'], score: 0.92,
      })
      activity.log('系统', `发现疑似重复：${c.name} 与 ${dup.name}（同手机号），待人工确认`, c.id)
    }
  }

  function search(keyword: string) {
    const k = keyword.trim()
    if (!k) return customers.value.filter((c) => !c.masterId)
    return customers.value.filter(
      (c) => !c.masterId && (c.name.includes(k) || c.phone?.includes(k) || c.phoneMask.includes(k)),
    )
  }

  /** 确认转介绍归属：被介绍客户在有效期内归介绍人咨询师 */
  function confirmReferral(newCustomerId: string, referrerCustomerId: string) {
    if (!settings.system.referral.requireConfirm) return
    const referrer = customers.value.find((c) => c.id === referrerCustomerId)
    const target = customers.value.find((c) => c.id === newCustomerId)
    if (!referrer || !target) return
    target.referralByCustomerId = referrerCustomerId
    target.ownerStaffId = referrer.ownerStaffId
    const days = settings.system.referral.ownershipValidDays
    target.referralExpiresAt = new Date(Date.now() + days * 86400000).toISOString()
    activity.log(auth.user.name, `确认转介绍：${target.name} 归属 ${referrer.ownerStaffId ? '介绍人咨询师' : '—'}（${days} 天有效）`, target.id)
  }

  /** 受控合并：需 customer:merge 权限；保留 master，作废 mergedIds，留痕 */
  function proposeMerge(masterId: string, mergedIds: string[], reason: string, evidence: string[]) {
    if (!auth.can('customer:merge')) {
      console.warn('[customer] 无 customer:merge 权限')
      return false
    }
    const m: CustomerMerge = {
      id: nextId('merge'), masterId, mergedIds, reason, evidence,
      status: 'APPROVED', requestedBy: auth.user.name, approvedBy: auth.user.name,
      executedAt: new Date().toISOString(),
    }
    // 演示期：有合并权限即直接执行（真实环境走审批流 PROPOSED→REVIEWING→APPROVED→MERGED）
    mergedIds.forEach((id) => {
      const c = customers.value.find((x) => x.id === id)
      if (c) {
        c.masterId = masterId
        if (!c.mergedFrom) c.mergedFrom = []
      }
    })
    const master = customers.value.find((c) => c.id === masterId)
    if (master) master.mergedFrom = [...(master.mergedFrom || []), ...mergedIds]
    merges.value.unshift(m)
    activity.log(auth.user.name, `合并客户 ${mergedIds.join(',')} → ${masterId}（留痕可追溯）`, masterId)
    return m
  }

  /** 开发期种子：补几条疑似重复关联（不自动合并，待人工确认） */
  let graphSeeded = false
  function seedGraph() {
    if (graphSeeded) return
    graphSeeded = true
    // 造两个重复客户（同手机号不同 ID）
    if (!customers.value.some((c) => c.id === 'C-210')) {
      customers.value.push({
        id: 'C-210', name: '王美丽', avatarLetter: '美', phoneMask: '138****2046', phone: '13812342046',
        channel: 'ONLINE_APPT', level: 'C', tags: ['线上'], storeId: STORE_ID, ownerStaffId: 'staff-lin',
        totalSpend: 0, visitCount: 1, dormantDays: 320, cardBalance: 0, points: 0,
        registerDate: '2025-07-20', lastVisitAt: '2025-07-20',
      })
    }
    if (!customers.value.some((c) => c.id === 'C-211')) {
      customers.value.push({
        id: 'C-211', name: '李女士(手机)', avatarLetter: '李', phoneMask: '139****8821', phone: '13987658821',
        channel: 'WALK_IN', level: 'B', tags: ['到店'], storeId: STORE_ID,
        totalSpend: 3200, visitCount: 4, dormantDays: 55, cardBalance: 800, points: 320,
        registerDate: '2025-08-02', lastVisitAt: '2025-08-02',
      })
    }
    if (!links.value.some((l) => l.customerIdA === 'C-201')) {
      links.value.push({
        id: nextId('link'), customerIdA: 'C-201', customerIdB: 'C-210',
        matchReason: ['PHONE', 'NAME_BIRTHDAY'], score: 0.95,
      })
    }
    if (!links.value.some((l) => l.customerIdA === 'C-202')) {
      links.value.push({
        id: nextId('link'), customerIdA: 'C-202', customerIdB: 'C-211',
        matchReason: ['PHONE'], score: 0.88,
      })
    }
  }

  /** 画像 / 360 种子数据（消费记录/服务轨迹/卡项） */
  let profileSeeded = false
  function seedProfile() {
    seedGraph()
    if (profileSeeded) return
    profileSeeded = true

    if (transactions.value.length === 0) {
      const base: CustomerTransaction[] = [
        { id: nextId('tx'), customerId: 'C-201', project: '热玛吉 FLX · 全面部抗衰', store: '上海静安旗舰店', operator: '张敏', amount: -12800, at: '2026-08-10 14:30', payMethod: '储值卡支付' },
        { id: nextId('tx'), customerId: 'C-201', project: '海菲秀 · 深层清洁补水', store: '上海静安旗舰店', operator: '王磊', amount: -2800, at: '2026-07-28 10:15', payMethod: '微信支付' },
        { id: nextId('tx'), customerId: 'C-201', project: '储值卡充值（钻石卡）', store: '上海静安旗舰店', operator: '夏沫', amount: 20000, at: '2026-07-25 16:42', payMethod: '微信支付', orderNo: 'TX20260725001' },
        { id: nextId('tx'), customerId: 'C-201', project: '超声炮 · 下颌缘提升', store: '上海静安旗舰店', operator: '顾屿', amount: -12800, at: '2026-06-12 15:00', payMethod: '储值卡支付' },
        { id: nextId('tx'), customerId: 'C-201', project: '胶原水光 · 基础疗程', store: '上海静安旗舰店', operator: '陈雅琳', amount: -6800, at: '2026-05-21 11:20', payMethod: '银行卡' },
        { id: nextId('tx'), customerId: 'C-201', project: '光子嫩肤（全模式）', store: '上海静安旗舰店', operator: '张敏', amount: -3800, at: '2026-04-18 14:00', payMethod: '储值卡支付' },
      ]
      transactions.value.push(...base)
    }

    if (serviceTrack.value.length === 0) {
      serviceTrack.value.push(
        { id: nextId('st'), customerId: 'C-201', date: '2025-11', title: '生美体验首次到店', detail: '体验光子嫩肤，建立客户档案', operator: '林微', tone: 'brand' },
        { id: nextId('st'), customerId: 'C-201', date: '2026-01', title: '首诊直客', detail: '咨询师林微跟进，完成抗衰方案规划', operator: '林微', tone: 'teal' },
        { id: nextId('st'), customerId: 'C-201', date: '2026-03', title: '超声炮升单', detail: '消费 ¥12,800，升级钻石卡', operator: '顾屿', tone: 'orange' },
        { id: nextId('st'), customerId: 'C-201', date: '2026-05', title: '胶原水光复购', detail: '购买胶原水光疗程 5 次', operator: '陈雅琳', tone: 'purple' },
        { id: nextId('st'), customerId: 'C-201', date: '2026-08', title: '维持+企微服务', detail: '定期回访与复购提醒，当前阶段', operator: '林微', tone: 'brand' },
      )
    }

    if (cards.value.length === 0) {
      cards.value.push(
        { id: nextId('card'), customerId: 'C-201', name: '钻石储值卡', remaining: '¥12,600', total: '¥50,000', expiresAt: '2027-07-25', status: 'active' },
        { id: nextId('card'), customerId: 'C-201', name: '胶原水光 5 次卡', remaining: '2 次', total: '5 次', expiresAt: '2026-11-21', status: 'expiring' },
        { id: nextId('card'), customerId: 'C-201', name: '光子嫩肤 3 次卡', remaining: '0 次', total: '3 次', expiresAt: '2026-04-18', status: 'expired' },
      )
    }
  }

  /** 按客户聚合（种子数据带 customerId；无归属的旧数据回退给 C-201） */
  function txOf(customerId: string) {
    return transactions.value.filter((t) => !t.customerId || t.customerId === customerId)
  }
  function trackOf(customerId: string) {
    return serviceTrack.value.filter((t) => !t.customerId || t.customerId === customerId)
  }
  function cardsOf(customerId: string) {
    return cards.value.filter((t) => !t.customerId || t.customerId === customerId)
  }
  function photosOf(customerId: string) {
    return photos.value.filter((p) => p.customerId === customerId)
  }
  function skinReportsOf(customerId: string) {
    return skinReports.value.filter((r) => r.customerId === customerId)
  }

  /** 上传档案照 / 面诊对比照（演示用 base64；生产走对象存储） */
  function addPhoto(input: {
    customerId: string; category: CustomerPhoto['category']; part: string
    dataUrl: string; consultId?: string; desensitized?: boolean
  }): CustomerPhoto {
    const p: CustomerPhoto = {
      id: nextId('photo'),
      customerId: input.customerId,
      consultId: input.consultId,
      category: input.category,
      part: input.part,
      dataUrl: input.dataUrl,
      takenAt: new Date().toISOString(),
      takenByName: auth.user.name,
      desensitized: input.desensitized ?? true,
    }
    photos.value.unshift(p)
    activity.log(auth.user.name, `上传${input.category === 'before' ? '面诊/术前' : input.category === 'after' ? '术后/复查' : '档案'}照（${input.part}），已脱敏加水印`, input.customerId)
    return p
  }

  /** 生成结构化面诊 / 皮肤检测报告 */
  function addSkinReport(input: Omit<SkinReport, 'id' | 'checkedAt' | 'checkedByName'>): SkinReport {
    const r: SkinReport = {
      ...input,
      id: nextId('skin'),
      checkedAt: new Date().toISOString(),
      checkedByName: auth.user.name,
    }
    skinReports.value.unshift(r)
    activity.log(auth.user.name, `生成面诊/皮肤检测报告（${r.device} · ${r.skinType}）`, input.customerId)
    return r
  }

  /** 标记疑似重复为非重复（从 links 中移除，演示） */
  function dismissLink(id: string) {
    const idx = links.value.findIndex((l) => l.id === id)
    if (idx >= 0) {
      links.value.splice(idx, 1)
      activity.log(auth.user.name, `标记疑似重复为非重复（${id}）`)
    }
  }

  return {
    customers, links, merges, mine,
    transactions, serviceTrack, cards, photos, skinReports,
    get, nameOf, phoneOf, search, create,
    confirmReferral, proposeMerge, seedGraph, seedProfile,
    txOf, trackOf, cardsOf, photosOf, skinReportsOf, addPhoto, addSkinReport, dismissLink,
  }
})
