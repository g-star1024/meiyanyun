// ============================================================
// 设置中心 store
// 所有业务可调参数从这里读取；页面禁止硬编码超时/阈值/有效期等数字。
// 后端就绪前：DEFAULT_SETTINGS + localStorage（按门店隔离）兜底。
// 写操作按数据域鉴权：system.* 需 settings:edit（集团），store.* 需 store settings:edit。
// 编辑采用"草稿 → 保存"模式：页面修改 draft，点保存才提交并写变更日志。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref, toRaw, watch } from 'vue'

/** 深拷贝纯数据（reactive proxy 无法用 structuredClone） */
function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(toRaw(v)))
}
import {
  DEFAULT_SETTINGS,
  type AppSettings,
  type SystemSettings,
  type StoreSettings,
  type SettingsChangeLog,
  signTierForAmount,
} from '@/config/settings'
import { useAuthStore } from './auth'

const STORAGE_KEY = 'meiyun.settings'
const LOG_KEY = 'meiyun.settings.log'

interface PersistShape {
  settings: AppSettings
  changeLog: SettingsChangeLog[]
}

function load(): PersistShape {
  const fallback: PersistShape = {
    settings: clone(DEFAULT_SETTINGS),
    changeLog: defaultLog(),
  }
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      fallback.settings = {
        version: DEFAULT_SETTINGS.version,
        system: { ...DEFAULT_SETTINGS.system, ...(parsed.system || parsed.settings?.system || {}) },
        store: { ...DEFAULT_SETTINGS.store, ...(parsed.store || parsed.settings?.store || {}) },
      }
    }
    const logRaw = localStorage.getItem(LOG_KEY)
    if (logRaw) fallback.changeLog = JSON.parse(logRaw)
  } catch {
    // ignore
  }
  return fallback
}

/** 种子日志，展示"保存自动写入审计日志" */
function defaultLog(): SettingsChangeLog[] {
  return [
    {
      id: 'log-1',
      time: '2026-08-14 09:32',
      operator: '张总监',
      group: '基础设置',
      field: '营业时间',
      change: '09:30-21:30 → 10:00-22:00',
      risk: 'LOW',
    },
    {
      id: 'log-2',
      time: '2026-08-10 14:15',
      operator: 'IT 李工',
      group: '基础设置',
      field: '系统版本号',
      change: 'v3.2.0 → v3.2.1',
      risk: 'MEDIUM',
    },
  ]
}

export const useSettingsStore = defineStore('settings', () => {
  const persisted = load()
  // committed = 已保存生效的值
  const committed = ref<AppSettings>(persisted.settings)
  // draft = 页面正在编辑的值
  const draft = ref<AppSettings>(clone(persisted.settings))
  const changeLog = ref<SettingsChangeLog[]>(persisted.changeLog)
  const auth = useAuthStore()

  const dirty = computed(
    () => JSON.stringify(draft.value) !== JSON.stringify(committed.value),
  )

  // 持久化
  watch(
    [committed, changeLog],
    () => {
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(committed.value))
        localStorage.setItem(LOG_KEY, JSON.stringify(changeLog.value))
      } catch {
        // ignore quota
      }
    },
    { deep: true },
  )

  const system = computed(() => committed.value.system)
  const store = computed(() => committed.value.store)

  function reset() {
    committed.value = clone(DEFAULT_SETTINGS)
    draft.value = clone(DEFAULT_SETTINGS)
  }

  /** 丢弃草稿，恢复到已保存值 */
  function discardDraft() {
    draft.value = clone(committed.value)
  }

  function appendLog(entry: Omit<SettingsChangeLog, 'id' | 'time' | 'operator'>) {
    const me = auth.user?.name || '当前用户'
    const now = new Date()
    const pad = (n: number) => String(n).padStart(2, '0')
    const time = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`
    changeLog.value = [
      {
        id: 'log-' + Date.now(),
        time,
        operator: me,
        ...entry,
      },
      ...changeLog.value,
    ].slice(0, 50)
  }

  /**
   * 保存草稿到生效配置。
   * @param entries 本次保存涉及的变更项（用于写审计日志）
   */
  function save(entries: Omit<SettingsChangeLog, 'id' | 'time' | 'operator'>[]) {
    if (!auth.can('settings:edit')) {
      console.warn('[settings] 无 settings:edit 权限，拒绝保存')
      return false
    }
    committed.value = clone(draft.value)
    for (const e of entries) appendLog(e)
    return true
  }

  /** 更新集团级草稿（编辑态，不立即生效） */
  function patchSystem(patch: Partial<SystemSettings>) {
    draft.value = { ...draft.value, system: { ...draft.value.system, ...patch } }
  }

  /** 更新门店级草稿 */
  function patchStore(patch: Partial<StoreSettings>) {
    draft.value = { ...draft.value, store: { ...draft.value.store, ...patch } }
  }

  /** 金额 → 签署层级（供退款/退卡/收银复用，读已生效值） */
  function tierFor(amount: number) {
    return signTierForAmount(amount, committed.value.system.dualSign)
  }

  return {
    // 已生效（业务模块读取）
    committed,
    system,
    store,
    // 编辑态（设置页读写）
    draft,
    dirty,
    changeLog,
    // actions
    reset,
    discardDraft,
    patchSystem,
    patchStore,
    save,
    tierFor,
  }
})
