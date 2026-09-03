import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import {
  listCouponWriteoffs,
  verifyCouponWriteoff,
  type CouponWriteoffDTO,
} from '@/api/marketing'

// ============================================================
// M5-12 券核销 store（接真实 API）
// - GET  /api/marketing/coupon-writeoffs   核销流水（倒序；伪造/重复/过期均落流水）
// - POST /api/marketing/coupon-writeoff    扫码核销（@RequirePerm couponWriteoff:verify）
// 金额口径：后端 orderAmountFen/discountFen bigint 存「分」，页面用「元」，换算在适配层。
// 异常核销（DUPLICATE/FORGED/EXPIRED）后端不抛错、落流水返回实体；
// 仅参数非法（券码/姓名/手机号/金额）抛 400 中文，store 捕获后返回 { ok:false, reason }。
// ============================================================

export type WriteoffStatus = 'OK' | 'DUPLICATE' | 'FORGED' | 'EXPIRED'

export interface WriteoffRecord {
  id: string
  couponCode: string
  couponName: string
  customerName: string
  customerPhone: string
  storeName: string
  amount: number       // 核销订单金额（元）
  discount: number     // 优惠抵扣金额（元）
  channel: string
  status: WriteoffStatus
  reason?: string
  verifiedAt: string
  operator: string
}

export const WRITEOFF_STATUS_LABEL: Record<WriteoffStatus, string> = {
  OK: '正常', DUPLICATE: '重复核销', FORGED: '伪造券码', EXPIRED: '已过期',
}
export const WRITEOFF_STATUS_PILL: Record<WriteoffStatus, 'success' | 'danger' | 'warning'> = {
  OK: 'success', DUPLICATE: 'danger', FORGED: 'danger', EXPIRED: 'warning',
}

// -------------------- 适配层（后端 DTO ↔ 页面活规格） --------------------

const fen2yuan = (f: number | null | undefined): number => (f == null ? 0 : f / 100)
const yuan2fen = (y: number): number => Math.round(y * 100)

function fmtTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function adapt(dto: CouponWriteoffDTO): WriteoffRecord {
  return {
    id: dto.writeoffId,
    couponCode: dto.couponCode,
    couponName: dto.couponName || '未知券',
    customerName: dto.customerName || '匿名',
    customerPhone: dto.customerPhone || '—',
    storeName: dto.storeName || dto.storeCode || '门店',
    amount: fen2yuan(dto.orderAmountFen),
    discount: fen2yuan(dto.discountFen),
    channel: dto.channel || '门店核销',
    status: (dto.status || 'FORGED') as WriteoffStatus,
    reason: dto.reason ?? undefined,
    verifiedAt: fmtTime(dto.verifiedAt),
    operator: dto.operator || '前台',
  }
}

function errText(e: unknown): string {
  const anyE = e as { response?: { data?: { message?: string; error?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || '核销失败，请重试'
}

export const useM5WriteoffStore = defineStore('m5Writeoff', () => {
  const activity = useActivityStore()
  const auth = useAuthStore()

  const writeoffs = ref<WriteoffRecord[]>([])
  const seeded = ref(false)

  const writeoffStats = computed(() => {
    const list = writeoffs.value
    return {
      total: list.length,
      ok: list.filter((w) => w.status === 'OK').length,
      abnormal: list.filter((w) => w.status !== 'OK').length,
      discount: list.filter((w) => w.status === 'OK').reduce((s, w) => s + w.discount, 0),
    }
  })

  /** 拉取真实核销流水；force=true 强制重拉（核销后刷新用）。 */
  async function seed(force = false) {
    if (seeded.value && !force) return
    try {
      const { data } = await listCouponWriteoffs()
      writeoffs.value = data.map(adapt)
    } catch (e) {
      console.error('券核销流水加载失败', e)
    }
    seeded.value = true
  }

  /**
   * 扫码核销（元转分提交）：
   * - 成功/异常拦截均由后端落流水并返回实体，按 status 判定 ok；
   * - 参数非法/无权限抛 400/403，捕获后返回 { ok:false, reason } 不抛出。
   */
  async function verifyCoupon(
    code: string,
    customerName: string,
    customerPhone: string,
    amountYuan: number,
  ): Promise<{ ok: boolean; record: WriteoffRecord; reason?: string }> {
    let dto: CouponWriteoffDTO
    try {
      const { data } = await verifyCouponWriteoff({
        couponCode: code,
        customerName,
        customerPhone,
        orderAmountFen: yuan2fen(amountYuan),
      })
      dto = data
    } catch (e) {
      const reason = errText(e)
      activity.log(auth.user?.name ?? '前台', `券核销失败：${reason}`, code)
      return {
        ok: false,
        reason,
        record: {
          id: '', couponCode: code, couponName: '', customerName, customerPhone: customerPhone || '—',
          storeName: '', amount: amountYuan, discount: 0, channel: '门店核销', status: 'FORGED',
          verifiedAt: '', operator: auth.user?.name ?? '前台',
        },
      }
    }
    const rec = adapt(dto)
    writeoffs.value.unshift(rec)
    const ok = dto.status === 'OK'
    activity.log(
      auth.user?.name ?? '前台',
      `核销券 ${code} ${ok ? (rec.discount > 0 ? `成功，抵扣 ¥${rec.discount}` : '成功（未达门槛未抵扣）') : '拦截：' + (rec.reason ?? '')}`,
      rec.id,
    )
    return { ok, record: rec, reason: rec.reason }
  }

  return {
    writeoffs, writeoffStats,
    WRITEOFF_STATUS_LABEL, WRITEOFF_STATUS_PILL,
    seed, verifyCoupon,
  }
})
