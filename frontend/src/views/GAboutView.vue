<script setup lang="ts">
/* ============================================================
 * G-08 关于/版本（/about）
 * 居中布局，产品介绍 / 技术栈 / 合规声明 / 开源许可 / 条款
 * ============================================================ */
import { ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'

const version = 'v3.2.0'
const build = '2026.08.20-stable+8f3a2c1'
const releaseDate = '2026-08-20'

const stacks = [
  { group: '前端', items: ['Vue 3', 'TypeScript', 'Vite', 'Pinia'] },
  { group: '后端', items: ['Java 17 / Spring Boot 3.2', 'Go（国密网关）'] },
  { group: '数据', items: ['PostgreSQL', 'Redis'] },
]
const compliance = [
  { label: '网络安全等级保护', value: '三级备案' },
  { label: '国密算法', value: 'SM2 / SM3 / SM4' },
  { label: '数据脱敏', value: 'A1-17 规则（手机号 / 身份证 / 卡号）' },
  { label: '信息安全管理体系', value: 'ISO 27001' },
]
const licenses = [
  { name: 'Vue', license: 'MIT' },
  { name: 'Vite', license: 'MIT' },
  { name: 'Pinia', license: 'MIT' },
  { name: 'Vue Router', license: 'MIT' },
  { name: 'Spring Boot', license: 'Apache-2.0' },
  { name: 'PostgreSQL JDBC', license: 'BSD-2-Clause' },
  { name: 'Go', license: 'BSD-3-Clause' },
]
const licenseOpen = ref(false)

function checkUpdate() {
  alert('当前已是最新版本 ' + version)
}
</script>

<template>
  <div class="g-about">
    <CCard padding="lg">
      <div class="brand">
        <div class="brand__logo">美</div>
        <h2 class="brand__name">美研云门店中台</h2>
        <p class="brand__ver">{{ version }} · 构建 {{ build }}</p>
        <p class="brand__date">发布日期：{{ releaseDate }}</p>
      </div>
    </CCard>

    <CCard title="产品介绍" padding="lg">
      <p class="para">
        美研云门店中台是面向美业连锁集团的一体化经营平台，覆盖预约分诊、接待咨询、交易收银、
        客户运营、财务结算、合规审计与数据决策全链路。我们以"前台轻、总部强、数据通"为设计理念，
        帮助门店提升转化、降低风险，让集团实时掌握经营全貌。
      </p>
    </CCard>

    <CCard title="技术栈" padding="lg">
      <div class="stacks">
        <div v-for="s in stacks" :key="s.group" class="stacks__grp">
          <h4 class="stacks__title">{{ s.group }}</h4>
          <div class="stacks__chips">
            <span v-for="it in s.items" :key="it" class="chip">{{ it }}</span>
          </div>
        </div>
      </div>
    </CCard>

    <CCard title="合规与安全" padding="lg">
      <ul class="comp">
        <li v-for="c in compliance" :key="c.label" class="comp__item">
          <CIcon name="shield" :size="16" class="comp__ico" />
          <span class="comp__label">{{ c.label }}</span>
          <span class="comp__value">{{ c.value }}</span>
        </li>
      </ul>
    </CCard>

    <CCard padding="none">
      <button class="license-head" @click="licenseOpen = !licenseOpen">
        <span>开源许可</span>
        <CIcon name="chevron-down" :size="16" :class="['license-arr', { 'is-open': licenseOpen }]" />
      </button>
      <div v-if="licenseOpen" class="license-body">
        <div v-for="l in licenses" :key="l.name" class="license-row">
          <span class="license-row__name">{{ l.name }}</span>
          <span class="license-row__lic">{{ l.license }}</span>
        </div>
      </div>
    </CCard>

    <CCard padding="lg">
      <div class="links">
        <a href="#" class="link">服务条款</a>
        <span class="dot">·</span>
        <a href="#" class="link">隐私政策</a>
        <span class="dot">·</span>
        <a href="#" class="link">第三方 SDK 目录</a>
      </div>
    </CCard>

    <div class="foot">
      <CButton variant="secondary" size="md" @click="checkUpdate">
        <CIcon name="check-square" :size="14" /> 检查更新
      </CButton>
      <p class="copy">© 2026 美研云科技 · 保留所有权利</p>
    </div>
  </div>
</template>

<style scoped>
.g-about { max-width: 640px; margin: 0 auto; display: flex; flex-direction: column; gap: var(--s-lg); }
.brand { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-md) 0; }
.brand__logo {
  width: 72px; height: 72px; border-radius: 50%;
  background: linear-gradient(135deg, var(--c-brand), var(--c-brand-press));
  color: #fff; font-size: 32px; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center;
  box-shadow: var(--shadow-fab);
}
.brand__name { margin: 0; font-size: var(--t-xl); font-weight: 700; color: var(--c-text); }
.brand__ver { margin: 0; font-size: var(--t-sm); color: var(--c-brand); font-weight: 600; }
.brand__date { margin: 0; font-size: var(--t-xs); color: var(--c-text-3); }
.para { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-lg); }

.stacks { display: flex; flex-direction: column; gap: var(--s-md); }
.stacks__title { margin: 0 0 var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); text-transform: uppercase; letter-spacing: .5px; font-weight: 600; }
.stacks__chips { display: flex; flex-wrap: wrap; gap: var(--s-xs); }
.chip {
  padding: var(--s-xxs) var(--s-sm);
  background: var(--c-bg-page);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-capsule);
  font-size: var(--t-xs); color: var(--c-text-2);
}

.comp { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--s-sm); }
.comp__item { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-xs) 0; font-size: var(--t-sm); }
.comp__ico { color: var(--c-success-fg); flex-shrink: 0; }
.comp__label { color: var(--c-text-2); flex: 1; }
.comp__value { color: var(--c-text); font-weight: 600; }

.license-head {
  width: 100%; display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-md) var(--s-lg);
  border: none; background: none; cursor: pointer;
  font-size: var(--t-md); font-weight: 700; color: var(--c-text); text-align: left;
}
.license-arr { color: var(--c-text-3); transition: transform .2s; }
.license-arr.is-open { transform: rotate(180deg); }
.license-body { padding: 0 var(--s-lg) var(--s-md); display: flex; flex-direction: column; gap: var(--s-xs); border-top: 1px solid var(--c-border-light); }
.license-row { display: flex; justify-content: space-between; padding: var(--s-xs) 0; font-size: var(--t-sm); }
.license-row__name { color: var(--c-text); }
.license-row__lic { color: var(--c-text-3); font-family: monospace; }

.links { display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.link { font-size: var(--t-sm); color: var(--c-brand-secondary); }
.link:hover { color: var(--c-brand); }
.dot { color: var(--c-text-3); }

.foot { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-md) 0; }
.copy { margin: 0; font-size: var(--t-xs); color: var(--c-text-3); }
</style>
