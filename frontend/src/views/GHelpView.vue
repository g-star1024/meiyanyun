<script setup lang="ts">
/* ============================================================
 * G-05 帮助中心（/help）
 * 搜索 + 分类 Tab + 快速入门 + FAQ + 视频教程
 * 反馈走 T3-03，按角色推不同手册
 * 注：项目已有 HelpView.vue（M2-22 门店培训用 /m2-help）
 * ============================================================ */
import { ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CSegmented from '@/components/CSegmented.vue'
import CIcon from '@/components/CIcon.vue'

const keyword = ref('')
const cat = ref('start')
const openFaq = ref<number | null>(0)

const CATS = [
  { label: '入门指南', value: 'start' },
  { label: '日常操作', value: 'daily' },
  { label: '常见问题', value: 'faq' },
  { label: '视频教程', value: 'video' },
]

const quickStart = [
  { icon: 'dashboard', title: '认识经营驾驶舱', desc: '3 分钟看懂今日核心指标' },
  { icon: 'calendar', title: '第一次创建预约', desc: '号源、分诊、到店提醒一条龙' },
  { icon: 'pos', title: '完成一笔收银', desc: '开单、支付、核销与小票' },
  { icon: 'customer', title: '建立客户档案', desc: '标签、等级与跟进记录' },
]

const faqs = [
  { q: '忘记密码怎么办？', a: '在登录页点击"忘记密码"，通过绑定手机号接收验证码重置。若手机号已变更，请联系门店管理员在 T1 权限管理中重置。' },
  { q: '退款为什么需要主管审批？', a: '根据集团双签阈值规则（G-08 / 系统设置），单笔退款超过 L1 阈值需主管单签，超过 L2 需双签。这是为了防范资金风险。' },
  { q: '客户手机号显示为 138****1234？', a: '默认开启手机号脱敏（A1-17 数据合规）。拥有 customer:view-phone 权限的角色可查看完整号码，且所有查看行为会写入审计日志。' },
  { q: '号源被超时未到店占用怎么办？', a: '系统按门店设置的"候诊超时"自动释放（默认 15 分钟），可在 /settings 营业分诊中调整。' },
  { q: '如何申请新功能或报告缺陷？', a: '点击页面右下"提交反馈"，工单进入 T3-03 平台；建议附截图与复现步骤，平均响应 4 小时。' },
  { q: '数据看板和实际经营对不上？', a: '先检查日期范围与门店筛选；如仍有差异，进入财务-日结对账，T+1 数据一般在次日 02:00 前完成归集。' },
]

const videos = [
  { title: '10 分钟搭建你的工作台', duration: '10:24', tag: '入门' },
  { title: '咨询师开单到收银全流程', duration: '15:08', tag: '日常' },
  { title: '客户标签与精准营销', duration: '12:45', tag: '运营' },
  { title: '月度经营报表怎么看', duration: '08:32', tag: '管理' },
]

function toggleFaq(i: number) {
  openFaq.value = openFaq.value === i ? null : i
}
function feedback() {
  alert('已为您打开 T3-03 反馈工单（按当前角色推荐分类）')
}
</script>

<template>
  <div class="g-help">
    <CCard padding="lg">
      <div class="hero">
        <p class="hero__sub">你好，有什么可以帮你？</p>
        <div class="hero__search">
          <CIcon name="search" :size="18" class="hero__ico" />
          <input
            v-model="keyword"
            class="hero__input"
            type="text"
            placeholder="搜索关键词，如：退款、预约、客户合并……"
          />
        </div>
        <div class="hero__cats">
          <CSegmented v-model="cat" :options="CATS" />
        </div>
      </div>
    </CCard>

    <div class="g-help__grid">
      <CCard title="快速入门" class="g-help__start">
        <template #header>
          <h3 class="block-title">快速入门</h3>
          <span class="block-sub">新手推荐 · 4 篇</span>
        </template>
        <div class="start-list">
          <button v-for="s in quickStart" :key="s.title" class="start-item">
            <span class="start-item__ico"><CIcon :name="(s.icon as any)" :size="20" /></span>
            <span class="start-item__text">
              <span class="start-item__title">{{ s.title }}</span>
              <span class="start-item__desc">{{ s.desc }}</span>
            </span>
            <CIcon name="chevron-right" :size="16" class="start-item__arr" />
          </button>
        </div>
      </CCard>

      <CCard title="常见问题" class="g-help__faq">
        <template #header>
          <h3 class="block-title">常见问题</h3>
          <span class="block-sub">{{ faqs.length }} 条</span>
        </template>
        <div class="faq">
          <div v-for="(f, i) in faqs" :key="i" class="faq__item">
            <button class="faq__q" @click="toggleFaq(i)">
              <span>{{ f.q }}</span>
              <CIcon name="chevron-down" :size="16" :class="['faq__arr', { 'is-open': openFaq === i }]" />
            </button>
            <p v-if="openFaq === i" class="faq__a">{{ f.a }}</p>
          </div>
        </div>
      </CCard>
    </div>

    <CCard title="视频教程" padding="lg">
      <template #header>
        <h3 class="block-title">视频教程</h3>
        <CButton variant="ghost" size="sm">查看全部</CButton>
      </template>
      <div class="videos">
        <div v-for="v in videos" :key="v.title" class="video">
          <div class="video__thumb">
            <span class="video__play">▶</span>
            <span class="video__dur">{{ v.duration }}</span>
          </div>
          <div class="video__meta">
            <span class="video__tag">{{ v.tag }}</span>
            <p class="video__title">{{ v.title }}</p>
          </div>
        </div>
      </div>
    </CCard>

    <CCard padding="lg">
      <div class="contact">
        <div class="contact__text">
          <h3 class="block-title">没找到答案？</h3>
          <p>7×12 小时在线客服，或提交工单，我们会在 4 小时内响应。反馈将根据您的角色自动推荐手册。</p>
        </div>
        <div class="contact__ops">
          <CButton variant="secondary" size="md">
            <CIcon name="phone" :size="14" /> 联系客服
          </CButton>
          <CButton variant="primary" size="md" @click="feedback">
            <CIcon name="edit" :size="14" /> 提交反馈
          </CButton>
        </div>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.g-help { display: flex; flex-direction: column; gap: var(--s-lg); }
.hero { display: flex; flex-direction: column; gap: var(--s-md); align-items: center; padding: var(--s-md) 0; }
.hero__sub { margin: 0; font-size: var(--t-lg); color: var(--c-text); font-weight: 600; }
.hero__search {
  display: flex; align-items: center; gap: var(--s-sm);
  width: 100%; max-width: 640px;
  padding: var(--s-sm) var(--s-md);
  border: 1px solid var(--c-border);
  border-radius: var(--r-capsule);
  background: var(--c-surface);
  box-shadow: var(--shadow-card);
}
.hero__ico { color: var(--c-text-3); }
.hero__input { flex: 1; border: none; outline: none; background: none; font-size: var(--t-base); color: var(--c-text); padding: var(--s-xs) 0; }
.hero__input::placeholder { color: var(--c-text-3); }
.hero__cats { display: flex; }
.block-title { font-size: var(--t-md); font-weight: 700; color: var(--c-text); margin: 0; }
.block-sub { font-size: var(--t-xs); color: var(--c-text-3); }
.g-help__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-lg); align-items: start; }

.start-list { display: flex; flex-direction: column; }
.start-item {
  display: flex; align-items: center; gap: var(--s-md);
  padding: var(--s-md);
  border: none; background: none; cursor: pointer;
  border-bottom: 1px solid var(--c-border-light); text-align: left;
  transition: background .15s;
}
.start-item:last-child { border-bottom: none; }
.start-item:hover { background: var(--c-bg-page); }
.start-item__ico {
  width: 40px; height: 40px; border-radius: var(--r-lg);
  background: var(--c-brand-soft); color: var(--c-brand);
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.start-item__text { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.start-item__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.start-item__desc { font-size: var(--t-xs); color: var(--c-text-3); }
.start-item__arr { color: var(--c-text-3); flex-shrink: 0; }

.faq { display: flex; flex-direction: column; }
.faq__item { border-bottom: 1px solid var(--c-border-light); }
.faq__item:last-child { border-bottom: none; }
.faq__q {
  width: 100%; display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-md) 0; border: none; background: none; cursor: pointer; text-align: left;
  font-size: var(--t-sm); font-weight: 600; color: var(--c-text);
}
.faq__arr { color: var(--c-text-3); transition: transform .2s; }
.faq__arr.is-open { transform: rotate(180deg); }
.faq__a {
  margin: 0 0 var(--s-md);
  font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md);
}

.videos { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.video { display: flex; flex-direction: column; gap: var(--s-xs); cursor: pointer; }
.video__thumb {
  position: relative; aspect-ratio: 16 / 10;
  background: linear-gradient(135deg, var(--c-brand-soft), var(--c-purple-soft));
  border-radius: var(--r-md);
  display: flex; align-items: center; justify-content: center;
}
.video__play {
  width: 40px; height: 40px; border-radius: 50%;
  background: rgba(255,255,255,.9); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center;
  font-size: 14px; padding-left: 3px;
  box-shadow: var(--shadow-pop);
}
.video__dur {
  position: absolute; right: var(--s-xs); bottom: var(--s-xs);
  background: rgba(0,0,0,.55); color: #fff;
  font-size: var(--t-xs); padding: 2px var(--s-xs); border-radius: var(--r-sm);
}
.video__meta { display: flex; flex-direction: column; gap: 4px; }
.video__tag {
  align-self: flex-start; font-size: var(--t-xs);
  padding: 1px var(--s-xs); background: var(--c-brand-soft); color: var(--c-brand);
  border-radius: var(--r-sm);
}
.video__title { margin: 0; font-size: var(--t-sm); color: var(--c-text); font-weight: 600; line-height: var(--lh-sm); }

.contact { display: flex; align-items: center; justify-content: space-between; gap: var(--s-lg); flex-wrap: wrap; }
.contact__text { display: flex; flex-direction: column; gap: var(--s-xs); max-width: 520px; }
.contact__text p { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); }
.contact__ops { display: flex; gap: var(--s-sm); flex-shrink: 0; }
</style>
