<script setup lang="ts">
/* 新建预约 pages/booking/new — 选项目/门店/日期/时段，提交同步 B 端 M4-01 */
import { ref, computed } from 'vue'
import { onShow, onLoad } from '@dcloudio/uni-app'
import { useAppointmentStore } from '@/stores/appointment'
import { useMemberStore } from '@/stores/member'
import { usePricelistStore } from '@/stores/pricelist'
import { redirectTo, toast } from '@/utils/nav'

const appt = useAppointmentStore()
const points = useMemberStore()
const pricelist = usePricelistStore()

// 项目选项取自价目表在售项目
const projectOptions = computed(() => pricelist.active.map((p) => p.name))
const stores = ['上海静安旗舰店', '上海徐汇万象城店', '上海浦东陆家嘴店']
const timeSlots = ['10:00', '11:00', '13:00', '14:00', '15:00', '16:00', '17:00', '18:00']

const form = ref({
  project: '',
  store: stores[0],
  date: new Date().toISOString().slice(0, 10),
  slot: '',
  note: '',
})
const done = ref(false)
const newId = ref('')

onLoad((options) => {
  form.value.project = options?.project ? decodeURIComponent(options.project) : ''
})
onShow(() => {
  appt.seed()
  points.seed()
  pricelist.seed()
})

function onProjectPick(e: any) {
  form.value.project = projectOptions.value[e.detail.value]
}
function onDatePick(e: any) {
  form.value.date = e.detail.value
}

function submit() {
  if (!form.value.project) {
    toast('请选择预约项目')
    return
  }
  if (!form.value.slot) {
    toast('请选择预约时段')
    return
  }
  const r = appt.create({
    customerId: points.member.memberId,
    timeSlot: `${form.value.date}T${form.value.slot}:00`,
    project: form.value.project,
    source: 'C_MINIAPP',
    note: form.value.note || 'C 端小程序预约',
  })
  if (r) {
    newId.value = r.id
    done.value = true
  } else {
    toast('预约提交失败，请稍后重试')
  }
}
</script>

<template>
  <view class="nb">
    <MNavbar title="新建预约" />
    <view v-if="done" class="success">
      <view class="success__icon"><uni-icons type="checkmarkempty" size="52" color="#52c41a" /></view>
      <view class="success__title">预约提交成功</view>
      <view class="success__sub">预约号 {{ newId }}，门店确认后将通知您</view>
      <view class="success__tip">已实时同步至 B 端预约看板，咨询师可在工作台为您确认排期。</view>
      <view class="success__btn" @click="redirectTo('/pages/booking/list')">查看我的预约</view>
    </view>

    <template v-else>
      <view class="card">
        <view class="field">
          <view class="field__label">预约项目</view>
          <picker mode="selector" :range="projectOptions" @change="onProjectPick">
            <view class="select" :class="{ ph: !form.project }">
              {{ form.project || '请选择项目' }}
            </view>
          </picker>
        </view>
        <view class="field">
          <view class="field__label">预约门店</view>
          <view class="store-chips">
            <view
              v-for="s in stores"
              :key="s"
              class="chip"
              :class="{ on: form.store === s }"
              @click="form.store = s"
            >{{ s }}</view>
          </view>
        </view>
        <view class="field">
          <view class="field__label">预约日期</view>
          <picker mode="date" :value="form.date" @change="onDatePick">
            <view class="select">{{ form.date }}</view>
          </picker>
        </view>
        <view class="field">
          <view class="field__label">预约时段</view>
          <view class="slots">
            <view
              v-for="t in timeSlots"
              :key="t"
              class="slot"
              :class="{ on: form.slot === t }"
              @click="form.slot = t"
            >{{ t }}</view>
          </view>
        </view>
        <view class="field">
          <view class="field__label">备注（选填）</view>
          <textarea v-model="form.note" class="textarea" placeholder="如有特殊需求请备注" />
        </view>
      </view>

      <view class="contact card">
        <view class="contact__row">
          <text>预约人</text>
          <text class="contact__val">{{ points.member.name }}</text>
        </view>
        <view class="contact__row">
          <text>手机号</text>
          <text class="contact__val">{{ points.member.phone }}</text>
        </view>
      </view>

      <view class="bottom-space"></view>
      <view class="bar">
        <view class="bar__btn" @click="submit">提交预约</view>
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.nb {
  padding-bottom: 0;
}
.card {
  background: #fff;
  border-radius: 28rpx;
  margin: 24rpx;
  padding: 32rpx;
}
.field {
  margin-bottom: 36rpx;
}
.field:last-child {
  margin-bottom: 0;
}
.field__label {
  font-size: 28rpx;
  font-weight: 600;
  color: #333;
  margin-bottom: 20rpx;
}
.select {
  width: 100%;
  padding: 22rpx 24rpx;
  border: 1rpx solid #ececec;
  border-radius: 20rpx;
  font-size: 28rpx;
  color: #333;
  background: #fafafa;
  box-sizing: border-box;
}
.select.ph {
  color: #999;
}
.textarea {
  width: 100%;
  height: 140rpx;
  padding: 22rpx 24rpx;
  border: 1rpx solid #ececec;
  border-radius: 20rpx;
  font-size: 28rpx;
  color: #333;
  background: #fafafa;
  box-sizing: border-box;
}
.store-chips {
  display: flex;
  flex-wrap: wrap;
}
.chip {
  padding: 16rpx 28rpx;
  border: 1rpx solid #ececec;
  background: #fff;
  border-radius: 32rpx;
  font-size: 26rpx;
  color: #666;
  margin: 0 16rpx 16rpx 0;
}
.chip.on {
  background: #fff0f5;
  border-color: #ff6b9e;
  color: #ff4d6d;
  font-weight: 600;
}
.slots {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16rpx;
}
.slot {
  padding: 20rpx 0;
  border: 1rpx solid #ececec;
  background: #fff;
  border-radius: 20rpx;
  font-size: 26rpx;
  color: #666;
  text-align: center;
}
.slot.on {
  background: linear-gradient(135deg, #ffbff0, #ff6b9e);
  border-color: transparent;
  color: #fff;
  font-weight: 600;
}
.contact {
  padding: 8rpx 32rpx;
}
.contact__row {
  display: flex;
  justify-content: space-between;
  padding: 24rpx 0;
  font-size: 28rpx;
  color: #999;
  border-bottom: 1rpx solid #f2f2f2;
}
.contact__row:last-child {
  border-bottom: none;
}
.contact__val {
  color: #333;
  font-weight: 600;
}
.bottom-space {
  height: 168rpx;
}
.bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  width: 100%;
  background: #fff;
  border-top: 1rpx solid #eee;
  padding: 16rpx 32rpx calc(16rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  z-index: 20;
}
.bar__btn {
  height: 92rpx;
  line-height: 92rpx;
  text-align: center;
  border-radius: 46rpx;
  background: linear-gradient(135deg, #ffbff0, #ff6b9e);
  color: #fff;
  font-size: 32rpx;
  font-weight: 700;
}
.success {
  text-align: center;
  padding: 140rpx 48rpx;
}
.success__icon {
  width: 160rpx;
  height: 160rpx;
  border-radius: 40rpx;
  background: #eaf8ef;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto;
}
.success__title {
  font-size: 40rpx;
  font-weight: 700;
  color: #1a1a1a;
  margin-top: 32rpx;
}
.success__sub {
  font-size: 28rpx;
  color: #666;
  margin-top: 20rpx;
}
.success__tip {
  font-size: 24rpx;
  color: #aaa;
  margin-top: 32rpx;
  line-height: 1.6;
  background: #fafafa;
  border-radius: 20rpx;
  padding: 24rpx;
}
.success__btn {
  height: 92rpx;
  line-height: 92rpx;
  margin-top: 56rpx;
  border-radius: 46rpx;
  background: linear-gradient(135deg, #ffbff0, #ff6b9e);
  color: #fff;
  font-size: 32rpx;
  font-weight: 700;
}
</style>
