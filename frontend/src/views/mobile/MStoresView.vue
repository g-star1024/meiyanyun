<script setup lang="ts">
/* C 端门店列表 /m/stores */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import CIcon from '@/components/CIcon.vue'

const router = useRouter()
const keyword = ref('')
const stores = [
  { id: 's1', name: '上海静安旗舰店', addr: '静安区南京西路 1266 号恒隆广场', distance: '1.2km', tags: ['旗舰店', '光电中心', '营业中'], hours: '10:00-21:00', phone: '021-6288-1234', rating: 4.9 },
  { id: 's2', name: '上海徐汇万象城店', addr: '徐汇区淮海中路 999 号环贸 iapm', distance: '3.8km', tags: ['医美中心', '营业中'], hours: '10:00-21:00', phone: '021-5456-7890', rating: 4.8 },
  { id: 's3', name: '上海浦东陆家嘴店', addr: '浦东新区世纪大道 8 号国金中心', distance: '5.5km', tags: ['精品店', '营业中'], hours: '10:00-22:00', phone: '021-2059-4567', rating: 4.7 },
  { id: 's4', name: '上海虹桥天地店', addr: '闵行区申长路 99 弄虹桥天地', distance: '12km', tags: ['社区店', '营业中'], hours: '10:00-20:30', phone: '021-6220-8888', rating: 4.6 },
]
const list = computed(() => stores.filter((s) => !keyword.value || s.name.includes(keyword.value) || s.addr.includes(keyword.value)))
function go(id: string) { router.push(`/m/store/${id}`) }
</script>

<template>
  <div class="stores">
    <div class="search">
      <span class="search__icon"><CIcon name="search" :size="15" /></span>
      <input v-model="keyword" class="search__input" placeholder="搜索门店 / 商圈" />
    </div>
    <div class="list">
      <div v-for="s in list" :key="s.id" class="scard" @click="go(s.id)">
        <div class="scard__img"><CIcon name="store" :size="28" /></div>
        <div class="scard__body">
          <div class="scard__name">{{ s.name }} <span class="scard__rating"><CIcon name="star" :size="12" /> {{ s.rating }}</span></div>
          <div class="scard__tags">
            <span v-for="t in s.tags" :key="t" class="scard__tag" :class="{ open: t.includes('营业') }">{{ t }}</span>
          </div>
          <div class="scard__addr"><CIcon name="map-pin" :size="12" /> {{ s.addr }}</div>
          <div class="scard__meta"><CIcon name="clock" :size="12" /> {{ s.hours }} · <CIcon name="phone" :size="12" /> {{ s.phone }}</div>
        </div>
        <div class="scard__dist">{{ s.distance }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stores { padding-bottom: 24px; }
.search { margin: 12px 16px; background: #fff; border-radius: 20px; padding: 9px 16px; display: flex; align-items: center; gap: 8px; }
.search__icon { color: #999; display: inline-flex; align-items: center; }
.search__input { border: none; outline: none; flex: 1; font-size: 14px; background: transparent; }
.list { padding: 0 16px; display: flex; flex-direction: column; gap: 12px; }
.scard { background: #fff; border-radius: 14px; padding: 14px; display: flex; gap: 12px; cursor: pointer; position: relative; }
.scard__img { width: 60px; height: 60px; border-radius: 10px; background: #fff0f5; color: #ff6b9e; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.scard__body { flex: 1; min-width: 0; }
.scard__name { font-size: 15px; font-weight: 700; color: #1a1a1a; }
.scard__rating { font-size: 12px; color: #f5a623; font-weight: 500; margin-left: 6px; display: inline-flex; align-items: center; gap: 2px; vertical-align: middle; }
.scard__tags { display: flex; gap: 5px; margin-top: 6px; flex-wrap: wrap; }
.scard__tag { font-size: 10px; color: #999; background: #f5f5f7; padding: 2px 7px; border-radius: 6px; }
.scard__tag.open { color: #52c41a; background: #f0fff0; }
.scard__addr { font-size: 12px; color: #666; margin-top: 8px; display: flex; align-items: center; gap: 3px; }
.scard__meta { font-size: 11px; color: #aaa; margin-top: 4px; display: flex; align-items: center; gap: 3px; }
.scard__dist { position: absolute; top: 14px; right: 14px; font-size: 12px; color: #ff6b9e; font-weight: 600; }
</style>
