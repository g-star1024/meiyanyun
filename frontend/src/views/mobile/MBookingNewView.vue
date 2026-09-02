<script setup lang="ts">
/* C 端新建预约 /m/booking/new — 选项目/门店/日期/时段，提交同步 B 端 M4-01 */
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppointmentStore } from '@/stores/appointment'
import { usePointsStore } from '@/stores/points'
import { usePricelistStore } from '@/stores/pricelist'
import CIcon from '@/components/CIcon.vue'

const route = useRoute()
const router = useRouter()
const appt = useAppointmentStore()
const points = usePointsStore()
const pricelist = usePricelistStore()
onMounted(() => { appt.seed(); points.seed(); pricelist.seed() })

// 项目选项取自价目表在售项目
const projectOptions = computed(() => pricelist.active.map((p) => p.name))
const stores = ['上海静安旗舰店', '上海徐汇万象城店', '上海浦东陆家嘴店']
const timeSlots = ['10:00', '11:00', '13:00', '14:00', '15:00', '16:00', '17:00', '18:00']

const form = ref({
  project: (route.query.project as string) || '',
  store: stores[0],
  date: new Date().toISOString().slice(0, 10),
  slot: '',
  note: '',
})
const done = ref(false)
const newId = ref('')

function submit() {
  if (!form.value.project) { window.alert('请选择预约项目'); return }
  if (!form.value.slot) { window.alert('请选择预约时段'); return }
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
    window.alert('预约提交失败，请稍后重试')
  }
}
</script>

<template>
  <div class="nb">
    <div v-if="done" class="success">
      <div class="success__icon"><CIcon name="check" :size="34" /></div>
      <div class="success__title">预约提交成功</div>
      <div class="success__sub">预约号 {{ newId }}，门店确认后将通知您</div>
      <div class="success__tip">已实时同步至 B 端预约看板，咨询师可在工作台为您确认排期。</div>
      <button class="success__btn" @click="router.replace('/m/booking')">查看我的预约</button>
    </div>

    <template v-else>
      <div class="card">
        <div class="field">
          <label>预约项目</label>
          <select v-model="form.project" class="select">
            <option value="" disabled>请选择项目</option>
            <option v-for="p in projectOptions" :key="p" :value="p">{{ p }}</option>
          </select>
        </div>
        <div class="field">
          <label>预约门店</label>
          <div class="store-chips">
            <button v-for="s in stores" :key="s" class="chip" :class="{ on: form.store === s }" @click="form.store = s">{{ s }}</button>
          </div>
        </div>
        <div class="field">
          <label>预约日期</label>
          <input type="date" v-model="form.date" class="input" />
        </div>
        <div class="field">
          <label>预约时段</label>
          <div class="slots">
            <button v-for="t in timeSlots" :key="t" class="slot" :class="{ on: form.slot === t }" @click="form.slot = t">{{ t }}</button>
          </div>
        </div>
        <div class="field">
          <label>备注（选填）</label>
          <textarea v-model="form.note" class="textarea" rows="2" placeholder="如有特殊需求请备注" />
        </div>
      </div>

      <div class="contact card">
        <div class="contact__row"><span>预约人</span><b>{{ points.member.name }}</b></div>
        <div class="contact__row"><span>手机号</span><b>{{ points.member.phone }}</b></div>
      </div>

      <div class="bottom-space"></div>
      <div class="bar">
        <button class="bar__btn" @click="submit">提交预约</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.nb { padding-bottom: 0; }
.card { background: #fff; border-radius: 14px; margin: 12px; padding: 16px; }
.field { margin-bottom: 18px; }
.field:last-child { margin-bottom: 0; }
.field label { display: block; font-size: 14px; font-weight: 600; color: #333; margin-bottom: 10px; }
.select, .input, .textarea { width: 100%; padding: 11px 12px; border: 1px solid #ececec; border-radius: 10px; font-size: 14px; color: #333; background: #fafafa; box-sizing: border-box; font-family: inherit; }
.textarea { resize: none; }
.store-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip { padding: 8px 14px; border: 1px solid #ececec; background: #fff; border-radius: 16px; font-size: 13px; color: #666; cursor: pointer; }
.chip.on { background: #fff0f5; border-color: #ff6b9e; color: #ff4d6d; font-weight: 600; }
.slots { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.slot { padding: 10px 0; border: 1px solid #ececec; background: #fff; border-radius: 10px; font-size: 13px; color: #666; cursor: pointer; }
.slot.on { background: linear-gradient(135deg,#FFBFF0,#FF6B9E); border-color: transparent; color: #fff; font-weight: 600; }
.contact { padding: 4px 16px; }
.contact__row { display: flex; justify-content: space-between; padding: 12px 0; font-size: 14px; color: #999; border-bottom: 0.5px solid #f2f2f2; }
.contact__row:last-child { border-bottom: none; }
.contact__row b { color: #333; font-weight: 600; }
.bottom-space { height: 84px; }
.bar { position: fixed; bottom: 0; left: 50%; transform: translateX(-50%); width: 100%; max-width: 390px; background: #fff; border-top: 0.5px solid #eee; padding: 8px 16px calc(8px + env(safe-area-inset-bottom)); box-sizing: border-box; z-index: 20; }
.bar__btn { width: 100%; height: 46px; border: none; border-radius: 23px; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; font-size: 16px; font-weight: 700; cursor: pointer; }
.success { text-align: center; padding: 70px 24px; }
.success__icon { width: 72px; height: 72px; margin: 0 auto; border-radius: 50%; background: #eaf8ef; color: #52c41a; display: flex; align-items: center; justify-content: center; }
.success__title { font-size: 20px; font-weight: 700; color: #1a1a1a; margin-top: 16px; }
.success__sub { font-size: 14px; color: #666; margin-top: 10px; }
.success__tip { font-size: 12px; color: #aaa; margin-top: 16px; line-height: 1.6; background: #fafafa; border-radius: 10px; padding: 12px; }
.success__btn { width: 100%; height: 46px; margin-top: 28px; border: none; border-radius: 23px; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; font-size: 16px; font-weight: 700; cursor: pointer; }
</style>
