<script setup lang="ts">
/* C 端专属顾问 /m/advisor */
import { useRouter } from 'vue-router'
import CIcon from '@/components/CIcon.vue'
const router = useRouter()
const advisor = {
  name: '林微',
  title: '资深皮肤管理顾问',
  avatar: '林',
  years: '8 年医美咨询经验',
  tags: ['皮肤管理', '抗衰方案', '术后护理'],
  served: '已服务 1,200+ 会员',
  rating: '4.9 分好评',
}
const faqs = [
  { q: '项目做完后多久能化妆？', a: '光电类项目建议 24-48 小时后再化妆，具体遵医嘱。' },
  { q: '疗程可以分次做吗？', a: '可以，疗程有效期内按建议周期分次到店即可。' },
  { q: '会员卡余额怎么用？', a: '到店消费出示会员码，可直接抵扣项目费用。' },
]
</script>

<template>
  <div class="adv">
    <!-- 顾问卡片 -->
    <div class="profile card">
      <div class="profile__avatar">{{ advisor.avatar }}</div>
      <div class="profile__info">
        <div class="profile__name">{{ advisor.name }} <span class="profile__badge">专属</span></div>
        <div class="profile__title">{{ advisor.title }}</div>
        <div class="profile__tags">
          <span v-for="t in advisor.tags" :key="t" class="profile__tag">{{ t }}</span>
        </div>
      </div>
    </div>
    <div class="stats card">
      <div class="stats__item"><b>{{ advisor.years }}</b><span>从业经验</span></div>
      <div class="stats__item"><b>{{ advisor.served }}</b><span>服务规模</span></div>
      <div class="stats__item"><b>{{ advisor.rating }}</b><span>会员口碑</span></div>
    </div>

    <!-- 快捷操作 -->
    <div class="card actions">
      <button class="action" @click="router.push('/m/booking/new')"><span><CIcon name="calendar" :size="16" /></span>预约顾问</button>
      <button class="action"><span><CIcon name="chat" :size="16" /></span>在线咨询</button>
      <button class="action"><span><CIcon name="phone" :size="16" /></span>电话联系</button>
    </div>

    <!-- 常见问题 -->
    <div class="card faq">
      <h3 class="faq__title">常见问题</h3>
      <div v-for="(f, i) in faqs" :key="i" class="faq__item">
        <div class="faq__q">Q：{{ f.q }}</div>
        <div class="faq__a">{{ f.a }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.adv { padding: 12px 0 24px; }
.card { background: #fff; border-radius: 14px; margin: 0 12px 12px; padding: 16px; }
.profile { display: flex; gap: 14px; }
.profile__avatar { width: 64px; height: 64px; border-radius: 50%; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 26px; font-weight: 700; flex-shrink: 0; }
.profile__info { flex: 1; }
.profile__name { font-size: 17px; font-weight: 700; color: #1a1a1a; }
.profile__badge { font-size: 10px; color: #fff; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); padding: 2px 7px; border-radius: 8px; margin-left: 6px; vertical-align: middle; }
.profile__title { font-size: 13px; color: #888; margin-top: 4px; }
.profile__tags { display: flex; gap: 6px; margin-top: 8px; flex-wrap: wrap; }
.profile__tag { font-size: 11px; color: #ff6b9e; background: #fff0f5; padding: 3px 9px; border-radius: 10px; }
.stats { display: flex; }
.stats__item { flex: 1; text-align: center; }
.stats__item b { display: block; font-size: 14px; color: #ff4d6d; }
.stats__item span { font-size: 12px; color: #999; margin-top: 4px; display: block; }
.actions { display: flex; gap: 10px; padding: 16px 12px; }
.action { flex: 1; border: none; background: #faf5f8; border-radius: 12px; padding: 14px 0; display: flex; flex-direction: column; align-items: center; gap: 6px; font-size: 13px; color: #555; cursor: pointer; }
.action span { color: #ff6b9e; display: inline-flex; align-items: center; justify-content: center; width: 38px; height: 38px; border-radius: 12px; background: #fff0f5; }
.faq__title { margin: 0 0 12px; font-size: 15px; font-weight: 700; color: #1a1a1a; }
.faq__item { padding: 12px 0; border-bottom: .5px solid #f5f5f5; }
.faq__item:last-child { border-bottom: none; }
.faq__q { font-size: 14px; font-weight: 600; color: #333; }
.faq__a { font-size: 13px; color: #888; margin-top: 6px; line-height: 1.6; }
</style>
