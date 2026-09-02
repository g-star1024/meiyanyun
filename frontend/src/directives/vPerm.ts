import type { Directive, DirectiveBinding } from 'vue'
import { useAuthStore } from '@/stores/auth'

// ============================================================
// v-perm 按钮 / 字段级权限指令（对齐 permission-matrix.md §5/§6）
//
// 用法：
//   v-perm="'cashier:sign'"                 // 单权限，无权则移除节点
//   v-perm="['refund:approve','refund:sign']" // 数组 = 任一满足即显示
//   v-perm.all="['x:a','x:b']"              // 全部满足才显示
//   v-perm.disable="'cashier:sign'"         // 无权时禁用(disabled)而非移除
//
// 说明：与路由守卫、菜单 buildNav 同源，统一走 auth.can()。
// ============================================================

type PermValue = string | string[]

function resolve(value: PermValue, all: boolean): boolean {
  const auth = useAuthStore()
  const list = Array.isArray(value) ? value : [value]
  if (list.length === 0) return true
  return all ? list.every((p) => auth.can(p)) : list.some((p) => auth.can(p))
}

function apply(el: HTMLElement, binding: DirectiveBinding<PermValue>) {
  const allowed = resolve(binding.value, !!binding.modifiers.all)
  const disableMode = !!binding.modifiers.disable

  if (allowed) {
    // disable 模式：仅清除"无权限"标记，不干预应用自身绑定的 disabled 状态
    if (disableMode) el.removeAttribute('data-no-perm')
    return
  }

  if (disableMode) {
    el.setAttribute('disabled', 'disabled')
    el.setAttribute('data-no-perm', 'true')
  } else {
    // 默认：无权直接从 DOM 移除（隐藏且不可点击）
    el.parentNode?.removeChild(el)
  }
}

export const vPerm: Directive<HTMLElement, PermValue> = {
  mounted: apply,
  updated: apply,
}

export default vPerm
