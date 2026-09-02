<script setup lang="ts">
/* ============================================================
 * G-09 新手引导（/guide）
 * 6 步引导：工作台 / 预约 / 接待咨询 / 交易收银 / 客户运营 / 数据报表
 * ============================================================ */
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'

interface Step {
  title: string
  subtitle: string
  desc: string
  points: string[]
  link: { text: string; path: string }
}
const steps: Step[] = [
  {
    title: '了解工作台',
    subtitle: '第 1 步 · 总览经营全貌',
    desc: '工作台是您每日打开美研云看到的第一个页面。顶部 KPI 展示今日核心指标，下方卡片展示待办、预约、告警与任务。',
    points: [
      '顶部 KPI：今日到店 / 待到店 / 成交额 / 转化率',
      '待办中心集中处理审批、回访与客诉',
      '告警区会高亮库存、异常订单等风险项',
      '点击任意卡片可下钻到对应业务页面',
    ],
    link: { text: '前往驾驶舱实践', path: '/dashboard' },
  },
  {
    title: '预约管理',
    subtitle: '第 2 步 · 预约分诊一条龙',
    desc: '在预约看板可以按日 / 周查看全部预约，支持拖拽改派、到店确认、取消与释放号源。新预约可由前台、企微或线上渠道汇入。',
    points: [
      '看板按时间轴展示预约，颜色区状态',
      '拖拽即可改派医生 / 房间 / 时间',
      '到店后一键"确认到店"进入接待',
      '超时未到店自动释放号源（可配置）',
    ],
    link: { text: '去预约看板实践', path: '/appointment/board' },
  },
  {
    title: '接待与咨询',
    subtitle: '第 3 步 · 从到店到建档',
    desc: '客户到店后，前台在接待台完成登记；咨询师在咨询页面记录皮肤检测、需求与方案，系统自动沉淀到客户 360。',
    points: [
      '前台扫码或手机号快速登记',
      '咨询记录支持图片、话术模板与处方建议',
      '敏感信息默认脱敏（A1-17）',
      '可直接发起开单或预约下次到店',
    ],
    link: { text: '查看接待台', path: '/reception' },
  },
  {
    title: '交易收银',
    subtitle: '第 4 步 · 开单到结账',
    desc: '收银台支持套餐、疗程、次卡与散客单，支持现金、刷卡、微信、支付宝与储值。退款按金额触发单签 / 双签审批。',
    points: [
      '扫码 / 搜索客户自动带出等级与权益',
      '套餐与疗程自动核销剩余次数',
      '退款超阈值自动走审批流（G-08）',
      '小票支持补打与电子小票',
    ],
    link: { text: '进入收银台', path: '/pos' },
  },
  {
    title: '客户运营',
    subtitle: '第 5 步 · 标签 · 分群 · 回访',
    desc: '客户 360 整合基础档案、消费、跟进、标签与旅程。运营可基于分群发起企微 / 短信 / 优惠券触达，系统自动记录 ROI。',
    points: [
      '客户 360 一屏看清全部交互',
      '标签工厂支持自定义标签与规则',
      '分群可一键发起营销任务（M5）',
      '回访任务自动派发到责任人',
    ],
    link: { text: '打开客户列表', path: '/customer/list' },
  },
  {
    title: '数据报表',
    subtitle: '第 6 步 · 用数据做决策',
    desc: '经营驾驶舱、财务报表、营销 ROI 与客户分析覆盖管理层到一线的全部数据需求。T+1 数据在次日 02:00 前完成归集。',
    points: [
      '驾驶舱看实时，经营报表看周期',
      '财务日结 / 月结对账自动生成',
      '营销 ROI 自动归因到活动',
      '支持按集团 / 区域 / 门店下钻',
    ],
    link: { text: '查看经营报表', path: '/daily' },
  },
]

const current = ref(2) // 从第 3 步开始演示（题目要求显示 3/6 之类）
const done = ref<Set<number>>(new Set([0, 1]))
const progress = computed(() => Math.round(((current.value + 1) / steps.length) * 100))
const step = computed(() => steps[current.value])
const isLast = computed(() => current.value === steps.length - 1)

function go(i: number) {
  if (i < 0 || i >= steps.length) return
  if (i > current.value) done.value.add(current.value)
  current.value = i
}
function next() {
  if (isLast.value) {
    done.value.add(current.value)
    alert('恭喜完成新手引导！可随时在 /help 回顾教程。')
    return
  }
  done.value.add(current.value)
  current.value += 1
}
function prev() {
  if (current.value > 0) current.value -= 1
}
function skip() {
  if (confirm('确定跳过新手引导？可随时在帮助中心重新进入。')) {
    current.value = 0
  }
}
</script>

<template>
  <div class="g-guide">
    <CCard padding="lg">
      <div class="bar">
        <div class="bar__top">
          <span class="bar__label">新手引导进度</span>
          <span class="bar__pct">{{ current + 1 }} / {{ steps.length }}</span>
        </div>
        <div class="bar__track">
          <div class="bar__fill" :style="{ width: progress + '%' }" />
        </div>
        <ol class="bar__steps">
          <li v-for="(s, i) in steps" :key="i" class="bar__step">
            <button
              class="dot"
              :class="{ 'is-done': done.has(i), 'is-active': i === current }"
              @click="go(i)"
            >
              <CIcon v-if="done.has(i) && i !== current" name="check" :size="12" />
              <span v-else>{{ i + 1 }}</span>
            </button>
            <span class="dot__label" :class="{ 'is-active': i === current }">{{ s.title }}</span>
          </li>
        </ol>
      </div>
    </CCard>

    <div class="g-guide__main">
      <CCard padding="lg">
        <div class="step">
          <p class="step__sub">{{ step.subtitle }}</p>
          <h2 class="step__title">{{ step.title }}</h2>
          <p class="step__desc">{{ step.desc }}</p>

          <div class="step__shot">
            <span class="step__shot-text">操作示意图</span>
          </div>

          <div class="step__points">
            <h4 class="step__points-title">本步骤要点</h4>
            <ul>
              <li v-for="(p, i) in step.points" :key="i">
                <CIcon name="check-square" :size="14" class="step__chk" />
                <span>{{ p }}</span>
              </li>
            </ul>
          </div>

          <RouterLink :to="step.link.path" class="step__link">
            {{ step.link.text }} <CIcon name="chevron-right" :size="14" />
          </RouterLink>
        </div>
      </CCard>

      <CCard title="引导进度" class="g-guide__side" padding="md">
        <ul class="prog">
          <li v-for="(s, i) in steps" :key="i">
            <button
              class="prog__item"
              :class="{ 'is-active': i === current, 'is-done': done.has(i) }"
              @click="go(i)"
            >
              <span class="prog__idx">
                <CIcon v-if="done.has(i)" name="check" :size="12" />
                <span v-else>{{ i + 1 }}</span>
              </span>
              <span class="prog__text">{{ s.title }}</span>
              <CStatusPill v-if="done.has(i)" status="success">已完成</CStatusPill>
              <CStatusPill v-else-if="i === current" status="info">进行中</CStatusPill>
              <CStatusPill v-else status="default">待开始</CStatusPill>
            </button>
          </li>
        </ul>
      </CCard>
    </div>

    <div class="g-guide__foot">
      <CButton variant="ghost" size="md" @click="skip">跳过引导</CButton>
      <div class="g-guide__foot-right">
        <CButton variant="secondary" size="md" :disabled="current === 0" @click="prev">上一步</CButton>
        <CButton variant="primary" size="md" @click="next">
          {{ isLast ? '完成' : '下一步' }}
        </CButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.g-guide { display: flex; flex-direction: column; gap: var(--s-lg); }

.bar__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-sm); }
.bar__label { font-size: var(--t-sm); color: var(--c-text-2); font-weight: 600; }
.bar__pct { font-size: var(--t-sm); color: var(--c-brand); font-weight: 600; }
.bar__track { height: 6px; background: var(--c-chart-track); border-radius: var(--r-capsule); overflow: hidden; }
.bar__fill { height: 100%; background: linear-gradient(90deg, var(--c-brand), var(--c-purple)); transition: width .3s; border-radius: var(--r-capsule); }
.bar__steps { list-style: none; margin: var(--s-md) 0 0; padding: 0; display: grid; grid-template-columns: repeat(6, 1fr); gap: var(--s-xs); }
.bar__step { display: flex; flex-direction: column; align-items: center; gap: var(--s-xs); }
.dot {
  width: 28px; height: 28px; border-radius: 50%;
  border: none; cursor: pointer;
  background: var(--c-bg-page); color: var(--c-text-3);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: var(--t-xs); font-weight: 600;
  transition: all .15s;
}
.dot.is-done { background: var(--c-success-fg); color: #fff; }
.dot.is-active { background: var(--c-brand); color: #fff; box-shadow: 0 0 0 4px var(--c-brand-soft); }
.dot__label { font-size: var(--t-xs); color: var(--c-text-3); text-align: center; }
.dot__label.is-active { color: var(--c-brand); font-weight: 600; }

.g-guide__main { display: grid; grid-template-columns: 1fr 280px; gap: var(--s-lg); align-items: start; }
.step { display: flex; flex-direction: column; gap: var(--s-md); }
.step__sub { margin: 0; font-size: var(--t-xs); color: var(--c-brand); font-weight: 600; }
.step__title { margin: 0; font-size: var(--t-2xl); font-weight: 700; color: var(--c-text); }
.step__desc { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-lg); }
.step__shot {
  height: 220px; border-radius: var(--r-lg);
  background: repeating-linear-gradient(135deg, var(--c-bg-page), var(--c-bg-page) 12px, var(--c-border-light) 12px, var(--c-border-light) 24px);
  display: flex; align-items: center; justify-content: center;
  border: 1px dashed var(--c-border);
}
.step__shot-text { font-size: var(--t-sm); color: var(--c-text-3); }
.step__points-title { margin: 0 0 var(--s-xs); font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.step__points ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--s-xs); }
.step__points li { display: flex; align-items: flex-start; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); }
.step__chk { color: var(--c-brand); flex-shrink: 0; margin-top: 3px; }
.step__link {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: var(--t-sm); color: var(--c-brand); font-weight: 600;
  align-self: flex-start;
}
.step__link:hover { color: var(--c-brand-press); }

.g-guide__side { position: sticky; top: var(--s-md); }
.prog { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.prog__item {
  display: grid; grid-template-columns: 24px 1fr auto; align-items: center; gap: var(--s-xs);
  padding: var(--s-xs) 0; border: none; background: none; cursor: pointer; text-align: left;
  border-bottom: 1px solid var(--c-border-light);
}
.prog__item:last-child { border-bottom: none; }
.prog__idx {
  width: 22px; height: 22px; border-radius: 50%;
  background: var(--c-bg-page); color: var(--c-text-3);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 10px; font-weight: 600;
}
.prog__item.is-done .prog__idx { background: var(--c-success-fg); color: #fff; }
.prog__item.is-active .prog__idx { background: var(--c-brand); color: #fff; }
.prog__text { font-size: var(--t-xs); color: var(--c-text-2); }
.prog__item.is-active .prog__text { color: var(--c-brand); font-weight: 600; }

.g-guide__foot {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-sm) 0;
}
.g-guide__foot-right { display: flex; gap: var(--s-sm); }
</style>
