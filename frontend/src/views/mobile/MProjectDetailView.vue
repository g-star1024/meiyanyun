<script setup lang="ts">
/* C 端项目详情 /m/project/:id — 粉色渐变 Hero + 项目信息 + 底部购买/预约 */
import { onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePricelistStore } from '@/stores/pricelist'
import CIcon from '@/components/CIcon.vue'

const route = useRoute()
const router = useRouter()
const pricelist = usePricelistStore()
onMounted(() => pricelist.seed())

const item = computed(() => pricelist.get(route.params.id as string))
const price = computed(() => (item.value?.promoPrice ?? item.value?.memberPrice ?? 0))

const features = [
  '正品保障 · 药品/仪器全程可溯源',
  '执业医师面诊定制方案',
  '术前检测 + 术后修复全程跟进',
  '支持会员卡余额 / 疗程次卡抵扣',
]
function buy() {
  if (item.value) router.push(`/m/project/${item.value.id}/buy`)
}
function book() {
  router.push({ pathname: '/m/booking', query: { project: item.value?.name } } as any)
}
</script>

<template>
  <div class="pdetail" v-if="item">
    <!-- 粉色渐变 Hero -->
    <div class="hero">
      <div class="hero__img">{{ item.name.charAt(0) }}</div>
    </div>

    <!-- 价格卡 -->
    <div class="price-card">
      <div class="price-card__row">
        <span class="price-card__now">¥{{ price.toLocaleString() }}</span>
        <span class="price-card__old">¥{{ item.originalPrice.toLocaleString() }}</span>
        <span v-if="item.promoPrice" class="price-card__promo">限时特惠</span>
      </div>
      <div class="price-card__member">会员价 ¥{{ item.memberPrice.toLocaleString() }} / {{ item.unit }}</div>
      <h2 class="price-card__name">{{ item.name }}</h2>
      <div class="price-card__meta">
        <span class="price-card__meta-item"><CIcon name="clock" :size="13" /> 服务时长 {{ item.duration }} 分钟</span>
        <span>·</span>
        <span>{{ item.unit }}</span>
      </div>
    </div>

    <!-- 项目介绍 -->
    <div class="block">
      <h3 class="block__title">项目介绍</h3>
      <p class="block__text">
        {{ item.name }}由专业医师操作，针对您的肤质与需求定制个性化方案。全程使用正品仪器与耗材，
        术前进行专业皮肤检测评估，术中严格无菌操作，术后配备修复护理与随访，确保安全与效果。
      </p>
    </div>

    <!-- 服务保障 -->
    <div class="block">
      <h3 class="block__title">服务保障</h3>
      <div class="feat">
        <div v-for="f in features" :key="f" class="feat__item">
          <span class="feat__check"><CIcon name="check" :size="11" /></span>{{ f }}
        </div>
      </div>
    </div>

    <!-- 推荐门店 -->
    <div class="block" @click="router.push('/m/stores')">
      <h3 class="block__title">可服务门店 <span class="block__more">查看全部 ›</span></h3>
      <div class="store-row">
        <div class="store-row__icon"><CIcon name="store" :size="22" /></div>
        <div class="store-row__body">
          <div class="store-row__name">上海静安旗舰店</div>
          <div class="store-row__sub">静安区南京西路 1266 号 · 距您 1.2km</div>
        </div>
        <span class="store-row__go">›</span>
      </div>
    </div>

    <div class="bottom-space"></div>

    <!-- 底部操作栏 -->
    <div class="action-bar">
      <button class="action-bar__btn action-bar__btn--ghost" @click="book">
        <span><CIcon name="calendar" :size="18" /></span><em>预约</em>
      </button>
      <button class="action-bar__btn action-bar__btn--ghost" @click="router.push('/m/advisor')">
        <span><CIcon name="chat" :size="18" /></span><em>咨询</em>
      </button>
      <button class="action-bar__buy" @click="buy">立即购买</button>
    </div>
  </div>

  <div v-else class="nf">项目不存在或已下架</div>
</template>

<style scoped>
.pdetail { padding-bottom: 0; }
.hero { height: 280px; background: linear-gradient(160deg, #FFBFF0 0%, #FF6B9E 100%); display: flex; align-items: center; justify-content: center; }
.hero__img { width: 130px; height: 130px; border-radius: 24px; background: rgba(255,255,255,.28); display: flex; align-items: center; justify-content: center; font-size: 56px; color: #fff; backdrop-filter: blur(4px); }

.price-card { background: #fff; margin: -20px 12px 0; border-radius: 16px; padding: 16px; position: relative; }
.price-card__row { display: flex; align-items: baseline; gap: 8px; }
.price-card__now { font-size: 28px; font-weight: 800; color: #ff4d6d; }
.price-card__old { font-size: 14px; color: #c0c0c0; text-decoration: line-through; }
.price-card__promo { font-size: 11px; color: #fff; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); padding: 2px 8px; border-radius: 8px; }
.price-card__member { font-size: 12px; color: #ff8c42; margin-top: 4px; }
.price-card__name { font-size: 19px; font-weight: 700; color: #1a1a1a; margin: 12px 0 8px; }
.price-card__meta { display: flex; gap: 8px; font-size: 13px; color: #888; }
.price-card__meta-item { display: inline-flex; align-items: center; gap: 4px; }

.block { background: #fff; margin: 12px; border-radius: 14px; padding: 16px; cursor: pointer; }
.block__title { margin: 0 0 12px; font-size: 15px; font-weight: 700; color: #1a1a1a; display: flex; justify-content: space-between; align-items: center; }
.block__more { font-size: 12px; color: #999; font-weight: 400; }
.block__text { margin: 0; font-size: 14px; color: #666; line-height: 1.7; }
.feat { display: flex; flex-direction: column; gap: 10px; }
.feat__item { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #555; }
.feat__check { width: 18px; height: 18px; border-radius: 50%; background: #fff0f5; color: #ff6b9e; display: inline-flex; align-items: center; justify-content: center; font-size: 11px; flex-shrink: 0; }
.store-row { display: flex; align-items: center; gap: 12px; }
.store-row__icon { width: 44px; height: 44px; border-radius: 12px; background: #fff0f5; color: #ff6b9e; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.store-row__body { flex: 1; }
.store-row__name { font-size: 14px; font-weight: 600; color: #1a1a1a; }
.store-row__sub { font-size: 12px; color: #999; margin-top: 3px; }
.store-row__go { font-size: 20px; color: #ccc; }
.bottom-space { height: 80px; }

.action-bar { position: fixed; bottom: 0; left: 50%; transform: translateX(-50%); width: 100%; max-width: 390px; background: #fff; border-top: 0.5px solid #eee; padding: 8px 12px calc(8px + env(safe-area-inset-bottom)); display: flex; gap: 10px; align-items: center; box-sizing: border-box; z-index: 20; }
.action-bar__btn { border: none; background: #f6f6f8; border-radius: 10px; padding: 8px 14px; display: flex; flex-direction: column; align-items: center; gap: 1px; cursor: pointer; color: #666; }
.action-bar__btn em { font-style: normal; font-size: 11px; }
.action-bar__btn span { display: inline-flex; align-items: center; color: #ff6b9e; }
.action-bar__buy { flex: 1; height: 44px; border: none; border-radius: 22px; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; font-size: 16px; font-weight: 700; cursor: pointer; }
.nf { text-align: center; color: #bbb; padding: 100px 0; }
</style>
