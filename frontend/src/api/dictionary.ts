// ============================================================
// Dictionary API（字典管理 - 支持动态增删改查）
// ============================================================
import client from './client'

export interface DictionaryDTO {
  id?: number
  category: string
  dictCode: string
  dictValue: string
  dictLabel: string
  dictColor?: string
  dictIcon?: string
  sortOrder?: number
  enabled?: boolean
  description?: string
  operator?: string
  createdAt?: string
  updatedAt?: string
}

export interface DictionaryStatistics {
  [category: string]: number
}

/** 获取所有启用的字典（按分类分组，业务端） */
export const getAllDictionaries = () =>
  client.get<Record<string, DictionaryDTO[]>>('/customer/dictionaries')

/** 管理端：获取全部字典（含停用项，按分类分组） */
export const getManageDictionaries = () =>
  client.get<Record<string, DictionaryDTO[]>>('/customer/dictionaries/manage')

/** 管理端：按分类获取全部字典（含停用项） */
export const getManageDictionariesByCategory = (category: string) =>
  client.get<DictionaryDTO[]>(`/customer/dictionaries/manage/category/${encodeURIComponent(category)}`)

/** 切换字典项启停（更新整条记录，后端审计 ENABLE/DISABLE） */
export const setDictionaryEnabled = (id: number, data: DictionaryDTO) =>
  client.put<DictionaryDTO>(`/customer/dictionaries/${id}`, data)

/** 按分类获取字典 */
export const getDictionariesByCategory = (category: string) =>
  client.get<DictionaryDTO[]>(`/customer/dictionaries/category/${category}`)

/** 按分类和编码获取字典 */
export const getDictionariesByCode = (category: string, code: string) =>
  client.get<DictionaryDTO[]>(`/customer/dictionaries/category/${category}/code/${code}`)

/** 创建字典项 */
export const createDictionary = (data: DictionaryDTO) =>
  client.post<DictionaryDTO>('/customer/dictionaries', data)

/** 更新字典项 */
export const updateDictionary = (id: number, data: DictionaryDTO) =>
  client.put<DictionaryDTO>(`/customer/dictionaries/${id}`, data)

/** 删除字典项（逻辑删除/停用，operator 用于审计） */
export const deleteDictionary = (id: number, operator?: string) =>
  client.delete(`/customer/dictionaries/${id}`, { params: operator ? { operator } : {} })

/** 获取所有分类 */
export const getCategories = () =>
  client.get<string[]>('/customer/dictionaries/categories')

/** 获取字典统计信息 */
export const getDictionaryStatistics = () =>
  client.get<DictionaryStatistics>('/customer/dictionaries/statistics')
