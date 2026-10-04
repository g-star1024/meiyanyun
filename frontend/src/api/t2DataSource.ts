import client from './client'

/** 数据源视图（对齐后端 DataSourceService.DataSourceView）。 */
export interface DataSourceView {
  id: number
  code: string
  name: string
  type: string
  endpoint: string | null
  description: string
  status: string
  owner: string
  lastSyncAt: string | null
  createdAt: string
  updatedAt: string
}

/** 新建请求体（code 建后不可变；type 三值 CDC/KAFKA/THIRD_PARTY）。 */
export interface DataSourceCreateReq {
  code: string
  name: string
  type: string
  endpoint?: string
  description?: string
}

/** 编辑请求体（仅 name/endpoint/description；code/type 不可变）。 */
export interface DataSourceUpdateReq {
  name: string
  endpoint?: string
  description?: string
}

export function fetchDataSources(type?: string, keyword?: string): Promise<DataSourceView[]> {
  return client.get('/customer/t2/datasources', { params: { type: type || undefined, keyword: keyword || undefined } }).then((r) => r.data)
}

export function createDataSource(cmd: DataSourceCreateReq): Promise<DataSourceView> {
  return client.post('/customer/t2/datasources', cmd).then((r) => r.data)
}

export function updateDataSource(id: number, patch: DataSourceUpdateReq): Promise<DataSourceView> {
  return client.put(`/customer/t2/datasources/${id}`, patch).then((r) => r.data)
}

export function disableDataSource(id: number): Promise<DataSourceView> {
  return client.post(`/customer/t2/datasources/${id}/disable`).then((r) => r.data)
}

/** 连通探测：三类型均真实探测（棒⑧卡5 放开 CDC/KAFKA）；失败后端如实 400 透出原因。 */
export function syncDataSource(id: number): Promise<DataSourceView> {
  return client.post(`/customer/t2/datasources/${id}/sync`).then((r) => r.data)
}
