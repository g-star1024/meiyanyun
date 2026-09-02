<script setup lang="ts">
/* ============================================================
 * A1-07 AI 客服
 * 路由 /ai/chatbot
 * 红线：AI 回复经 A1-04 敏感词过滤；医疗建议不覆盖 M4-10 禁忌硬阻断
 * ============================================================ */
import { ref, computed } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CSegmented from '@/components/CSegmented.vue'
import CIcon from '@/components/CIcon.vue'

type Filter = 'all' | 'ai' | 'human'

interface Session {
  id: string
  customer: string
  lastMsg: string
  time: string
  channel: 'ai' | 'human'
  unread: number
}

interface Message {
  id: string
  from: 'ai' | 'user'
  text: string
  time: string
}

interface KnowledgeHit {
  id: string
  title: string
  snippet: string
  score: number
}

const filter = ref<Filter>('all')
const filterOptions = [
  { label: '全部会话', value: 'all' },
  { label: 'AI 接待', value: 'ai' },
  { label: '人工接待', value: 'human' },
]

const sessions = ref<Session[]>([
  { id: 'S001', customer: '王女士', lastMsg: '光子嫩肤后多久可以化妆？', time: '14:32', channel: 'ai', unread: 2 },
  { id: 'S002', customer: '李先生', lastMsg: '我想预约下周三的面诊', time: '14:25', channel: 'ai', unread: 0 },
  { id: 'S003', customer: '张小姐', lastMsg: '这个项目适合敏感肌吗？', time: '14:18', channel: 'human', unread: 0 },
  { id: 'S004', customer: '陈女士', lastMsg: '会员卡余额查询', time: '14:05', channel: 'ai', unread: 1 },
  { id: 'S005', customer: '赵先生', lastMsg: '退款什么时候到账？', time: '13:48', channel: 'human', unread: 0 },
  { id: 'S006', customer: '周女士', lastMsg: '热玛吉和超声炮的区别', time: '13:30', channel: 'ai', unread: 0 },
  { id: 'S007', customer: '吴小姐', lastMsg: '术后护理注意事项', time: '13:12', channel: 'ai', unread: 3 },
])

const activeId = ref('S001')

const activeSession = computed(() => sessions.value.find((s) => s.id === activeId.value)!)

const filteredSessions = computed(() => {
  if (filter.value === 'all') return sessions.value
  return sessions.value.filter((s) => s.channel === filter.value)
})

const messages = ref<Message[]>([
  { id: 'M1', from: 'user', text: '你好，我想咨询下光子嫩肤后的注意事项', time: '14:28' },
  { id: 'M2', from: 'ai', text: '您好王女士，光子嫩肤术后请注意：1）24小时内避免化妆；2）3天内使用温和洁面；3）加强保湿和防晒（SPF50+）；4）避免高温环境如桑拿、汗蒸。如有持续红肿请及时联系门店。', time: '14:28' },
  { id: 'M3', from: 'user', text: '那多久可以化妆呢？', time: '14:31' },
  { id: 'M4', from: 'ai', text: '建议术后 24-48 小时后再化妆，具体视皮肤恢复情况而定。如果还有泛红或刺痛，建议再延后 1-2 天。', time: '14:32' },
])

const knowledgeHits = ref<KnowledgeHit[]>([
  { id: 'K1', title: '光子嫩肤术后护理规范 v2.3', snippet: '24h 禁化妆、3d 温和洁面、SPF50+ 防晒...', score: 0.96 },
  { id: 'K2', title: '常见项目恢复期对照表', snippet: '光子嫩肤 24-48h、热玛吉 72h、水光针 24h...', score: 0.88 },
  { id: 'K3', title: 'M4-10 禁忌词库 - 术后类', snippet: '不得承诺"零恢复期""绝对安全"等表述...', score: 0.82 },
])

const draft = ref('')

function pickSession(id: string) {
  activeId.value = id
  const s = sessions.value.find((x) => x.id === id)
  if (s) s.unread = 0
}

function transferToHuman() {
  alert('已转接 M4-09 咨询工作台，上下文已同步')
}

function sendMsg() {
  const text = draft.value.trim()
  if (!text) return
  messages.value.push({
    id: 'M' + Date.now(),
    from: 'user',
    text,
    time: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
  })
  draft.value = ''
}

function channelPill(c: Session['channel']) {
  return c === 'ai'
    ? { status: 'info' as const, label: 'AI 接待' }
    : { status: 'primary' as const, label: '人工' }
}
</script>

<template>
  <div class="a1-chatbot">
    <div class="a1-chatbot__kpis">
      <CKpi label="今日会话" value="328" tone="purple" trend="+12.4%" trend-up trend-good icon="chat" />
      <CKpi label="AI 独立解决率" value="72%" tone="teal" trend="+3.1%" trend-up trend-good icon="trend-up" />
      <CKpi label="转人工" value="92" tone="orange" trend="-5.2%" trend-up trend-good icon="chat" />
      <CKpi label="平均响应" value="1.2s" tone="brand" trend="0.1s" trend-up trend-good icon="chat" />
    </div>

    <CCard padding="none" class="a1-chatbot__board">
      <template #header>
        <div class="board-head">
          <h3>AI 客服工作台</h3>
          <CSegmented v-model="filter" :options="filterOptions" size="sm" />
        </div>
      </template>

      <div class="board-body">
        <!-- 左侧会话列表 -->
        <div class="session-list">
          <div
            v-for="s in filteredSessions"
            :key="s.id"
            class="session-item"
            :class="{ 'is-active': s.id === activeId }"
            @click="pickSession(s.id)"
          >
            <div class="session-item__avatar">{{ s.customer.charAt(0) }}</div>
            <div class="session-item__main">
              <div class="session-item__row">
                <span class="session-item__name">{{ s.customer }}</span>
                <CStatusPill :status="channelPill(s.channel).status" dot>
                  {{ channelPill(s.channel).label }}
                </CStatusPill>
              </div>
              <div class="session-item__msg">{{ s.lastMsg }}</div>
            </div>
            <div class="session-item__meta">
              <div class="session-item__time">{{ s.time }}</div>
              <div v-if="s.unread > 0" class="session-item__badge">{{ s.unread }}</div>
            </div>
          </div>
        </div>

        <!-- 右侧消息详情 -->
        <div class="chat-panel">
          <div class="chat-panel__head">
            <div class="chat-panel__title">
              <span>{{ activeSession.customer }}</span>
              <CStatusPill :status="channelPill(activeSession.channel).status" dot>
                {{ channelPill(activeSession.channel).label }}
              </CStatusPill>
            </div>
            <CButton size="sm" variant="secondary" @click="transferToHuman">
              <CIcon name="handover" :size="14" />
              转人工
            </CButton>
          </div>

          <div class="chat-panel__msgs">
            <div
              v-for="m in messages"
              :key="m.id"
              class="bubble-row"
              :class="m.from === 'ai' ? 'bubble-row--ai' : 'bubble-row--user'"
            >
              <div class="bubble">
                <div class="bubble__text">{{ m.text }}</div>
                <div class="bubble__time">{{ m.time }}</div>
              </div>
            </div>

            <!-- 知识命中卡片 -->
            <div class="knowledge-hit">
              <div class="knowledge-hit__title">
                <CIcon name="search" :size="14" />
                知识命中（{{ knowledgeHits.length }} 条）
              </div>
              <div class="knowledge-hit__list">
                <div v-for="k in knowledgeHits" :key="k.id" class="kh-item">
                  <div class="kh-item__head">
                    <span class="kh-item__name">{{ k.title }}</span>
                    <span class="kh-item__score">{{ Math.round(k.score * 100) }}%</span>
                  </div>
                  <div class="kh-item__snippet">{{ k.snippet }}</div>
                </div>
              </div>
            </div>
          </div>

          <div class="chat-panel__input">
            <input
              v-model="draft"
              class="chat-input"
              placeholder="输入回复，AI 将辅助生成..."
              @keydown.enter="sendMsg"
            />
            <CButton size="sm" @click="sendMsg">发送</CButton>
            <CButton size="sm" variant="primary" @click="transferToHuman">
              <CIcon name="handover" :size="14" />
              转人工
            </CButton>
          </div>
        </div>
      </div>

      <div class="compliance-bar">
        <CIcon name="shield" :size="14" />
        <span>AI 回复经 A1-04 敏感词过滤；医疗建议不覆盖 M4-10 禁忌硬阻断；会话日志写入 T1-04 审计。</span>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.a1-chatbot { display: flex; flex-direction: column; gap: var(--s-lg); }
.a1-chatbot__kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .a1-chatbot__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.board-head { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: var(--s-sm); flex-wrap: wrap; }
.board-head h3 { margin: 0; font-size: var(--t-md); font-weight: 700; }

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

/* 消息面板 */
.chat-panel { display: flex; flex-direction: column; min-width: 0; }
.chat-panel__head {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-md) var(--s-lg);
  border-bottom: 1px solid var(--c-border);
}
.chat-panel__title { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-md); font-weight: 600; }
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
.bubble__time { font-size: 11px; opacity: 0.7; margin-top: 4px; }

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
.knowledge-hit__list { display: flex; flex-direction: column; gap: var(--s-xs); }
.kh-item {
  background: var(--c-surface);
  border-radius: var(--r-sm);
  padding: var(--s-xs) var(--s-sm);
}
.kh-item__head { display: flex; justify-content: space-between; align-items: center; }
.kh-item__name { font-size: var(--t-xs); font-weight: 600; color: var(--c-text); }
.kh-item__score { font-size: 11px; color: var(--c-purple); font-weight: 600; }
.kh-item__snippet { font-size: 11px; color: var(--c-text-3); margin-top: 2px; }

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
</style>
