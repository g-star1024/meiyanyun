import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { listStores } from '@/api/org'
import { useAuthStore } from '@/stores/auth'

// ============================================================
// 全局门店上下文 store（门店中台 = 单门店视角）
// - 门店列表来自真实 /api/stores（store-service）
// - 当前门店选择优先级：localStorage 上次选择 → 登录人所属门店（JWT/storeId）
//   → 列表首家；不再内置任何写死的演示门店码（各环境主数据不同）
// - 业务操作页（预约/收银/核销/退款/咨询…）统一按 currentStoreCode 过滤，
//   严禁跨门店混排；集团多店对比屏（M1 系列）不走此上下文。
// ============================================================

export interface StoreOption {
  storeCode: string
  storeName: string
  region?: string
  nature?: string
  status?: string
  openDate?: string
}

const STORAGE_KEY = 'meiyun:store-code'

export const useStoreContext = defineStore('storeContext', () => {
  const stores = ref<StoreOption[]>([])
  const currentStoreCode = ref<string>('')
  const loaded = ref(false)

  const currentStore = computed<StoreOption | undefined>(() =>
    stores.value.find((s) => s.storeCode === currentStoreCode.value),
  )
  /** 当前门店名（侧栏选择器展示用）；未选定前为空串，由侧栏自行降级展示 */
  const currentStoreName = computed<string>(() => currentStore.value?.storeName || currentStoreCode.value)
  /** 门店名列表（侧栏选择器 v-for 用，保持组件原 props 契约） */
  const storeNames = computed<string[]>(() => stores.value.map((s) => s.storeName))

  /** 从 localStorage 恢复上次选择；旧版本可能缓存了演示库门店码（SST01），标记待列表校验 */
  function restore() {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved && saved.trim()) currentStoreCode.value = saved.trim()
  }

  /** 拉取真实门店列表；拉取失败不抛出（侧栏退回门店编码/空名，不阻断页面） */
  async function loadStores(force = false) {
    if (loaded.value && !force) return
    try {
      const res = await listStores()
      const list = (res.data || []) as StoreOption[]
      stores.value = list
      // 当前选中不在列表里（门店下线/旧缓存/演示码残留），按 登录人门店 → 列表首家 兜底
      if (!list.some((s) => s.storeCode === currentStoreCode.value)) {
        const auth = useAuthStore()
        const ownStore = auth.storeId
        currentStoreCode.value = list.some((s) => s.storeCode === ownStore)
          ? ownStore
          : (list[0]?.storeCode ?? '')
      }
      loaded.value = true
    } catch {
      // 网络/后端异常：尝试用登录人所属门店兜底（业务页按本店数据域过滤仍可用）
      if (!currentStoreCode.value) {
        const auth = useAuthStore()
        currentStoreCode.value = auth.storeId || ''
      }
      loaded.value = false
    }
  }

  /** 按门店编码切换 */
  function setStore(code: string) {
    if (!code || code === currentStoreCode.value) return
    currentStoreCode.value = code
    localStorage.setItem(STORAGE_KEY, code)
  }

  /** 侧栏选择器回传的是「门店名」，据此反查编码 */
  function setStoreByName(name: string) {
    const hit = stores.value.find((s) => s.storeName === name)
    if (hit) setStore(hit.storeCode)
  }

  /** 初始化：恢复缓存 + 拉列表 */
  async function init() {
    restore()
    await loadStores()
  }

  return {
    stores,
    currentStoreCode,
    loaded,
    currentStore,
    currentStoreName,
    storeNames,
    loadStores,
    setStore,
    setStoreByName,
    init,
  }
})
