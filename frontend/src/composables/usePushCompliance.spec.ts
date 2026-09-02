import { describe, it, expect } from 'vitest'
import type { PushQuota } from '@/api/marketing'
import {
  PUSH_CONTENT_MAX,
  quotaUsagePct,
  quotaTone,
  checkPushReady,
  type PushFormInput,
} from './usePushCompliance'

function quota(over?: Partial<PushQuota>): PushQuota {
  return {
    customerId: 'C001',
    sentLast7Days: 0,
    weeklyLimit: 3,
    remaining: 3,
    ...over,
  }
}

function form(over?: Partial<PushFormInput>): PushFormInput {
  return {
    hasCustomer: true,
    content: '您有一张专属优惠券待领取，到店即可使用',
    wordHits: [],
    quota: quota(),
    ...over,
  }
}

describe('quotaUsagePct 周频用量百分比', () => {
  it('无配额数据返回 0', () => {
    expect(quotaUsagePct(null)).toBe(0)
  })

  it('weeklyLimit 为 0 时返回 0（防除零）', () => {
    expect(quotaUsagePct(quota({ weeklyLimit: 0, sentLast7Days: 2 }))).toBe(0)
  })

  it('2/3 用量四舍五入为 67%', () => {
    expect(quotaUsagePct(quota({ sentLast7Days: 2, weeklyLimit: 3 }))).toBe(67)
  })

  it('3/3 用量为 100%', () => {
    expect(quotaUsagePct(quota({ sentLast7Days: 3, weeklyLimit: 3 }))).toBe(100)
  })

  it('超发（4/3）封顶 100%，不出现负余量条', () => {
    expect(quotaUsagePct(quota({ sentLast7Days: 4, weeklyLimit: 3 }))).toBe(100)
  })
})

describe('quotaTone 进度条色调边界', () => {
  it('100% → danger（红）', () => {
    expect(quotaTone(100)).toBe('danger')
  })

  it('70% → warn（黄），边界含等号', () => {
    expect(quotaTone(70)).toBe('warn')
  })

  it('69% → ok（绿）', () => {
    expect(quotaTone(69)).toBe('ok')
  })

  it('0% → ok（绿）', () => {
    expect(quotaTone(0)).toBe('ok')
  })
})

describe('checkPushReady 发送前置闸门', () => {
  it('未选客户 → 拦截在第一关', () => {
    const r = checkPushReady(form({ hasCustomer: false }))
    expect(r.ok).toBe(false)
    expect(r.error).toBe('请先选择客户')
  })

  it('文案为空 → 提示输入推送内容', () => {
    const r = checkPushReady(form({ content: '' }))
    expect(r.ok).toBe(false)
    expect(r.error).toBe('请输入推送内容')
  })

  it('文案全为空白字符 → trim 后视为空', () => {
    const r = checkPushReady(form({ content: '   \n\t ' }))
    expect(r.ok).toBe(false)
    expect(r.error).toBe('请输入推送内容')
  })

  it(`文案超过 ${PUSH_CONTENT_MAX} 字 → 长度拦截`, () => {
    const r = checkPushReady(form({ content: '美'.repeat(PUSH_CONTENT_MAX + 1) }))
    expect(r.ok).toBe(false)
    expect(r.error).toBe(`推送内容最多 ${PUSH_CONTENT_MAX} 字`)
  })

  it(`文案恰好 ${PUSH_CONTENT_MAX} 字 → 长度关通过`, () => {
    const r = checkPushReady(form({ content: '美'.repeat(PUSH_CONTENT_MAX) }))
    expect(r.ok).toBe(true)
  })

  it('命中违禁词 → 列出命中词并拦截', () => {
    const r = checkPushReady(form({ wordHits: ['根治', '100%'] }))
    expect(r.ok).toBe(false)
    expect(r.error).toBe('文案命中违禁词：根治、100%，请修改后再发')
  })

  it('周频余量为 0 → 拦截并提示下周再发', () => {
    const r = checkPushReady(form({ quota: quota({ sentLast7Days: 3, remaining: 0 }) }))
    expect(r.ok).toBe(false)
    expect(r.error).toBe('该客户本周触达已达周频上限，请下周再发')
  })

  it('配额数据缺失（quota=null）→ 不因配额拦截，交给后端强校验', () => {
    const r = checkPushReady(form({ quota: null }))
    expect(r.ok).toBe(true)
    expect(r.error).toBe('')
  })

  it('全部条件满足 → 放行', () => {
    const r = checkPushReady(form())
    expect(r.ok).toBe(true)
    expect(r.error).toBe('')
  })

  it('闸门顺序：未选客 + 空文案 + 违禁词同时存在时，只报第一关', () => {
    const r = checkPushReady({
      hasCustomer: false,
      content: '',
      wordHits: ['根治'],
      quota: quota({ remaining: 0 }),
    })
    expect(r.error).toBe('请先选择客户')
  })
})
