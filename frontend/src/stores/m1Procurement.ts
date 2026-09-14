import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { useStoreContext } from '@/stores/storeContext'
import { listConsumables, type ConsumableDTO } from '@/api/consumable'
import {
  listSuppliers, listPurchaseOrders,
  submitPurchaseOrder, approvePurchaseOrder, rejectPurchaseOrder,
  cancelPurchaseOrder, receivePurchaseOrder,
  type SupplierDTO, type PurchaseOrderDTO,
} from '@/api/procurement'
import { shDateStr } from '@/utils/datetime'

// ============================================================
// 采购供应链 store（M1 集团管控 / 采购供应链）
// B49 卡5 全量接真（诚实降级）：
// - 供应商 Supplier / 采购订单 PO / 审批 / 收货工作流：权威源 store-service
//   /stores/suppliers + /stores/purchase-orders（六态状态机，收货联动耗材库存）。
//   集团视角不传 storeCode，后端按数据域返回可见门店全集。
// - 库存 Inventory 行：权威源 /stores/consumables 真实耗材台账。
// - 任一路径 API 不可用或空库，对应区块回落本地演示数据（demo / workflowDemo=true）。
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
function day(n: number) { const d = new Date(); d.setDate(d.getDate() + n); return shDateStr(d) }

const r2 = (v: number) => Math.round(v * 100) / 100
const nz = (s: string | null | undefined) => s ?? ''

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
  /** 供应商/PO/审批工作流是否为演示状态机（真实端点不可用/空库回落） */
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

  // ---- DTO → view 模型映射 ----
  function mapSupplier(s: SupplierDTO): Supplier {
    const out: Supplier = {
      id: String(s.id),
      code: s.code,
      name: s.name,
      contact: nz(s.contact),
      phone: nz(s.phone),
      paymentTerms: s.paymentTerms,
      qualified: s.qualified,
      status: s.status,
    }
    if (s.remark) out.remark = s.remark
    return out
  }

  function mapPo(p: PurchaseOrderDTO): PurchaseOrder {
    const storeName = p.storeName
      || ctx.stores.find((s) => s.storeCode === p.storeCode)?.storeName
      || p.storeCode
    const out: PurchaseOrder = {
      id: String(p.id),
      poNo: p.poNo,
      supplierId: String(p.supplierId),
      storeId: p.storeCode,
      storeName,
      status: p.status,
      items: p.items.map((it) => ({
        sku: it.skuCode,
        name: it.name,
        brand: nz(it.brand),
        unit: it.unit,
        qty: it.qty,
        receivedQty: it.receivedQty,
        unitPrice: r2(Number(it.unitPriceYuan) || 0),
      })),
      totalAmount: r2(Number(p.totalYuan) || 0),
      signTier: p.signTier,
      expectDate: nz(p.expectDate),
      createdAt: p.createdAt,
    }
    if (p.approver) out.approver = p.approver
    if (p.approvedAt) out.approvedAt = p.approvedAt
    if (p.remark) out.remark = p.remark
    return out
  }

  // ---- 真实数据拉取 ----
  async function reloadInventory() {
    const resp = await listConsumables()
    const list = resp.data ?? []
    inventory.value = list.map((c) => {
      const storeName = ctx.stores.find((s) => s.storeCode === c.storeCode)?.storeName || c.storeCode
      return mapInventory(c, storeName)
    })
    demo.value = false
  }

  async function reloadWorkflow() {
    const [supResp, poResp] = await Promise.all([listSuppliers(), listPurchaseOrders()])
    suppliers.value = (supResp.data ?? []).map(mapSupplier)
    orders.value = (poResp.data ?? []).map(mapPo)
    receipts.value = []
    workflowDemo.value = false
  }

  // ---- 采购单操作（真实状态机；成功后重拉保证与后端一致） ----
  async function submit(id: string) {
    await submitPurchaseOrder(id)
    await reloadWorkflow()
  }
  async function approve(id: string, approver: string, _note?: string) {
    void approver
    await approvePurchaseOrder(id)
    await reloadWorkflow()
  }
  async function reject(id: string, note?: string) {
    await rejectPurchaseOrder(id, note)
    await reloadWorkflow()
  }
  async function cancel(id: string, note?: string) {
    await cancelPurchaseOrder(id, note)
    await reloadWorkflow()
  }

  // 收货入库：后端逐 SKU 联动耗材库存（移动均价 + PURCHASE 流水），成功后 PO 与库存一并重拉
  async function receive(id: string, items: { sku: string; qty: number }[], _receiver: string, note?: string) {
    await receivePurchaseOrder(id, items.map((it) => ({ skuCode: it.sku, qty: it.qty })), note)
    const results = await Promise.allSettled([reloadWorkflow(), reloadInventory()])
    results.forEach((r) => { if (r.status === 'rejected') console.error('[m1Procurement] 收货后刷新失败', r.reason) })
  }

  // 安全库存：真实台账暂无写端点（领用/报损/调整须走 txn 双签），仅本地视图调整不落库
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

  // ---- seed：供应商/PO + 库存各拉真实端点；任一失败/空库对应区块回落演示 ----
  let seeding: Promise<void> | null = null
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (loaded.value && !force) return Promise.resolve()
    seeding = (async () => {
      await ctx.loadStores().catch(() => undefined)
      loadWorkflowDemo()
      loadInventoryDemo()
      try {
        await reloadWorkflow()
      } catch (e) {
        console.error('[m1Procurement] 加载供应商/采购单失败，工作流回落本地演示数据', e)
        loadWorkflowDemo()
        workflowDemo.value = true
      }
      try {
        await reloadInventory()
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

  // ---- 演示数据（真实端点不可用/空库回落） ----
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
