// 跨 store 共享的活动流水（闭环可见性的核心）。
// 每个领域 store 在完成状态迁移后调用 activity.log()，页面统一订阅。
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Activity } from '@/types/domain'

let seq = 100
export function nextId(prefix: string) {
  seq += 1
  return `${prefix}-${seq.toString(36)}`
}

export const useActivityStore = defineStore('activity', () => {
  const items = ref<Activity[]>([])

  function log(actor: string, text: string, refId?: string) {
    const at = new Date().toTimeString().slice(0, 5)
    items.value.unshift({ id: nextId('act'), at, actor, text, refId })
  }

  function forRef(refId: string) {
    return items.value.filter((a) => a.refId === refId)
  }

  function reset() {
    items.value = []
  }

  return { items, log, forRef, reset }
})
