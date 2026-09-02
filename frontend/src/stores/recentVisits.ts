import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { PAGE_TITLES } from '@/config/nav'

export interface RecentVisit {
  path: string
  title: string
  timestamp: number
}

const STORAGE_KEY = 'meiyun_recent_visits'
const MAX_ITEMS = 15

export const useRecentVisitsStore = defineStore('recentVisits', () => {
  const visits = ref<RecentVisit[]>(loadFromStorage())

  function loadFromStorage(): RecentVisit[] {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      return raw ? JSON.parse(raw) : []
    } catch {
      return []
    }
  }

  function saveToStorage() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(visits.value))
    } catch {
      // ignore
    }
  }

  function addVisit(path: string) {
    // 过滤掉不需要记录的路径
    if (path === '/search' || path === '/notifications' || path.startsWith('/no-auth')) {
      return
    }

    const title = PAGE_TITLES[path as keyof typeof PAGE_TITLES]?.title || 
                  PAGE_TITLES[path as keyof typeof PAGE_TITLES]?.breadcrumb || 
                  path

    const existing = visits.value.findIndex(v => v.path === path)
    if (existing !== -1) {
      visits.value.splice(existing, 1)
    }

    visits.value.unshift({
      path,
      title,
      timestamp: Date.now()
    })

    if (visits.value.length > MAX_ITEMS) {
      visits.value = visits.value.slice(0, MAX_ITEMS)
    }

    saveToStorage()
  }

  function removeVisit(path: string) {
    visits.value = visits.value.filter(v => v.path !== path)
    saveToStorage()
  }

  function clearAll() {
    visits.value = []
    saveToStorage()
  }

  const recentItems = computed(() => visits.value)

  return {
    visits,
    recentItems,
    addVisit,
    removeVisit,
    clearAll
  }
})
