// ============================================================
// Settings API（设置中心参数）
// 后端就绪前：stores/settings.ts 用 DEFAULT_SETTINGS + localStorage 兜底。
// 后端就绪后：替换为以下真实端点（集团级/门店级分级鉴权）。
// ============================================================
import client from './client'
import type { AppSettings } from '@/config/settings'

/** 拉取当前门店可见的设置（集团基线 + 门店覆盖合并后） */
export const getSettings = (storeCode?: string) =>
  client.get<AppSettings>('/settings', { params: { storeCode } })

/** 更新集团级设置（需 settings:edit，集团超管） */
export const updateSystemSettings = (patch: Partial<AppSettings['system']>) =>
  client.patch<AppSettings>('/settings/system', patch)

/** 更新门店级设置（需 settings:edit，店长及以上；不得突破集团上限） */
export const updateStoreSettings = (patch: Partial<AppSettings['store']>) =>
  client.patch<AppSettings>('/settings/store', patch)
