<script setup lang="ts">
/* C 端术后回访 /m/followup — 接 M4-11/followup（联动 5） */
import { onMounted, computed, ref } from 'vue'
import { useFollowupStore } from '@/stores/followup'
import { usePointsStore } from '@/stores/points'
import CIcon from '@/components/CIcon.vue'

const followup = useFollowupStore()
const points = usePointsStore()

onMounted(() => {
  followup.seed()
  points.seed()
})

const member = computed(() => points.member)
const myFollowups = computed(() =>
  followup.followups
    .filter((f) => f.customerName === member.value.name)
    .sort((a, b) => (a.planDate > b.planDate ? 1 : -1)),
)

const activeId = ref<string | null>(null)
const satisfaction = ref(5)
const feedback = ref('')
const hasAdverse = ref(false)
const adverseNote = ref('')

function toggle(id: string) {
  activeId.value = activeId.value === id ? null : id
  satisfaction.value = 5
  feedback.value = ''
  hasAdverse.value = false
  adverseNote.value = ''
}

function submit(f: (typeof followup.followups)[0]) {
  const ok = followup.submitByCustomer(f.id, {
    satisfaction: satisfaction.value,
    note: feedback.value || undefined,
    adverseReaction: hasAdverse.value,
    adverseNote: hasAdverse.value ? adverseNote.value : undefined,
  })
  if (ok) {
    window.alert(`回访已提交，满意度 ${satisfaction.value} 星，感谢您的反馈！`)
    activeId.value = null
  }
}

function fmtDate(s: string) { return s?.slice(0, 16).replace('T', ' ') }

const satLabels = ['非常不满意', '不满意', '一般', '满意', '非常满意']
</script>

<template>
  <div class="followup">
    <div class="followup__header">
      <h3>术后回访</h3>
      <p>我们关心您的每一次恢复，欢迎填写回访反馈</p>
    </div>

    <div v-if="myFollowups.length === 0" class="followup__empty">
      <div class="followup__empty-icon"><CIcon name="check-square" :size="34" /></div>
      <p>暂无待回访项目</p>
    </div>

    <div class="followup__list">
      <div
        v-for="f in myFollowups"
        :key="f.id"
        class="fu-card"
        :class="{ 'fu-card--done': f.status === 'DONE', 'fu-card--active': activeId === f.id }"
      >
        <div class="fu-card__head" @click="toggle(f.id)">
          <div class="fu-card__info">
            <div class="fu-card__title">{{ f.project }}</div>
            <div class="fu-card__date">计划日期：{{ f.planDate?.slice(0, 10) }}</div>
          </div>
          <span class="fu-card__status" :class="f.status.toLowerCase()">
            {{ f.status === 'PENDING' ? '待回访' : f.status === 'DONE' ? '已完成' : '已跳过' }}
          </span>
        </div>

        <!-- 展开：回填表单 -->
        <div v-if="activeId === f.id && f.status === 'PENDING'" class="fu-card__body">
          <div class="fu-field">
            <label>恢复情况</label>
            <div class="fu-stars">
              <button
                v-for="n in 5"
                :key="n"
                class="fu-star"
                :class="{ active: n <= satisfaction }"
                @click="satisfaction = n"
              ><CIcon name="star" :size="24" /></button>
              <span class="fu-stars__label">{{ satLabels[satisfaction - 1] }}</span>
            </div>
          </div>
          <div class="fu-field">
            <label>反馈备注（选填）</label>
            <textarea
              v-model="feedback"
              rows="2"
              placeholder="请描述恢复情况、有无不适或建议…"
            ></textarea>
          </div>
          <div class="fu-field">
            <label class="fu-check">
              <input type="checkbox" v-model="hasAdverse" />
              <span>有不良反应（红肿/过敏/疼痛等）</span>
            </label>
            <textarea
              v-if="hasAdverse"
              v-model="adverseNote"
              rows="2"
              placeholder="请描述不良反应，我们将安排医生跟进…"
              style="margin-top: 8px"
            ></textarea>
          </div>
          <div class="fu-actions">
            <button class="fu-btn fu-btn--ghost" @click="activeId = null">取消</button>
            <button class="fu-btn fu-btn--primary" @click="submit(f)">提交回访</button>
          </div>
          <div class="fu-tip">提交后同步至 B 端 M4-11 术后回访记录，咨询师将跟进您的恢复</div>
        </div>

        <!-- 已完成：展示结果 -->
        <div v-else-if="f.status === 'DONE' && activeId === f.id" class="fu-card__body">
          <div class="fu-result">
            <span>满意度：{{ f.satisfaction || 5 }} 星</span>
            <span>完成时间：{{ fmtDate(f.doneAt || '') }}</span>
          </div>
          <div v-if="f.note" class="fu-summary">{{ f.note }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.followup { padding: 0 0 24px; }
.followup__header {
  background: linear-gradient(135deg, var(--c-teal, #2ed4bf), var(--c-brand, #ff6b9d));
  padding: var(--s-lg); color: #fff;
}
.followup__header h3 { margin: 0; font-size: var(--t-lg); }
.followup__header p { margin: 4px 0 0; font-size: var(--t-sm); opacity: .85; }
.followup__empty { text-align: center; padding: 64px 20px; color: var(--c-text-3); }
.followup__empty-icon { width: 64px; height: 64px; margin: 0 auto 12px; border-radius: 16px; background: #fff0f5; color: #ff6b9e; display: flex; align-items: center; justify-content: center; }
.followup__list { padding: var(--s-md); display: flex; flex-direction: column; gap: var(--s-md); }
.fu-card {
  background: var(--c-bg-card); border-radius: var(--r-md); overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,.06);
}
.fu-card--done { opacity: .8; }
.fu-card__head {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-md); cursor: pointer;
}
.fu-card__title { font-size: var(--t-md); font-weight: 600; color: var(--c-text-1); }
.fu-card__date { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.fu-card__status { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-full); }
.fu-card__status.pending { background: var(--c-warning-soft, #fff5e6); color: var(--c-warning); }
.fu-card__status.done { background: var(--c-success-soft, #eaf8ef); color: var(--c-success); }
.fu-card__status.skipped { background: var(--c-bg-page); color: var(--c-text-3); }
.fu-card__body { padding: 0 var(--s-md) var(--s-md); border-top: 1px solid var(--c-border); }
.fu-field { margin-top: var(--s-md); }
.fu-field label { display: block; font-size: var(--t-sm); color: var(--c-text-2); margin-bottom: 6px; }
.fu-stars { display: flex; align-items: center; gap: 4px; }
.fu-star {
  border: none; background: none; cursor: pointer;
  color: var(--c-text-4); padding: 0; line-height: 1; display: inline-flex; align-items: center;
}
.fu-star.active { color: #f5a623; }
.fu-stars__label { margin-left: 8px; font-size: var(--t-sm); color: var(--c-text-2); }
.fu-field textarea {
  width: 100%; border: 1px solid var(--c-border); border-radius: var(--r-sm);
  padding: var(--s-sm); font-size: var(--t-sm); resize: none; font-family: inherit;
  box-sizing: border-box;
}
.fu-field textarea:focus { outline: none; border-color: var(--c-brand); }
.fu-actions { display: flex; gap: var(--s-md); margin-top: var(--s-md); }
.fu-btn { flex: 1; height: 40px; border-radius: var(--r-sm); font-size: var(--t-sm); cursor: pointer; border: none; }
.fu-btn--ghost { background: var(--c-bg-page); color: var(--c-text-2); }
.fu-btn--primary { background: var(--c-brand); color: #fff; }
.fu-tip { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 8px; text-align: center; }
.fu-result { display: flex; justify-content: space-between; font-size: var(--t-sm); color: var(--c-text-2); padding-top: var(--s-md); }
.fu-summary { margin-top: 8px; font-size: var(--t-sm); color: var(--c-text-1); background: var(--c-bg-page); padding: var(--s-sm); border-radius: var(--r-sm); }
</style>
