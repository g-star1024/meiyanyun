<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import { useAuthStore } from '@/stores/auth'
import type { Role } from '@/types/domain'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const loginName = ref('')
const password = ref('')
const errorMsg = ref('')
const loading = ref(false)
const roleLoading = ref<Role | ''>('')

// 演示环境快捷登录角色（与顶栏切换器同源）
const QUICK_ROLES: { key: Role; label: string }[] = [
  { key: 'SUPER_ADMIN', label: '集团管理员' },
  { key: 'REGION_MGR', label: '区域经理' },
  { key: 'STORE_MGR', label: '门店店长' },
  { key: 'CONSULTANT', label: '咨询顾问' },
  { key: 'DOCTOR', label: '医生' },
  { key: 'FRONT_DESK', label: '前台/收银' },
  { key: 'OPERATOR', label: '运营' },
  { key: 'FINANCE', label: '财务' },
]

function redirectTarget(): string {
  const r = route.query.redirect
  return typeof r === 'string' && r.startsWith('/') ? r : '/my-workbench'
}

function afterLogin() {
  router.replace(redirectTarget())
}

function failMsg(e: any, fallback: string): string {
  const data = e?.response?.data
  return data?.message || data?.error || fallback
}

async function onSubmit() {
  if (loading.value) return
  errorMsg.value = ''
  if (!loginName.value.trim() || !password.value) {
    errorMsg.value = '请输入工号和密码'
    return
  }
  loading.value = true
  try {
    await auth.login(loginName.value.trim(), password.value)
    afterLogin()
  } catch (e: any) {
    errorMsg.value = failMsg(e, '登录失败，请检查工号或密码')
  } finally {
    loading.value = false
  }
}

async function quickLogin(role: Role) {
  if (roleLoading.value) return
  errorMsg.value = ''
  roleLoading.value = role
  try {
    await auth.loginByRole(role)
    afterLogin()
  } catch (e: any) {
    errorMsg.value = failMsg(e, '快捷登录不可用，请使用工号密码登录')
  } finally {
    roleLoading.value = ''
  }
}
</script>

<template>
  <div class="login">
    <div class="login__card">
      <div class="login__brand">
        <div class="login__logo">美</div>
        <div class="login__brand-text">
          <div class="login__title">美研云 · 门店中台</div>
          <div class="login__subtitle">医美连锁经营管理平台</div>
        </div>
      </div>

      <form class="login__form" @submit.prevent="onSubmit">
        <CInput
          v-model="loginName"
          label="工号"
          placeholder="请输入工号（如 E002）"
          :error="!!errorMsg"
        />
        <CInput
          v-model="password"
          label="密码"
          type="password"
          placeholder="请输入密码"
          :error="!!errorMsg"
        />
        <div v-if="errorMsg" class="login__error">{{ errorMsg }}</div>
        <CButton type="submit" variant="primary" block :disabled="loading">
          {{ loading ? '登录中…' : '登 录' }}
        </CButton>
      </form>

      <div class="login__divider"><span>演示环境快捷登录</span></div>
      <div class="login__roles">
        <button
          v-for="r in QUICK_ROLES"
          :key="r.key"
          class="login__role-chip"
          :disabled="!!roleLoading"
          @click="quickLogin(r.key)"
        >
          {{ roleLoading === r.key ? '进入中…' : r.label }}
        </button>
      </div>
    </div>
    <div class="login__foot">美研云 · 内部系统，仅限授权员工使用</div>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--s-lg);
  padding: var(--s-xl);
  background: linear-gradient(160deg, rgba(77, 90, 217, 0.08) 0%, rgba(77, 90, 217, 0.02) 40%, var(--c-bg) 100%);
}
.login__card {
  width: 100%;
  max-width: 400px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-xl);
  padding: var(--s-xxl);
  box-shadow: var(--shadow-card);
}
.login__brand {
  display: flex;
  align-items: center;
  gap: var(--s-md);
  margin-bottom: var(--s-xl);
}
.login__logo {
  width: 44px;
  height: 44px;
  border-radius: var(--r-md);
  background: var(--c-brand);
  color: #fff;
  font-size: 22px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.login__title {
  font-size: var(--t-lg);
  font-weight: 600;
  color: var(--c-text);
}
.login__subtitle {
  font-size: var(--t-sm);
  color: var(--c-text-2);
  margin-top: 2px;
}
.login__form {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.login__error {
  font-size: var(--t-sm);
  color: var(--c-danger-fg, #ff4d4f);
  line-height: var(--lh-sm);
}
.login__divider {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  margin: var(--s-xl) 0 var(--s-md);
  color: var(--c-text-3, #999);
  font-size: var(--t-xs);
}
.login__divider::before,
.login__divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--c-border-light);
}
.login__roles {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--s-sm);
}
.login__role-chip {
  padding: 8px 10px;
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-sm);
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}
.login__role-chip:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
  background: rgba(77, 90, 217, 0.04);
}
.login__role-chip:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.login__foot {
  font-size: var(--t-xs);
  color: var(--c-text-3, #999);
}
</style>
