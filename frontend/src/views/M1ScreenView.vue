<template>
  <div class="scr">
    <!-- 顶部标题栏 -->
    <header class="scr__header">
      <div class="scr__title">美云集团 · 经营数据大屏</div>
      <div class="scr__clock">{{ scr.timeStr }}</div>
    </header>

    <!-- KPI 行 -->
    <section class="scr__kpis">
      <div v-for="k in scr.kpis" :key="k.label" class="skpi">
        <div class="skpi__label">{{ k.label }}</div>
        <div class="skpi__value">
          <span v-if="k.prefix" class="skpi__prefix">{{ k.prefix }}</span>{{ k.value.toLocaleString() }}<span class="skpi__unit">{{ k.unit }}</span>
        </div>
        <div class="skpi__delta" :class="k.delta >= 0 ? 'up' : 'down'">
          <CIcon :name="k.delta >= 0 ? 'trend-up' : 'trend-down'" :size="13" />
          {{ Math.abs(k.delta) }}% 较昨日
        </div>
      </div>
    </section>

    <div class="scr__grid">
      <!-- 左：实时营收趋势 -->
      <section class="panel">
        <div class="panel__h">今日实时营收趋势（万元/时）</div>
        <div class="panel__b">
          <svg viewBox="0 0 480 200" class="linechart" preserveAspectRatio="none">
            <defs>
              <linearGradient id="lg" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="#ff6b9e" stop-opacity="0.4" />
                <stop offset="100%" stop-color="#ff6b9e" stop-opacity="0" />
              </linearGradient>
            </defs>
            <line v-for="i in 4" :key="i" class="lc-grid" :x1="40" :x2="470" :y1="40 * i" :y2="40 * i" />
            <polygon :points="areaPoints" fill="url(#lg)" />
            <polyline :points="linePoints" fill="none" stroke="#ff6b9e" stroke-width="2.5" stroke-linejoin="round" />
            <circle v-for="(p, i) in points" :key="i" :cx="p.x" :cy="p.y" r="3.5" fill="#fff" stroke="#ff6b9e" stroke-width="2" />
            <text v-for="(p, i) in points" :key="'t'+i" :x="p.x" :y="p.y - 10" class="lc-val">{{ scr.hourly[i].v }}</text>
            <text v-for="(h, i) in scr.hourly" :key="'h'+i" :x="40 + i * stepX" y="192" class="lc-axis">{{ h.h }}</text>
          </svg>
        </div>
      </section>

      <!-- 中：品类占比 + 门店排行 -->
      <section class="panel">
        <div class="panel__h">项目品类营收占比</div>
        <div class="panel__b panel__b--pie">
          <svg viewBox="0 0 200 200" class="pie">
            <circle v-for="(d, i) in donutSegs" :key="i" cx="100" cy="100" r="70"
                    fill="transparent" :stroke="d.color" stroke-width="28"
                    :stroke-dasharray="d.len + ' ' + (circ - d.len)"
                    :stroke-dashoffset="-d.offset"
                    transform="rotate(-90 100 100)" />
            <text x="100" y="96" text-anchor="middle" class="pie__total">100%</text>
            <text x="100" y="114" text-anchor="middle" class="pie__sub">营收构成</text>
          </svg>
          <div class="legend">
            <div v-for="d in scr.categoryShare" :key="d.name" class="legend__row">
              <i :style="{ background: d.color }" /><span>{{ d.name }}</span><b>{{ d.value }}%</b>
            </div>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel__h">门店营收排行（万元）</div>
        <div class="panel__b">
          <div v-for="(s, i) in scr.storeRanks" :key="s.name" class="rank-row">
            <span class="rank-row__no" :class="'no--' + (i+1)">{{ i + 1 }}</span>
            <span class="rank-row__name">{{ s.name }}</span>
            <div class="rank-row__bar"><i :style="{ width: (s.value / 40 * 100) + '%' }" /></div>
            <span class="rank-row__v">{{ s.value }}</span>
          </div>
        </div>
      </section>
    </div>

    <!-- 底部：实时成交流 -->
    <section class="panel panel--stream">
      <div class="panel__h"><CIcon name="order" :size="15" /> 实时成交流
        <span class="live"><i />LIVE</span>
      </div>
      <div class="stream">
        <div v-for="o in scr.realtime" :key="o.id" class="stream__row">
          <span class="stream__time">{{ o.time }}</span>
          <span class="stream__store">{{ o.store }}</span>
          <span class="stream__cust">{{ o.customer }}</span>
          <span class="stream__item">{{ o.item }}</span>
          <span class="stream__ch">{{ o.channel }}</span>
          <span class="stream__amt">¥{{ o.amount.toLocaleString() }}</span>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import CIcon from '@/components/CIcon.vue'
import { useM1ScreenStore } from '@/stores/m1Screen'

const scr = useM1ScreenStore()
let clock: ReturnType<typeof setInterval>
onMounted(() => {
  scr.tick()
  clock = setInterval(() => { scr.tick(); if (Math.random() > 0.5) scr.pushOrder() }, 3000)
})
onUnmounted(() => clearInterval(clock))

const stepX = 430 / (scr.hourly.length - 1)
const maxV = computed(() => Math.max(...scr.hourly.map((h) => h.v)))
const points = computed(() => scr.hourly.map((h, i) => ({
  x: 40 + i * stepX,
  y: 170 - (h.v / maxV.value) * 140,
})))
const linePoints = computed(() => points.value.map((p) => p.x + ',' + p.y).join(' '))
const areaPoints = computed(() => `40,170 ${linePoints.value} 470,170`)

// 环形图分段
const circ = 2 * Math.PI * 70
const donutSegs = computed(() => {
  let acc = 0
  return scr.categoryShare.map((d) => {
    const len = (d.value / 100) * circ
    const seg = { color: d.color, len, offset: acc }
    acc += len
    return seg
  })
})
</script>

<style scoped>
.scr {
  min-height: 100%;
  background: radial-gradient(ellipse at top, #15233f 0%, #0a1124 60%, #060b18 100%);
  color: #e6edff;
  padding: var(--s-md);
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
  box-sizing: border-box;
}
.scr__header { display: flex; justify-content: space-between; align-items: center; }
.scr__title { font-size: var(--t-xl); font-weight: 700; letter-spacing: 2px;
  background: linear-gradient(90deg, #ff6b9e, #6b8aff); -webkit-background-clip: text; background-clip: text; color: transparent; }
.scr__clock { font-family: monospace; font-size: var(--t-lg); color: #8fa3d1; }

.scr__kpis { display: grid; grid-template-columns: repeat(6, 1fr); gap: var(--s-sm); }
.skpi { background: rgba(255,255,255,0.04); border: 1px solid rgba(107,138,255,0.2); border-radius: var(--r-lg); padding: var(--s-sm) var(--s-md); min-width: 0; }
.skpi__label { font-size: var(--t-xs); color: #8fa3d1; white-space: nowrap; }
.skpi__value { font-size: 22px; font-weight: 700; margin: 6px 0 2px; color: #fff; white-space: nowrap; font-variant-numeric: tabular-nums; }
.skpi__prefix { font-size: var(--t-sm); margin-right: 2px; color: #8fa3d1; }
.skpi__unit { font-size: var(--t-xs); color: #8fa3d1; margin-left: 3px; font-weight: 400; }
.skpi__delta { display: inline-flex; align-items: center; gap: 3px; font-size: var(--t-xs); }
.skpi__delta.up { color: #52c41a; }
.skpi__delta.down { color: #ff4d4f; }

.scr__grid { display: grid; grid-template-columns: 1.4fr 1fr 1fr; gap: var(--s-md); flex: 1; min-height: 0; }
.panel { background: rgba(255,255,255,0.03); border: 1px solid rgba(107,138,255,0.18); border-radius: var(--r-lg); padding: var(--s-md); display: flex; flex-direction: column; min-height: 0; }
.panel__h { font-size: var(--t-sm); font-weight: 600; color: #b9c8ee; margin-bottom: var(--s-sm); display: flex; align-items: center; gap: 6px; }
.panel__b { flex: 1; }
.panel__b--pie { display: flex; align-items: center; gap: var(--s-sm); }
.pie { width: 150px; height: 150px; flex-shrink: 0; }
.pie__total { fill: #fff; font-size: 22px; font-weight: 700; }
.pie__sub { fill: #8fa3d1; font-size: 11px; }
.legend { flex: 1; display: flex; flex-direction: column; gap: 6px; }
.legend__row { display: flex; align-items: center; gap: 8px; font-size: var(--t-xs); color: #c5d2f0; }
.legend__row i { width: 10px; height: 10px; border-radius: 2px; }
.legend__row b { margin-left: auto; color: #fff; }

.linechart { width: 100%; height: 200px; }
.lc-grid { stroke: rgba(107,138,255,0.12); stroke-width: 1; }
.lc-val { fill: #ff9ec0; font-size: 10px; text-anchor: middle; }
.lc-axis { fill: #6b7fa8; font-size: 10px; text-anchor: middle; }

.rank-row { display: grid; grid-template-columns: 24px 1fr 100px 44px; align-items: center; gap: var(--s-xs); margin-bottom: var(--s-sm); font-size: var(--t-xs); }
.rank-row__no { width: 22px; height: 22px; border-radius: 4px; background: rgba(255,255,255,0.08); color: #8fa3d1; display: flex; align-items: center; justify-content: center; font-weight: 700; }
.rank-row__no.no--1 { background: #f5c518; color: #1a1a1a; }
.rank-row__no.no--2 { background: #b0b8c1; color: #1a1a1a; }
.rank-row__no.no--3 { background: #cd7f32; color: #fff; }
.rank-row__name { color: #c5d2f0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rank-row__bar { height: 8px; background: rgba(255,255,255,0.06); border-radius: 4px; overflow: hidden; }
.rank-row__bar i { display: block; height: 100%; background: linear-gradient(90deg, #6b8aff, #ff6b9e); border-radius: 4px; }
.rank-row__v { text-align: right; color: #fff; font-weight: 700; }

.panel--stream { padding: var(--s-sm) var(--s-md); }
.live { margin-left: auto; font-size: 10px; color: #ff4d4f; display: inline-flex; align-items: center; gap: 4px; }
.live i { width: 7px; height: 7px; border-radius: 50%; background: #ff4d4f; animation: blink 1.2s infinite; }
@keyframes blink { 0%,100% { opacity: 1; } 50% { opacity: 0.3; } }
.stream { display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px var(--s-md); }
.stream__row { display: contents; font-size: var(--t-xs); }
.stream__row > span { padding: 5px 0; border-bottom: 1px solid rgba(107,138,255,0.08); }
.stream__time { color: #6b7fa8; font-family: monospace; }
.stream__store { color: #b9c8ee; }
.stream__cust { color: #8fa3d1; }
.stream__item { color: #c5d2f0; }
.stream__ch { color: #6b8aff; }
.stream__amt { color: #ff9ec0; font-weight: 700; text-align: right; }

@media (max-width: 1200px) {
  .scr__kpis { grid-template-columns: repeat(3, 1fr); }
  .scr__grid { grid-template-columns: 1fr; }
  .stream { grid-template-columns: 1fr; }
}
</style>
