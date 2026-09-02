<script setup lang="ts">
import { useRouter } from 'vue-router'
import CButton from '@/components/CButton.vue'

const router = useRouter()

function goHome() {
  router.push('/appointment')
}
function goBack() {
  router.back()
}
</script>

<template>
  <div class="nf">
    <div class="nf__card">
      <div class="nf__code">404</div>
      <h2 class="nf__title">页面走丢了</h2>
      <p class="nf__desc">抱歉，您访问的页面不存在或已被移动。请检查网址是否正确，或返回工作台继续操作。</p>
      <div class="nf__actions">
        <CButton variant="primary" @click="goHome">返回工作台</CButton>
        <CButton variant="secondary" @click="goBack">返回上一页</CButton>
      </div>
      <div class="nf__hint">
        错误代码：404 · 如持续出现请联系系统管理员
      </div>
    </div>
  </div>
</template>

<style scoped>
.nf {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
}
.nf__card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-xl);
  padding: var(--s-xxl);
  max-width: 460px;
  text-align: center;
  box-shadow: var(--shadow-card);
}
.nf__code {
  font-size: 72px;
  font-weight: 800;
  color: var(--c-brand);
  line-height: 1;
  letter-spacing: -2px;
}
.nf__title {
  font-size: var(--t-xl);
  margin-top: var(--s-sm);
}
.nf__desc {
  color: var(--c-text-2);
  font-size: var(--t-sm);
  line-height: var(--lh-sm);
  margin: var(--s-md) 0 var(--s-lg);
}
.nf__actions {
  display: flex;
  gap: var(--s-sm);
  justify-content: center;
}
.nf__hint {
  margin-top: var(--s-lg);
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
</style>
