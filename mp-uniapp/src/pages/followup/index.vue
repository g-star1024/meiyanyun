<script setup lang="ts">
/* C 端术后回访 pages/followup/index — 接 M4-11/followup（联动 5） */
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useFollowupStore } from '@/stores/followup'
import { useMemberStore } from '@/stores/member'
import { toast } from '@/utils/nav'

const followup = useFollowupStore()
const memberStore = useMemberStore()

onShow(() => {
  followup.seed()
  memberStore.seed()
})

const member = computed(() => memberStore.member)
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

function submit(f: (typeof followup.followups)[number]) {
  const ok = followup.submitByCustomer(f.id, {
    satisfaction: satisfaction.value,
    note: feedback.value || undefined,
    adverseReaction: hasAdverse.value,
    adverseNote: hasAdverse.value ? adverseNote.value : undefined,
  })
  if (ok) {
    toast(`回访已提交，满意度 ${satisfaction.value} 星，感谢您的反馈！`)
    activeId.value = null
  }
}

function fmtDate(s: string) { return s ? s.slice(0, 16).replace('T', ' ') : '' }

const satLabels = ['非常不满意', '不满意', '一般', '满意', '非常满意']
</script>

<template>
  <view class="followup">
    <MNavbar title="术后回访" />
    <view class="followup__header">
      <view class="followup__title">术后回访</view>
      <view class="followup__sub">我们关心您的每一次恢复，欢迎填写回访反馈</view>
    </view>

    <view v-if="myFollowups.length === 0" class="followup__empty">
      <view class="followup__empty-icon"><uni-icons type="images" size="56" color="#e3b6c8" /></view>
      <view>暂无待回访项目</view>
    </view>

    <view class="followup__list">
      <view
        v-for="f in myFollowups"
        :key="f.id"
        class="fu-card"
        :class="{ 'fu-card--done': f.status === 'DONE', 'fu-card--active': activeId === f.id }"
      >
        <view class="fu-card__head" @click="toggle(f.id)">
          <view class="fu-card__info">
            <view class="fu-card__title">{{ f.project }}</view>
            <view class="fu-card__date">计划日期：{{ f.planDate ? f.planDate.slice(0, 10) : '' }}</view>
          </view>
          <text class="fu-card__status" :class="f.status.toLowerCase()">
            {{ f.status === 'PENDING' ? '待回访' : f.status === 'DONE' ? '已完成' : '已跳过' }}
          </text>
        </view>

        <!-- 展开：回填表单 -->
        <view v-if="activeId === f.id && f.status === 'PENDING'" class="fu-card__body">
          <view class="fu-field">
            <text class="fu-field__label">恢复情况</text>
            <view class="fu-stars">
              <uni-icons
                v-for="n in 5"
                :key="n"
                class="fu-star"
                :type="n <= satisfaction ? 'star-filled' : 'star'"
                size="26"
                :color="n <= satisfaction ? '#f5a623' : '#ddd'"
                @click="satisfaction = n"
              />
              <text class="fu-stars__label">{{ satLabels[satisfaction - 1] }}</text>
            </view>
          </view>
          <view class="fu-field">
            <text class="fu-field__label">反馈备注（选填）</text>
            <textarea
              v-model="feedback"
              class="fu-textarea"
              placeholder="请描述恢复情况、有无不适或建议…"
            ></textarea>
          </view>
          <view class="fu-field">
            <view class="fu-check" @click="hasAdverse = !hasAdverse">
              <view class="fu-check__box" :class="{ checked: hasAdverse }">
                <uni-icons v-if="hasAdverse" type="checkmarkempty" size="14" color="#fff" />
              </view>
              <text class="fu-check__label">有不良反应（红肿/过敏/疼痛等）</text>
            </view>
            <textarea
              v-if="hasAdverse"
              v-model="adverseNote"
              class="fu-textarea fu-textarea--mt"
              placeholder="请描述不良反应，我们将安排医生跟进…"
            ></textarea>
          </view>
          <view class="fu-actions">
            <view class="fu-btn fu-btn--ghost" @click="activeId = null">取消</view>
            <view class="fu-btn fu-btn--primary" @click="submit(f)">提交回访</view>
          </view>
          <view class="fu-tip">提交后同步至 B 端 M4-11 术后回访记录，咨询师将跟进您的恢复</view>
        </view>

        <!-- 已完成：展示结果 -->
        <view v-else-if="f.status === 'DONE' && activeId === f.id" class="fu-card__body">
          <view class="fu-result">
            <text>满意度：{{ f.satisfaction || 5 }} 星</text>
            <text>完成时间：{{ fmtDate(f.doneAt || '') }}</text>
          </view>
          <view v-if="f.note" class="fu-summary">{{ f.note }}</view>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.followup { padding: 0 0 48rpx; }
.followup__header {
  background: linear-gradient(135deg, #2ed4bf, #ff6b9e);
  padding: 32rpx; color: #fff;
}
.followup__title { font-size: 32rpx; font-weight: 700; }
.followup__sub { font-size: 24rpx; opacity: .85; margin-top: 8rpx; }
.followup__empty { text-align: center; padding: 128rpx 40rpx; color: #999; font-size: 28rpx; }
.followup__empty-icon { margin-bottom: 24rpx; }
.followup__list { padding: 24rpx; display: flex; flex-direction: column; }
.fu-card {
  background: #fff; border-radius: 20rpx; overflow: hidden;
  box-shadow: 0 2rpx 8rpx rgba(0,0,0,.06); margin-bottom: 24rpx;
}
.fu-card--done { opacity: .8; }
.fu-card__head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 24rpx;
}
.fu-card__title { font-size: 28rpx; font-weight: 600; color: #1a1a1a; }
.fu-card__date { font-size: 20rpx; color: #999; margin-top: 8rpx; }
.fu-card__status { font-size: 20rpx; padding: 4rpx 16rpx; border-radius: 999rpx; }
.fu-card__status.pending { background: #fff5e6; color: #fa8c16; }
.fu-card__status.done { background: #eaf8ef; color: #52c41a; }
.fu-card__status.skipped { background: #f6f6f8; color: #999; }
.fu-card__body { padding: 0 24rpx 24rpx; border-top: 1rpx solid #eee; }
.fu-field { margin-top: 24rpx; }
.fu-field__label { display: block; font-size: 24rpx; color: #666; margin-bottom: 12rpx; }
.fu-stars { display: flex; align-items: center; }
.fu-star { margin-right: 8rpx; }
.fu-stars__label { margin-left: 16rpx; font-size: 24rpx; color: #666; }
.fu-textarea {
  width: 100%; height: 120rpx; border: 1rpx solid #eee; border-radius: 12rpx;
  padding: 16rpx; font-size: 24rpx; box-sizing: border-box;
}
.fu-textarea--mt { margin-top: 16rpx; }
.fu-check { display: flex; align-items: center; }
.fu-check__box {
  width: 36rpx; height: 36rpx; border: 1rpx solid #ccc; border-radius: 8rpx;
  display: flex; align-items: center; justify-content: center;
  font-size: 24rpx; color: #fff; margin-right: 12rpx; box-sizing: border-box;
}
.fu-check__box.checked { background: #ff6b9e; border-color: #ff6b9e; }
.fu-check__label { font-size: 24rpx; color: #666; }
.fu-actions { display: flex; margin-top: 24rpx; }
.fu-btn { flex: 1; height: 80rpx; line-height: 80rpx; text-align: center; border-radius: 12rpx; font-size: 24rpx; margin: 0 12rpx; box-sizing: border-box; }
.fu-btn--ghost { background: #f6f6f8; color: #666; }
.fu-btn--primary { background: #ff6b9e; color: #fff; }
.fu-tip { font-size: 20rpx; color: #999; margin-top: 16rpx; text-align: center; }
.fu-result { display: flex; justify-content: space-between; font-size: 24rpx; color: #666; padding-top: 24rpx; }
.fu-summary { margin-top: 16rpx; font-size: 24rpx; color: #1a1a1a; background: #f6f6f8; padding: 16rpx; border-radius: 12rpx; }
</style>
