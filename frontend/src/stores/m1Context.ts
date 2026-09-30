// M1 集团管控 · 三级层级选择上下文（棒②卡1 新建）。
// 层级模型：集团（单根）→ 区域（org_unit REGION 节点）→ 门店（org_unit STORE 节点挂 store 主数据）。
// 事实源定案：区域主数据 = org_unit 区域节点（prod 23/23 门店挂载六区 · store.region 中文短名冗余文本
//   与挂载 100% 一致已实证）；选择器选项复用 m1Org 树（零额外请求）。
// 选择状态：regionCode=区域节点 orgCode / storeCode=门店挂载的 store 主数据编码；'' = 全部。
// 持久化 localStorage；区域切换自动清空门店；中文短名桥接（去「事业部/大区」后缀）供
//   排行/图表等以 store.region 文本为键的既有口径做层级过滤（卡3 起全量后端参数化）。
import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useM1OrgStore } from './m1Org'

const STORAGE_KEY = 'meiyun:m1-scope'

export const useM1ContextStore = defineStore('m1Context', () => {
  const org = useM1OrgStore()

  const regionCode = ref('')
  const storeCode = ref('')

  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const saved = JSON.parse(raw)
      regionCode.value = typeof saved.regionCode === 'string' ? saved.regionCode : ''
      storeCode.value = typeof saved.storeCode === 'string' ? saved.storeCode : ''
    }
  } catch { /* 损坏即视为空选择 */ }

  watch([regionCode, storeCode], ([rc, sc]) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ regionCode: rc, storeCode: sc }))
    } catch { /* 隐私模式等写入失败不阻塞交互 */ }
  })

  watch(regionCode, () => { storeCode.value = '' })

  // ---- 级联选项（只列启用节点，排序沿用 org_unit.sort_no） ----
  const groupNode = computed(() => org.roots[0] ?? null)
  const groupLabel = computed(() => groupNode.value?.name ?? '集团')

  const regionNodes = computed(() =>
    org.nodes
      .filter((n) => n.type === 'REGION' && n.status === 'ACTIVE')
      .sort((a, b) => a.sort - b.sort),
  )

  const storeNodes = computed(() =>
    org.nodes
      .filter((n) =>
        n.type === 'STORE' && n.status === 'ACTIVE' && n.storeCode
        && (!regionCode.value || n.parentId === regionCode.value),
      )
      .sort((a, b) => a.sort - b.sort),
  )

  const regionOptions = computed(() => [
    { label: '全部区域', value: '' },
    ...regionNodes.value.map((n) => ({ label: n.name, value: n.id })),
  ])

  const storeOptions = computed(() => [
    { label: '全部门店', value: '' },
    ...storeNodes.value.map((n) => ({ label: n.name, value: n.storeCode! })),
  ])

  // ---- 桥接派生 ----
  /** 选中区域的中文短名（去「事业部/大区」后缀）：华东事业部→华东 · 华东大区→华东；'' = 未选区域 */
  const selectedRegionShort = computed(() => {
    const node = regionCode.value ? org.get(regionCode.value) : undefined
    return node ? node.name.replace(/(事业部|大区)$/, '') : ''
  })

  /** 面包屑文本：美颜集团 / 华东事业部 / 上海一店 */
  const pathLabel = computed(() => {
    const parts = [groupLabel.value]
    const region = regionCode.value ? org.get(regionCode.value) : undefined
    if (region) parts.push(region.name)
    const store = storeCode.value
      ? storeNodes.value.find((n) => n.storeCode === storeCode.value)
      : undefined
    if (store) parts.push(store.name)
    return parts.join(' / ')
  })

  function reset() {
    regionCode.value = ''
    storeCode.value = ''
  }

  return {
    regionCode, storeCode,
    groupNode, groupLabel, regionNodes, storeNodes,
    regionOptions, storeOptions,
    selectedRegionShort, pathLabel, reset,
  }
})
