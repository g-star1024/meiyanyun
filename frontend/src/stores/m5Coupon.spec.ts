import { describe, it, expect } from 'vitest'
import {
  fen2yuan, yuan2fen, rate2view, view2rate,
  adaptCoupon, adaptGrant, errMsg,
  type CouponTemplate,
} from './m5Coupon'
import type { CouponTemplateDTO, CouponGrantDTO } from '@/api/marketing'

// ============================================================
// M5 营销前端适配层单测（vitest，node 环境）。
// 守护前后端金额/折扣口径契约：后端 bigint「分」、折扣 faceValue=折扣×10；
// 前端活规格用「元」、折扣 8.5。EXPIRED 不落库，由有效期日期在适配层派生。
// ============================================================

describe('金额口径换算（分 <-> 元）', () => {
  it('fen2yuan：分转元，null/undefined 回落 0', () => {
    expect(fen2yuan(19900)).toBe(199)
    expect(fen2yuan(5)).toBe(0.05)
    expect(fen2yuan(null)).toBe(0)
    expect(fen2yuan(undefined)).toBe(0)
  })

  it('yuan2fen：元转分，四舍五入避免浮点误差', () => {
    expect(yuan2fen(199)).toBe(19900)
    expect(yuan2fen(0.05)).toBe(5)
    expect(yuan2fen(NaN)).toBe(0)
    // 8.5 折场景常见金额不产生 19899 之类脏值
    expect(yuan2fen(128.64)).toBe(12864)
  })

  it('fen/yuan 往返一致（抽样）', () => {
    for (const yuan of [0.01, 9.9, 50, 128.64, 2000, 28600]) {
      expect(fen2yuan(yuan2fen(yuan))).toBeCloseTo(yuan, 2)
    }
  })
})

describe('折扣口径换算（faceValue = 折扣×10）', () => {
  it('rate2view：85 -> 8.5', () => {
    expect(rate2view(85)).toBe(8.5)
    expect(rate2view(70)).toBe(7)
    expect(rate2view(0)).toBe(0)
  })

  it('view2rate：8.5 -> 85', () => {
    expect(view2rate(8.5)).toBe(85)
    expect(view2rate(7)).toBe(70)
    expect(view2rate(0)).toBe(0)
  })
})

function baseCouponDTO(over: Partial<CouponTemplateDTO> = {}): CouponTemplateDTO {
  return {
    couponId: 'CPN1',
    couponName: '新人满减券',
    couponType: 'AMOUNT',
    faceValue: 5000,
    threshold: 10000,
    totalQty: 100,
    issuedQty: 20,
    usedQty: 5,
    status: 'ACTIVE',
    grantScope: 'ALL',
    grantScopeName: '全部客户',
    packageItems: null,
    campaignId: null,
    couponCode: 'CPN20260902-000001',
    validStart: '2026-09-01',
    validEnd: '2026-12-31',
    createdAt: '2026-09-01T10:00:00',
    ...over,
  }
}

describe('adaptCoupon：后端券模板 -> 前端活规格', () => {
  it('满减券：分转元，字段名映射，状态进行中', () => {
    const c = adaptCoupon(baseCouponDTO())
    expect(c.id).toBe('CPN1')
    expect(c.name).toBe('新人满减券')
    expect(c.type).toBe('AMOUNT')
    expect(c.value).toBe(50)        // 5000 分 = 50 元
    expect(c.threshold).toBe(100)   // 10000 分 = 100 元
    expect(c.total).toBe(100)
    expect(c.received).toBe(20)
    expect(c.used).toBe(5)
    expect(c.status).toBe('ACTIVE')
    expect(c.scope).toBe('ALL')
    expect(c.startDate).toBe('2026-09-01')
    expect(c.endDate).toBe('2026-12-31')
    expect(c.code).toBe('CPN20260902-000001')
  })

  it('折扣券：faceValue 85 派生为 8.5 折', () => {
    const c = adaptCoupon(baseCouponDTO({ couponType: 'RATE', faceValue: 85 }))
    expect(c.type).toBe('RATE')
    expect(c.value).toBe(8.5)
  })

  it('券包：packageItems JSON 解析且子项金额分转元，value 为子项总额', () => {
    const items = JSON.stringify([
      { name: '补水项目', value: 10000 },
      { name: '修护面膜', value: 5000 },
    ])
    const c = adaptCoupon(baseCouponDTO({ couponType: 'PACKAGE', faceValue: 0, packageItems: items }))
    expect(c.type).toBe('PACKAGE')
    expect(c.packageItems).toHaveLength(2)
    expect(c.packageItems?.[0]).toEqual({ name: '补水项目', value: 100 })
    expect(c.value).toBe(150) // 100 + 50 元
  })

  it('券包 packageItems 非法 JSON 时不抛错，回落 undefined', () => {
    const c = adaptCoupon(baseCouponDTO({ couponType: 'PACKAGE', packageItems: 'not-json' }))
    expect(c.packageItems).toBeUndefined()
  })

  it('EXPIRED 派生：ACTIVE 但有效期已过 -> EXPIRED', () => {
    const c = adaptCoupon(baseCouponDTO({ status: 'ACTIVE', validEnd: '2020-01-01' }))
    expect(c.status).toBe('EXPIRED')
  })

  it('未过期 ACTIVE 保持 ACTIVE', () => {
    const c = adaptCoupon(baseCouponDTO({ status: 'ACTIVE', validEnd: '2099-01-01' }))
    expect(c.status).toBe('ACTIVE')
  })

  it('无券码时 code 回落 couponId', () => {
    const c = adaptCoupon(baseCouponDTO({ couponCode: null }))
    expect(c.code).toBe('CPN1')
  })
})

describe('adaptGrant：后端发券记录 -> 前端活规格', () => {
  it('字段映射 + 时间格式化 + GRANTED 状态', () => {
    const dto: CouponGrantDTO = {
      grantId: 'GR20260902-000001',
      couponId: 'CPN1',
      couponName: '新人满减券',
      grantScope: 'NEW',
      targetName: '新客群体',
      grantCount: 50,
      status: 'GRANTED',
      grantedAt: '2026-09-02T13:44:00',
      operator: '蒋IT',
    }
    const g = adaptGrant(dto)
    expect(g.id).toBe('GR20260902-000001')
    expect(g.couponName).toBe('新人满减券')
    expect(g.scope).toBe('NEW')
    expect(g.targetName).toBe('新客群体')
    expect(g.count).toBe(50)
    expect(g.status).toBe('GRANTED')
    expect(g.grantedAt).toBe('2026-09-02 13:44')
    expect(g.operator).toBe('蒋IT')
  })

  it('非 GRANTED 状态归一为 FAILED，缺省 operator 回落系统', () => {
    const g = adaptGrant({
      grantId: 'GR2', couponId: 'CPN1', couponName: 'x', grantScope: 'ALL',
      targetName: '', grantCount: 1, status: 'FAILED', grantedAt: '', operator: '',
    })
    expect(g.status).toBe('FAILED')
    expect(g.targetName).toBe('—')
    expect(g.operator).toBe('系统')
  })
})

describe('errMsg：后端中文错误提取', () => {
  it('优先取 axios response.data.message（后端 400/409 中文）', () => {
    const e = { response: { data: { message: '券「新人满减券」库存已发完' } } }
    expect(errMsg(e)).toBe('券「新人满减券」库存已发完')
  })

  it('无 response 时取 Error.message', () => {
    expect(errMsg(new Error('无发券权限'))).toBe('无发券权限')
  })

  it('都没有时回落兜底文案', () => {
    expect(errMsg(null)).toBe('网络异常，请稍后重试')
    expect(errMsg({}, '自定义兜底')).toBe('自定义兜底')
  })
})

describe('库存与统计派生（store 纯函数口径）', () => {
  // 直接用 adaptCoupon 构造的活规格验证库存/临期口径，与 store.stockLeft 公式一致
  const stockLeft = (c: CouponTemplate) => c.total - c.received

  it('stockLeft = total - received', () => {
    const c = adaptCoupon(baseCouponDTO({ totalQty: 100, issuedQty: 20 }))
    expect(stockLeft(c)).toBe(80)
  })

  it('库存发完时 stockLeft 为 0（防超发前置判断口径）', () => {
    const c = adaptCoupon(baseCouponDTO({ totalQty: 20, issuedQty: 20 }))
    expect(stockLeft(c)).toBe(0)
  })
})
