<script setup lang="ts">
/* ============================================================
 * 卡项疗程（/card-course）
 * 资产双聚合：CashAsset(余额/储值) + TimesAsset(次数/疗程)。
 * 左列有资产的客户；右列客户资产账户、充值/购卡/扣次、资产流水。
 * 权限：course:view 查看；写操作 course:edit。
 * ============================================================ */
import { computed, ref, onMounted } from 'vue'
import { useAssetStore, type AssetTxnKind } from '@/stores/asset'
import { useCustomerStore } from '@/stores/customer'
import { useAuthStore } from '@/stores/auth'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import CIcon from '@/components/CIcon.vue'
import type { Customer } from '@/types/domain'

const asset = useAssetStore()
const customer = useCustomerStore()
const auth = useAuthStore()

onMounted(() => asset.seed())

const keyword = ref('')
const selectedId = ref<string>('')

const custList = computed(() => {
  const ids = new Set(asset.customersWithAssets.map((c) => c!.id))
  if (keyword.value.trim()) {
    return customer.search(keyword.value).filter((c) => ids.has(c.id))
  }
  return customer.customers.filter((c) => ids.has(c.id))
})

function select(c: Customer) {
  selectedId.value = c.id
}
const selected = computed(() => (selectedId.value ? customer.get(selectedId.value) : undefined))
const acct = computed(() => (selectedId.value ? asset.account(selectedId.value) : null))

const kpi = computed(() => {
  let balance = 0
  let courses = 0
  let finished = 0
  asset.cashAssets.forEach((a) => { if (a.status === 'ACTIVE') balance += a.balance + a.giftBalance })
  asset.timesAssets.forEach((a) => {
    if (a.status === 'ACTIVE') courses += 1
    if (a.status === 'FINISHED') finished += 1
  })
  return { customers: asset.customersWithAssets.length, balance, courses, finished }
})

// ---- 操作弹层 ----
type Panel = null | 'recharge' | 'purchase' | 'consume'
const panel = ref<Panel>(null)
const amount = ref('')
const gift = ref('')
const buyName = ref('')
const buyTimes = ref('')
const consumeAssetId = ref('')
const consumeTimes = ref('1')

function openRecharge() { panel.value = 'recharge'; amount.value = ''; gift.value = '' }
function openPurchase() { panel.value = 'purchase'; buyName.value = ''; buyTimes.value = '' }
function openConsume(assetId: string) { panel.value = 'consume'; consumeAssetId.value = assetId; consumeTimes.value = '1' }
function close() { panel.value = null }

function doRecharge() {
  if (!selected.value) return
  const amt = Number(amount.value)
  const gft = Number(gift.value) || 0
  if (amt <= 0) return
  asset.recharge({ customerId: selected.value.id, amount: amt, gift: gft })
  close()
}
function doPurchase() {
  if (!selected.value) return
  const t = Number(buyTimes.value)
  if (!buyName.value.trim() || t <= 0) return
  asset.purchaseTimes({
    customerId: selected.value.id,
    itemSku: 'SKU-' + Date.now().toString(36).toUpperCase(),
    itemName: buyName.value.trim(),
    totalTimes: t,
  })
  close()
}
function doConsume() {
  const t = Number(consumeTimes.value)
  if (t <= 0) return
  asset.consumeTimes(consumeAssetId.value, t, '页面手动扣次')
  close()
}

// ---- 展示辅助 ----
const TXN_LABEL: Record<AssetTxnKind, string> = {
  RECHARGE: '充值', PURCHASE: '购卡', CONSUME: '核销扣减',
  REFUND: '退款回退', FREEZE: '冻结', UNFREEZE: '解冻', ADJUST: '调整',
}
function fmtTime(iso: string) {
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function txnColor(kind: AssetTxnKind) {
  if (kind === 'RECHARGE' || kind === 'PURCHASE' || kind === 'REFUND' || kind === 'UNFREEZE') return 'var(--c-success-fg)'
  if (kind === 'CONSUME' || kind === 'FREEZE') return 'var(--c-danger-fg)'
  return 'var(--c-text-2)'
}
function txnSign(kind: AssetTxnKind) {
  return ['RECHARGE', 'PURCHASE', 'REFUND', 'UNFREEZE'].includes(kind) ? '+' : ''
}

const canEdit = computed(() => auth.can('course:edit'))

const recentTxns = computed(() => {
  if (!acct.value) return []
  const ids = [
    ...acct.value.cashAssets.map((a) => a.id),
    ...acct.value.timesAssets.map((a) => a.id),
  ]
  return asset.txns.filter((t) => ids.includes(t.assetId)).slice(0, 12)
})
</script>

<template>
  <div class="cc-page">
    <div class="cc-kpis">
      <CKpi label="持卡客户" :value="String(kpi.customers)" tone="brand" icon="customer" />
      <CKpi label="储值总额" :value="'¥' + kpi.balance.toLocaleString()" tone="teal" icon="finance" />
      <CKpi label="有效疗程" :value="String(kpi.courses)" tone="orange" icon="card" />
      <CKpi label="已用完疗程" :value="String(kpi.finished)" tone="text" icon="card" />
    </div>

    <div class="cc-body">
      <!-- 左列：客户 -->
      <CCard title="持卡客户" class="cc-list">
        <div class="cc-search">
          <CInput v-model="keyword" placeholder="搜姓名 / 手机号" clearable />
        </div>
        <div class="cc-clist">
          <button
            v-for="c in custList"
            :key="c.id"
            class="cc-cust"
            :class="{ 'is-on': selectedId === c.id }"
            @click="select(c)"
          >
            <div class="cc-cust__avatar">{{ c.avatarLetter }}</div>
            <div class="cc-cust__main">
              <div class="cc-cust__name">{{ c.name }}</div>
              <div class="cc-cust__sub">{{ c.phoneMask }}</div>
            </div>
            <div class="cc-cust__bal">
              <span v-if="asset.account(c.id).totalBalance" class="cc-cust__amt">
                ¥{{ asset.account(c.id).totalBalance.toLocaleString() }}
              </span>
              <span v-if="asset.account(c.id).totalRemainingTimes" class="cc-cust__times">
                {{ asset.account(c.id).totalRemainingTimes }}次
              </span>
            </div>
          </button>
          <div v-if="!custList.length" class="cc-empty">暂无持卡客户</div>
        </div>
      </CCard>

      <!-- 右列：资产账户 -->
      <div class="cc-detail">
        <template v-if="selected && acct">
          <CCard class="cc-acct-head">
            <div class="cc-acct-id">
              <div class="cc-acct__avatar">{{ selected.avatarLetter }}</div>
              <div>
                <div class="cc-acct__name">{{ selected.name }}</div>
                <div class="cc-acct__sub">{{ selected.phoneMask }} · 等级 {{ selected.level }}</div>
              </div>
            </div>
            <div class="cc-acct-sum">
              <div class="cc-acct-sum__item">
                <span class="cc-acct-sum__label">账户余额</span>
                <span class="cc-acct-sum__val cc-acct-sum__val--teal">¥{{ acct.totalBalance.toLocaleString() }}</span>
              </div>
              <div class="cc-acct-sum__item">
                <span class="cc-acct-sum__label">剩余次数</span>
                <span class="cc-acct-sum__val cc-acct-sum__val--orange">{{ acct.totalRemainingTimes }} 次</span>
              </div>
            </div>
          </CCard>

          <div class="cc-actions">
            <CButton variant="primary" size="sm" v-perm.disable="'course:edit'" @click="openRecharge">
              <CIcon name="pos" :size="14" /> 充值
            </CButton>
            <CButton variant="secondary" size="sm" v-perm.disable="'course:edit'" @click="openPurchase">
              <CIcon name="card" :size="14" /> 购疗程卡
            </CButton>
          </div>

          <!-- 储值卡 -->
          <CCard v-if="acct.cashAssets.length" title="储值卡 / 余额" class="cc-asset-card">
            <div v-for="a in acct.cashAssets" :key="a.id" class="asset">
              <div class="asset__top">
                <div class="asset__title">
                  <CIcon name="pos" :size="16" class="asset__icon asset__icon--teal" />
                  储值账户
                  <CStatusPill :status="a.status === 'ACTIVE' ? 'success' : 'default'">
                    {{ a.status === 'ACTIVE' ? '正常' : a.status === 'FROZEN' ? '冻结' : a.status }}
                  </CStatusPill>
                </div>
                <div class="asset__balance">¥{{ (a.balance + a.giftBalance).toLocaleString() }}</div>
              </div>
              <div class="asset__sub">
                本金 ¥{{ a.balance.toLocaleString() }}<span v-if="a.giftBalance"> · 赠送 ¥{{ a.giftBalance.toLocaleString() }}</span>
              </div>
            </div>
          </CCard>

          <!-- 疗程卡 -->
          <CCard v-if="acct.timesAssets.length" title="疗程 / 次卡" class="cc-asset-card">
            <div v-for="a in acct.timesAssets" :key="a.id" class="asset">
              <div class="asset__top">
                <div class="asset__title">
                  <CIcon name="card" :size="16" class="asset__icon asset__icon--orange" />
                  {{ a.itemName }}
                  <CStatusPill :status="a.status === 'ACTIVE' ? 'primary' : a.status === 'FINISHED' ? 'default' : 'warning'">
                    {{ a.status === 'ACTIVE' ? '可用' : a.status === 'FINISHED' ? '已用完' : a.status === 'FROZEN' ? '冻结' : a.status }}
                  </CStatusPill>
                </div>
                <div class="asset__times">{{ a.remainingTimes }} / {{ a.totalTimes }} 次</div>
              </div>
              <CProgressBar
                v-if="a.totalTimes > 0"
                :value="a.totalTimes - a.remainingTimes"
                :max="a.totalTimes"
                :label="`已用 ${a.totalTimes - a.remainingTimes}/${a.totalTimes}`"
                color="var(--c-brand)"
              />
              <div class="asset__ops">
                <CButton
                  v-if="a.status === 'ACTIVE' && canEdit"
                  size="sm" variant="ghost"
                  @click="openConsume(a.id)"
                >扣次核销</CButton>
                <CButton
                  v-if="canEdit"
                  size="sm" variant="ghost"
                  @click="asset.setFrozen(a.id, a.status !== 'FROZEN')"
                >{{ a.status === 'FROZEN' ? '解冻' : '冻结' }}</CButton>
              </div>
            </div>
          </CCard>

          <div v-if="!acct.cashAssets.length && !acct.timesAssets.length" class="cc-noasset">
            该客户暂无资产，可点击右上角「充值」或「购疗程卡」开户。
          </div>

          <!-- 资产流水 -->
          <CCard title="资产流水" class="cc-txns">
            <div class="txn-head">
              <span>时间</span><span>类型</span><span class="txn-r">变动</span><span>操作人</span>
            </div>
            <div class="txn-list">
              <div
                v-for="t in recentTxns"
                :key="t.id"
                class="txn-row"
              >
                <span class="txn-time">{{ fmtTime(t.at) }}</span>
                <span class="txn-kind">{{ TXN_LABEL[t.kind] }}</span>
                <span class="txn-r" :style="{ color: txnColor(t.kind) }">
                  <template v-if="t.amount !== undefined">{{ txnSign(t.kind) }}¥{{ Math.abs(t.amount).toLocaleString() }}</template>
                  <template v-else-if="t.times !== undefined">{{ txnSign(t.kind) }}{{ t.times }} 次</template>
                </span>
                <span class="txn-op">{{ t.operatorName }}</span>
              </div>
              <div v-if="!asset.txns.length" class="txn-empty">暂无流水</div>
            </div>
          </CCard>
        </template>

        <CCard v-else class="cc-placeholder">
          <div class="cc-placeholder__inner">
            <CIcon name="card" :size="40" class="cc-placeholder__icon" />
            <p>从左侧选择一位持卡客户，查看其储值与疗程资产。</p>
          </div>
        </CCard>
      </div>
    </div>

    <!-- 操作面板（轻量弹层） -->
    <div v-if="panel" class="mask" @click.self="close">
      <CCard class="dlg" :title="panel === 'recharge' ? '账户充值' : panel === 'purchase' ? '购买疗程卡' : '扣次核销'">
        <div class="dlg__body">
          <template v-if="panel === 'recharge'">
            <div class="dlg__row">
              <label>充值金额</label>
              <CInput v-model="amount" type="number" placeholder="0" />
            </div>
            <div class="dlg__row">
              <label>赠送金额（可选）</label>
              <CInput v-model="gift" type="number" placeholder="0" />
            </div>
          </template>
          <template v-else-if="panel === 'purchase'">
            <div class="dlg__row">
              <label>项目名称</label>
              <CInput v-model="buyName" placeholder="如：光子嫩肤疗程" />
            </div>
            <div class="dlg__row">
              <label>总次数</label>
              <CInput v-model="buyTimes" type="number" placeholder="如 6" />
            </div>
          </template>
          <template v-else>
            <p class="dlg__tip">将从所选疗程卡扣减次数，剩余次数自动更新。</p>
            <div class="dlg__row">
              <label>扣减次数</label>
              <CInput v-model="consumeTimes" type="number" placeholder="1" />
            </div>
          </template>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="close">取消</CButton>
          <CButton variant="primary" @click="panel === 'recharge' ? doRecharge() : panel === 'purchase' ? doPurchase() : doConsume()">
            确认
          </CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.cc-page { display: flex; flex-direction: column; gap: var(--s-lg); }
.cc-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.cc-body { display: grid; grid-template-columns: 340px 1fr; gap: var(--s-lg); align-items: start; }

/* 左列客户 */
.cc-list { max-height: calc(100vh - 200px); display: flex; flex-direction: column; }
.cc-search { margin-bottom: var(--s-sm); }
.cc-clist { overflow-y: auto; margin: 0 -8px; padding: 0 8px; }
.cc-cust {
  width: 100%; display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md); border: none; background: transparent;
  border-radius: var(--r-md); cursor: pointer; text-align: left;
}
.cc-cust:hover { background: var(--c-brand-soft); }
.cc-cust.is-on { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.cc-cust__avatar {
  width: 36px; height: 36px; border-radius: 50%;
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center;
  font-weight: 600; flex-shrink: 0;
}
.cc-cust__main { flex: 1; min-width: 0; }
.cc-cust__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.cc-cust__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.cc-cust__bal { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.cc-cust__amt { font-size: var(--t-xs); font-weight: 600; color: var(--c-teal-dark); font-variant-numeric: tabular-nums; }
.cc-cust__times { font-size: var(--t-xs); color: var(--c-orange-dark); font-variant-numeric: tabular-nums; }
.cc-empty { text-align: center; color: var(--c-text-3); padding: var(--s-xl); font-size: var(--t-sm); }

/* 右列详情 */
.cc-detail { display: flex; flex-direction: column; gap: var(--s-md); }
.cc-acct-head { display: flex; align-items: center; justify-content: space-between; }
.cc-acct-id { display: flex; align-items: center; gap: var(--s-md); }
.cc-acct__avatar {
  width: 44px; height: 44px; border-radius: 50%;
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; font-weight: 600;
}
.cc-acct__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.cc-acct__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.cc-acct-sum { display: flex; gap: var(--s-xl); }
.cc-acct-sum__item { display: flex; flex-direction: column; align-items: flex-end; }
.cc-acct-sum__label { font-size: var(--t-xs); color: var(--c-text-3); }
.cc-acct-sum__val { font-size: var(--t-xl); font-weight: 700; font-variant-numeric: tabular-nums; }
.cc-acct-sum__val--teal { color: var(--c-teal-dark); }
.cc-acct-sum__val--orange { color: var(--c-orange-dark); }

.cc-actions { display: flex; gap: var(--s-sm); }

.cc-asset-card { padding: var(--s-md); }
.asset { padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.asset:last-child { border-bottom: none; }
.asset__top { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--s-xs); }
.asset__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-base); font-weight: 600; color: var(--c-text); }
.asset__icon { flex-shrink: 0; }
.asset__icon--teal { color: var(--c-teal-dark); }
.asset__icon--orange { color: var(--c-orange-dark); }
.asset__balance { font-size: var(--t-lg); font-weight: 700; color: var(--c-teal-dark); font-variant-numeric: tabular-nums; }
.asset__times { font-size: var(--t-base); font-weight: 600; color: var(--c-orange-dark); font-variant-numeric: tabular-nums; }
.asset__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-sm); }
.asset__ops { display: flex; gap: var(--s-xs); margin-top: var(--s-sm); }

.cc-noasset { padding: var(--s-xl); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); background: var(--c-surface); border-radius: var(--r-md); }

/* 流水 */
.cc-txns { padding: var(--s-md); }
.txn-head, .txn-row {
  display: grid; grid-template-columns: 90px 90px 1fr 120px; gap: var(--s-sm);
  align-items: center; padding: var(--s-xs) var(--s-sm);
}
.txn-head { font-size: var(--t-xs); color: var(--c-text-3); border-bottom: 1px solid var(--c-border-light); }
.txn-row { font-size: var(--t-sm); color: var(--c-text-2); border-bottom: 1px solid var(--c-border-light); }
.txn-time { color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.txn-r { text-align: right; font-weight: 600; font-variant-numeric: tabular-nums; }
.txn-op { font-size: var(--t-xs); color: var(--c-text-3); }
.txn-empty { text-align: center; color: var(--c-text-3); padding: var(--s-lg); font-size: var(--t-sm); }

/* 占位 */
.cc-placeholder__inner { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: var(--s-xxl); color: var(--c-text-3); gap: var(--s-sm); }
.cc-placeholder__icon { color: var(--c-text-4); }
.cc-placeholder p { margin: 0; font-size: var(--t-sm); }

/* 弹层 */
.mask { position: fixed; inset: 0; background: rgba(0,0,0,0.32); display: flex; align-items: center; justify-content: center; z-index: 100; }
.dlg { width: 420px; }
.dlg__body { display: flex; flex-direction: column; gap: var(--s-md); padding: var(--s-sm) 0; }
.dlg__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.dlg__row label { font-size: var(--t-sm); color: var(--c-text-2); font-weight: 500; }
.dlg__tip { margin: 0; font-size: var(--t-sm); color: var(--c-text-3); }

/* Pad 堆叠 */
@media (max-width: 834px) {
  .cc-kpis { grid-template-columns: repeat(2, 1fr); }
  .cc-body { grid-template-columns: 1fr; }
  .cc-list { max-height: 320px; }
  .cc-acct-head { flex-direction: column; align-items: flex-start; gap: var(--s-md); }
  .cc-acct-sum { width: 100%; justify-content: space-between; }
}
</style>
