<script setup lang="ts">
/* C 端我的套餐 /m/packages — 会员已购卡项/疗程 */
import { onMounted, ref } from 'vue'
import { usePointsStore } from '@/stores/points'
import { listMyPackages, type PackageDTO } from '@/api/package'
import { CUSTOMER_LEVEL as _CUSTOMER_LEVEL } from '@/config/dictionary'

const points = usePointsStore()
const myPackages = ref<PackageDTO[]>([])

onMounted(async () => {
  points.seed()
  try {
    const res = await listMyPackages(points.member.memberId)
    myPackages.value = res.data
  } catch {
    // API 失败时显示空状态
  }
})
</script>

<template>
  <div class="pk">
    <div class="list">
      <div v-for="(p, i) in myPackages" :key="i" class="pk-card">
        <div class="pk-card__head">
          <span class="pk-card__name">{{ p.name }}</span>
          <span class="pk-card__type">{{ p.type }}</span>
        </div>
        <div class="pk-card__progress">
          <template v-if="p.total > 1">
            <b>{{ p.used }}</b> / {{ p.total }} 次
            <div class="bar"><i :style="{ width: (p.used / p.total * 100) + '%' }"></i></div>
          </template>
          <template v-else>
            <b>{{ p.balance || '未使用' }}</b>
          </template>
        </div>
        <div class="pk-card__foot">
          <span>有效期至 {{ p.expire }}</span>
          <span class="pk-card__left" v-if="p.total > 1">剩余 {{ p.total - p.used }} 次</span>
        </div>
      </div>
    </div>
    <div class="tip">套餐/疗程到店核销后自动扣次，消费记录可查看明细。</div>
  </div>
</template>

<style scoped>
.pk { padding: 12px 16px 24px; }
.list { display: flex; flex-direction: column; gap: 12px; }
.pk-card { background: linear-gradient(135deg,#fff,#fff7fa); border: 1px solid #ffe3ee; border-radius: 14px; padding: 16px; }
.pk-card__head { display: flex; justify-content: space-between; align-items: center; }
.pk-card__name { font-size: 16px; font-weight: 700; color: #1a1a1a; }
.pk-card__type { font-size: 11px; color: #ff6b9e; background: #fff0f5; padding: 3px 9px; border-radius: 10px; }
.pk-card__progress { margin: 14px 0; font-size: 14px; color: #666; }
.pk-card__progress b { font-size: 22px; color: #ff4d6d; font-weight: 800; }
.bar { height: 6px; background: #ffeef4; border-radius: 3px; margin-top: 8px; overflow: hidden; }
.bar i { display: block; height: 100%; background: linear-gradient(90deg,#FFBFF0,#FF6B9E); border-radius: 3px; }
.pk-card__foot { display: flex; justify-content: space-between; font-size: 12px; color: #999; }
.pk-card__left { color: #ff6b9e; font-weight: 600; }
.tip { font-size: 12px; color: #bbb; text-align: center; margin-top: 16px; line-height: 1.6; }
</style>
