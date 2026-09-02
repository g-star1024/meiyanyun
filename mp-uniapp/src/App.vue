<script setup lang="ts">
import { onLaunch, onShow } from '@dcloudio/uni-app'
import { useRemoteConfig } from '@/api/config'

onLaunch(() => {
  // 启动时从后台拉取公开运行时配置（品牌名、客服电话、支付开关等，不含密钥）
  useRemoteConfig().load().catch(() => {
    // 拉取失败不阻塞，使用内置默认值
  })
})

onShow(() => {
  // 小程序从后台切回
})
</script>

<style lang="scss">
/* 全局样式：页面背景 + 通用卡片 */
page {
  background: #f6f6f8;
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', sans-serif;
  color: #1a1a1a;
}
.m-card {
  background: #fff;
  border-radius: 28rpx;
}
</style>
