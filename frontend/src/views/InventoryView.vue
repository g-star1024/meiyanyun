<script setup lang="ts">
// M2-02 库存耗材：SKU 库存列表 + 安全库存预警 + 出入库流水 + 入库/出库/报损操作。
// B10：项目配方 BOM（划扣自动扣料配方维护）+ 扣料异常处理（库存不足等失败重试/手工销项）。
import { computed, onMounted, ref } from 'vue'
import { useInventoryStore, type InvCategory } from '@/stores/inventory'
import { useBomStore } from '@/stores/bom'
import type { ProjectBomDTO } from '@/api/bom'
import { useAuthStore } from '@/stores/auth'
import CKpi from '@/components/CKpi.vue'
import CCard from '@/components/CCard.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CDrawer from '@/components/CDrawer.vue'
import CIcon from '@/components/CIcon.vue'
import { useToast } from '@/composables/useToast'

const toast = useToast()

const inv = useInventoryStore()
const auth = useAuthStore()
onMounted(() => inv.seed())

const catOptions = [
  { label: '全部分类', value: 'ALL' },
  ...(Object.keys(inv.CATEGORY_LABEL) as InvCategory[]).map((k) => ({ label: inv.CATEGORY_LABEL[k], value: k })),
]

const kpi = computed(() => ({
  sku: inv.totalSkuCount,
  low: inv.lowStock.length,
  out: inv.outOfStock.length,
  value: inv.totalValue,
}))

function statusInfo(s: ReturnType<typeof inv.stockStatus>) {
  if (s === 'OUT') return { status: 'danger' as const, text: '缺货' }
  if (s === 'LOW') return { status: 'warning' as const, text: '低库存' }
  return { status: 'success' as const, text: '正常' }
}

function fmtMoney(n: number) {
  return '¥' + n.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}
function fmtDate(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()}`
}

// 操作抽屉
const opType = ref<'IN' | 'OUT' | 'LOSS'>('IN')
const opOpen = ref(false)
const opQty = ref('')
const opCost = ref('')
const opRemark = ref('')

function openOp(type: 'IN' | 'OUT' | 'LOSS') {
  opType.value = type
  opQty.value = ''
  opCost.value = ''
  opRemark.value = type === 'IN' ? '采购入库' : type === 'LOSS' ? '' : '日常领用'
  opOpen.value = true
}

const opTitle = computed(() => ({ IN: '入库', OUT: '出库', LOSS: '报损' }[opType.value]))
// 权限与后端一致：入库/建档走 inventory:consumable:edit；领用出库走 requisition:edit；报损走 wastage:edit
const canStockIn = computed(() => auth.can('inventory:consumable:edit'))
const canStockOut = computed(() => auth.can('requisition:edit'))
const canLoss = computed(() => auth.can('wastage:edit'))
const canEdit = computed(() => canStockIn.value || canStockOut.value || canLoss.value)

function errMsg(e: any) {
  return e?.response?.data?.message || e?.message || '网络异常'
}

async function doOp() {
  if (!inv.selected) return
  const qty = Number(opQty.value)
  if (!qty || qty <= 0) return
  const skuId = inv.selected.id
  const name = inv.selected.name
  try {
    if (opType.value === 'IN') {
      const ok = await inv.stockIn(skuId, qty, Number(opCost.value) || inv.selected.avgCost, opRemark.value)
      if (!ok) { toast.warning('无入库权限或数量无效'); return }
      toast.success(`已入库「${name}」×${qty}，台账已刷新`)
    } else if (opType.value === 'OUT') {
      const ok = await inv.stockOut(skuId, qty, opRemark.value)
      if (!ok) { toast.warning('无领用申请权限或数量超过现有库存'); return }
      toast.success(`「${name}」领用申请已提交审批中心，双签通过后扣减库存`)
    } else {
      const ok = await inv.reportLoss(skuId, qty, opRemark.value || '损耗报损')
      if (!ok) { toast.warning('无报损权限或数量超过现有库存'); return }
      toast.success(`「${name}」报损单已提交审批中心，终审通过后扣减库存`)
    }
    opOpen.value = false
  } catch (e) {
    toast.error('操作失败：' + errMsg(e))
  }
}

function txnTypePill(type: string) {
  if (type === 'IN') return { status: 'success' as const, text: '入库', sign: '+' }
  if (type === 'OUT' || type === 'REQUISITION') return { status: 'primary' as const, text: inv.txnLabel(type as any), sign: '-' }
  if (type === 'LOSS') return { status: 'danger' as const, text: '报损', sign: '-' }
  return { status: 'info' as const, text: '调整', sign: '' }
}

// 新建 SKU 抽屉
const createOpen = ref(false)
const createForm = ref({
  name: '', skuCode: '', category: 'CONSUMABLE' as InvCategory,
  spec: '', unit: '', stock: '', safetyStock: '', avgCost: '',
  supplier: '', location: '',
})
const categoryOptions = [
  { value: 'CONSUMABLE', label: '耗材' },
  { value: 'PRODUCT', label: '商品' },
  { value: 'DRUG', label: '药品' },
  { value: 'DEVICE', label: '设备配件' },
]
function openCreate() {
  createForm.value = {
    name: '', skuCode: '', category: 'CONSUMABLE',
    spec: '', unit: '', stock: '', safetyStock: '', avgCost: '',
    supplier: '', location: '',
  }
  createOpen.value = true
}
async function doCreate() {
  const f = createForm.value
  if (!f.name.trim() || !f.skuCode.trim()) return
  try {
    const ok = await inv.addSku({
      name: f.name.trim(), skuCode: f.skuCode.trim(), category: f.category,
      spec: f.spec, unit: f.unit || '个', stock: Number(f.stock) || 0, safetyStock: Number(f.safetyStock) || 0,
      avgCost: Number(f.avgCost) || 0, supplier: f.supplier || undefined, location: f.location || undefined,
    })
    if (!ok) { toast.warning('无建档权限（inventory:consumable:edit）'); return }
    createOpen.value = false
    toast.success(`已创建库存项「${f.name}」，台账已刷新`)
  } catch (e) {
    toast.error('创建失败：' + errMsg(e))
  }
}

// ============ B10 项目配方 BOM ============
const bom = useBomStore()
onMounted(() => {
  bom.loadBoms().catch((e) => console.error('[bom] 配方加载失败', e))
  bom.loadExceptions().catch((e) => console.error('[bom] 扣料异常加载失败', e))
})

const bomScopeOptions = [
  { value: 'STORE', label: '本店配方' },
  { value: 'GROUP', label: '集团模板' },
]

const bomDrawerOpen = ref(false)
const bomForm = ref({ projectName: '', skuCode: '', qty: '1' })
const bomSkuOptions = computed(() => bom.skuOptions())

function openBomDrawer() {
  bomForm.value = { projectName: '', skuCode: '', qty: '1' }
  bomDrawerOpen.value = true
}

function pickQuickProject(name: string) {
  bomForm.value.projectName = name
}

async function doSaveBom() {
  const f = bomForm.value
  if (!f.projectName.trim() || !f.skuCode || !Number(f.qty) || Number(f.qty) <= 0) {
    toast.warning('请填写项目名、选择 SKU 并填写正整数用量')
    return
  }
  try {
    await bom.saveBom({
      projectName: f.projectName.trim(),
      skuCode: f.skuCode,
      qty: Math.floor(Number(f.qty)),
      enabled: true,
    })
    bomDrawerOpen.value = false
    toast.success(`已保存「${f.projectName.trim()}」配方，划扣该项目时将按配方自动扣料`)
  } catch (e) {
    toast.error('配方保存失败：' + errMsg(e))
  }
}

async function doToggleBom(b: ProjectBomDTO) {
  try {
    await bom.toggleEnabled(b)
  } catch (e) {
    toast.error('启停失败：' + errMsg(e))
  }
}

function bomScopeLabel(code: string) {
  return code === 'GROUP' ? '集团' : code
}

// ============ B10 扣料异常 ============
const excTabs = [
  { value: 'PENDING', label: '待处理' },
  { value: 'RESOLVED', label: '已处理' },
  { value: '', label: '全部' },
]

function fmtDateTime(iso?: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}/${d.getDate()} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const excBusy = ref<string | null>(null)

async function doRetry(excId: string) {
  excBusy.value = excId
  try {
    await bom.retry(excId)
    toast.success('已重新自动扣料，异常单已核销')
  } catch (e) {
    toast.error('重试失败：' + errMsg(e))
  } finally {
    excBusy.value = null
  }
}

async function doResolve(excId: string) {
  excBusy.value = excId
  try {
    await bom.markResolved(excId)
    toast.success('已标记为手工处理')
  } catch (e) {
    toast.error('标记失败：' + errMsg(e))
  } finally {
    excBusy.value = null
  }
}
</script>

<template>
  <div class="iv">
    <!-- KPI -->
    <div class="iv__kpis">
      <CKpi :value="String(kpi.sku)" label="SKU 总数" tone="brand" icon="package" />
      <CKpi :value="String(kpi.low)" label="低库存预警" tone="warning" icon="alert" />
      <CKpi :value="String(kpi.out)" label="缺货项" tone="danger" icon="alert" />
      <CKpi :value="fmtMoney(kpi.value)" label="库存总值" tone="success" icon="package" />
    </div>

    <div class="iv__body">
      <!-- 左：SKU 列表 -->
      <CCard padding="none" class="iv__list">
        <div class="iv__toolbar">
          <div class="iv__cats">
            <button v-for="c in catOptions" :key="c.value"
                    :class="{ 'is-active': inv.filterCategory === c.value }"
                    @click="inv.filterCategory = c.value as any">{{ c.label }}</button>
          </div>
          <CInput v-model="inv.keyword" placeholder="搜索名称/编码/规格" :error="false" class="iv__search" />
          <CButton variant="primary" size="sm" v-perm.disable="'inventory:consumable:edit'" @click="openCreate">
            <CIcon name="plus" :size="14" />新建 SKU
          </CButton>
        </div>
        <div class="iv__table-wrap">
          <table class="itbl">
            <thead>
              <tr>
                <th>SKU / 名称</th>
                <th>分类</th>
                <th class="num">库存</th>
                <th class="num">安全库存</th>
                <th class="num">均价</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in inv.filteredSkus" :key="s.id"
                  :class="{ 'is-active': inv.selectedId === s.id, 'row--low': inv.stockStatus(s) !== 'NORMAL' }"
                  @click="inv.selectedId = s.id">
                <td>
                  <div class="sku__name">{{ s.name }}</div>
                  <div class="sku__code">{{ s.skuCode }} · {{ s.spec }}</div>
                </td>
                <td><span class="cat-tag">{{ inv.categoryLabel(s.category) }}</span></td>
                <td class="num"><b :class="'stock--' + inv.stockStatus(s).toLowerCase()">{{ s.stock }}</b> {{ s.unit }}</td>
                <td class="num c-text3">{{ s.safetyStock }}</td>
                <td class="num">{{ fmtMoney(s.avgCost) }}</td>
                <td><CStatusPill :status="statusInfo(inv.stockStatus(s)).status" dot>{{ statusInfo(inv.stockStatus(s)).text }}</CStatusPill></td>
              </tr>
            </tbody>
          </table>
          <div v-if="inv.filteredSkus.length === 0" class="empty">未找到匹配的库存项</div>
        </div>
      </CCard>

      <!-- 右：详情 + 流水 -->
      <CCard v-if="inv.selected" padding="lg" class="iv__detail">
        <div class="det__head">
          <div>
            <h3 class="det__title">{{ inv.selected.name }}</h3>
            <div class="det__sub">
              <span>{{ inv.selected.skuCode }}</span>
              <span>{{ inv.selected.spec }}</span>
              <span v-if="inv.selected.supplier"><CIcon name="package" :size="12" /> {{ inv.selected.supplier }}</span>
              <span v-if="inv.selected.location">货位 {{ inv.selected.location }}</span>
            </div>
          </div>
          <CStatusPill :status="statusInfo(inv.stockStatus(inv.selected)).status">{{ statusInfo(inv.stockStatus(inv.selected)).text }}</CStatusPill>
        </div>

        <div class="det__nums">
          <div class="dnum">
            <span class="dnum__l">当前库存</span>
            <span class="dnum__v" :class="'stock--' + inv.stockStatus(inv.selected).toLowerCase()">{{ inv.selected.stock }} <i>{{ inv.selected.unit }}</i></span>
          </div>
          <div class="dnum">
            <span class="dnum__l">安全库存</span>
            <span class="dnum__v">{{ inv.selected.safetyStock }}</span>
          </div>
          <div class="dnum">
            <span class="dnum__l">移动均价</span>
            <span class="dnum__v">{{ fmtMoney(inv.selected.avgCost) }}</span>
          </div>
          <div class="dnum">
            <span class="dnum__l">库存金额</span>
            <span class="dnum__v">{{ fmtMoney(inv.selected.stock * inv.selected.avgCost) }}</span>
          </div>
        </div>

        <div v-if="canEdit" class="det__ops">
          <CButton v-if="canStockIn" variant="primary" size="sm" @click="openOp('IN')"><CIcon name="upload" :size="14" /> 入库</CButton>
          <CButton v-if="canStockOut" variant="secondary" size="sm" :disabled="inv.selected.stock === 0" @click="openOp('OUT')"><CIcon name="export" :size="14" /> 出库</CButton>
          <CButton v-if="canLoss" variant="danger" size="sm" :disabled="inv.selected.stock === 0" @click="openOp('LOSS')"><CIcon name="alert" :size="14" /> 报损</CButton>
        </div>
        <div v-else class="det__readonly">
          <CStatusPill status="disabled">只读（无库存操作权限）</CStatusPill>
        </div>

        <div class="det__flow-h">
          <CIcon name="order" :size="14" /> 出入库流水
        </div>
        <div class="flow">
          <div v-for="t in inv.txnsOfSku(inv.selected.id)" :key="t.id" class="flow__row">
            <div class="flow__left">
              <span class="flow__type" :class="'flow__type--' + t.type.toLowerCase()">
                {{ txnTypePill(t.type).text }}
              </span>
              <span class="flow__remark">{{ t.remark }}</span>
            </div>
            <div class="flow__right">
              <span class="flow__qty" :class="{ 'qty--in': t.quantity > 0, 'qty--out': t.quantity < 0 }">
                {{ t.quantity > 0 ? '+' : '' }}{{ t.quantity }}
              </span>
              <span class="flow__meta">{{ t.operator }} · {{ fmtDate(t.createdAt) }}</span>
            </div>
          </div>
          <div v-if="inv.txnsOfSku(inv.selected.id).length === 0" class="flow__empty">暂无流水记录</div>
        </div>
      </CCard>

      <CCard v-else padding="lg" class="iv__detail iv__detail--empty">
        <div class="empty-big">
          <CIcon name="box" :size="40" />
          <p>从左侧选择一个库存项查看详情与流水</p>
        </div>
      </CCard>
    </div>

    <!-- B10 项目配方 BOM：划扣核销后按项目自动扣料的配方维护（本店行覆盖集团模板，按 SKU 去重） -->
    <CCard padding="none">
      <div class="iv__toolbar">
        <div class="iv__cats">
          <button v-for="t in bomScopeOptions" :key="t.value"
                  v-show="t.value === 'STORE' || bom.canViewGroup"
                  :class="{ 'is-active': bom.scope === t.value }"
                  @click="bom.switchScope(t.value as any)">{{ t.label }}</button>
        </div>
        <CInput v-model="bom.bomProject" placeholder="按项目名筛选" :error="false" class="iv__search" />
        <CButton variant="primary" size="sm" v-perm.disable="'inventory:consumable:edit'" @click="openBomDrawer">
          <CIcon name="plus" :size="14" />添加配方
        </CButton>
      </div>
      <div class="bom__hint">
        <CIcon name="alert" :size="13" />
        划扣核销事务提交后，系统按「项目名」匹配配方自动扣减耗材并结转耗材成本：本店配方优先，未配置时回落集团模板；项目未配 BOM 则静默跳过，扣料失败（如库存不足）不影响划扣，登记到下方「扣料异常」可补货后重试。
      </div>
      <div class="iv__table-wrap">
        <table class="itbl">
          <thead>
            <tr>
              <th>治疗项目</th>
              <th>SKU / 耗材</th>
              <th class="num">单次用量</th>
              <th>适用范围</th>
              <th>状态</th>
              <th>最近维护</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in bom.filteredBoms" :key="b.bomId">
              <td><div class="sku__name">{{ b.projectName }}</div></td>
              <td>
                <div class="sku__name">{{ bom.skuDisplayName(b) }}</div>
                <div class="sku__code">{{ b.skuCode }}</div>
              </td>
              <td class="num"><b>{{ b.qty }}</b></td>
              <td><span class="cat-tag">{{ bomScopeLabel(b.storeCode) }}</span></td>
              <td>
                <CStatusPill :status="b.enabled ? 'success' : 'disabled'" dot>{{ b.enabled ? '启用' : '停用' }}</CStatusPill>
                <CButton variant="ghost" size="sm" class="bom__toggle"
                         v-perm.disable="'inventory:consumable:edit'"
                         @click="doToggleBom(b)">{{ b.enabled ? '停用' : '启用' }}</CButton>
              </td>
              <td class="c-text3">{{ b.updatedBy || '—' }} · {{ fmtDateTime(b.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="bom.filteredBoms.length === 0" class="empty">
          {{ bom.scope === 'GROUP' ? '集团模板暂无配方' : '本店暂无专属配方（未配置时自动回落集团模板）' }}
        </div>
      </div>
    </CCard>

    <!-- B10 扣料异常：划扣成功但自动扣料失败（库存不足/服务不可用）的登记，可重试或手工销项 -->
    <CCard padding="none">
      <div class="iv__toolbar">
        <div class="iv__cats">
          <button v-for="t in excTabs" :key="t.value || 'ALL'"
                  :class="{ 'is-active': bom.excStatus === t.value }"
                  @click="bom.switchExcStatus(t.value as any)">{{ t.label }}</button>
        </div>
        <span class="bom__exc-count" v-if="bom.pendingExceptions.length > 0">
          <CStatusPill status="danger" dot>{{ bom.pendingExceptions.length }} 笔待处理</CStatusPill>
        </span>
      </div>
      <div class="iv__table-wrap">
        <table class="itbl">
          <thead>
            <tr>
              <th>异常单号 / 划扣单</th>
              <th>门店 / 项目</th>
              <th>失败原因</th>
              <th class="num">失败次数</th>
              <th>状态</th>
              <th>时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in bom.exceptions" :key="e.excId" :class="{ 'row--low': e.status === 'PENDING' }">
              <td>
                <div class="sku__name">{{ e.excId }}</div>
                <div class="sku__code">划扣 {{ e.writeoffId }}</div>
              </td>
              <td>
                <div class="sku__name">{{ e.storeName || e.storeCode }}</div>
                <div class="sku__code">{{ e.projectName || '—' }}</div>
              </td>
              <td>
                <span class="bom__reason">{{ e.reason }}</span>
                <div v-for="s in (e.shortages || [])" :key="s.skuCode" class="sku__code">
                  {{ s.skuName }}（{{ s.skuCode }}）需 {{ s.needQty }}{{ s.unit }} · 现存 {{ s.stockQty }}{{ s.unit }} · 缺 {{ s.needQty - s.stockQty }}{{ s.unit }}
                </div>
              </td>
              <td class="num">{{ e.failCount }}</td>
              <td>
                <CStatusPill :status="e.status === 'PENDING' ? 'danger' : 'success'" dot>
                  {{ e.status === 'PENDING' ? '待处理' : '已处理' }}
                </CStatusPill>
              </td>
              <td class="c-text3">
                <div>{{ fmtDateTime(e.createdAt) }}</div>
                <div class="sku__code" v-if="e.resolvedAt">{{ e.resolvedBy || '—' }} 销项 {{ fmtDateTime(e.resolvedAt) }}</div>
              </td>
              <td>
                <div v-if="e.status === 'PENDING'" class="bom__ops">
                  <CButton variant="primary" size="sm" :disabled="excBusy === e.excId"
                          v-perm.disable="'inventory:consumable:edit'"
                          @click="doRetry(e.excId)">补货后重试</CButton>
                  <CButton variant="ghost" size="sm" :disabled="excBusy === e.excId"
                          v-perm.disable="'inventory:consumable:edit'"
                          @click="doResolve(e.excId)">标记已处理</CButton>
                </div>
                <span v-else class="c-text3">—</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="bom.exceptions.length === 0" class="empty">暂无扣料异常记录</div>
      </div>
    </CCard>

    <!-- 出入库/报损抽屉 -->
    <CDrawer v-model:show="opOpen" :title="opTitle + ' · ' + (inv.selected?.name || '')" size="sm">
      <div class="opform">
        <CInput v-model="opQty" label="数量" type="number" :placeholder="'请输入' + opTitle + '数量'" />
        <CInput v-if="opType === 'IN'" v-model="opCost" label="单价（元）" type="number" placeholder="入库单价，留空沿用均价" />
        <CInput v-model="opRemark" label="备注" :placeholder="opType === 'LOSS' ? '请说明报损原因' : '备注（可选）'" />
        <div class="opform__hint" v-if="opType === 'LOSS'">
          <CIcon name="alert" :size="13" /> 报损不直接扣库：提交审批中心终审（损失 ≥¥5000 需双签），通过后由系统扣减库存并计入损耗成本。
        </div>
        <div class="opform__hint" v-else-if="opType === 'OUT'">
          <CIcon name="alert" :size="13" /> 领用不直接扣库：提交审批中心双签（店长一审 → 财务终审），通过后由系统扣减库存并计入耗材成本。
        </div>
      </div>
      <div class="drawer__ops">
        <CButton variant="ghost" size="sm" @click="opOpen = false">取消</CButton>
        <CButton :variant="opType === 'LOSS' ? 'danger' : 'primary'" size="sm" :disabled="!opQty || Number(opQty) <= 0" @click="doOp">
          确认{{ opTitle }}
        </CButton>
      </div>
    </CDrawer>
    <!-- 新建 SKU 抽屉 -->
    <CDrawer v-model:show="createOpen" title="新建库存 SKU" size="sm">
      <div class="opform">
        <CInput v-model="createForm.name" label="名称 *" placeholder="如：润百颜玻尿酸" />
        <CInput v-model="createForm.skuCode" label="SKU 编码 *" placeholder="如：HC-003" />
        <CSelect v-model="createForm.category" :options="categoryOptions" label="分类" width="100%" />
        <CInput v-model="createForm.spec" label="规格" placeholder="如：1ml/支" />
        <CInput v-model="createForm.unit" label="单位" placeholder="如：支、盒、瓶" />
        <CInput v-model="createForm.stock" label="初始库存" placeholder="0" />
        <CInput v-model="createForm.safetyStock" label="安全库存" placeholder="0" />
        <CInput v-model="createForm.avgCost" label="均价（元）" placeholder="0" />
        <CInput v-model="createForm.supplier" label="供应商" placeholder="如：华东医药" />
        <CInput v-model="createForm.location" label="货位" placeholder="如：A-01" />
      </div>
      <div class="drawer__ops">
        <CButton variant="ghost" size="sm" @click="createOpen = false">取消</CButton>
        <CButton variant="primary" size="sm" :disabled="!createForm.name.trim() || !createForm.skuCode.trim()" @click="doCreate">
          创建
        </CButton>
      </div>
    </CDrawer>
    <!-- B10 添加配方抽屉 -->
    <CDrawer v-model:show="bomDrawerOpen" :title="bom.scope === 'GROUP' ? '添加集团模板配方' : '添加本店配方'" size="sm">
      <div class="opform">
        <div class="bomform__row">
          <label class="bomform__label">治疗项目 *</label>
          <CInput v-model="bomForm.projectName" placeholder="与订单/划扣单项目名一致，如：水光针单次" :error="false" />
          <div class="bomform__chips">
            <button v-for="p in bom.QUICK_PROJECTS" :key="p" type="button"
                    :class="{ 'is-active': bomForm.projectName === p }"
                    @click="pickQuickProject(p)">{{ p }}</button>
          </div>
        </div>
        <CSelect v-model="bomForm.skuCode" :options="bomSkuOptions" label="耗材 SKU *" width="100%" />
        <CInput v-model="bomForm.qty" label="单次用量 *" type="number" placeholder="每做 1 次该项目消耗的数量（正整数）" />
        <div class="opform__hint">
          <CIcon name="alert" :size="13" />
          {{ bom.scope === 'GROUP'
            ? '集团模板对全部门店生效；门店可另配本店行覆盖同名 SKU 用量。'
            : '本店配方优先于集团模板（按 SKU 去重覆盖）；同名项目+SKU 重复提交即更新用量。' }}
        </div>
      </div>
      <div class="drawer__ops">
        <CButton variant="ghost" size="sm" @click="bomDrawerOpen = false">取消</CButton>
        <CButton variant="primary" size="sm"
                 :disabled="!bomForm.projectName.trim() || !bomForm.skuCode || !(Number(bomForm.qty) > 0)"
                 @click="doSaveBom">保存配方</CButton>
      </div>
    </CDrawer>
  </div>
</template>

<style scoped>
.iv { display: flex; flex-direction: column; gap: var(--s-lg); }
.iv__kpis { display: flex; gap: var(--s-md); flex-wrap: wrap; }
.iv__kpis :deep(.ckpi) { flex: 1 1 0; min-width: 168px; }

.iv__body { display: grid; grid-template-columns: 1fr 400px; gap: var(--s-lg); align-items: start; }

/* 工具栏 */
.iv__toolbar { display: flex; gap: var(--s-md); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border); align-items: center; flex-wrap: wrap; }
.iv__toolbar :deep(.cinput) { max-width: 220px; }
.iv__search { margin-left: auto; }
.iv__cats { display: flex; gap: var(--s-xxs); flex-wrap: wrap; }
.iv__cats button { border: none; background: none; padding: 4px 12px; border-radius: var(--r-sm); font-size: var(--t-xs); color: var(--c-text-2); cursor: pointer; }
.iv__cats button.is-active { background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }
.iv__table-wrap { max-height: 620px; overflow-y: auto; }

/* 表格 */
.itbl { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.itbl th { position: sticky; top: 0; background: var(--c-surface-muted, #f7f8fa); text-align: left; padding: var(--s-sm) var(--s-lg); font-weight: 600; color: var(--c-text-2); font-size: var(--t-xs); border-bottom: 1px solid var(--c-border); z-index: 1; }
.itbl td { padding: var(--s-sm) var(--s-lg); border-bottom: 1px solid var(--c-border); vertical-align: middle; }
.itbl tbody tr { cursor: pointer; transition: background .15s; }
.itbl tbody tr:hover { background: var(--c-surface-muted, #f7f8fa); }
.itbl tbody tr.is-active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.itbl .num { text-align: right; font-variant-numeric: tabular-nums; }
.c-text3 { color: var(--c-text-3); }
.sku__name { font-weight: 600; color: var(--c-text); }
.sku__code { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.cat-tag { font-size: var(--t-xs); padding: 1px 7px; border-radius: var(--r-sm); background: var(--c-surface-muted, #f0f2f5); color: var(--c-text-2); white-space: nowrap; }
.stock--normal { color: var(--c-success-fg); }
.stock--low { color: var(--c-warning-fg); }
.stock--out { color: var(--c-danger-fg); }
.empty { text-align: center; color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-xl) 0; }

/* 详情 */
.det__head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border); }
.det__title { margin: 0 0 var(--s-xs); font-size: var(--t-lg); font-weight: 700; }
.det__sub { display: flex; flex-wrap: wrap; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); }
.det__sub span { display: inline-flex; align-items: center; gap: 4px; }
.det__nums { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-sm); margin: var(--s-md) 0; }
.dnum { background: var(--c-surface-muted, #f7f8fa); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); }
.dnum__l { display: block; font-size: var(--t-xs); color: var(--c-text-3); }
.dnum__v { display: block; font-size: var(--t-lg); font-weight: 700; margin-top: 2px; font-variant-numeric: tabular-nums; }
.dnum__v i { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); font-style: normal; }
.det__ops { display: flex; gap: var(--s-xs); margin-bottom: var(--s-md); }
.det__readonly { padding: var(--s-sm) 0; margin-bottom: var(--s-md); }

.det__flow-h { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); font-weight: 600; margin-bottom: var(--s-sm); padding-top: var(--s-md); border-top: 1px solid var(--c-border); }
.flow { max-height: 240px; overflow-y: auto; }
.flow__row { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.flow__left { display: flex; align-items: center; gap: var(--s-sm); }
.flow__type { font-size: var(--t-xs); padding: 1px 7px; border-radius: var(--r-sm); }
.flow__type--in { background: var(--c-success-bg); color: var(--c-success-fg); }
.flow__type--out, .flow__type--requisition { background: var(--c-brand-soft); color: var(--c-brand); }
.flow__type--loss { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.flow__type--adjust { background: var(--c-surface-muted, #f0f2f5); color: var(--c-text-2); }
.flow__remark { font-size: var(--t-xs); color: var(--c-text-2); }
.flow__right { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.flow__qty { font-weight: 700; font-size: var(--t-sm); font-variant-numeric: tabular-nums; }
.qty--in { color: var(--c-success-fg); }
.qty--out { color: var(--c-danger-fg); }
.flow__meta { font-size: 10px; color: var(--c-text-3); }
.flow__empty { text-align: center; font-size: var(--t-xs); color: var(--c-text-3); padding: var(--s-md) 0; }

.iv__detail--empty { display: flex; align-items: center; justify-content: center; min-height: 400px; }
.empty-big { text-align: center; color: var(--c-text-3); }
.empty-big p { margin-top: var(--s-md); font-size: var(--t-sm); }

.opform { display: flex; flex-direction: column; gap: var(--s-md); }
.opform__hint { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-warning-fg); background: var(--c-warning-bg); padding: var(--s-sm); border-radius: var(--r-md); }
.drawer__ops { display: flex; justify-content: flex-end; gap: var(--s-xs); margin-top: var(--s-lg); }

/* B10 BOM 配方 & 扣料异常 */
.bom__hint { display: flex; align-items: flex-start; gap: 6px; font-size: var(--t-xs); color: var(--c-text-2); background: var(--c-surface-muted, #f7f8fa); padding: var(--s-sm) var(--s-lg); border-bottom: 1px solid var(--c-border); line-height: 1.6; }
.bom__toggle { margin-left: var(--s-xs); }
.bom__reason { font-size: var(--t-xs); color: var(--c-danger-fg); line-height: 1.5; }
.bom__ops { display: flex; gap: var(--s-xxs); flex-wrap: wrap; }
.bom__exc-count { margin-left: auto; display: inline-flex; align-items: center; }
.bomform__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.bomform__label { font-size: var(--t-xs); color: var(--c-text-2); font-weight: 600; }
.bomform__chips { display: flex; gap: var(--s-xxs); flex-wrap: wrap; }
.bomform__chips button { border: 1px solid var(--c-border); background: none; padding: 3px 10px; border-radius: var(--r-sm); font-size: var(--t-xs); color: var(--c-text-2); cursor: pointer; }
.bomform__chips button.is-active { background: var(--c-brand-soft); border-color: var(--c-brand); color: var(--c-brand); font-weight: 600; }

@media (max-width: 900px) {
  .iv__body { grid-template-columns: 1fr; }
}
</style>
