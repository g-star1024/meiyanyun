import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { listStores } from '@/api/org'

// ============================================================
// 全局门店上下文 store（门店中台 = 单门店视角）
// - 门店列表来自真实 /api/stores（store-service）
// - 当前门店默认锁定 SST01 上海徐汇店，localStorage 持久化
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
const DEFAULT_STORE_CODE = 'SST01'

export const useStoreContext = defineStore('storeContext', () => {
  const stores = ref<StoreOption[]>([])
  const currentStoreCode = ref<string>(DEFAULT_STORE_CODE)
  const loaded = ref(false)

  const currentStore = computed<StoreOption | undefined>(() =>
    stores.value.find((s) => s.storeCode === currentStoreCode.value),
  )
  /** 当前门店名（侧栏选择器展示用） */
  const currentStoreName = computed<string>(() => currentStore.value?.storeName || currentStoreCode.value)
  /** 门店名列表（侧栏选择器 v-for 用，保持组件原 props 契约） */
  const storeNames = computed<string[]>(() => stores.value.map((s) => s.storeName))

  /** 从 localStorage 恢复上次选择 */
  function restore() {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved && saved.trim()) currentStoreCode.value = saved.trim()
  }

  /** 拉取真实门店列表；拉取失败不抛出（侧栏退回默认门店名） */
  async function loadStores(force = false) {
    if (loaded.value && !force) return
    try {
      const res = await listStores()
      const list = (res.data || []) as StoreOption[]
      stores.value = list
      // 若当前选中不在列表里（门店下线/旧缓存），回退默认店
      if (!list.some((s) => s.storeCode === currentStoreCode.value)) {
        currentStoreCode.value = list.some((s) => s.storeCode === DEFAULT_STORE_CODE)
          ? DEFAULT_STORE_CODE
          : (list[0]?.storeCode ?? DEFAULT_STORE_CODE)
      }
      loaded.value = true
    } catch {
      // 网络/后端异常：保留默认 SST01，不阻断页面
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
