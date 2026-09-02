/**
 * 路由导航 helper（替代 vue-router）
 * tabBar 三页必须用 switchTab，其余页 navigateTo，redirectTo 对应 replace。
 * 统一传 /pages/xxx/yyy?k=v 形式的小程序路径。
 */
const TAB_PAGES = ['/pages/home/index', '/pages/projects/list', '/pages/me/index']

export function navTo(url: string) {
  const path = url.split('?')[0]
  if (TAB_PAGES.includes(path)) {
    uni.switchTab({ url: path })
  } else {
    uni.navigateTo({ url })
  }
}

export function redirectTo(url: string) {
  uni.redirectTo({ url })
}

export function navigateBack(fallback = '/pages/home/index') {
  const pages = getCurrentPages()
  if (pages.length > 1) {
    uni.navigateBack()
  } else {
    uni.switchTab({ url: fallback })
  }
}

/** 提示（替代 window.alert） */
export function toast(title: string, icon: 'none' | 'success' | 'error' = 'none') {
  uni.showToast({ title, icon, duration: 2000 })
}
