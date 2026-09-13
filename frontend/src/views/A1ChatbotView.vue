<script setup lang="ts">
/* ============================================================
 * A1-07 AI 客服
 * 路由 /ai/chatbot
 * 真实会话/消息落 ai_chat_session/ai_chat_message（B47 卡5 去 mock）
 * AI 回复经 chatbot invoke 全治理链：角色/门店灰度 + A1-04 敏感词 + 配额计费 + ai_invoke_log；
 * 医疗建议不覆盖 M4-10 禁忌硬阻断；写动作经 T1-04 审计留痕。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CSegmented from '@/components/CSegmented.vue'
import CIcon from '@/components/CIcon.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import {
  getChatbotSessions, createChatbotSession, getChatbotSession,
  sendChatbotMessage, transferChatbot, staffReplyChatbot, getChatbotStats,
  type ChatbotSession, type ChatbotMessage, type ChatbotStats as Stats,
} from '@/api/ai'

type Filter = 'all' | 'ai' | 'human'

const toast = useToast()

const filter = ref<Filter>('all')
const filterOptions = [
  { label: '全部会话', value: 'all' },
  { label: 'AI 接待', value: 'ai' },
  { label: '人工接待', value: 'human' },
]

const sessions = ref<ChatbotSession[]>([])
const messages = ref<ChatbotMessage[]>([])
const stats = ref<Stats | null>(null)
const activeId = ref<number | null>(null)
const draft = ref('')

const loading = ref(false)
const sending = ref(false)
const transferring = ref(false)
const showCreate = ref(false)
const newCustomer = ref('')
const newFirstMsg = ref('')
const creating = ref(false)

const activeSession = computed<ChatbotSession | null>(
  () => sessions.value.find((s) => s.sessionId === activeId.value) ?? null,
)

const isHuman = computed(() => activeSession.value?.channel === 'human' || !!activeSession.value?.transferred)

const filteredSessions = computed(() => {
  if (filter.value === 'all') return sessions.value
  return sessions.value.filter((s) => s.channel === filter.value)
})

const lastAiMeta = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.from === 'ai' && m.modelCode) {
      const yuan = ((m.costFen ?? 0) / 100).toFixed(2)
      return `${m.modelCode} · ${m.totalTokens ?? 0} tokens · ¥${yuan} · ${m.latencyMs ?? '-'}ms`
    }
  }
  return ''
})

function fmtNum(n: number | null | undefined): string {
  return (n ?? 0).toLocaleString('zh-CN')
}

function rateText(v: number | null | undefined): string {
  return v === null || v === undefined ? '—' : `${v}%`
}

function trendText(pct: number | null | undefined): string {
  if (pct === null || pct === undefined) return '环比不可算'
  return `${pct >= 0 ? '+' : ''}${pct}%`
}

const kpis = computed(() => {
  const s = stats.value
  return [
    {
      label: '今日会话', icon: 'chat', value: s ? fmtNum(s.sessionCount) : '—',
      tone: 'purple' as const,
      trend: s ? trendText(s.sessionDeltaPct) : '加载中…',
      trendUp: (s?.sessionDeltaPct ?? 0) >= 0, trendGood: (s?.sessionDeltaPct ?? 0) >= 0,
    },
    {
      label: 'AI 独立解决率', icon: 'trend-up', value: s ? rateText(s.aiResolveRate) : '—',
      tone: 'teal' as const,
      trend: s ? `AI 回复 ${fmtNum(s.aiReplyCount)} 条` : '加载中…',
      trendUp: true, trendGood: true,
    },
    {
      label: '转人工', icon: 'chat', value: s ? fmtNum(s.transferredCount) : '—',
      tone: 'orange' as const,
      trend: s ? `占比 ${rateText(s.transferRate)}` : '加载中…',
      trendUp: true, trendGood: false,
    },
    {
      label: '平均响应', icon: 'chat',
      value: s && s.avgLatencyMs !== null ? `${(s.avgLatencyMs / 1000).toFixed(1)}s` : '—',
      tone: 'brand' as const,
      trend: s ? `本周调用 ${fmtNum(s.weekInvokes)}` : '加载中…',
      trendUp: true, trendGood: true,
    },
  ]
})

function channelPill(c: ChatbotSession['channel']) {
  return c === 'ai'
    ? { status: 'info' as const, label: 'AI 接待' }
    : { status: 'primary' as const, label: '人工' }
}

async function loadSessions(preserveActive = true) {
  loading.value = true
  try {
    sessions.value = await getChatbotSessions(filter.value === 'all' ? undefined : filter.value)
    if (preserveActive && activeId.value !== null) {
      if (!sessions.value.some((s) => s.sessionId === activeId.value)) {
        activeId.value = sessions.value[0]?.sessionId ?? null
      }
    } else if (activeId.value === null) {
      activeId.value = sessions.value[0]?.sessionId ?? null
    }
    if (activeId.value !== null) await loadMessages(activeId.value, true)
    else messages.value = []
  } catch (e) {
    toast.error('会话列表加载失败：' + errMsg(e))
  } finally {
    loading.value = false
  }
}

async function loadMessages(id: number, silent = false) {
  try {
    const detail = await getChatbotSession(id)
    messages.value = detail.messages
    const idx = sessions.value.findIndex((s) => s.sessionId === id)
    if (idx >= 0) sessions.value[idx] = detail.session
  } catch (e) {
    if (!silent) toast.error('会话消息加载失败：' + errMsg(e))
  }
}

async function loadStats() {
  try {
    stats.value = await getChatbotStats()
  } catch (e) {
    toast.error('客服统计加载失败：' + errMsg(e))
  }
}

async function pickSession(id: number) {
  activeId.value = id
  await loadMessages(id)
}

function openCreate() {
  newCustomer.value = ''
  newFirstMsg.value = ''
  showCreate.value = true
}

async function submitCreate() {
  const name = newCustomer.value.trim()
  if (!name) {
    toast.info('请先填写顾客称呼')
    return
  }
  creating.value = true
  try {
    const first = newFirstMsg.value.trim()
    const detail = await createChatbotSession(
      first ? { customerName: name, firstMessage: first } : { customerName: name },
    )
    showCreate.value = false
    activeId.value = detail.session.sessionId
    await Promise.all([loadSessions(), loadStats()])
    toast.success(first
      ? `会话 ${detail.session.sessionNo} 已建立，首条 AI 回复来自真实模型出站`
      : `会话 ${detail.session.sessionNo} 已建立`)
  } catch (e) {
    toast.error('新建会话失败：' + errMsg(e))
  } finally {
    creating.value = false
  }
}

async function sendMsg() {
  if (sending.value || activeId.value === null) return
  const text = draft.value.trim()
  if (!text) return
  sending.value = true
  try {
    if (isHuman.value) {
      const detail = await staffReplyChatbot(activeId.value, text)
      messages.value = detail.messages
      const idx = sessions.value.findIndex((s) => s.sessionId === activeId.value)
      if (idx >= 0) sessions.value[idx] = detail.session
    } else {
      const detail = await sendChatbotMessage(activeId.value, text)
      messages.value = detail.messages
      const idx = sessions.value.findIndex((s) => s.sessionId === activeId.value)
      if (idx >= 0) sessions.value[idx] = detail.session
    }
    draft.value = ''
    await Promise.all([loadSessions(), loadStats()])
  } catch (e) {
    toast.error(isHuman.value ? '人工回复发送失败：' + errMsg(e) : '发送失败（顾客消息已保留，可重试 AI 回复）：' + errMsg(e))
  } finally {
    sending.value = false
  }
}

async function transferToHuman() {
  if (transferring.value || activeId.value === null) return
  transferring.value = true
  try {
    const res = await transferChatbot(activeId.value)
    if (res.changed) {
      toast.success('已转人工接待（站内登记，M4-09 工作台联动为远期能力）')
    } else {
      toast.info('该会话已是人工接待，无需重复转接')
    }
    await Promise.all([loadMessages(activeId.value), loadSessions(), loadStats()])
  } catch (e) {
    toast.error('转人工失败：' + errMsg(e))
  } finally {
    transferring.value = false
  }
}

onMounted(() => {
  loadSessions()
  loadStats()
})

watch(filter, () => {
  activeId.value = null
  loadSessions(false)
})
</script>

<template>
  <div class="a1-chatbot">
    <div class="a1-chatbot__kpis">
      <CKpi
        v-for="k in kpis"
        :key="k.label"
        :label="k.label"
        :value="k.value"
        :tone="k.tone"
        :trend="k.trend"
        :trend-up="k.trendUp"
        :trend-good="k.trendGood"
        :icon="k.icon" />
    </div>

    <CCard padding="none" class="a1-chatbot__board">
      <template #header>
        <div class="board-head">
          <h3>AI 客服工作台</h3>
          <div class="board-head__ops">
            <CSegmented v-model="filter" :options="filterOptions" size="sm" />
            <CButton size="sm" variant="primary" @click="openCreate">
              <CIcon name="chat" :size="14" />
              新建会话
            </CButton>
          </div>
        </div>
      </template>

      <div class="board-body">
        <!-- 左侧会话列表 -->
        <div class="session-list">
          <div v-if="loading && filteredSessions.length === 0" class="list-empty">会话加载中…</div>
          <div v-else-if="filteredSessions.length === 0" class="list-empty">
            <CIcon name="chat" :size="20" />
            <div class="list-empty__title">暂无会话</div>
            <div class="list-empty__tip">点击右上角「新建会话」录入顾客称呼，开始真实 AI 接待</div>
          </div>
          <div
            v-for="s in filteredSessions"
            :key="s.sessionId"
            class="session-item"
            :class="{ 'is-active': s.sessionId === activeId }"
            @click="pickSession(s.sessionId)"
          >
            <div class="session-item__avatar">{{ (s.customerName || '顾').charAt(0) }}</div>
            <div class="session-item__main">
              <div class="session-item__row">
                <span class="session-item__name">{{ s.customerName || '未命名顾客' }}</span>
                <CStatusPill :status="channelPill(s.channel).status" dot>
                  {{ channelPill(s.channel).label }}
                </CStatusPill>
              </div>
              <div class="session-item__msg">{{ s.lastMessage || '（暂无消息）' }}</div>
            </div>
            <div class="session-item__meta">
              <div class="session-item__time">{{ s.time || s.sessionNo }}</div>
              <div v-if="s.unreadCount > 0" class="session-item__badge">{{ s.unreadCount }}</div>
            </div>
          </div>
        </div>

        <!-- 右侧消息详情 -->
        <div class="chat-panel">
          <template v-if="activeSession">
            <div class="chat-panel__head">
              <div class="chat-panel__title">
                <span>{{ activeSession.customerName || '未命名顾客' }}</span>
                <span class="chat-panel__no">{{ activeSession.sessionNo }}</span>
                <CStatusPill :status="channelPill(activeSession.channel).status" dot>
                  {{ channelPill(activeSession.channel).label }}
                </CStatusPill>
              </div>
              <CButton
                size="sm"
                variant="secondary"
                :disabled="isHuman || transferring"
                @click="transferToHuman">
                <CIcon name="handover" :size="14" />
                {{ transferring ? '转接中…' : '转人工' }}
              </CButton>
            </div>

            <div class="chat-panel__msgs">
              <div
                v-for="m in messages"
                :key="m.messageId"
                class="bubble-row"
                :class="m.from === 'ai' ? 'bubble-row--ai' : 'bubble-row--user'"
              >
                <div class="bubble">
                  <div v-if="m.from !== 'customer'" class="bubble__from">
                    {{ m.from === 'ai' ? 'AI 客服' : '人工客服' }}
                  </div>
                  <div class="bubble__text">{{ m.content }}</div>
                  <div class="bubble__time">{{ m.time }}</div>
                </div>
              </div>
              <div v-if="messages.length === 0" class="chat-empty">暂无消息，在下方输入顾客问题开始接待</div>

              <!-- 知识命中卡：本期无结构化知识检索，诚实置空说明，不伪造命中文档 -->
              <div class="knowledge-hit">
                <div class="knowledge-hit__title">
                  <CIcon name="search" :size="14" />
                  知识命中
                </div>
                <div v-if="lastAiMeta" class="knowledge-hit__meta">最近 AI 回复：{{ lastAiMeta }}</div>
                <div class="knowledge-hit__empty">
                  本期未接入结构化知识库检索，AI 仅基于通用医美常识经合规提示作答，不展示伪造命中文档；
                  知识库检索命中为远期能力。
                </div>
              </div>
            </div>

            <div class="chat-panel__input">
              <input
                v-model="draft"
                class="chat-input"
                :placeholder="isHuman ? '以人工客服身份输入回复…' : '录入顾客问题，AI 将经治理链真实回复…'"
                :disabled="sending"
                @keydown.enter="sendMsg"
              />
              <CButton size="sm" :disabled="sending" @click="sendMsg">
                {{ sending ? '发送中…' : isHuman ? '人工回复' : '发送给 AI' }}
              </CButton>
            </div>
          </template>
          <div v-else class="chat-panel__placeholder">
            <CIcon name="chat" :size="28" />
            <div class="chat-panel__placeholder-title">选择左侧会话，或新建会话开始接待</div>
            <CButton size="sm" variant="primary" @click="openCreate">新建会话</CButton>
          </div>
        </div>
      </div>

      <div class="compliance-bar">
        <CIcon name="shield" :size="14" />
        <span>
          AI 回复经 A1-04 敏感词过滤与 chatbot 功能角色/门店灰度、配额计费链治理（ai_invoke_log 留痕）；
          医疗建议不覆盖 M4-10 禁忌硬阻断；新建会话/转人工写 T1-04 审计；转人工当前为站内登记，M4-09 工作台联动为远期能力。
        </span>
      </div>
    </CCard>

    <!-- 新建会话弹层 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <CCard class="modal-card">
        <template #header>
          <div class="board-head">
            <h3>新建客服会话</h3>
            <CButton size="sm" variant="ghost" @click="showCreate = false">关闭</CButton>
          </div>
        </template>
        <div class="create-form">
          <label class="create-form__label">顾客称呼</label>
          <input v-model="newCustomer" class="chat-input" maxlength="64" placeholder="如：王女士" />
          <label class="create-form__label">顾客首条问题（选填，提交后同步生成 AI 真实回复）</label>
          <textarea
            v-model="newFirstMsg"
            class="create-form__textarea"
            rows="3"
            maxlength="2000"
            placeholder="如：光子嫩肤后多久可以化妆？" />
          <div class="create-form__ops">
            <CButton size="sm" variant="secondary" @click="showCreate = false">取消</CButton>
            <CButton size="sm" variant="primary" :disabled="creating" @click="submitCreate">
              {{ creating ? '创建中（AI 回复可能耗时数秒）…' : '建立会话' }}
            </CButton>
          </div>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.a1-chatbot { display: flex; flex-direction: column; gap: var(--s-lg); }
.a1-chatbot__kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .a1-chatbot__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.board-head { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: var(--s-sm); flex-wrap: wrap; }
.board-head h3 { margin: 0; font-size: var(--t-md); font-weight: 700; }
.board-head__ops { display: flex; align-items: center; gap: var(--s-sm); }

.board-body {
  display: grid;
  grid-template-columns: 320px 1fr;
  min-height: 560px;
}

/* 会话列表 */
.session-list {
  border-right: 1px solid var(--c-border);
  overflow-y: auto;
  max-height: 600px;
}
.session-item {
  display: flex;
  align-items: flex-start;
  gap: var(--s-sm);
  padding: var(--s-md);
  border-bottom: 1px solid var(--c-border-light);
  cursor: pointer;
  transition: background 0.15s;
}
.session-item:hover { background: var(--c-brand-soft); }
.session-item.is-active { background: var(--c-brand-soft); }
.session-item__avatar {
  width: 36px; height: 36px; border-radius: 50%;
  background: var(--c-purple); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: var(--t-sm); font-weight: 600;
  flex-shrink: 0;
}
.session-item__main { flex: 1; min-width: 0; }
.session-item__row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-xs); }
.session-item__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.session-item__msg {
  font-size: var(--t-xs); color: var(--c-text-3);
  margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.session-item__meta { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; }
.session-item__time { font-size: 11px; color: var(--c-text-3); }
.session-item__badge {
  min-width: 18px; height: 18px; padding: 0 5px;
  background: var(--c-brand); color: #fff;
  border-radius: 9px; font-size: 11px; font-weight: 600;
  display: inline-flex; align-items: center; justify-content: center;
}
.list-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: var(--s-xs); padding: var(--s-xl) var(--s-md);
  color: var(--c-text-3); font-size: var(--t-xs); text-align: center;
}
.list-empty__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text-2); }
.list-empty__tip { line-height: var(--lh-sm); }

/* 消息面板 */
.chat-panel { display: flex; flex-direction: column; min-width: 0; }
.chat-panel__head {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-md) var(--s-lg);
  border-bottom: 1px solid var(--c-border);
}
.chat-panel__title { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-md); font-weight: 600; }
.chat-panel__no { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.chat-panel__msgs {
  flex: 1; padding: var(--s-lg);
  overflow-y: auto;
  background: var(--c-bg-page);
  display: flex; flex-direction: column; gap: var(--s-md);
  min-height: 400px;
}
.bubble-row { display: flex; }
.bubble-row--ai { justify-content: flex-start; }
.bubble-row--user { justify-content: flex-end; }
.bubble {
  max-width: 70%;
  padding: var(--s-sm) var(--s-md);
  border-radius: var(--r-lg);
  font-size: var(--t-sm); line-height: var(--lh-sm);
}
.bubble-row--ai .bubble {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  color: var(--c-text);
  border-top-left-radius: var(--s-xxs);
}
.bubble-row--user .bubble {
  background: var(--c-brand);
  color: #fff;
  border-top-right-radius: var(--s-xxs);
}
.bubble__from { font-size: 11px; opacity: 0.75; margin-bottom: 2px; }
.bubble__time { font-size: 11px; opacity: 0.7; margin-top: 4px; }
.chat-empty,
.chat-panel__placeholder {
  color: var(--c-text-3); font-size: var(--t-xs); text-align: center; padding: var(--s-lg);
}
.chat-panel__placeholder {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: var(--s-md); min-height: 400px;
}
.chat-panel__placeholder-title { font-size: var(--t-sm); color: var(--c-text-2); }

.knowledge-hit {
  background: var(--c-purple-soft);
  border: 1px solid var(--c-purple);
  border-radius: var(--r-md);
  padding: var(--s-sm) var(--s-md);
}
.knowledge-hit__title {
  display: flex; align-items: center; gap: var(--s-xs);
  font-size: var(--t-xs); font-weight: 600; color: var(--c-purple);
  margin-bottom: var(--s-xs);
}
.knowledge-hit__meta { font-size: 11px; color: var(--c-text-2); margin-bottom: 4px; }
.knowledge-hit__empty { font-size: 11px; color: var(--c-text-3); line-height: var(--lh-sm); }

.chat-panel__input {
  display: flex; gap: var(--s-sm); align-items: center;
  padding: var(--s-md) var(--s-lg);
  border-top: 1px solid var(--c-border);
  background: var(--c-surface);
}
.chat-input {
  flex: 1; height: 36px;
  padding: 0 var(--s-md);
  border: 1px solid var(--c-border);
  border-radius: var(--r-capsule);
  background: var(--c-bg-page);
  font-size: var(--t-sm);
  color: var(--c-text);
  outline: none;
  transition: border-color 0.15s;
}
.chat-input:focus { border-color: var(--c-brand); }

.compliance-bar {
  display: flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-sm) var(--s-lg);
  background: var(--c-danger-bg);
  color: var(--c-danger-fg);
  font-size: 11px;
  border-top: 1px solid var(--c-border-light);
}

/* 新建会话弹层 */
.modal-mask {
  position: fixed; inset: 0; z-index: 100;
  background: rgba(15, 23, 42, 0.45);
  display: flex; align-items: center; justify-content: center;
  padding: var(--s-lg);
}
.modal-card { width: 480px; max-width: 100%; }
.create-form { display: flex; flex-direction: column; gap: var(--s-xs); }
.create-form__label { font-size: var(--t-xs); font-weight: 600; color: var(--c-text-2); }
.create-form__textarea {
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
  background: var(--c-bg-page);
  font-size: var(--t-sm);
  color: var(--c-text);
  padding: var(--s-sm) var(--s-md);
  outline: none;
  resize: vertical;
  font-family: inherit;
}
.create-form__textarea:focus { border-color: var(--c-brand); }
.create-form__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-sm); }
</style>
