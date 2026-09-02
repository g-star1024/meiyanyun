/**
 * 运行环境配置
 * ---------------------------------------------------------------
 * 【API 域名怎么改】
 * 开发期可在微信开发者工具「详情 - 本地设置」勾选「不校验合法域名」。
 * 上线前必须在「微信公众平台 → 开发管理 → 开发设置 → 服务器域名」
 * 把 request 合法域名配置成下面的 API_BASE（需 https + 已备案）。
 *
 * AppID 不在这里：AppID 在 src/manifest.json 的 mp-weixin.appid，
 *            由微信开发者工具/CI 读取；后台也可做下发展示，但构建以 manifest 为准。
 */

// 接口网关地址（对接美研云 gateway :8443 的线上域名；末尾不要带斜杠）
export const API_BASE = 'https://api.meiyun.example.com'

// 租户/品牌标识（多租户时由后台按门店下发）
export const TENANT_ID = 'meiyun-demo'

// 小程序端版本（用于灰度/兼容判断）
export const APP_VERSION = '0.1.0'

/** 拼接完整接口地址 */
export function apiUrl(path: string): string {
  if (/^https?:\/\//.test(path)) return path
  return `${API_BASE}${path.startsWith('/') ? path : `/${path}`}`
}
