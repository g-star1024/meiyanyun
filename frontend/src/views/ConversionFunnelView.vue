<script setup lang="ts">
/* ============================================================
 * 转化漏斗分析（/conversion-funnel）· 跨业务域全链路（P5-B99 切真）
 * 线索(有效预约+落地页留资) → 到院(到店登记) → 咨询(面诊开单) → 成交(收款客户级) → 复购(≥2 次消费)
 * 数据源 /finance/funnel：finance 聚合 txn 客户级五级 + marketing 落地页留资（门店域后端收敛），
 * 与营销域漏斗（/m5-dashboard，仅营销活动）互补：本页看门店服务动线转化。
 * ============================================================ */
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import { useConversionFunnelStore } from '@/stores/conversionFunnel'

const router = useRouter()
const funnelStore = useConversionFunnelStore()

onMounted(() => funnelStore.seed())

const funnel = computed(() => funnelStore.funnel)
const consultantRank = computed(() => funnelStore.consultantRank)

const kpis = computed(() => {
  const f = funnel.value
  const get = (k: string) => f.find((s) => s.key === k)
  return [
    { label: '到院率', value: `${get('arrive')?.stepRate ?? 0}%`, tone: 'brand' as const, icon: 'customer' },
    { label: '咨询转化率', value: `${get('consult')?.stepRate ?? 0}%`, tone: 'orange' as const, icon: 'chat' },
    { label: '成交率（咨询→成交）', value: `${get('deal')?.stepRate ?? 0}%`, tone: 'teal' as const, icon: 'pos' },
    { label: '复购率', value: `${get('repurchase')?.stepRate ?? 0}%`, tone: 'warning' as const, icon: 'trend-up' },
  ]
})

// —— 流失环节诊断 ——
const leaks = computed(() => {
  const f = funnel.value
  const get = (k: string) => f.find((s) => s.key === k)!
  const out: { stage: string; lost: number; rate: number; tip: string; tone: 'warning' | 'danger' | 'info' }[] = []
  const pairs: [string, string, string, 'warning' | 'danger' | 'info'][] = [
    ['lead', 'arrive', '预约未到店：加强到院前一天提醒与确认，减少爽约。', 'warning'],
    ['arrive', 'consult', '到院未进入咨询：优化分诊与等待体验，避免客户流失。', 'danger'],
    ['consult', 'deal', '咨询未成交：复用 AI 话术/方案推荐，跟进异议处理。', 'danger'],
    ['deal', 'repurchase', '成交未复购：术后 SOP 随访 + 疗程复购提醒触达。', 'info'],
  ]
  pairs.forEach(([from, to, tip, tone]) => {
    const a = get(from)
    const b = get(to)
    const lost = a.value - b.value
    out.push({ stage: `${a.label}→${b.label}`, lost: Math.max(0, lost), rate: a.value > 0 ? Math.round((Math.max(0, lost) / a.value) * 100) : 0, tip, tone })
  })
  return out.sort((a, b) => b.lost - a.lost)
})
</script>

<template>
  <div class="fn">
    <div class="fn__kpis">
      <CKpi v-for="k in kpis" :key="k.label" :value="k.value" :label="k.label" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="fn__body">
      <!-- 漏斗主图 -->
      <CCard class="fn__funnel" padding="lg">
        <template #header>
          <h3 class="fn__card-title">全链路转化漏斗</h3>
          <CButton variant="secondary" size="sm" @click="router.push('/m5-dashboard')">
            <CIcon name="funnel" :size="14" />营销活动漏斗 →
          </CButton>
        </template>
        <div class="funnel">
          <div v-for="(s, i) in funnel" :key="s.key" class="fstage">
            <div class="fstage__bar-wrap">
              <div class="fstage__bar" :style="{ width: s.width + '%', background: s.tone }">
                <CIcon :name="s.icon as any" :size="16" class="fstage__ic" />
                <span class="fstage__label">{{ s.label }}</span>
                <span class="fstage__count">{{ s.value }}</span>
              </div>
            </div>
            <div class="fstage__rate">
              <template v-if="i === 0">
                <span class="fstage__overall">整体 100%</span>
              </template>
              <template v-else>
                <span class="fstage__step">{{ s.stepRate }}%</span>
                <span class="fstage__overall">累计 {{ s.overallRate }}%</span>
              </template>
            </div>
            <div class="fstage__sub">{{ s.sub }}</div>
          </div>
        </div>
      </CCard>

      <!-- 右侧：流失诊断 -->
      <CCard class="fn__leak" title="流失环节诊断" padding="lg">
        <div v-for="l in leaks" :key="l.stage" class="leak">
          <div class="leak__head">
            <CStatusPill :status="l.tone === 'danger' ? 'danger' : l.tone === 'warning' ? 'warning' : 'info'">
              {{ l.stage }}
            </CStatusPill>
            <span class="leak__lost">流失 {{ l.lost }} · {{ l.rate }}%</span>
          </div>
          <p class="leak__tip">{{ l.tip }}</p>
        </div>
      </CCard>
    </div>

    <!-- 咨询师转化排行 -->
    <CCard title="咨询师转化排行" padding="none">
      <div class="rank">
        <div class="rank__th">
          <span>咨询师</span><span>咨询数</span><span>成交数</span><span>转化率</span><span class="rank__amt">成交额</span>
        </div>
        <div v-for="(r, i) in consultantRank" :key="r.id" class="rank__row">
          <span class="rank__name"><i class="rank__no" :class="{ 'is-top': i === 0 }">{{ i + 1 }}</i>{{ r.name }}</span>
          <span class="rank__n">{{ r.consult }}</span>
          <span class="rank__n">{{ r.deal }}</span>
          <span class="rank__rate">
            <span class="rank__bar"><i :style="{ width: r.rate + '%' }" /></span>
            <b>{{ r.rate }}%</b>
          </span>
          <span class="rank__amt">¥{{ r.amount.toLocaleString() }}</span>
        </div>
        <div v-if="!consultantRank.length" class="fn__empty">暂无咨询转化数据</div>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.fn { display: flex; flex-direction: column; gap: var(--s-md); }
.fn__card-title { font-size: var(--t-md); line-height: var(--lh-md); font-weight: 700; }
.fn__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }

.fn__body { display: grid; grid-template-columns: 1.6fr 1fr; gap: var(--s-md); align-items: start; }

.funnel { display: flex; flex-direction: column; gap: var(--s-md); padding: var(--s-sm) 0; }
.fstage { display: grid; grid-template-columns: 1fr 150px 130px; align-items: center; gap: var(--s-md); }
.fstage__bar-wrap { min-width: 0; }
.fstage__bar { display: flex; align-items: center; gap: var(--s-sm); height: 46px; border-radius: var(--r-md) var(--r-pill) var(--r-pill) var(--r-md); color: #fff; padding: 0 var(--s-md); min-width: 120px; transition: width .4s; }
.fstage__ic { flex-shrink: 0; }
.fstage__label { font-size: var(--t-base); font-weight: 700; }
.fstage__count { margin-left: auto; font-size: var(--t-xl); font-weight: 800; font-variant-numeric: tabular-nums; }
.fstage__rate { display: flex; flex-direction: column; gap: 2px; }
.fstage__step { font-size: var(--t-lg); font-weight: 800; color: var(--c-brand); }
.fstage__overall { font-size: var(--t-xs); color: var(--c-text-3); }
.fstage__sub { font-size: var(--t-xs); color: var(--c-text-3); }

.leak { padding: var(--s-sm) 0; border-bottom: 1px dashed var(--c-border-light); }
.leak:last-child { border-bottom: none; }
.leak__head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); }
.leak__lost { font-size: var(--t-xs); color: var(--c-text-2); font-weight: 600; }
.leak__tip { font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6; margin: 6px 0 0; }

.rank { padding: var(--s-sm) var(--s-lg); }
.rank__th, .rank__row { display: grid; grid-template-columns: 1.4fr 0.7fr 0.7fr 1.4fr 1fr; align-items: center; gap: var(--s-md); padding: var(--s-sm) 0; }
.rank__th { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; border-bottom: 1px solid var(--c-border-light); }
.rank__row { border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); }
.rank__row:last-of-type { border-bottom: none; }
.rank__name { display: flex; align-items: center; gap: var(--s-sm); font-weight: 600; }
.rank__no { width: 22px; height: 22px; border-radius: 50%; background: var(--c-disabled-bg); color: var(--c-text-3); display: inline-flex; align-items: center; justify-content: center; font-size: var(--t-xs); font-style: normal; font-weight: 700; }
.rank__no.is-top { background: var(--c-warning-fg, #fa8c16); color: #fff; }
.rank__n { text-align: center; font-variant-numeric: tabular-nums; }
.rank__rate { display: flex; align-items: center; gap: var(--s-sm); }
.rank__bar { flex: 1; height: 8px; background: var(--c-chart-track); border-radius: var(--r-capsule); overflow: hidden; }
.rank__bar i { display: block; height: 100%; background: linear-gradient(90deg, var(--c-brand), var(--c-purple)); border-radius: var(--r-capsule); }
.rank__rate b { width: 42px; text-align: right; color: var(--c-brand); font-variant-numeric: tabular-nums; }
.rank__amt { text-align: right; font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.fn__empty { text-align: center; color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-lg); }

@media (max-width: 1100px) {
  .fn__body { grid-template-columns: 1fr; }
  .fn__kpis { grid-template-columns: repeat(2, 1fr); }
  .fstage { grid-template-columns: 1fr; gap: var(--s-xs); }
  .fstage__rate { flex-direction: row; gap: var(--s-md); }
}
</style>
