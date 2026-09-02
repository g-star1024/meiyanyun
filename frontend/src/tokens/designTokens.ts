// 设计令牌（直映 DESIGN-TOKEN.md / 技术架构方案 §2.3）。
// 与 styles/tokens.css 同源；组件通过 CSS 变量引用，主题可换。

export const colors = {
  brandPink: '#FF6B9D', // 主品牌色 / 主 CTA
  blue: '#6B8AFF', // 次级操作 / 链接
  gold: '#FFCB47', // 预警 / 临期 / 待处理
  cyan: '#2ED4BF', // 成功 / 已完成 / 正常
  purple: '#8B5CF6', // AI / 智能相关
  danger: '#FF4D4F', // 危险 / 阻断 / 异常 / 失败（D-17 新增第 8 色）
  text: '#1A1A2E', // 正文
  sidebar: '#14152B', // 侧栏背景
} as const

// 间距 4px 栅格
export const space = [4, 8, 12, 16, 24, 32, 48] as const

export const radius = {
  sm: 4,
  md: 8,
  lg: 12,
  capsule: 20,
  pill: 999,
} as const

export const fonts = {
  latin: 'Inter',
  cjk: 'Sarasa Gothic SC',
  // 禁用 Medium(500)，lineHeight 必显式
  weights: { regular: 400, semibold: 600, bold: 700 },
} as const

// 双端断点（GLOBAL-01）
export const breakpoints = {
  desktop: 1280,
  tabletMin: 768,
  tabletMax: 1024,
}
