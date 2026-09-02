// ============================================================
// 全局轻提示 Toast（单例 reactive 队列 + CToastHost 渲染）
// 用法：const toast = useToast(); toast.success('已提交') / .error('失败原因')
// 替代各页 window.alert，自动消失，最多保留 4 条。
// ============================================================
import { reactive } from 'vue'

export type ToastTone = 'success' | 'error' | 'info' | 'warning'

export interface ToastItem {
  id: number
  tone: ToastTone
  message: string
  leaving: boolean
}

let seq = 0
const toasts = reactive<ToastItem[]>([])
const DURATION = 2800
const MAX = 4

function push(tone: ToastTone, message: string) {
  seq += 1
  const item: ToastItem = { id: seq, tone, message, leaving: false }
  toasts.push(item)
  if (toasts.length > MAX) toasts.shift()
  // 到时先播离场动画，再移除
  window.setTimeout(() => {
    const t = toasts.find((x) => x.id === item.id)
    if (t) t.leaving = true
  }, DURATION - 220)
  window.setTimeout(() => {
    const i = toasts.findIndex((x) => x.id === item.id)
    if (i >= 0) toasts.splice(i, 1)
  }, DURATION)
}

function dismiss(id: number) {
  const i = toasts.findIndex((x) => x.id === id)
  if (i >= 0) toasts.splice(i, 1)
}

export function useToast() {
  return {
    toasts,
    dismiss,
    success: (m: string) => push('success', m),
    error: (m: string) => push('error', m),
    info: (m: string) => push('info', m),
    warning: (m: string) => push('warning', m),
  }
}
