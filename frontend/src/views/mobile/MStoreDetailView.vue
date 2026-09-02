<script setup lang="ts">
/* C 端门店详情 /m/store/:id */
import { useRouter } from 'vue-router'
import CIcon from '@/components/CIcon.vue'
const router = useRouter()
const store = {
  name: '上海静安旗舰店',
  addr: '静安区南京西路 1266 号恒隆广场 B1',
  hours: '10:00 - 21:00',
  phone: '021-6288-1234',
  rating: 4.9,
  sold: '月服务 2,300+ 人次',
  intro: '美研云旗舰医美中心，配备热玛吉 FLX、M22 光子、VISIA 皮肤检测等高端光电设备，拥有 12 位执业医师团队，提供注射、光电、皮肤管理、形体塑形一站式服务。',
  facilities: ['免费停车', 'VIP 休息室', '术后修复区', '皮肤检测室', '无菌手术室'],
}
const hotProjects = ['热玛吉紧致', '光子嫩肤', '玻尿酸填充', '水光焕肤', 'VISIA 检测']
</script>

<template>
  <div class="sd">
    <div class="hero">
      <div class="hero__emoji"><CIcon name="store" :size="36" /></div>
      <div class="hero__name">{{ store.name }}</div>
      <div class="hero__rating"><CIcon name="star" :size="14" class="hero__star" /> {{ store.rating }} · {{ store.sold }}</div>
    </div>

    <div class="card info">
      <div class="info__row"><CIcon name="map-pin" :size="14" /> {{ store.addr }}</div>
      <div class="info__row"><CIcon name="clock" :size="14" /> 营业时间 {{ store.hours }}</div>
      <div class="info__row"><CIcon name="phone" :size="14" /> {{ store.phone }}</div>
    </div>

    <div class="card">
      <h3 class="t">门店设施</h3>
      <div class="fac">
        <span v-for="f in store.facilities" :key="f" class="fac__item"><CIcon name="check" :size="11" class="fac__check" /> {{ f }}</span>
      </div>
    </div>

    <div class="card">
      <h3 class="t">门店介绍</h3>
      <p class="intro">{{ store.intro }}</p>
    </div>

    <div class="card">
      <h3 class="t">热门项目</h3>
      <div class="hot">
        <button v-for="p in hotProjects" :key="p" class="hot__item" @click="router.push('/m/projects')">{{ p }} ›</button>
      </div>
    </div>

    <div class="bottom-space"></div>
    <div class="bar">
      <button class="bar__call"><CIcon name="phone" :size="15" /> 电话</button>
      <button class="bar__nav"><CIcon name="navigate" :size="15" /> 导航</button>
      <button class="bar__book" @click="router.push('/m/booking/new')">预约到店</button>
    </div>
  </div>
</template>

<style scoped>
.sd { padding-bottom: 0; }
.hero { background: linear-gradient(160deg,#FFBFF0,#FF6B9E); padding: 30px 20px 36px; text-align: center; color: #fff; }
.hero__emoji { width: 72px; height: 72px; margin: 0 auto; border-radius: 22px; background: rgba(255,255,255,.22); display: flex; align-items: center; justify-content: center; color: #fff; }
.hero__name { font-size: 20px; font-weight: 700; margin-top: 8px; }
.hero__rating { font-size: 13px; margin-top: 6px; opacity: .95; display: inline-flex; align-items: center; gap: 4px; }
.hero__star { color: #f5a623; }
.card { background: #fff; border-radius: 14px; margin: 12px; padding: 16px; }
.info__row { font-size: 14px; color: #555; line-height: 2.1; display: flex; align-items: center; gap: 5px; }
.info__row .cicon { color: #ff6b9e; }
.t { margin: 0 0 12px; font-size: 15px; font-weight: 700; color: #1a1a1a; }
.fac { display: flex; flex-wrap: wrap; gap: 8px; }
.fac__item { font-size: 12px; color: #666; background: #f6f6f8; padding: 6px 12px; border-radius: 14px; display: inline-flex; align-items: center; gap: 3px; }
.fac__check { color: #52c41a; }
.intro { margin: 0; font-size: 14px; color: #666; line-height: 1.7; }
.hot { display: flex; flex-direction: column; }
.hot__item { border: none; background: #fafafa; border-radius: 10px; padding: 13px 14px; margin-bottom: 8px; font-size: 14px; color: #333; text-align: left; cursor: pointer; }
.bottom-space { height: 80px; }
.bar { position: fixed; bottom: 0; left: 50%; transform: translateX(-50%); width: 100%; max-width: 390px; background: #fff; border-top: .5px solid #eee; padding: 8px 12px calc(8px + env(safe-area-inset-bottom)); display: flex; gap: 8px; box-sizing: border-box; z-index: 20; }
.bar__call, .bar__nav { width: 64px; height: 44px; border: 1px solid #eee; background: #fff; border-radius: 22px; font-size: 13px; color: #666; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; gap: 3px; }
.bar__book { flex: 1; height: 44px; border: none; border-radius: 22px; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; font-size: 16px; font-weight: 700; cursor: pointer; }
</style>
