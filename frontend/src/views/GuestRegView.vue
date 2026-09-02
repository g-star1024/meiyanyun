<script setup lang="ts">
/* ============================================================
 * M4-06 客情登记（/guest-reg）
 * 新客建档：基础信息 + 来源渠道 + 皮肤/过敏史 + 咨询意向。
 * 输入手机号时实时撞单提示（customer.search 同号），不自动合并。
 * 权限：customer:create（路由守卫 + 提交按钮 v-perm 双保险）。
 * ============================================================ */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useCustomerStore } from '@/stores/customer'
import { useArrivalStore } from '@/stores/arrival'
import { useToast } from '@/composables/useToast'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CChoiceChip from '@/components/CChoiceChip.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import type { Customer } from '@/types/domain'

const router = useRouter()
const customer = useCustomerStore()
const arrival = useArrivalStore()
const toast = useToast()

/* ---------- 基础信息 ---------- */
const name = ref('')
const phone = ref('')
const gender = ref('女')
const age = ref('')
const channel = ref<'WALK_IN' | 'ONLINE_APPT' | 'REFERRAL' | 'MARKETING'>('WALK_IN')
const referrerPhone = ref('')

const genderOptions = [
  { label: '女', value: '女' },
  { label: '男', value: '男' },
  { label: '其他', value: '其他' },
]
const channelOptions = [
  { label: '自然到店', value: 'WALK_IN' },
  { label: '线上预约', value: 'ONLINE_APPT' },
  { label: '转介绍', value: 'REFERRAL' },
  { label: '营销活动', value: 'MARKETING' },
]

/* ---------- 皮肤/过敏史（医疗安全基线） ---------- */
const skinType = ref('')
const concerns = ref<string[]>([])
const allergyNone = ref(false)
const allergies = ref<string[]>([])
const allergyNote = ref('')

const skinTypeOptions = [
  { label: '干性', value: '干性' },
  { label: '油性', value: '油性' },
  { label: '混合性', value: '混合性' },
  { label: '敏感性', value: '敏感性' },
  { label: '中性', value: '中性' },
]
const concernOptions = ['痤疮', '色斑', '抗衰', '敏感泛红', '毛孔粗大', '补水', '除皱', '形体']
const allergyOptions = ['药物', '麻醉药', '食物', '金属', '乳胶', '其他']

const skinOptions = skinTypeOptions.map((o) => ({ label: o.label, value: o.value }))

function toggleConcern(label: string) {
  const set = new Set(concerns.value)
  if (set.has(label)) set.delete(label); else set.add(label)
  concerns.value = Array.from(set)
}
function toggleAllergy(label: string) {
  if (label === '无过敏史') {
    allergyNone.value = !allergyNone.value
    if (allergyNone.value) allergies.value = []
    return
  }
  const set = new Set(allergies.value)
  if (set.has(label)) set.delete(label); else set.add(label)
  allergies.value = Array.from(set)
  if (allergies.value.length) allergyNone.value = false
}
function onAllergyNone(v: boolean) {
  allergyNone.value = v
  if (v) allergies.value = []
}

/* ---------- 咨询意向 ---------- */
const intentProjects = ref<string[]>([])
const intentLevel = ref('')
const budget = ref('')
const intentNote = ref('')

const intentOptions = ['光子嫩肤', '热玛吉', '水光针', '玻尿酸', '瘦脸针', '果酸焕肤', '双眼皮', '皮肤检测']
const intentLevelOptions = [
  { label: '高（近期成交）', value: '高' },
  { label: '中（需跟进）', value: '中' },
  { label: '低（潜在）', value: '低' },
]
const budgetOptions = [
  { label: '3千以下', value: '3千以下' },
  { label: '3千-1万', value: '3千-1万' },
  { label: '1万-3万', value: '1万-3万' },
  { label: '3万以上', value: '3万以上' },
]
function toggleIntent(label: string) {
  const set = new Set(intentProjects.value)
  if (set.has(label)) set.delete(label); else set.add(label)
  intentProjects.value = Array.from(set)
}

/* ---------- 撞单实时提示（同手机号） ---------- */
const duplicate = computed<Customer | undefined>(() => {
  const p = phone.value.trim()
  if (p.length < 7) return undefined
  return customer.customers.find((c) => !c.masterId && c.phone === p)
})
const showDupWarning = computed(() => !!duplicate.value)

/* ---------- 提交 ---------- */
const tags = computed(() => {
  const t: string[] = ['新客']
  if (concerns.value.length) t.push(...concerns.value)
  if (intentLevel.value === '高') t.push('高意向')
  return t
})

const canSubmit = computed(
  () => name.value.trim() && /^1\d{10}$/.test(phone.value.trim()) && !duplicate.value,
)

/** 过敏史落库为结构化字符串，开方/开单禁忌初筛读取 */
const allergyRecords = computed(() => {
  if (allergyNone.value) return []
  const list = allergies.value.map((a) => `${a}过敏史`)
  if (allergyNote.value.trim()) list.push(allergyNote.value.trim())
  return list
})

function submit() {
  if (!canSubmit.value) return
  const c = customer.create({
    name: name.value.trim(),
    phone: phone.value.trim(),
    phoneMask: phone.value.trim().replace(/(\d{3})\d{4}(\d{4})/, '$1****$2'),
    channel: channel.value,
    level: intentLevel.value === '高' ? 'A' : intentLevel.value === '中' ? 'B' : 'NEW',
    tags: tags.value,
    allergies: allergyRecords.value,
  })
  // 建档即到店登记：直接写入接待台候诊队列（分诊列表数据源），跳转后无需再手工登记
  arrival.checkIn({ customerId: c.id, channel: channel.value, note: '客情登记建档后自动到店' })
  toast.success(`已为「${c.name}」完成建档并登记到店，可在接待台候诊队列直接分诊`)
  router.push({ path: '/reception', query: { newId: c.id } })
}

function goDup() {
  if (duplicate.value) router.push('/customers')
}
</script>

<template>
  <div class="greg">
    <div class="greg__main">
      <!-- 基础信息 -->
      <CCard title="基础信息" class="form-card">
        <div class="grid-2">
          <CInput v-model="name" label="客户姓名" placeholder="请输入真实姓名" />
          <CInput v-model="phone" label="手机号" placeholder="11 位手机号" :error="showDupWarning" />
        </div>
        <!-- 撞单提示 -->
        <div v-if="showDupWarning" class="dup">
          <CIcon name="alert" :size="18" class="dup__icon" />
          <div class="dup__body">
            <div class="dup__title">检测到疑似重复客户：{{ duplicate?.name }}（{{ duplicate?.phoneMask }}）</div>
            <div class="dup__desc">为避免撞单，请先核对是否为同一人；确认非同一人可继续建档，系统将记录疑似关联待人工合并。</div>
          </div>
          <CButton variant="ghost" size="sm" @click="goDup">查看档案</CButton>
        </div>

        <div class="grid-2">
          <div class="fld">
            <label class="fld-label">性别</label>
            <CSelect v-model="gender" :options="genderOptions" width="100%" />
          </div>
          <CInput v-model="age" label="年龄" placeholder="选填" type="number" />
        </div>
        <div class="fld">
          <label class="fld-label">来源渠道</label>
          <CSelect v-model="channel" :options="channelOptions" width="100%" />
        </div>
        <div v-if="channel === 'REFERRAL'" class="fld">
          <CInput v-model="referrerPhone" label="介绍人手机号" placeholder="选填，用于转介绍归属" />
        </div>
      </CCard>

      <!-- 皮肤与过敏史 -->
      <CCard title="皮肤与过敏史" class="form-card">
        <div class="fld">
          <label class="fld-label">肤质类型</label>
          <CSelect v-model="skinType" :options="skinOptions" width="100%" placeholder="请选择" />
        </div>
        <div class="chips">
          <label class="fld-label">主要诉求（可多选）</label>
          <div class="chips__row">
            <CChoiceChip
              v-for="c in concernOptions"
              :key="c"
              type="checkbox"
              :label="c"
              :model-value="concerns.includes(c)"
              @update:model-value="toggleConcern(c)"
            />
          </div>
        </div>
        <div class="chips">
          <label class="fld-label">过敏史（医疗安全必填确认）</label>
          <div class="chips__row">
            <CChoiceChip
              type="checkbox"
              label="无过敏史"
              :model-value="allergyNone"
              cyan
              @update:model-value="(v: boolean | string | number) => onAllergyNone(!!v)"
            />
            <CChoiceChip
              v-for="a in allergyOptions"
              :key="a"
              type="checkbox"
              :label="a"
              :model-value="allergies.includes(a)"
              solid
              @update:model-value="toggleAllergy(a)"
            />
          </div>
        </div>
        <CTextarea v-model="allergyNote" label="过敏/病史补充" placeholder="如具体药物名称、既往病史、用药情况等（选填）" :rows="2" />
      </CCard>

      <!-- 咨询意向 -->
      <CCard title="咨询意向" class="form-card">
        <div class="chips">
          <label class="fld-label">意向项目（可多选）</label>
          <div class="chips__row">
            <CChoiceChip
              v-for="p in intentOptions"
              :key="p"
              type="checkbox"
              :label="p"
              :model-value="intentProjects.includes(p)"
              @update:model-value="toggleIntent(p)"
            />
          </div>
        </div>
        <div class="grid-2">
          <div class="fld">
            <label class="fld-label">意向程度</label>
            <CSelect v-model="intentLevel" :options="intentLevelOptions" width="100%" placeholder="请选择" />
          </div>
          <div class="fld">
            <label class="fld-label">预算区间</label>
            <CSelect v-model="budget" :options="budgetOptions" width="100%" placeholder="请选择" />
          </div>
        </div>
        <CTextarea v-model="intentNote" label="沟通要点" placeholder="客户关注的问题、顾虑、约定跟进事项等（选填）" :rows="3" />
      </CCard>

      <!-- 底部操作 -->
      <div class="footer">
        <CButton variant="ghost" @click="router.back()">取消</CButton>
        <CButton
          v-perm.disable="'customer:create'"
          variant="primary"
          :disabled="!canSubmit"
          @click="submit"
        >
          完成建档
        </CButton>
      </div>
    </div>

    <!-- 右侧：建档须知 -->
    <aside class="greg__side">
      <CCard title="建档须知" :header-border="false">
        <ul class="tips">
          <li><CIcon name="check" :size="14" class="tips__ok" /> 手机号用于撞单识别与会员唯一档案，<b>请务必准确</b>。</li>
          <li><CIcon name="alert" :size="14" class="tips__warn" /> 过敏史关系医疗安全，阳性项将在开方/核销环节硬阻断提醒。</li>
          <li><CIcon name="shield" :size="14" class="tips__info" /> 同手机号建档会产生疑似关联，需 <code>customer:merge</code> 权限人工合并。</li>
        </ul>
        <div class="tag-preview">
          <div class="tag-preview__label">将自动打标签</div>
          <div class="tag-preview__row">
            <CStatusPill v-for="t in tags" :key="t" status="default">{{ t }}</CStatusPill>
          </div>
        </div>
      </CCard>
    </aside>
  </div>
</template>

<style scoped>
.greg { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: var(--s-md); align-items: start; }
.greg__main { display: flex; flex-direction: column; gap: var(--s-md); min-width: 0; }

.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.fld { display: flex; flex-direction: column; gap: 6px; }
.fld-label { font-size: var(--t-sm); color: var(--c-text); line-height: 18px; }

/* 表单卡：表单项之间统一垂直间距 */
.form-card :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); }
.form-card :deep(.card__body) .dup { margin-top: 0; }

/* 撞单提示 */
.dup { display: flex; align-items: flex-start; gap: var(--s-sm); padding: var(--s-sm); background: var(--c-danger-bg); border: 1px solid var(--c-danger-fg); border-radius: var(--r-md); margin-top: var(--s-sm); }
.dup__icon { color: var(--c-danger-fg); flex-shrink: 0; margin-top: 1px; }
.dup__body { flex: 1; min-width: 0; }
.dup__title { font-size: var(--t-sm); font-weight: 700; color: var(--c-danger-fg); }
.dup__desc { font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.5; margin-top: 2px; }

/* 多选胶囊组 */
.chips { display: flex; flex-direction: column; gap: var(--s-xs); }
.chips__row { display: flex; flex-wrap: wrap; gap: var(--s-xs); }

/* 底部 */
.footer { display: flex; justify-content: flex-end; gap: var(--s-sm); padding: var(--s-sm) 0; }

/* 侧栏 */
.greg__side { position: sticky; top: 0; }
.tips { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--s-sm); }
.tips li { display: flex; align-items: flex-start; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; }
.tips__ok { color: var(--c-teal); flex-shrink: 0; margin-top: 3px; }
.tips__warn { color: var(--c-danger-fg); flex-shrink: 0; margin-top: 3px; }
.tips__info { color: var(--c-blue); flex-shrink: 0; margin-top: 3px; }
.tips code { background: var(--c-disabled-bg); padding: 0 4px; border-radius: 3px; font-size: var(--t-xs); }

.tag-preview { margin-top: var(--s-md); padding-top: var(--s-md); border-top: 1px solid var(--c-border-light); }
.tag-preview__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.tag-preview__row { display: flex; flex-wrap: wrap; gap: var(--s-xxs); }

@media (max-width: 1024px) {
  .greg { grid-template-columns: 1fr; }
  .greg__side { position: static; }
  .grid-2 { grid-template-columns: 1fr; }
}
</style>
