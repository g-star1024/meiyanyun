// ============================================================
// Inventory 库存耗材 store（M2-02，B5 接真实 API）
// 门店实物库存：耗材/商品/药品 SKU，安全库存预警，出入库流水，成本核算。
// 权威源：store-service /stores/consumables（台账）与 /movements（流水），
//   读金额单位「元」（avgCostYuan/unitCostYuan，后端已由分换算）。
// 入库（PURCHASE）：POST /stores/consumables/stock-in（元→分，batchNo 幂等），直接落库。
// 出库（领用）/报损：无公开扣库端点——必须提交 txn 审批中心双签
//   （/txn/approval/requisition、/txn/approval/loss-report），终审通过后由服务端
//   内部回调扣库并落 TK-MATERIAL / TK-LOSS 成本，前端不直扣库存。
// API 不可用或空库时回落本地演示数据（demo 标记）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useStoreContext } from './storeContext'
import {
  listConsumables, listMovements, createConsumable, stockInConsumable,
  type ConsumableDTO, type ConsumableMovementDTO,
} from '@/api/consumable'
import { submitRequisition, submitLossReport } from '@/api/approval'

export type InvCategory = 'CONSUMABLE' | 'PRODUCT' | 'DRUG' | 'DEVICE'
export type TxnType = 'IN' | 'OUT' | 'ADJUST' | 'LOSS' | 'REQUISITION'

export interface InventorySku {
  id: string
  skuCode: string
  name: string
  category: InvCategory
  spec: string            // 规格
  unit: string            // 单位
  stock: number           // 当前库存
  safetyStock: number     // 安全库存
  avgCost: number         // 移动平均成本（元）
  supplier?: string
  location?: string       // 货位
  lastInAt?: string
}

export interface InventoryTxn {
  id: string
  skuId: string
  skuName: string
  type: TxnType
  quantity: number        // 正=入库/盘盈，负=出库/报损
  unitCost: number
  operator: string
  remark: string
  createdAt: string
  refNo?: string
}

const CATEGORY_LABEL: Record<InvCategory, string> = {
  CONSUMABLE: '耗材',
  PRODUCT: '商品',
  DRUG: '药品',
  DEVICE: '设备配件',
}

const TXN_LABEL: Record<TxnType, string> = {
  IN: '入库',
  OUT: '出库',
  ADJUST: '盘点调整',
  LOSS: '报损',
  REQUISITION: '申领出库',
}

const yuan2fen = (yuan: number) => Math.round((Number(yuan) || 0) * 100)
const r2 = (v: number) => Math.round(v * 100) / 100

/** 后端流水类型 → 前端展示类型（USE 领用/出库，SCRAP 报损，PURCHASE 入库，ADJUST 调整） */
function mapMoveType(t: string): TxnType {
  if (t === 'PURCHASE') return 'IN'
  if (t === 'SCRAP') return 'LOSS'
  if (t === 'ADJUST') return 'ADJUST'
  return 'OUT' // USE
}

export const useInventoryStore = defineStore('inventory', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const ctx = useStoreContext()

  const skus = ref<InventorySku[]>([])
  const txns = ref<InventoryTxn[]>([])
  const selectedId = ref<string | null>(null)
  const filterCategory = ref<InvCategory | 'ALL'>('ALL')
  const keyword = ref('')
  const loaded = ref(false)
  const demo = ref(false)

  const selected = computed(() => skus.value.find((s) => s.id === selectedId.value))

  const lowStock = computed(() => skus.value.filter((s) => s.stock <= s.safetyStock))
  const outOfStock = computed(() => skus.value.filter((s) => s.stock === 0))
  const totalValue = computed(() =>
    r2(skus.value.reduce((sum, s) => sum + s.stock * s.avgCost, 0)),
  )
  const totalSkuCount = computed(() => skus.value.length)

  const filteredSkus = computed(() => {
    return skus.value.filter((s) => {
      if (filterCategory.value !== 'ALL' && s.category !== filterCategory.value) return false
      if (keyword.value && !`${s.name}${s.skuCode}${s.spec}`.includes(keyword.value)) return false
      return true
    })
  })

  function stockStatus(s: InventorySku): 'OUT' | 'LOW' | 'NORMAL' {
    if (s.stock === 0) return 'OUT'
    if (s.stock <= s.safetyStock) return 'LOW'
    return 'NORMAL'
  }

  function txnsOfSku(skuId: string) {
    return txns.value.filter((t) => t.skuId === skuId)
  }

  function categoryLabel(c: InvCategory) {
    return CATEGORY_LABEL[c]
  }
  function txnLabel(t: TxnType) {
    return TXN_LABEL[t]
  }

  // ---- DTO 映射（读金额「元」直接取用） ----
  function mapSku(c: ConsumableDTO): InventorySku {
    return {
      id: String(c.id),
      skuCode: c.skuCode,
      name: c.name,
      category: (c.category as InvCategory) || 'CONSUMABLE',
      spec: c.spec || '',
      unit: c.unit,
      stock: c.qty,
      safetyStock: c.safetyStock,
      avgCost: r2(Number(c.avgCostYuan) || 0),
      supplier: c.supplier || undefined,
      location: c.location || undefined,
      lastInAt: c.lastInAt || undefined,
    }
  }

  function mapTxn(m: ConsumableMovementDTO): InventoryTxn {
    return {
      id: String(m.id),
      skuId: String(m.consumableId),
      skuName: m.name,
      type: mapMoveType(m.moveType),
      quantity: m.qtyChange,
      unitCost: r2(Number(m.unitCostYuan) || 0),
      operator: m.operator || '系统',
      remark: m.remark || '',
      createdAt: m.createdAt,
      refNo: m.bizRef || undefined,
    }
  }

  // ---- 拉取 ----
  let seeding: Promise<void> | null = null
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (loaded.value && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        await ctx.loadStores()
        const storeCode = ctx.currentStoreCode
        const [skuResp, mvResp] = await Promise.all([
          listConsumables({ storeCode }),
          listMovements({ storeCode }),
        ])
        const skuList = skuResp.data ?? []
        if (skuList.length > 0) {
          skus.value = skuList.map(mapSku)
          txns.value = (mvResp.data ?? []).map(mapTxn)
          demo.value = false
        } else {
          loadDemo()
          demo.value = true
        }
        loaded.value = true
      } catch (e) {
        console.error('[inventory] 加载耗材台账/流水失败，回落本地演示数据', e)
        loadDemo()
        demo.value = true
        loaded.value = true
      }
    })()
    return seeding
  }
  void seed()

  /** 入库（采购）：POST /stock-in（元→分，batchNo 幂等），成功后强制刷新台账。 */
  async function stockIn(skuId: string, quantity: number, unitCost: number, remark = '采购入库'): Promise<boolean> {
    const s = skus.value.find((x) => x.id === skuId)
    if (!s || quantity <= 0) return false
    if (!auth.can('inventory:consumable:edit')) {
      console.warn('[inventory] 无 inventory:consumable:edit 权限')
      return false
    }
    const cost = unitCost > 0 ? unitCost : s.avgCost
    const batchNo = `BIN-${Date.now().toString(36).toUpperCase()}-${s.skuCode}`
    await stockInConsumable({
      storeCode: ctx.currentStoreCode,
      skuCode: s.skuCode,
      qty: quantity,
      unitCostFen: yuan2fen(cost),
      batchNo,
      remark,
    })
    activity.log(auth.user.name, `入库 ${s.name} ×${quantity}${s.unit}，单价 ¥${cost}（批次 ${batchNo}）`, s.id)
    await seed(true)
    return true
  }

  /**
   * 出库（日常领用）：不直扣库存，提交审批中心固定双签（店长一审 → 财务终审），
   * 终审通过后由服务端内部扣库并落 TK-MATERIAL 成本。
   */
  async function stockOut(skuId: string, quantity: number, remark = '日常领用'): Promise<boolean> {
    const s = skus.value.find((x) => x.id === skuId)
    if (!s || quantity <= 0 || quantity > s.stock) return false
    if (!auth.can('requisition:edit')) {
      console.warn('[inventory] 无 requisition:edit 权限')
      return false
    }
    const todo = await submitRequisition({
      storeCode: ctx.currentStoreCode,
      purpose: remark || '耗材领用',
      lines: [{ skuCode: s.skuCode, name: s.name, qty: quantity, remark }],
    })
    activity.log(auth.user.name, `提交领用审批 ${s.name} ×${quantity}${s.unit}（待办 ${todo.data.todoNo}，双签通过后扣库）`, s.id)
    return true
  }

  /**
   * 报损（损耗）：不直扣库存，提交审批中心（损失额分，<¥5000 财务单签 / ≥¥5000 双签），
   * 终审通过后由服务端内部扣 SCRAP 并落 TK-LOSS 成本。
   */
  async function reportLoss(skuId: string, quantity: number, reason: string): Promise<boolean> {
    const s = skus.value.find((x) => x.id === skuId)
    if (!s || quantity <= 0 || quantity > s.stock) return false
    if (!auth.can('wastage:edit')) {
      console.warn('[inventory] 无 wastage:edit 权限')
      return false
    }
    const amountFen = yuan2fen(r2(quantity * s.avgCost))
    const todo = await submitLossReport({
      storeCode: ctx.currentStoreCode,
      reason: reason || '损耗报损',
      amount: amountFen,
      lines: [{ skuCode: s.skuCode, name: s.name, qty: quantity, remark: reason }],
    })
    activity.log(auth.user.name, `提交报损审批 ${s.name} ×${quantity}${s.unit}（待办 ${todo.data.todoNo}，终审通过后扣库）`, s.id)
    return true
  }

  /** 新建 SKU（建档）：POST /consumables（成本价元→分，初始库存>0 由后端写 PURCHASE 流水）。 */
  async function addSku(data: Omit<InventorySku, 'id'>): Promise<boolean> {
    if (!auth.can('inventory:consumable:edit')) return false
    await createConsumable({
      storeCode: ctx.currentStoreCode,
      skuCode: data.skuCode,
      name: data.name,
      category: data.category,
      spec: data.spec || undefined,
      unit: data.unit || '个',
      costPriceFen: yuan2fen(data.avgCost),
      safetyStock: data.safetyStock,
      initialQty: data.stock > 0 ? data.stock : undefined,
      supplier: data.supplier,
      location: data.location,
    })
    activity.log(auth.user.name, `新建 SKU ${data.name}（${data.skuCode}）`, data.skuCode)
    await seed(true)
    return true
  }

  // ---- 离线演示数据（API 不可用/空库回落） ----
  function loadDemo() {
    const now = Date.now()
    const daysAgo = (d: number) => new Date(now - d * 86400_000).toISOString()
    const seedSkus: Array<Omit<InventorySku, 'id'>> = [
      { skuCode: 'HC-001', name: '润百颜玻尿酸', category: 'DRUG', spec: '1ml/支', unit: '支', stock: 8, safetyStock: 20, avgCost: 380, supplier: '华东医药', location: 'A-01', lastInAt: daysAgo(3) },
      { skuCode: 'HC-002', name: '瘦脸针 100U', category: 'DRUG', spec: '100U/瓶', unit: '瓶', stock: 15, safetyStock: 10, avgCost: 1200, supplier: '兰州生物', location: 'A-02', lastInAt: daysAgo(7) },
      { skuCode: 'CS-101', name: '一次性床单', category: 'CONSUMABLE', spec: '80×180cm', unit: '张', stock: 320, safetyStock: 100, avgCost: 1.8, supplier: '稳健医疗', location: 'B-03', lastInAt: daysAgo(2) },
      { skuCode: 'CS-102', name: '医用消毒棉片', category: 'CONSUMABLE', spec: '50片/盒', unit: '盒', stock: 45, safetyStock: 30, avgCost: 12, supplier: '稳健医疗', location: 'B-04' },
      { skuCode: 'CS-103', name: '补水面膜', category: 'PRODUCT', spec: '5片/盒', unit: '盒', stock: 12, safetyStock: 20, avgCost: 30, supplier: '敷尔佳', location: 'C-01' },
      { skuCode: 'CS-104', name: '医用手套', category: 'CONSUMABLE', spec: 'M码 100只/盒', unit: '盒', stock: 0, safetyStock: 15, avgCost: 28, supplier: '英科医疗', location: 'B-05' },
      { skuCode: 'DV-201', name: '热玛吉探头', category: 'DEVICE', spec: '四代专用', unit: '个', stock: 6, safetyStock: 3, avgCost: 2800, supplier: '博士伦', location: 'D-01', lastInAt: daysAgo(15) },
      { skuCode: 'CS-105', name: '生理盐水', category: 'DRUG', spec: '250ml/瓶', unit: '瓶', stock: 88, safetyStock: 40, avgCost: 3.5, supplier: '科伦药业', location: 'A-03' },
    ]
    skus.value = seedSkus.map((s) => ({ id: nextId('sk'), ...s }))

    const txnSeed: Array<[number, TxnType, number, string, number]> = [
      [0, 'IN', 30, '采购入库', 3],
      [0, 'OUT', -22, '光子嫩肤项目领用', 2],
      [2, 'IN', 200, '月度采购', 5],
      [2, 'OUT', -80, '日常消耗', 1],
      [4, 'LOSS', -8, '过期报损', 0],
      [5, 'OUT', -15, '前台日常领用', 1],
      [1, 'IN', 20, '采购入库', 7],
      [1, 'OUT', -5, '注射项目领用', 2],
    ]
    txns.value = txnSeed.map(([idx, type, qty, remark, days]) => {
      const s = skus.value[idx]
      return {
        id: nextId('tx'),
        skuId: s.id,
        skuName: s.name,
        type,
        quantity: qty,
        unitCost: s.avgCost,
        operator: ['吴桐（库管）', '李娜（前台）', '周敏（美容师）'][idx % 3],
        remark,
        createdAt: daysAgo(days),
      }
    })
  }

  return {
    skus, txns, selectedId, selected, filterCategory, keyword,
    lowStock, outOfStock, totalValue, totalSkuCount, filteredSkus,
    stockStatus, txnsOfSku, categoryLabel, txnLabel, CATEGORY_LABEL, TXN_LABEL,
    stockIn, stockOut, reportLoss, addSku, seed, loaded, demo,
  }
})
