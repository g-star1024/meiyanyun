import { describe, it, expect } from 'vitest'
import { adaptStats, emptyStats } from './m5Roi'
import type { MarketingStatsDTO } from '@/api/marketing'

// ============================================================
// M5-06 投放 ROI 统计适配层单测（vitest，node 环境）。
// 守护前后端统计口径契约：后端金额 bigint「分」、比率 0~1 四位小数；
// 前端金额展示用「元」、类型/状态码映射为中文标签。
// 行级达成率 = 实际成交 / 目标金额（用分单位原值计算，避免换算误差）。
// ============================================================

function baseStatsDTO(over: Partial<MarketingStatsDTO> = {}): MarketingStatsDTO {
  return {
    coupon: {
      couponKinds: 1,
      totalStock: 100,
      totalIssued: 60,
      totalUsed: 15,
      writeoffRate: 0.25,
      grantBatches: 1,
      grantedPcs: 60,
      rows: [
        {
          couponId: 'CPN20260902-000001',
          couponName: '秋嗨购满2000减500券',
          couponType: 'AMOUNT',
          status: 'ACTIVE',
          totalQty: 100,
          issuedQty: 60,
          usedQty: 15,
          writeoffRate: 0.25,
          campaignId: null,
        },
      ],
    },
    campaign: {
      campaignCount: 1,
      runningCount: 1,
      totalSpent: 2000000,
      totalActualAmount: 6800000,
      totalBudget: 5000000,
      totalTargetAmount: 20000000,
      totalNewCustomers: 38,
      overallRoi: 3.4,
      achieveRate: 0.34,
      rows: [
        {
          campaignId: 'CP20260902-000001',
          campaignName: '9月秋嗨购医美节',
          campaignType: 'FULL_REDUCE',
          status: 'RUNNING',
          spent: 2000000,
          actualAmount: 6800000,
          budget: 5000000,
          targetAmount: 20000000,
          newCustomers: 38,
          roi: 3.4,
        },
      ],
    },
    ...over,
  }
}

describe('emptyStats：后端空表 / 请求失败回落', () => {
  it('券与活动两块汇总全 0 且明细为空数组（不返回 null/undefined）', () => {
    const s = emptyStats()
    expect(s.coupon.couponKinds).toBe(0)
    expect(s.coupon.totalStock).toBe(0)
    expect(s.coupon.totalUsed).toBe(0)
    expect(s.coupon.writeoffRate).toBe(0)
    expect(s.coupon.rows).toEqual([])
    expect(s.campaign.campaignCount).toBe(0)
    expect(s.campaign.totalSpent).toBe(0)
    expect(s.campaign.overallRoi).toBe(0)
    expect(s.campaign.rows).toEqual([])
  })
})

describe('adaptStats：发券核销统计（券块）', () => {
  it('汇总标量原样透传（数量/比率不做单位换算）', () => {
    const v = adaptStats(baseStatsDTO())
    expect(v.coupon.couponKinds).toBe(1)
    expect(v.coupon.totalStock).toBe(100)
    expect(v.coupon.totalIssued).toBe(60)
    expect(v.coupon.totalUsed).toBe(15)
    expect(v.coupon.writeoffRate).toBe(0.25)
    expect(v.coupon.grantBatches).toBe(1)
    expect(v.coupon.grantedPcs).toBe(60)
  })

  it('券行字段名映射 + 类型/状态码转中文', () => {
    const row = adaptStats(baseStatsDTO()).coupon.rows[0]
    expect(row.id).toBe('CPN20260902-000001')
    expect(row.name).toBe('秋嗨购满2000减500券')
    expect(row.type).toBe('满减券')
    expect(row.status).toBe('进行中')
    expect(row.total).toBe(100)
    expect(row.issued).toBe(60)
    expect(row.used).toBe(15)
    expect(row.writeoffRate).toBe(0.25)
  })

  it('折扣券/券包类型与草稿/停用状态均有中文映射', () => {
    const dto = baseStatsDTO()
    dto.coupon.rows = [
      { ...dto.coupon.rows[0], couponId: 'R1', couponType: 'RATE', status: 'DRAFT' },
      { ...dto.coupon.rows[0], couponId: 'P1', couponType: 'PACKAGE', status: 'DISABLED' },
    ]
    const rows = adaptStats(dto).coupon.rows
    expect(rows[0].type).toBe('折扣券')
    expect(rows[0].status).toBe('草稿')
    expect(rows[1].type).toBe('券包')
    expect(rows[1].status).toBe('已停用')
  })

  it('后端返回未知类型/状态码时回落原始码（不渲染成 undefined）', () => {
    const dto = baseStatsDTO()
    dto.coupon.rows = [{
      ...dto.coupon.rows[0], couponType: 'FUTURE_X' as never, status: 'UNKNOWN' as never,
    }]
    const row = adaptStats(dto).coupon.rows[0]
    expect(row.type).toBe('FUTURE_X')
    expect(row.status).toBe('UNKNOWN')
  })
})

describe('adaptStats：活动维度 ROI（活动块）', () => {
  it('汇总金额分→元，数量/ROI/比率透传', () => {
    const c = adaptStats(baseStatsDTO()).campaign
    expect(c.totalSpent).toBe(20000)       // 2,000,000 分 = 20,000 元
    expect(c.totalActualAmount).toBe(68000)
    expect(c.totalTargetAmount).toBe(200000)
    expect(c.campaignCount).toBe(1)
    expect(c.runningCount).toBe(1)
    expect(c.totalNewCustomers).toBe(38)
    expect(c.overallRoi).toBe(3.4)
    expect(c.achieveRate).toBe(0.34)
  })

  it('活动行金额分→元 + 类型/状态码转中文 + ROI 透传', () => {
    const row = adaptStats(baseStatsDTO()).campaign.rows[0]
    expect(row.id).toBe('CP20260902-000001')
    expect(row.name).toBe('9月秋嗨购医美节')
    expect(row.type).toBe('满减')
    expect(row.status).toBe('进行中')
    expect(row.spent).toBe(20000)
    expect(row.actualAmount).toBe(68000)
    expect(row.targetAmount).toBe(200000)
    expect(row.roi).toBe(3.4)
    expect(row.newCustomers).toBe(38)
  })

  it('行级达成率 = 实际成交/目标金额（分单位原值），保留四位小数', () => {
    const row = adaptStats(baseStatsDTO()).campaign.rows[0]
    // 6,800,000 / 20,000,000 = 0.34
    expect(row.achieveRate).toBe(0.34)
  })

  it('目标金额为 0 时达成率回落 0（防除零）', () => {
    const dto = baseStatsDTO()
    dto.campaign.rows = [{ ...dto.campaign.rows[0], targetAmount: 0, actualAmount: 0 }]
    const row = adaptStats(dto).campaign.rows[0]
    expect(row.achieveRate).toBe(0)
  })

  it('达成率四位小数四舍五入（如 1/3 场景）', () => {
    const dto = baseStatsDTO()
    dto.campaign.rows = [{ ...dto.campaign.rows[0], targetAmount: 3, actualAmount: 1 }]
    const row = adaptStats(dto).campaign.rows[0]
    // Math.round(1/3 * 10000)/10000 = 0.3333
    expect(row.achieveRate).toBe(0.3333)
  })

  it('非满减类型（会员日/新客礼等）映射中文', () => {
    const dto = baseStatsDTO()
    dto.campaign.rows = [
      { ...dto.campaign.rows[0], campaignId: 'V1', campaignType: 'VIP_DAY', status: 'ENDED' },
      { ...dto.campaign.rows[0], campaignId: 'N1', campaignType: 'NEWBIE', status: 'SCHEDULED' },
    ]
    const rows = adaptStats(dto).campaign.rows
    expect(rows[0].type).toBe('会员日')
    expect(rows[0].status).toBe('已结束')
    expect(rows[1].type).toBe('新客礼')
    expect(rows[1].status).toBe('待开始')
  })
})

describe('adaptStats：与演示数据同口径的端到端换算', () => {
  it('秋嗨购场景：核销率 25% / 综合 ROI 3.4 / 达成率 34%（与浏览器实测一致）', () => {
    const v = adaptStats(baseStatsDTO())
    expect(v.coupon.writeoffRate).toBeCloseTo(0.25, 4)
    expect(v.campaign.overallRoi).toBe(3.4)
    expect(v.campaign.achieveRate).toBeCloseTo(0.34, 4)
    // 页面 pct() 口径：0.25 -> '25.0%'、0.34 -> '34.0%'
    const pct = (r: number) => `${(r * 100).toFixed(1)}%`
    expect(pct(v.coupon.writeoffRate)).toBe('25.0%')
    expect(pct(v.campaign.achieveRate)).toBe('34.0%')
  })
})
