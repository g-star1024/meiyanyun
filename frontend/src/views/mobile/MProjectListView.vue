<script setup lang="ts">
/* C 端项目列表 /m/projects — 分类筛选 + 项目卡片流 */
import { onMounted, computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { usePricelistStore } from '@/stores/pricelist'
import CIcon from '@/components/CIcon.vue'

const router = useRouter()
const pricelist = usePricelistStore()
onMounted(() => pricelist.seed())

const cats = [
  { key: 'ALL', label: '全部' },
  { key: 'INJECTION', label: '注射美容' },
  { key: 'LASER', label: '光电仪器' },
  { key: 'SKINCARE', label: '皮肤管理' },
  { key: 'BODY', label: '形体管理' },
  { key: 'EXAM', label: '检测咨询' },
] as const
const activeCat = ref<(typeof cats)[number]['key']>('ALL')

const list = computed(() => {
  const base = activeCat.value === 'ALL' ? pricelist.active : pricelist.active.filter((p) => p.category === activeCat.value)
  return base
})
function priceOf(p: { promoPrice: number | null; memberPrice: number }) {
  return p.promoPrice ?? p.memberPrice
}
function go(id: string) {
  router.push(`/m/project/${id}`)
}
</script>

<template>
  <div class="plist">
    <!-- 分类 tab -->
    <div class="catbar">
      <button
        v-for="c in cats"
        :key="c.key"
        class="cat"
        :class="{ on: activeCat === c.key }"
        @click="activeCat = c.key"
      >{{ c.label }}</button>
    </div>

    <div class="plist__list">
      <div v-for="p in list" :key="p.id" class="pcard" @click="go(p.id)">
        <div class="pcard__img">{{ p.name.charAt(0) }}</div>
        <div class="pcard__body">
          <div class="pcard__name">{{ p.name }}</div>
          <div class="pcard__tags">
            <span class="pcard__tag"><CIcon name="clock" :size="11" /> {{ p.duration }}分钟</span>
            <span v-if="p.promoPrice" class="pcard__tag pcard__tag--hot">限时特惠</span>
          </div>
          <div class="pcard__price">
            <span class="pcard__now">¥{{ priceOf(p).toLocaleString() }}</span>
            <span class="pcard__old">¥{{ p.originalPrice.toLocaleString() }}</span>
            <span class="pcard__unit">/{{ p.unit }}</span>
          </div>
        </div>
      </div>
      <div v-if="!list.length" class="empty">该分类暂无在售项目</div>
    </div>
  </div>
</template>

<style scoped>
.plist { padding-bottom: 24px; }
.catbar { position: sticky; top: 0; z-index: 5; background: #fff; display: flex; overflow-x: auto; padding: 10px 12px; gap: 8px; border-bottom: 0.5px solid #eee; }
.catbar::-webkit-scrollbar { display: none; }
.cat { flex-shrink: 0; border: none; background: #f4f4f6; color: #666; font-size: 13px; padding: 6px 14px; border-radius: 16px; cursor: pointer; }
.cat.on { background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; font-weight: 600; }
.plist__list { padding: 12px 16px; display: flex; flex-direction: column; gap: 12px; }
.pcard { background: #fff; border-radius: 14px; padding: 12px; display: flex; gap: 12px; cursor: pointer; }
.pcard__img { width: 92px; height: 92px; border-radius: 10px; flex-shrink: 0; background: linear-gradient(135deg,#fff0f5,#ffe0ec); display: flex; align-items: center; justify-content: center; font-size: 36px; color: #ff6b9e; }
.pcard__body { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.pcard__name { font-size: 15px; font-weight: 600; color: #1a1a1a; }
.pcard__tags { display: flex; gap: 6px; margin-top: 6px; }
.pcard__tag { font-size: 11px; color: #999; background: #f6f6f8; padding: 2px 7px; border-radius: 6px; display: inline-flex; align-items: center; gap: 3px; }
.pcard__tag--hot { color: #ff4d6d; background: #fff0f3; }
.pcard__price { margin-top: auto; display: flex; align-items: baseline; gap: 6px; }
.pcard__now { font-size: 19px; font-weight: 700; color: #ff4d6d; }
.pcard__old { font-size: 12px; color: #c0c0c0; text-decoration: line-through; }
.pcard__unit { font-size: 12px; color: #999; }
.empty { text-align: center; color: #bbb; font-size: 14px; padding: 60px 0; }
</style>
