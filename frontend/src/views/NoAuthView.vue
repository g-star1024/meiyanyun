<script setup lang="ts">
import { useRouter } from 'vue-router'
import CButton from '@/components/CButton.vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

function reLogin() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="noauth">
    <div class="noauth__card">
      <div class="noauth__code">403</div>
      <h2 class="noauth__title">无访问权限</h2>
      <p class="noauth__desc">当前角色无权访问该页面。如需开通，请联系门店店长或集团管理员调整角色权限。</p>
      <div class="noauth__actions">
        <CButton variant="primary" @click="router.push('/my-workbench')">返回工作台</CButton>
        <CButton variant="secondary" @click="reLogin">切换账号</CButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.noauth {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
}
.noauth__card {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-xl);
  padding: var(--s-xxl);
  max-width: 420px;
  text-align: center;
  box-shadow: var(--shadow-card);
}
.noauth__code {
  font-size: 48px;
  font-weight: 700;
  color: var(--c-brand);
  line-height: 1;
}
.noauth__title {
  font-size: var(--t-2xl);
  margin-top: var(--s-sm);
}
.noauth__desc {
  color: var(--c-text-2);
  font-size: var(--t-sm);
  line-height: var(--lh-sm);
  margin: var(--s-md) 0 var(--s-lg);
}
.noauth__actions {
  display: flex;
  gap: var(--s-sm);
  justify-content: center;
}
</style>
