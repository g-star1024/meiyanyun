<script setup lang="ts">
/* C 端邀请有礼 /m/invite */
import { ref } from 'vue'
import CIcon from '@/components/CIcon.vue'
const code = 'MEIYUN888'
const copied = ref(false)
const rewards = [
  { icon: 'gift' as const, title: '好友首单', desc: '好友通过您的链接首次到店消费', reward: '您得 200 积分' },
  { icon: 'wallet' as const, title: '好友开卡', desc: '好友购买会员卡/疗程', reward: '您得 ¥200 卡余额' },
  { icon: 'share' as const, title: '双人同行', desc: '邀请满 3 位好友', reward: '赠水光护理 1 次' },
]
function copy() {
  navigator.clipboard?.writeText(code).catch(() => {})
  copied.value = true
  setTimeout(() => (copied.value = false), 2000)
}
</script>

<template>
  <div class="inv">
    <!-- 主视觉 -->
    <div class="hero">
      <div class="hero__emoji"><CIcon name="gift" :size="34" /></div>
      <div class="hero__title">邀请好友 · 一起变美</div>
      <div class="hero__sub">好友首单立减 ¥100，您享积分/余额双重奖励</div>
      <div class="hero__code">
        <span>我的邀请码</span>
        <b>{{ code }}</b>
        <button class="hero__copy" @click="copy">{{ copied ? '已复制' : '复制' }}</button>
      </div>
    </div>

    <!-- 奖励规则 -->
    <div class="card">
      <h3 class="t">邀请奖励</h3>
      <div v-for="(r, i) in rewards" :key="i" class="rrow">
        <div class="rrow__emoji"><CIcon :name="r.icon" :size="22" /></div>
        <div class="rrow__body">
          <div class="rrow__title">{{ r.title }}</div>
          <div class="rrow__desc">{{ r.desc }}</div>
        </div>
        <div class="rrow__reward">{{ r.reward }}</div>
      </div>
    </div>

    <!-- 邀请记录 -->
    <div class="card stat">
      <div class="stat__item"><b>6</b><span>已邀请</span></div>
      <div class="stat__item"><b>3</b><span>已到店</span></div>
      <div class="stat__item"><b>1,200</b><span>累计积分</span></div>
    </div>

    <div class="bottom-space"></div>
    <div class="bar">
      <button class="bar__btn"><CIcon name="share" :size="16" /> 立即邀请好友</button>
    </div>
  </div>
</template>

<style scoped>
.inv { padding-bottom: 0; }
.hero { background: linear-gradient(160deg,#FFBFF0,#FF6B9E); padding: 36px 24px 30px; text-align: center; color: #fff; }
.hero__emoji { width: 68px; height: 68px; margin: 0 auto; border-radius: 22px; background: rgba(255,255,255,.22); display: flex; align-items: center; justify-content: center; color: #fff; }
.hero__title { font-size: 22px; font-weight: 800; margin-top: 10px; }
.hero__sub { font-size: 13px; opacity: .95; margin-top: 8px; }
.hero__code { display: inline-flex; align-items: center; gap: 10px; margin-top: 20px; background: rgba(255,255,255,.2); border-radius: 24px; padding: 8px 8px 8px 18px; }
.hero__code span { font-size: 12px; opacity: .9; }
.hero__code b { font-size: 18px; letter-spacing: 1px; }
.hero__copy { border: none; background: #fff; color: #ff4d6d; font-weight: 700; font-size: 13px; padding: 7px 16px; border-radius: 16px; cursor: pointer; }
.card { background: #fff; border-radius: 14px; margin: 12px; padding: 16px; }
.t { margin: 0 0 8px; font-size: 15px; font-weight: 700; color: #1a1a1a; }
.rrow { display: flex; align-items: center; gap: 12px; padding: 12px 0; border-bottom: .5px solid #f5f5f5; }
.rrow:last-child { border-bottom: none; }
.rrow__emoji { width: 44px; height: 44px; border-radius: 12px; background: #fff0f5; color: #ff6b9e; display: flex; align-items: center; justify-content: center; }
.rrow__body { flex: 1; }
.rrow__title { font-size: 14px; font-weight: 600; color: #1a1a1a; }
.rrow__desc { font-size: 12px; color: #999; margin-top: 3px; }
.rrow__reward { font-size: 13px; color: #ff4d6d; font-weight: 700; }
.stat { display: flex; }
.stat__item { flex: 1; text-align: center; }
.stat__item b { display: block; font-size: 20px; color: #ff4d6d; font-weight: 800; }
.stat__item span { font-size: 12px; color: #999; margin-top: 4px; display: block; }
.bottom-space { height: 80px; }
.bar { position: fixed; bottom: 0; left: 50%; transform: translateX(-50%); width: 100%; max-width: 390px; background: #fff; border-top: .5px solid #eee; padding: 8px 16px calc(8px + env(safe-area-inset-bottom)); box-sizing: border-box; z-index: 20; }
.bar__btn { width: 100%; height: 46px; border: none; border-radius: 23px; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; font-size: 16px; font-weight: 700; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; gap: 6px; }
</style>
