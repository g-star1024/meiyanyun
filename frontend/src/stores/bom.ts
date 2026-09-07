// ============================================================
// BOM 项目配方 & 扣料异常 store（B10）
// 配方权威源：store-service /stores/project-boms（配方归库存域维护）；
// 异常权威源：txn-service /txn/bom-exceptions（划扣后 afterCommit 自动扣料的失败登记）。
// 门店角色只能看/维护本店配方；集团模板（GROUP）仅集团/品牌角色可见可写（后端强制，前端按 scope 收口 UI）。
// SKU 选项复用库存台账（inventory store），项目名为自由文本（与订单/划扣单 project 勾兑）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  listProjectBoms, upsertProjectBom, listBomExceptions,
  retryBomException, resolveBomException,
  type ProjectBomDTO, type BomExceptionDTO,
} from '@/api/bom'
import { useAuthStore } from './auth'
import { useStoreContext } from './storeContext'
import { useInventoryStore } from './inventory'

/** 高频治疗项目快捷项（与后端播种配方一致，供录入时点选；项目名仍为自由文本） */
const QUICK_PROJECTS = ['水光针单次', '光子嫩肤', '热玛吉全面部', '小气泡清洁']

export const useBomStore = defineStore('bom', () => {
  const auth = useAuthStore()
  const ctx = useStoreContext()

  /** 配方查看范围：STORE=本店（门店角色强制）；GROUP=集团模板（仅集团/品牌角色可切） */
  const scope = ref<'STORE' | 'GROUP'>('STORE')
  const boms = ref<ProjectBomDTO[]>([])
  const bomProject = ref('')
  const bomsLoaded = ref(false)

  const exceptions = ref<BomExceptionDTO[]>([])
  const excStatus = ref<'' | 'PENDING' | 'RESOLVED'>('PENDING')
  const excLoaded = ref(false)

  /** 集团/品牌/超管：可查看维护集团模板（与后端 resolveWriteStoreCode 口径一致） */
  const canViewGroup = computed(() => {
    const s = auth.user?.scope
    return auth.isSuper || s === 'GROUP' || s === 'BRAND'
  })
  const canEditBom = computed(() => auth.can('inventory:consumable:edit'))

  /** 当前范围下经项目名过滤的配方行 */
  const filteredBoms = computed(() => {
    const kw = bomProject.value.trim()
    if (!kw) return boms.value
    return boms.value.filter((b) => b.projectName.includes(kw))
  })

  const pendingExceptions = computed(() => exceptions.value.filter((e) => e.status === 'PENDING'))
  const resolvedExceptions = computed(() => exceptions.value.filter((e) => e.status === 'RESOLVED'))

  function scopeStoreCode(): string {
    if (scope.value === 'GROUP') return 'GROUP'
    return ctx.currentStoreCode
  }

  async function loadBoms(force = false): Promise<void> {
    if (bomsLoaded.value && !force) return
    const resp = await listProjectBoms({ storeCode: scopeStoreCode() })
    boms.value = resp.data ?? []
    bomsLoaded.value = true
  }

  async function switchScope(next: 'STORE' | 'GROUP') {
    if (next === 'GROUP' && !canViewGroup.value) return
    scope.value = next
    bomsLoaded.value = false
    await loadBoms(true)
  }

  /**
   * 新增/更新配方行。scope=GROUP 时落集团模板（门店角色后端会拒绝），否则落当前门店。
   * 同 项目+门店+SKU 重复提交即更新用量/启用态（后端幂等）。
   */
  async function saveBom(cmd: { projectName: string; skuCode: string; qty: number; enabled: boolean }): Promise<void> {
    await upsertProjectBom({ ...cmd, storeCode: scopeStoreCode() })
    await loadBoms(true)
  }

  /** 快捷启停：点击行内开关直接 upsert 翻转 enabled */
  async function toggleEnabled(b: ProjectBomDTO): Promise<void> {
    await upsertProjectBom({
      projectName: b.projectName,
      storeCode: b.storeCode === 'GROUP' ? 'GROUP' : b.storeCode,
      skuCode: b.skuCode,
      qty: b.qty,
      enabled: !b.enabled,
    })
    await loadBoms(true)
  }

  async function loadExceptions(force = false): Promise<void> {
    if (excLoaded.value && !force) return
    const resp = await listBomExceptions({
      status: excStatus.value || undefined,
      storeCode: ctx.currentStoreCode,
    })
    exceptions.value = resp.data ?? []
    excLoaded.value = true
  }

  async function switchExcStatus(next: '' | 'PENDING' | 'RESOLVED') {
    excStatus.value = next
    excLoaded.value = false
    await loadExceptions(true)
  }

  /** 重试自动扣料：库存补齐后重放；成功后端自动置 RESOLVED 并补记成本事件 */
  async function retry(excId: string): Promise<void> {
    await retryBomException(excId)
    await loadExceptions(true)
  }

  /** 手工标记已处理（已走领用审批手工补单等场景） */
  async function markResolved(excId: string): Promise<void> {
    await resolveBomException(excId)
    await loadExceptions(true)
  }

  /** 配方录入的 SKU 选项：复用当前门店库存台账（编码 + 名称 + 单位） */
  function skuOptions() {
    const inv = useInventoryStore()
    return inv.skus.map((s) => ({ value: s.skuCode, label: `${s.skuCode} · ${s.name}（${s.unit}）`, unit: s.unit }))
  }

  /** 配方行 SKU 展示名：门店行后端富化 skuName；集团模板行用本地台账兜底，找不到显示编码 */
  function skuDisplayName(b: ProjectBomDTO): string {
    if (b.skuName) return b.skuName
    const inv = useInventoryStore()
    return inv.skus.find((s) => s.skuCode === b.skuCode)?.name || b.skuCode
  }

  return {
    scope, boms, bomProject, bomsLoaded,
    exceptions, excStatus, excLoaded,
    canViewGroup, canEditBom,
    filteredBoms, pendingExceptions, resolvedExceptions, QUICK_PROJECTS,
    loadBoms, switchScope, saveBom, toggleEnabled,
    loadExceptions, switchExcStatus, retry, markResolved,
    skuOptions, skuDisplayName,
  }
})
