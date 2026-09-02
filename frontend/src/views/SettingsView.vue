<script setup lang="ts">
/* ============================================================
 * 系统设置（/m1-settings） —— SCREEN-M1-16
 * IA（设计稿 Tablet 版为完整真值，Desktop 为宽壳）：
 *   顶部：标题 + 保存配置
 *   KPI：设置项数 / 待更新 / 权限组 / 最近更新
 *   左：设置分组导航（基础/通知/安全/双签/候诊/转介绍合规/营业分诊）
 *   右：当前分组编辑面板
 *   底：变更操作日志（append-only，保存时写入）
 * 业务模块通过 settings.system/store 读取"已生效"值；本页编辑 draft，
 * 点"保存配置"才提交（settings:edit），草稿不影响线上业务。
 * ============================================================ */
import { computed, ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { useAuthStore } from '@/stores/auth'
import CCard from '@/components/CCard.vue'
import CInput from '@/components/CInput.vue'
import CCheckbox from '@/components/CCheckbox.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CButton from '@/components/CButton.vue'
import CSegmented from '@/components/CSegmented.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import type { EnvType, SettingsChangeLog } from '@/config/settings'

const settings = useSettingsStore()
const auth = useAuthStore()
const canEdit = computed(() => auth.can('settings:edit'))

const sys = computed(() => settings.draft.system)
const st = computed(() => settings.draft.store)

const ENV_OPTIONS = [
  { label: '生产', value: 'PROD' },
  { label: '预发布', value: 'STAGING' },
  { label: '开发', value: 'DEV' },
]
const ENV_NOTE: Record<EnvType, string> = {
  PROD: '当前为生产环境，变更将影响全部门店',
  STAGING: '预发布环境，仅用于验证，不影响线上',
  DEV: '开发环境，数据为测试数据',
}
const ENV_RISK: Record<EnvType, SettingsChangeLog['risk']> = {
  PROD: 'HIGH',
  STAGING: 'MEDIUM',
  DEV: 'LOW',
}

function num(v: string) {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

// ---------- 分组定义 ----------
interface Group {
  key: string
  label: string
  icon: string
  count: number
  scope: '集团' | '门店'
  desc: string
}
const groups = computed<Group[]>(() => [
  { key: 'brand', label: '基础设置', icon: 'store', count: 5, scope: '集团', desc: '品牌、版本与运行环境' },
  { key: 'notify', label: '通知配置', icon: 'alert', count: 4, scope: '集团', desc: '审批、退款、告警与周报通知' },
  { key: 'security', label: '安全设置', icon: 'shield', count: 4, scope: '集团', desc: '会话、双因素与敏感操作' },
  { key: 'sign', label: '双签与金额阈值', icon: 'finance', count: 4, scope: '集团', desc: '签署层级与退卡扣倒扣' },
  { key: 'queue', label: '候诊与号源', icon: 'calendar', count: 4, scope: '集团', desc: '超时、释放与号源预生成' },
  { key: 'compliance', label: '转介绍与合规', icon: 'profile', count: 5, scope: '集团', desc: '归属有效期、脱敏与数据保留' },
  { key: 'store', label: '营业与分诊', icon: 'customer', count: 6, scope: '门店', desc: '营业时间、迟到与跨店改派' },
])
const active = ref('brand')
const activeGroup = computed(() => groups.value.find((g) => g.key === active.value)!)
const totalItems = computed(() => groups.value.reduce((s, g) => s + g.count, 0))

// ---------- KPI：待更新 = draft 与 committed 的差异字段数 ----------
function diffCount(a: unknown, b: unknown, prefix = ''): string[] {
  const out: string[] = []
  if (a && b && typeof a === 'object' && typeof b === 'object') {
    const keys = new Set([...Object.keys(a as object), ...Object.keys(b as object)])
    for (const k of keys) out.push(...diffCount((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k], prefix ? `${prefix}.${k}` : k))
  } else if (a !== b) {
    out.push(prefix)
  }
  return out
}
const pendingPaths = computed(() => diffCount(settings.draft, settings.committed))
const pendingCount = computed(() => pendingPaths.value.length)
const roleCount = computed(() => 8)
const lastUpdate = computed(() => settings.changeLog[0]?.time ?? '—')

// ---------- 保存：收集本次变更写日志 ----------
function labelOf(path: string): string {
  const map: Record<string, string> = {
    'system.brand.name': '品牌名称',
    'system.brand.color': '品牌色',
    'system.brand.versionLabel': '系统版本号',
    'system.env': '运行环境',
    'system.notifications.approvalInbox': '审批站内信',
    'system.notifications.refundSms': '退款短信',
    'system.notifications.alertWecom': '告警企微',
    'system.notifications.weeklyEmail': '周报邮件',
    'system.security.sessionTimeoutMin': '会话超时',
    'system.security.require2FA': '双因素认证',
    'system.security.sensitiveConfirm': '敏感二次确认',
    'system.security.passwordMinLen': '密码最短长度',
    'system.dualSign.l1': 'L1 单签阈值',
    'system.dualSign.l2': 'L2 双签阈值',
    'system.dualSign.l3': 'L3 三签阈值',
    'system.dualSign.cardClawbackRate': '退卡扣倒扣比例',
    'system.queue.waitingTimeoutMin': '候诊超时',
    'system.queue.autoReleaseSlot': '自动释放号源',
    'system.queue.releaseGraceMin': '释放宽限',
    'system.queue.slotPreloadDays': '号源预生成天数',
    'system.referral.ownershipValidDays': '转介绍有效期',
    'system.referral.requireConfirm': '转介绍需确认',
    'system.compliance.dataRetentionYears': '数据保留年限',
    'system.compliance.phoneMaskByDefault': '手机号默认脱敏',
    'system.compliance.impersonateReadOnly': 'impersonate 只读',
    'store.businessHours.open': '营业开始时间',
    'store.businessHours.close': '营业结束时间',
    'store.arrivalLateMin': '到店迟到标记',
    'store.consultFollowupDays': '咨询跟进间隔',
    'store.consultantCommissionRate': '咨询师提成比例',
    'store.allowCrossStoreTriage': '允许跨店分诊',
  }
  return map[path] || path
}
function groupOf(path: string): string {
  if (path.startsWith('system.brand')) return '基础设置'
  if (path.startsWith('system.notifications')) return '通知配置'
  if (path.startsWith('system.security')) return '安全设置'
  if (path.startsWith('system.dualSign')) return '双签与金额阈值'
  if (path.startsWith('system.queue')) return '候诊与号源'
  if (path.startsWith('system.referral') || path.startsWith('system.compliance')) return '转介绍与合规'
  return '营业与分诊'
}
function riskOf(path: string): SettingsChangeLog['risk'] {
  if (path.startsWith('system.dualSign') || path === 'system.env' || path.startsWith('system.security')) return 'HIGH'
  if (path.startsWith('system.compliance')) return 'MEDIUM'
  return 'LOW'
}
function display(v: unknown): string {
  if (typeof v === 'boolean') return v ? '开启' : '关闭'
  if (v === undefined || v === null) return '—'
  return String(v)
}

function doSave() {
  if (!canEdit.value) return
  const oldCommitted = settings.committed
  const entries: Omit<SettingsChangeLog, 'id' | 'time' | 'operator'>[] = pendingPaths.value.map((p) => {
    const oldV = p.split('.').reduce((o: unknown, k) => (o as Record<string, unknown>)?.[k], oldCommitted)
    const newV = p.split('.').reduce((o: unknown, k) => (o as Record<string, unknown>)?.[k], settings.draft)
    return {
      group: groupOf(p),
      field: labelOf(p),
      change: `${display(oldV)} → ${display(newV)}`,
      risk: riskOf(p),
    }
  })
  // 环境切换单独记为高风险并带说明
  if (settings.draft.system.env !== oldCommitted.system.env) {
    const e = entries.find((x) => x.field === '运行环境')
    if (e) e.risk = ENV_RISK[settings.draft.system.env]
  }
  settings.save(entries)
}

function discard() {
  settings.discardDraft()
}

const previewAmount = ref('8000')
const tierPreview = computed(() => {
  const n = Number(previewAmount.value) || 0
  const t = settings.committed.system.dualSign
  if (n >= t.l3) return { tier: 'L3', text: '三签 / 更高审批', tone: 'danger' as const }
  if (n >= t.l2) return { tier: 'L2', text: '双签', tone: 'warning' as const }
  if (n >= t.l1) return { tier: 'L1', text: '单签', tone: 'info' as const }
  return { tier: 'L1', text: '基础签署', tone: 'info' as const }
})

function logPillClass(risk: SettingsChangeLog['risk']) {
  return { 'log__risk--high': risk === 'HIGH', 'log__risk--med': risk === 'MEDIUM', 'log__risk--low': risk === 'LOW' }
}
</script>

<template>
  <div class="set">
    <!-- 顶部操作条（全局顶栏已含页面标题，此处不重复） -->
    <div class="set__head">
      <p class="set__desc">业务参数在此调整，无需改代码。集团级对全集团生效，门店级仅作用于当前门店。</p>
      <div class="set__ops">
        <CStatusPill v-if="!canEdit" status="default">只读（无 settings:edit 权限）</CStatusPill>
        <CStatusPill v-else-if="settings.dirty" status="warning">{{ pendingCount }} 项待保存</CStatusPill>
        <CStatusPill v-else status="success">配置已是最新</CStatusPill>
        <CButton variant="ghost" size="sm" :disabled="!settings.dirty" @click="discard">放弃更改</CButton>
        <CButton variant="primary" size="sm" :disabled="!canEdit || !settings.dirty" @click="doSave">保存配置</CButton>
      </div>
    </div>

    <!-- KPI 概览 -->
    <div class="set__kpis">
      <CKpi :value="String(totalItems)" label="设置项" icon="settings" />
      <CKpi :value="String(pendingCount)" label="待更新" :trend="pendingCount ? `${pendingCount} 项未保存` : ''" :trend-good="false" icon="settings" />
      <CKpi :value="String(roleCount)" label="权限组" icon="org" />
      <CKpi :value="lastUpdate" label="最近更新" icon="settings" />
    </div>

    <div class="set__main">
      <!-- 左：分组导航 -->
      <CCard class="set__nav" padding="none">
        <div class="nav">
          <button
            v-for="g in groups"
            :key="g.key"
            class="nav__item"
            :class="{ 'is-active': active === g.key }"
            @click="active = g.key"
          >
            <span class="nav__ico"><CIcon :name="(g.icon as any)" :size="16" /></span>
            <span class="nav__text">
              <span class="nav__label">{{ g.label }}</span>
              <span class="nav__desc">{{ g.desc }}</span>
            </span>
            <span class="nav__meta">
              <span class="nav__scope" :class="`nav__scope--${g.scope === '集团' ? 'grp' : 'store'}`">{{ g.scope }}</span>
              <span class="nav__cnt">{{ g.count }}</span>
            </span>
          </button>
        </div>
      </CCard>

      <!-- 右：编辑面板 -->
      <CCard class="set__panel">
        <template #header>
          <h3 class="panel__title">{{ activeGroup.label }} · 编辑</h3>
          <span class="panel__scope">{{ activeGroup.scope }}级</span>
        </template>
        <div class="panel">
          <!-- 基础设置 -->
          <template v-if="active === 'brand'">
            <div class="row">
              <span class="row__l">系统版本号</span>
              <span class="row__r"><CInput :model-value="sys.brand.versionLabel" :disabled="!canEdit" @update:model-value="(v) => (sys.brand.versionLabel = v)" /></span>
            </div>
            <div class="row">
              <span class="row__l">品牌名称</span>
              <span class="row__r"><CInput :model-value="sys.brand.name" :disabled="!canEdit" @update:model-value="(v) => (sys.brand.name = v)" /></span>
            </div>
            <div class="row">
              <span class="row__l">营业时间</span>
              <span class="row__r row__r--time">
                <CInput :model-value="st.businessHours.open" :disabled="!canEdit" @update:model-value="(v) => (st.businessHours.open = v)" />
                <span class="sep">—</span>
                <CInput :model-value="st.businessHours.close" :disabled="!canEdit" @update:model-value="(v) => (st.businessHours.close = v)" />
              </span>
            </div>
            <div class="row">
              <span class="row__l">品牌色</span>
              <span class="row__r row__r--color">
                <input type="color" class="colorpick" :value="sys.brand.color" :disabled="!canEdit" @input="(e) => (sys.brand.color = (e.target as HTMLInputElement).value)" />
                <code class="hex">{{ sys.brand.color }}</code>
              </span>
            </div>
            <div class="row">
              <span class="row__l">运行环境<span class="row__hint">切换需 T3-01 审批</span></span>
              <span class="row__r">
                <CSegmented :model-value="sys.env" :options="ENV_OPTIONS" :disabled="!canEdit" @update:model-value="(v) => (sys.env = v as EnvType)" />
              </span>
            </div>
            <div class="row__alert" :class="{ 'row__alert--danger': sys.env === 'PROD' }">
              <CIcon :name="('alert' as any)" :size="14" />
              <span>{{ ENV_NOTE[sys.env] }}</span>
            </div>
          </template>

          <!-- 通知配置 -->
          <template v-else-if="active === 'notify'">
            <div class="row row--check" v-for="(item, key) in {
              approvalInbox: '审批待办站内信',
              refundSms: '退款 / 退卡结果短信通知客户',
              alertWecom: '巡店 / 合规告警推送企业微信',
              weeklyEmail: '经营周报邮件订阅',
            }" :key="key">
              <span class="row__l">{{ item }}</span>
              <span class="row__r">
                <CCheckbox :model-value="(sys.notifications as Record<string, boolean>)[key as string]" :disabled="!canEdit"
                  @update:model-value="(v) => ((sys.notifications as Record<string, unknown>)[key as string] = v)">开启</CCheckbox>
              </span>
            </div>
          </template>

          <!-- 安全设置 -->
          <template v-else-if="active === 'security'">
            <div class="row">
              <span class="row__l">登录会话超时（分钟）</span>
              <span class="row__r"><CInput type="number" :model-value="String(sys.security.sessionTimeoutMin)" :disabled="!canEdit" @update:model-value="(v) => (sys.security.sessionTimeoutMin = num(v))" /></span>
            </div>
            <div class="row">
              <span class="row__l">密码最短长度</span>
              <span class="row__r"><CInput type="number" :model-value="String(sys.security.passwordMinLen)" :disabled="!canEdit" @update:model-value="(v) => (sys.security.passwordMinLen = num(v))" /></span>
            </div>
            <div class="row row--check">
              <span class="row__l">强制双因素认证（2FA）</span>
              <span class="row__r"><CCheckbox :model-value="sys.security.require2FA" :disabled="!canEdit" @update:model-value="(v) => (sys.security.require2FA = v)">已启用</CCheckbox></span>
            </div>
            <div class="row row--check">
              <span class="row__l">敏感操作需二次确认（退款 / 退卡 / 环境切换）</span>
              <span class="row__r"><CCheckbox :model-value="sys.security.sensitiveConfirm" :disabled="!canEdit" @update:model-value="(v) => (sys.security.sensitiveConfirm = v)">已启用</CCheckbox></span>
            </div>
          </template>

          <!-- 双签与金额阈值 -->
          <template v-else-if="active === 'sign'">
            <div class="row" v-for="(f, key) in [
              { key: 'l1', label: 'L1 单签阈值（元）' },
              { key: 'l2', label: 'L2 双签阈值（元）' },
              { key: 'l3', label: 'L3 三签阈值（元）' },
            ]" :key="key">
              <span class="row__l">{{ f.label }}</span>
              <span class="row__r"><CInput type="number" :model-value="String((sys.dualSign as Record<string, number>)[f.key])" :disabled="!canEdit"
                @update:model-value="(v) => ((sys.dualSign as Record<string, unknown>)[f.key] = num(v))" /></span>
            </div>
            <div class="row">
              <span class="row__l">退卡扣倒扣比例（0-1）</span>
              <span class="row__r"><CInput type="number" :model-value="String(sys.dualSign.cardClawbackRate)" :disabled="!canEdit" @update:model-value="(v) => (sys.dualSign.cardClawbackRate = num(v))" /></span>
            </div>
            <div class="tier">
              <span class="tier__lbl">签署层级预览：单笔</span>
              <CInput type="number" v-model="previewAmount" width="120px" />
              <span class="tier__lbl">元 →</span>
              <CStatusPill :status="tierPreview.tone">{{ tierPreview.tier }} · {{ tierPreview.text }}</CStatusPill>
              <span class="tier__hint">（按已生效配置计算）</span>
            </div>
          </template>

          <!-- 候诊与号源 -->
          <template v-else-if="active === 'queue'">
            <div class="row">
              <span class="row__l">候诊超时（分钟）</span>
              <span class="row__r"><CInput type="number" :model-value="String(sys.queue.waitingTimeoutMin)" :disabled="!canEdit" @update:model-value="(v) => (sys.queue.waitingTimeoutMin = num(v))" /></span>
            </div>
            <div class="row">
              <span class="row__l">释放前宽限（分钟）</span>
              <span class="row__r"><CInput type="number" :model-value="String(sys.queue.releaseGraceMin)" :disabled="!canEdit" @update:model-value="(v) => (sys.queue.releaseGraceMin = num(v))" /></span>
            </div>
            <div class="row">
              <span class="row__l">号源提前生成（天）</span>
              <span class="row__r"><CInput type="number" :model-value="String(sys.queue.slotPreloadDays)" :disabled="!canEdit" @update:model-value="(v) => (sys.queue.slotPreloadDays = num(v))" /></span>
            </div>
            <div class="row row--check">
              <span class="row__l">超时自动释放号源</span>
              <span class="row__r"><CCheckbox :model-value="sys.queue.autoReleaseSlot" :disabled="!canEdit" @update:model-value="(v) => (sys.queue.autoReleaseSlot = v)">已启用</CCheckbox></span>
            </div>
          </template>

          <!-- 转介绍与合规 -->
          <template v-else-if="active === 'compliance'">
            <div class="row">
              <span class="row__l">转介绍归属有效期（天）</span>
              <span class="row__r"><CInput type="number" :model-value="String(sys.referral.ownershipValidDays)" :disabled="!canEdit" @update:model-value="(v) => (sys.referral.ownershipValidDays = num(v))" /></span>
            </div>
            <div class="row">
              <span class="row__l">数据保留年限</span>
              <span class="row__r"><CInput type="number" :model-value="String(sys.compliance.dataRetentionYears)" :disabled="!canEdit" @update:model-value="(v) => (sys.compliance.dataRetentionYears = num(v))" /></span>
            </div>
            <div class="row row--check">
              <span class="row__l">转介绍需被介绍客户确认才生效</span>
              <span class="row__r"><CCheckbox :model-value="sys.referral.requireConfirm" :disabled="!canEdit" @update:model-value="(v) => (sys.referral.requireConfirm = v)">已启用</CCheckbox></span>
            </div>
            <div class="row row--check">
              <span class="row__l">手机号默认脱敏</span>
              <span class="row__r"><CCheckbox :model-value="sys.compliance.phoneMaskByDefault" :disabled="!canEdit" @update:model-value="(v) => (sys.compliance.phoneMaskByDefault = v)">已启用</CCheckbox></span>
            </div>
            <div class="row row--check">
              <span class="row__l">超管 impersonate 默认只读</span>
              <span class="row__r"><CCheckbox :model-value="sys.compliance.impersonateReadOnly" :disabled="!canEdit" @update:model-value="(v) => (sys.compliance.impersonateReadOnly = v)">已启用</CCheckbox></span>
            </div>
          </template>

          <!-- 营业与分诊 -->
          <template v-else-if="active === 'store'">
            <div class="row">
              <span class="row__l">营业时间 起</span>
              <span class="row__r"><CInput :model-value="st.businessHours.open" :disabled="!canEdit" @update:model-value="(v) => (st.businessHours.open = v)" /></span>
            </div>
            <div class="row">
              <span class="row__l">营业时间 止</span>
              <span class="row__r"><CInput :model-value="st.businessHours.close" :disabled="!canEdit" @update:model-value="(v) => (st.businessHours.close = v)" /></span>
            </div>
            <div class="row">
              <span class="row__l">到店迟到标记（分钟）</span>
              <span class="row__r"><CInput type="number" :model-value="String(st.arrivalLateMin)" :disabled="!canEdit" @update:model-value="(v) => (st.arrivalLateMin = num(v))" /></span>
            </div>
            <div class="row">
              <span class="row__l">咨询后跟进间隔（天）</span>
              <span class="row__r"><CInput type="number" :model-value="String(st.consultFollowupDays)" :disabled="!canEdit" @update:model-value="(v) => (st.consultFollowupDays = num(v))" /></span>
            </div>
            <div class="row">
              <span class="row__l">咨询师提成比例（0-1）</span>
              <span class="row__r"><CInput type="number" :model-value="String(st.consultantCommissionRate)" :disabled="!canEdit" @update:model-value="(v) => (st.consultantCommissionRate = num(v))" /></span>
            </div>
            <div class="row row--check">
              <span class="row__l">允许分诊跨门店改派</span>
              <span class="row__r"><CCheckbox :model-value="st.allowCrossStoreTriage" :disabled="!canEdit" @update:model-value="(v) => (st.allowCrossStoreTriage = v)">已启用</CCheckbox></span>
            </div>
          </template>
        </div>
      </CCard>
    </div>

    <!-- 操作日志 -->
    <CCard title="系统设置 · 操作" subtitle="保存自动写入审计日志（append-only）">
      <div class="log">
        <div class="log__row" v-for="l in settings.changeLog" :key="l.id">
          <span class="log__time">{{ l.time }}</span>
          <span class="log__op">{{ l.operator }}</span>
          <span class="log__grp">{{ l.group }} · {{ l.field }}</span>
          <span class="log__chg">{{ l.change }}</span>
          <span class="log__risk" :class="logPillClass(l.risk)">{{ l.risk === 'HIGH' ? '高风险' : l.risk === 'MEDIUM' ? '中风险' : '常规' }}</span>
        </div>
        <div v-if="!settings.changeLog.length" class="log__empty">暂无变更记录</div>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.set { display: flex; flex-direction: column; gap: var(--s-lg); }

.set__head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); }
.set__desc { font-size: var(--t-sm); color: var(--c-text-3); margin: 0; max-width: 620px; line-height: var(--lh-md); }
.set__ops { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; }

.set__kpis { display: flex; gap: var(--s-md); flex-wrap: wrap; }
.set__kpis :deep(.ckpi) { flex: 1 1 0; min-width: 168px; }

.set__main { display: grid; grid-template-columns: 280px 1fr; gap: var(--s-lg); align-items: start; }

/* 左导航 */
.set__nav { position: sticky; top: var(--s-md); }
.nav { display: flex; flex-direction: column; padding: var(--s-xs); }
.nav__item {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md);
  border: none; background: none; cursor: pointer;
  border-radius: var(--r-md); text-align: left;
  color: var(--c-text-2); transition: background .15s;
}
.nav__item:hover { background: var(--c-surface-muted); }
.nav__item.is-active { background: var(--c-brand-soft); color: var(--c-brand); }
.nav__ico { display: inline-flex; flex-shrink: 0; }
.nav__text { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.nav__label { font-size: var(--t-sm); font-weight: 600; }
.nav__desc { font-size: var(--t-xs); color: var(--c-text-3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.nav__item.is-active .nav__desc { color: var(--c-brand); opacity: .8; }
.nav__meta { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; }
.nav__scope { font-size: 10px; padding: 1px 6px; border-radius: var(--r-sm); line-height: 1.4; }
.nav__scope--grp { background: var(--c-brand-soft); color: var(--c-brand); }
.nav__scope--store { background: var(--c-surface-muted); color: var(--c-text-3); }
.nav__cnt { font-size: var(--t-xs); color: var(--c-text-3); }
.nav__item.is-active .nav__cnt { color: var(--c-brand); }

/* 面板 */
.set__panel { min-width: 0; }
.panel__scope { font-size: var(--t-xs); color: var(--c-brand); background: var(--c-brand-soft); padding: 2px var(--s-sm); border-radius: var(--r-sm); margin-left: auto; }
.panel__title { font-size: var(--t-md); font-weight: 700; }
.panel { display: flex; flex-direction: column; }
.row {
  display: grid; grid-template-columns: 260px 1fr; align-items: center; gap: var(--s-lg);
  padding: var(--s-md) 0; border-bottom: 1px solid var(--c-border);
}
.row:last-of-type { border-bottom: none; }
.row__l { font-size: var(--t-sm); color: var(--c-text-2); display: flex; flex-direction: column; gap: 2px; }
.row__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.row__r { display: flex; align-items: center; gap: var(--s-sm); min-width: 0; }
.row__r :deep(.c-input) { max-width: 320px; }
.row--check { grid-template-columns: 1fr auto; }
.row__r--time { gap: var(--s-xs); }
.row__r--time .sep { color: var(--c-text-3); }
.row__r--color { gap: var(--s-sm); }
.colorpick {
  width: var(--s-xl); height: var(--s-xl); padding: 0; border: 1px solid var(--c-border);
  border-radius: var(--r-sm); background: none; cursor: pointer; flex-shrink: 0;
}
.colorpick::-webkit-color-swatch-wrapper { padding: var(--s-xxs); }
.colorpick::-webkit-color-swatch { border: none; border-radius: 3px; }
.hex { font-size: var(--t-sm); color: var(--c-text-2); font-family: monospace; }

.row__alert {
  display: flex; align-items: center; gap: var(--s-xs);
  margin-top: var(--s-md); padding: var(--s-sm) var(--s-md);
  background: var(--c-brand-soft); color: var(--c-brand);
  border-radius: var(--r-md); font-size: var(--t-sm);
}
.row__alert--danger { background: rgba(239, 68, 68, .1); color: var(--c-danger-fg); }

.tier {
  display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap;
  margin-top: var(--s-md); padding-top: var(--s-md);
  border-top: 1px dashed var(--c-border);
}
.tier__lbl { font-size: var(--t-sm); color: var(--c-text-2); }
.tier__hint { font-size: var(--t-xs); color: var(--c-text-3); }

/* 日志 */
.log { display: flex; flex-direction: column; }
.log__row {
  display: grid; grid-template-columns: 132px 96px 1.2fr 1.4fr auto;
  align-items: center; gap: var(--s-md);
  padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border);
  font-size: var(--t-sm);
}
.log__row:last-child { border-bottom: none; }
.log__time { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.log__op { font-weight: 600; color: var(--c-text); }
.log__grp { color: var(--c-text-2); }
.log__chg { color: var(--c-text-3); font-size: var(--t-xs); }
.log__risk { font-size: var(--t-xs); padding: 2px var(--s-sm); border-radius: var(--r-sm); white-space: nowrap; }
.log__risk--low { background: var(--c-surface-muted); color: var(--c-text-3); }
.log__risk--med { background: rgba(245, 158, 11, .12); color: var(--c-warning-fg); }
.log__risk--high { background: rgba(239, 68, 68, .12); color: var(--c-danger-fg); }
.log__empty { padding: var(--s-lg) 0; text-align: center; color: var(--c-text-3); font-size: var(--t-sm); }

@media (max-width: 1024px) {
  .set__main { grid-template-columns: 1fr; }
  .set__nav { position: static; }
  .nav { flex-direction: row; overflow-x: auto; }
  .nav__item { flex-shrink: 0; }
  .nav__desc, .nav__meta { display: none; }
  .row { grid-template-columns: 1fr; gap: var(--s-xs); align-items: flex-start; }
  .row--check { grid-template-columns: 1fr auto; }
  .log__row { grid-template-columns: 1fr; gap: 2px; padding: var(--s-sm) 0; }
}
</style>
