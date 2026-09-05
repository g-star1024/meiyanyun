import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { useStoreContext } from '@/stores/storeContext'
import { listConsumables, type ConsumableDTO } from '@/api/consumable'

// ============================================================
// 采购供应链 store（M1 集团管控 / 采购供应链）
// B5 数据口径（诚实降级）：
// - 库存 Inventory 行：权威源为 store-service /stores/consumables 真实耗材台账
//   （集团视角不传 storeCode，后端按数据域返回可见门店全集；只读投影，本页不写库）。
//   API 不可用或空库时回落本地演示数据（demo=true）。
// - 供应商 Supplier / 采购订单 PO / 审批 / 入库工作流：后端暂无供应商/PO 实体，
//   维持本地演示状态机（workflowDemo=true），数据不落库，仅演示审批流转交互。
// ============================================================

export type PoStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'PARTIAL' | 'RECEIVED' | 'CANCELLED'
export const PO_STATUS_LABEL: Record<PoStatus, string> = {
  DRAFT: '草稿', SUBMITTED: '待审批', APPROVED: '待入库', PARTIAL: '部分入库', RECEIVED: '已入库', CANCELLED: '已取消',
}
export const PO_TRANSITIONS: Record<PoStatus, PoStatus[]> = {
  DRAFT: ['SUBMITTED', 'CANCELLED'],
  SUBMITTED: ['APPROVED', 'CANCELLED', 'DRAFT'], // 驳回回草稿
  APPROVED: ['PARTIAL', 'RECEIVED', 'CANCELLED'],
  PARTIAL: ['RECEIVED', 'CANCELLED'],
  RECEIVED: [],
  CANCELLED: [],
}

export interface Supplier {
  id: string
  code: string
  name: string
  contact: string
  phone: string
  paymentTerms: number // 账期（天）
  qualified: boolean // 资质是否有效
  status: 'ACTIVE' | 'INACTIVE'
  remark?: string
}

export interface PoItem {
  sku: string
  name: string
  brand: string
  unit: string
  qty: number
  receivedQty: number
  unitPrice: number
}

export interface PurchaseOrder {
  id: string
  poNo: string
  supplierId: string
  storeId: string
  storeName: string
  status: PoStatus
  items: PoItem[]
  totalAmount: number
  signTier: 'STORE' | 'REGION' | 'GROUP'
  approver?: string
  approvedAt?: string
  expectDate: string
  createdAt: string
  remark?: string
}

export interface InventoryLine {
  id: string
  sku: string
  name: string
  brand: string
  storeName: string
  unit: string
  onHand: number // 现存量
  safety: number // 安全库存
  value: number // 库存金额（元，真实台账取 stockValueYuan）
  batchNo?: string
  expireDate?: string
}

export interface GoodsReceipt {
  id: string
  poId: string
  poNo: string
  receivedAt: string
  receiver: string
  qty: number
  amount: number
  note?: string
}

let _cid = 0
function cid(p: string) { _cid += 1; return `${p}-${Date.now().toString(36)}-${_cid}` }
function now() { return new Date().toISOString() }
function day(n: number) { const d = new Date(); d.setDate(d.getDate() + n); return d.toISOString().slice(0, 10) }

const r2 = (v: number) => Math.round(v * 100) / 100

export const useM1ProcurementStore = defineStore('m1Procurement', () => {
  const settings = useSettingsStore()
  const ctx = useStoreContext()

  const suppliers = ref<Supplier[]>([])
  const orders = ref<PurchaseOrder[]>([])
  const inventory = ref<InventoryLine[]>([])
  const receipts = ref<GoodsReceipt[]>([])
  const loaded = ref(false)
  /** 库存行是否为本地演示数据（真实台账不可用/空库回落） */
  const demo = ref(false)
  /** 供应商/PO/审批工作流是否为演示状态机（后端暂无实体，恒为 true） */
  const workflowDemo = ref(true)

  // ---- 派生 ----
  function supplier(id: string) { return suppliers.value.find((s) => s.id === id) }
  function order(id: string) { return orders.value.find((o) => o.id === id) }

  const pendingApprove = computed(() => orders.value.filter((o) => o.status === 'SUBMITTED'))
  const pendingReceive = computed(() => orders.value.filter((o) => o.status === 'APPROVED' || o.status === 'PARTIAL'))
  const lowStock = computed(() => inventory.value.filter((i) => i.onHand <= i.safety))

  const stats = computed(() => {
    const totalAmount = orders.value.filter((o) => ['SUBMITTED', 'APPROVED', 'PARTIAL', 'RECEIVED'].includes(o.status))
      .reduce((s, o) => s + o.totalAmount, 0)
    return {
      supplierCount: suppliers.value.filter((s) => s.status === 'ACTIVE').length,
      pendingApprove: pendingApprove.value.length,
      pendingReceive: pendingReceive.value.length,
      lowStock: lowStock.value.length,
      totalAmount,
      inventoryValue: r2(inventory.value.reduce((s, i) => s + i.value, 0)),
    }
  })

  // 审批层级：按设置中心阈值（L1 门店 / L2 区域 / L3 集团）
  function tierFor(amount: number): 'STORE' | 'REGION' | 'GROUP' {
    const t = settings.tierFor(amount)
    if (t === 'L3') return 'GROUP'
    if (t === 'L2') return 'REGION'
    return 'STORE'
  }

  function canTransit(o: PurchaseOrder, to: PoStatus) {
    return PO_TRANSITIONS[o.status].includes(to)
  }

  function calcTotal(items: { qty: number; unitPrice: number }[]) {
    return items.reduce((s, it) => s + it.qty * it.unitPrice, 0)
  }

  // ---- 采购单操作（演示工作流：仅改本地状态，不落库） ----
  function submit(id: string) {
    const o = order(id)
    if (!o || o.status !== 'DRAFT') return
    o.status = 'SUBMITTED'
    o.totalAmount = calcTotal(o.items)
    o.signTier = tierFor(o.totalAmount)
  }
  function approve(id: string, approver: string) {
    const o = order(id)
    if (!o || !canTransit(o, 'APPROVED')) return
    o.status = 'APPROVED'; o.approver = approver; o.approvedAt = now()
  }
  function reject(id: string) {
    const o = order(id)
    if (!o || !canTransit(o, 'DRAFT')) return
    o.status = 'DRAFT'
  }
  function cancel(id: string) {
    const o = order(id)
    if (!o || !canTransit(o, 'CANCELLED')) return
    o.status = 'CANCELLED'
  }

  // 入库（演示）：按行累加 receivedQty，写本地库存行与 receipt；真实入库请走库存页「入库」
  function receive(id: string, items: { sku: string; qty: number }[], receiver: string, note?: string) {
    const o = order(id)
    if (!o || !(o.status === 'APPROVED' || o.status === 'PARTIAL')) return
    let totalQty = 0, totalAmount = 0
    for (const r of items) {
      const line = o.items.find((it) => it.sku === r.sku)
      if (!line) continue
      const remain = line.qty - line.receivedQty
      const q = Math.min(r.qty, remain)
      if (q <= 0) continue
      line.receivedQty += q
      totalQty += q
      totalAmount += q * line.unitPrice
      // 写库存（按 sku + 门店 找现有行累加，否则新建）
      const inv = inventory.value.find((i) => i.sku === line.sku && i.storeName === o.storeName)
      if (inv) {
        inv.onHand += q
        inv.value = r2(inv.onHand * line.unitPrice)
      } else {
        inventory.value.push({
          id: cid('inv'), sku: line.sku, name: line.name, brand: line.brand, storeName: o.storeName,
          unit: line.unit, onHand: q, safety: 5, value: r2(q * line.unitPrice),
          batchNo: `B${Date.now().toString(36).toUpperCase()}`,
        })
      }
    }
    if (totalQty > 0) {
      receipts.value.unshift({
        id: cid('gr'), poId: o.id, poNo: o.poNo, receivedAt: now(), receiver,
        qty: totalQty, amount: totalAmount, note,
      })
    }
    // 更新状态
    const allReceived = o.items.every((it) => it.receivedQty >= it.qty)
    const anyReceived = o.items.some((it) => it.receivedQty > 0)
    o.status = allReceived ? 'RECEIVED' : anyReceived ? 'PARTIAL' : 'APPROVED'
  }

  // 调整安全库存（演示工作流下仅改本地；真实台账无此写端点）
  function setSafety(invId: string, safety: number) {
    const inv = inventory.value.find((i) => i.id === invId)
    if (inv) inv.safety = Math.max(0, safety)
  }

  // ---- 真实耗材台账 → 库存行投影（只读） ----
  function mapInventory(c: ConsumableDTO, storeName: string): InventoryLine {
    const avg = r2(Number(c.avgCostYuan) || 0)
    return {
      id: String(c.id),
      sku: c.skuCode,
      name: c.name,
      brand: c.supplier || c.category || '—',
      storeName,
      unit: c.unit || '个',
      onHand: c.qty,
      safety: c.safetyStock,
      value: r2(Number(c.stockValueYuan) || c.qty * avg),
      batchNo: c.lastInAt ? `最近入库 ${c.lastInAt.slice(0, 10)}` : undefined,
    }
  }

  // ---- seed：库存拉真实台账；供应商/PO 填演示数据 ----
  let seeding: Promise<void> | null = null
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (loaded.value && !force) return Promise.resolve()
    seeding = (async () => {
      loadWorkflowDemo()
      try {
        await ctx.loadStores()
        // 集团采购视角：不传 storeCode，后端按数据域返回可见门店全集
        const resp = await listConsumables()
        const list = resp.data ?? []
        if (list.length > 0) {
          inventory.value = list.map((c) => {
            const storeName = ctx.stores.find((s) => s.storeCode === c.storeCode)?.storeName || c.storeCode
            return mapInventory(c, storeName)
          })
          demo.value = false
        } else {
          loadInventoryDemo()
          demo.value = true
        }
      } catch (e) {
        console.error('[m1Procurement] 加载耗材台账失败，库存回落本地演示数据', e)
        loadInventoryDemo()
        demo.value = true
      }
      loaded.value = true
    })()
    return seeding
  }
  void seed()

  // ---- 演示数据（后端无实体/不可用回落） ----
  function loadWorkflowDemo() {
    suppliers.value = [
      { id: cid('sup'), code: 'SUP-001', name: '艾尔建信息咨询(上海)有限公司', contact: '王磊', phone: '13800001111', paymentTerms: 30, qualified: true, status: 'ACTIVE' },
      { id: cid('sup'), code: 'SUP-002', name: '华熙生物科技股份有限公司', contact: '李娜', phone: '13800002222', paymentTerms: 45, qualified: true, status: 'ACTIVE' },
      { id: cid('sup'), code: 'SUP-003', name: '北京中韩光电科技有限公司', contact: '张强', phone: '13800003333', paymentTerms: 60, qualified: true, status: 'ACTIVE' },
      { id: cid('sup'), code: 'SUP-004', name: '科医人医疗激光设备(上海)有限公司', contact: '陈静', phone: '13800004444', paymentTerms: 30, qualified: false, status: 'INACTIVE', remark: '资质到期，待复审' },
    ]
    const s1 = suppliers.value[0].id, s2 = suppliers.value[1].id, s3 = suppliers.value[2].id

    const mk = (poNo: string, sid: string, storeName: string, status: PoStatus, items: PoItem[], expect: string, remark?: string): PurchaseOrder => {
      const total = calcTotal(items)
      return {
        id: cid('po'), poNo, supplierId: sid, storeId: 'store-jingan', storeName, status, items,
        totalAmount: total, signTier: tierFor(total), expectDate: expect, createdAt: now(), remark,
        approver: ['APPROVED', 'PARTIAL', 'RECEIVED'].includes(status) ? '陈野' : undefined,
        approvedAt: ['APPROVED', 'PARTIAL', 'RECEIVED'].includes(status) ? now() : undefined,
      }
    }
    orders.value = [
      mk('PO20260820001', s1, '静安旗舰店', 'SUBMITTED', [
        { sku: 'AGN-BTX-100', name: '保妥适100U瘦脸针', brand: '艾尔建', unit: '支', qty: 20, receivedQty: 0, unitPrice: 1650 },
        { sku: 'AGN-JUV-1ML', name: '乔雅登极致1ml', brand: '艾尔建', unit: '支', qty: 10, receivedQty: 0, unitPrice: 3200 },
      ], day(7), '本月注射类补货'),
      mk('PO20260818003', s2, '静安旗舰店', 'APPROVED', [
        { sku: 'HX-RST-2.5ML', name: '润致娃娃针2.5ml', brand: '华熙生物', unit: '支', qty: 50, receivedQty: 0, unitPrice: 680 },
      ], day(3)),
      mk('PO20260815007', s3, '徐汇社区店', 'PARTIAL', [
        { sku: 'ZH-THERMAGE-FL', name: '热玛吉FLX面部900发', brand: '中韩光电', unit: '部位', qty: 8, receivedQty: 3, unitPrice: 7200 },
      ], day(10), '热玛吉探头分批到货'),
      mk('PO20260810012', s2, '静安旗舰店', 'RECEIVED', [
        { sku: 'HX-QUADHA', name: '润百颜次抛精华(疗程)', brand: '华熙生物', unit: '盒', qty: 100, receivedQty: 100, unitPrice: 220 },
      ], day(-5)),
      mk('PO20260808015', s1, '浦东诊所', 'DRAFT', [
        { sku: 'AGN-BTX-100', name: '保妥适100U瘦脸针', brand: '艾尔建', unit: '支', qty: 5, receivedQty: 0, unitPrice: 1650 },
      ], day(14), '草稿-待确认数量'),
    ]
  }

  function loadInventoryDemo() {
    inventory.value = [
      { id: cid('inv'), sku: 'HX-QUADHA', name: '润百颜次抛精华(疗程)', brand: '华熙生物', storeName: '静安旗舰店', unit: '盒', onHand: 86, safety: 20, value: 86 * 220, batchNo: 'BHU812', expireDate: day(365) },
      { id: cid('inv'), sku: 'ZH-THERMAGE-FL', name: '热玛吉FLX面部900发', brand: '中韩光电', storeName: '徐汇社区店', unit: '部位', onHand: 3, safety: 5, value: 3 * 7200, batchNo: 'BZH077', expireDate: day(180) },
      { id: cid('inv'), sku: 'AGN-BTX-100', name: '保妥适100U瘦脸针', brand: '艾尔建', storeName: '静安旗舰店', unit: '支', onHand: 4, safety: 10, value: 4 * 1650, batchNo: 'BAGN12', expireDate: day(90) },
      { id: cid('inv'), sku: 'HX-RST-2.5ML', name: '润致娃娃针2.5ml', brand: '华熙生物', storeName: '静安旗舰店', unit: '支', onHand: 12, safety: 15, value: 12 * 680, batchNo: 'BHX33', expireDate: day(200) },
      { id: cid('inv'), sku: 'AGN-JUV-1ML', name: '乔雅登极致1ml', brand: '艾尔建', storeName: '浦东诊所', unit: '支', onHand: 7, safety: 3, value: 7 * 3200, batchNo: 'BAGJ9', expireDate: day(120) },
    ]
  }

  return {
    suppliers, orders, inventory, receipts,
    stats, pendingApprove, pendingReceive, lowStock,
    loaded, demo, workflowDemo,
    PO_STATUS_LABEL, PO_TRANSITIONS,
    supplier, order, canTransit, tierFor,
    submit, approve, reject, cancel, receive, setSafety, seed,
  }
})
