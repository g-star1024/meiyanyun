<script setup lang="ts">
import { ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const user = auth.user

const tab = ref<'info' | 'security' | 'preferences'>('info')

const form = ref({
  name: user.name,
  phone: '138****8866',
  email: `${user.staffId}@meiyun.com`,
  jobTitle: user.jobTitle,
  dept: user.storeId === 'grp' ? '集团总部' : user.storeId === 'rgn-east' ? '华东大区' : '静安旗舰店',
})

const security = ref({
  passwordChanged: '2026-07-15',
  mfaEnabled: true,
  lastLogin: '2026-08-26 09:12',
  lastIp: '10.0.12.88',
})

const prefs = ref({
  defaultPage: '/appointment',
  pageSize: '20',
  timezone: 'Asia/Shanghai',
  dateFormat: 'YYYY-MM-DD',
})

const storeLabel: Record<string, string> = {
  grp: '集团总部',
  'rgn-east': '华东大区',
  'store-jingan': '静安旗舰店',
}
const scopeLabel: Record<string, string> = {
  SELF: '仅本人',
  STORE: '本店',
  BRAND: '品牌',
  REGION: '大区',
  GROUP: '集团',
}

function save() {
  window.alert('个人信息已保存')
}
</script>

<template>
  <div class="g-profile">
    <!-- 顶部个人信息卡 -->
    <CCard padding="lg">
      <div class="profile-head">
        <div class="profile-avatar">{{ user.avatarLetter }}</div>
        <div class="profile-meta">
          <div class="profile-name">
            {{ user.name }}
            <CStatusPill status="success" dot>{{ user.roleLabels }}</CStatusPill>
          </div>
          <div class="profile-sub">{{ user.jobTitle }} · {{ storeLabel[user.storeId] || user.storeId }}</div>
          <div class="profile-sub">工号：{{ user.staffId }} · 数据范围：{{ scopeLabel[String(user.scope)] || user.scope }}</div>
        </div>
        <div class="profile-actions">
          <CButton variant="secondary" size="sm">切换角色</CButton>
        </div>
      </div>
    </CCard>

    <div class="profile-body">
      <!-- 左侧 Tab -->
      <CCard padding="none" class="profile-side">
        <div class="side-tabs">
          <button
            v-for="t in [
              { k: 'info', label: '基本信息' },
              { k: 'security', label: '安全设置' },
              { k: 'preferences', label: '偏好设置' },
            ]"
            :key="t.k"
            class="side-tab"
            :class="{ 'side-tab--active': tab === t.k }"
            @click="tab = t.k as typeof tab"
          >
            {{ t.label }}
          </button>
        </div>
      </CCard>

      <!-- 右侧内容 -->
      <CCard padding="lg" class="profile-content">
        <!-- 基本信息 -->
        <template v-if="tab === 'info'">
          <h3 class="section-title">基本信息</h3>
          <div class="form-grid">
            <div class="form-item">
              <label>姓名</label>
              <CInput v-model="form.name" />
            </div>
            <div class="form-item">
              <label>工号</label>
              <CInput :model-value="user.staffId" disabled />
            </div>
            <div class="form-item">
              <label>手机号</label>
              <CInput v-model="form.phone" />
            </div>
            <div class="form-item">
              <label>邮箱</label>
              <CInput v-model="form.email" />
            </div>
            <div class="form-item">
              <label>职位</label>
              <CInput v-model="form.jobTitle" />
            </div>
            <div class="form-item">
              <label>所属部门</label>
              <CInput v-model="form.dept" />
            </div>
          </div>
          <div class="form-actions">
            <CButton variant="primary" @click="save">保存修改</CButton>
          </div>
        </template>

        <!-- 安全设置 -->
        <template v-else-if="tab === 'security'">
          <h3 class="section-title">安全设置</h3>
          <div class="security-list">
            <div class="security-row">
              <div>
                <div class="security-label">登录密码</div>
                <div class="security-desc">上次修改：{{ security.passwordChanged }}</div>
              </div>
              <CButton variant="secondary" size="sm">修改密码</CButton>
            </div>
            <div class="security-row">
              <div>
                <div class="security-label">双因素认证（MFA）</div>
                <div class="security-desc">{{ security.mfaEnabled ? '已开启，登录需验证码' : '未开启' }}</div>
              </div>
              <CButton :variant="security.mfaEnabled ? 'secondary' : 'primary'" size="sm">
                {{ security.mfaEnabled ? '关闭' : '开启' }}
              </CButton>
            </div>
            <div class="security-row">
              <div>
                <div class="security-label">最近登录</div>
                <div class="security-desc">{{ security.lastLogin }} · IP {{ security.lastIp }}</div>
              </div>
              <CButton variant="text" size="sm">查看登录记录</CButton>
            </div>
            <div class="security-row">
              <div>
                <div class="security-label">会话管理</div>
                <div class="security-desc">当前设备：macOS / Chrome · 其他在线设备 0 台</div>
              </div>
              <CButton variant="secondary" size="sm">退出其他设备</CButton>
            </div>
          </div>
        </template>

        <!-- 偏好设置 -->
        <template v-else>
          <h3 class="section-title">偏好设置</h3>
          <div class="form-grid">
            <div class="form-item">
              <label>默认首页</label>
              <CSelect
                v-model="prefs.defaultPage"
                :options="[
                  { value: '/appointment', label: '预约看板' },
                  { value: '/m2-schedule', label: '排班管理' },
                  { value: '/m3-customers', label: '客户列表' },
                  { value: '/m1-screen', label: '经营大屏' },
                ]"
              />
            </div>
            <div class="form-item">
              <label>每页条数</label>
              <CSelect
                v-model="prefs.pageSize"
                :options="[
                  { value: '10', label: '10 条' },
                  { value: '20', label: '20 条' },
                  { value: '50', label: '50 条' },
                ]"
              />
            </div>
            <div class="form-item">
              <label>时区</label>
              <CSelect
                v-model="prefs.timezone"
                :options="[{ value: 'Asia/Shanghai', label: 'Asia/Shanghai (UTC+8)' }]"
              />
            </div>
            <div class="form-item">
              <label>日期格式</label>
              <CSelect
                v-model="prefs.dateFormat"
                :options="[
                  { value: 'YYYY-MM-DD', label: '2026-08-26' },
                  { value: 'DD/MM/YYYY', label: '26/08/2026' },
                  { value: 'MM/DD/YYYY', label: '08/26/2026' },
                ]"
              />
            </div>
          </div>
          <div class="form-actions">
            <CButton variant="primary" @click="save">保存偏好</CButton>
          </div>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.g-profile {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}

.profile-head {
  display: flex;
  align-items: center;
  gap: var(--s-lg);
}
.profile-avatar {
  width: 64px;
  height: 64px;
  border-radius: var(--r-full);
  background: var(--c-brand);
  color: #fff;
  font-size: 28px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.profile-meta {
  flex: 1;
}
.profile-name {
  font-size: var(--t-lg);
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: var(--s-sm);
}
.profile-sub {
  font-size: var(--t-sm);
  color: var(--c-text-2);
  margin-top: 4px;
}

.profile-body {
  display: flex;
  gap: var(--s-md);
  align-items: flex-start;
}
.profile-side {
  width: 180px;
  flex-shrink: 0;
}
.side-tabs {
  display: flex;
  flex-direction: column;
}
.side-tab {
  padding: 10px var(--s-md);
  text-align: left;
  font-size: var(--t-sm);
  color: var(--c-text-2);
  background: none;
  border: none;
  border-left: 2px solid transparent;
  cursor: pointer;
  transition: all 0.12s;
}
.side-tab:hover {
  background: var(--c-bg-page);
  color: var(--c-text-1);
}
.side-tab--active {
  color: var(--c-brand);
  border-left-color: var(--c-brand);
  background: var(--c-brand-soft);
  font-weight: 600;
}

.profile-content {
  flex: 1;
  min-width: 0;
}
.section-title {
  font-size: var(--t-md);
  font-weight: 600;
  margin: 0 0 var(--s-md);
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-md);
}
.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form-item label {
  font-size: var(--t-xs);
  color: var(--c-text-2);
}
.form-actions {
  margin-top: var(--s-lg);
  display: flex;
  gap: var(--s-sm);
}

.security-list {
  display: flex;
  flex-direction: column;
}
.security-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--s-md) 0;
  border-bottom: 1px solid var(--c-border-light);
}
.security-row:last-child {
  border-bottom: none;
}
.security-label {
  font-size: var(--t-sm);
  font-weight: 500;
}
.security-desc {
  font-size: var(--t-xs);
  color: var(--c-text-3);
  margin-top: 2px;
}
</style>
