// ============================================================
// Inventory 库存耗材 store（M2-02）
// 门店实物库存：耗材/商品/药品 SKU，安全库存预警，出入库流水，成本核算。
// 是物料申领(M2-11)、损耗报损(M2-12)、M6 成本的上游数据来源。
// 对齐 docs/business-flows.md、permission-matrix.md。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

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
  avgCost: number         // 移动平均成本
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

export const useInventoryStore = defineStore('inventory', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const skus = ref<InventorySku[]>([])
  const txns = ref<InventoryTxn[]>([])
  const selectedId = ref<string | null>(null)
  const filterCategory = ref<InvCategory | 'ALL'>('ALL')
  const keyword = ref('')

  const selected = computed(() => skus.value.find((s) => s.id === selectedId.value))

  const lowStock = computed(() => skus.value.filter((s) => s.stock <= s.safetyStock))
  const outOfStock = computed(() => skus.value.filter((s) => s.stock === 0))
  const totalValue = computed(() =>
    skus.value.reduce((sum, s) => sum + s.stock * s.avgCost, 0),
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

  /** 入库（采购/退货入），移动平均成本重算 */
  function stockIn(skuId: string, quantity: number, unitCost: number, remark = '采购入库') {
    const s = skus.value.find((x) => x.id === skuId)
    if (!s || quantity <= 0) return false
    if (!auth.can('inventory:edit')) {
      console.warn('[inventory] 无 inventory:edit 权限')
      return false
    }
    const totalValueBefore = s.stock * s.avgCost
    const totalValueAdd = quantity * unitCost
    s.stock += quantity
    s.avgCost = s.stock > 0 ? (totalValueBefore + totalValueAdd) / s.stock : unitCost
    s.lastInAt = new Date().toISOString()
    addTxn(s, 'IN', quantity, unitCost, remark)
    activity.log(auth.user.name, `入库 ${s.name} ×${quantity}${s.unit}，单价 ¥${unitCost}`, s.id)
    return true
  }

  /** 出库（日常领用），库存不足拒绝 */
  function stockOut(skuId: string, quantity: number, remark = '日常领用') {
    const s = skus.value.find((x) => x.id === skuId)
    if (!s || quantity <= 0 || quantity > s.stock) return false
    if (!auth.can('inventory:edit')) return false
    s.stock -= quantity
    addTxn(s, 'OUT', -quantity, s.avgCost, remark)
    activity.log(auth.user.name, `出库 ${s.name} ×${quantity}${s.unit}`, s.id)
    return true
  }

  /** 报损（损耗），需审批时走 approval store，这里直接扣减并记录 */
  function reportLoss(skuId: string, quantity: number, reason: string) {
    const s = skus.value.find((x) => x.id === skuId)
    if (!s || quantity <= 0 || quantity > s.stock) return false
    if (!auth.can('inventory:edit')) return false
    s.stock -= quantity
    addTxn(s, 'LOSS', -quantity, s.avgCost, reason)
    activity.log(auth.user.name, `报损 ${s.name} ×${quantity}${s.unit}：${reason}`, s.id)
    return true
  }

  function addTxn(s: InventorySku, type: TxnType, quantity: number, unitCost: number, remark: string) {
    txns.value.unshift({
      id: nextId('tx'),
      skuId: s.id,
      skuName: s.name,
      type,
      quantity,
      unitCost,
      operator: auth.user.name,
      remark,
      createdAt: new Date().toISOString(),
    })
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
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
    seedSkus.forEach((s) => skus.value.push({ id: nextId('sk'), ...s }))

    // 流水种子
    const flowSkus = skus.value
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
    txnSeed.forEach(([idx, type, qty, remark, days]) => {
      const s = flowSkus[idx]
      txns.value.push({
        id: nextId('tx'),
        skuId: s.id,
        skuName: s.name,
        type,
        quantity: qty,
        unitCost: s.avgCost,
        operator: ['吴桐（库管）', '李娜（前台）', '周敏（美容师）'][idx % 3],
        remark,
        createdAt: daysAgo(days),
      })
    })
  }

  /** 新建 SKU */
  function addSku(data: Omit<InventorySku, 'id'>): boolean {
    if (!auth.can('inventory:edit')) return false
    const sku: InventorySku = { id: nextId('sk'), ...data }
    skus.value.push(sku)
    activity.log(auth.user.name, `新建 SKU ${sku.name}（${sku.skuCode}）`, sku.id)
    return true
  }

  return {
    skus, txns, selectedId, selected, filterCategory, keyword,
    lowStock, outOfStock, totalValue, totalSkuCount, filteredSkus,
    stockStatus, txnsOfSku, categoryLabel, txnLabel, CATEGORY_LABEL, TXN_LABEL,
    stockIn, stockOut, reportLoss, addSku, seed,
  }
})
