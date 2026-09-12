<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import CDrawer from '@/components/CDrawer.vue'
import CButton from '@/components/CButton.vue'
import CTextarea from '@/components/CTextarea.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { applyApproval } from '@/api/ai'

const props = defineProps<{
  show: boolean
  approvalType: 'MODEL' | 'BINDING'
  targetId: number | null
  targetLabel: string
}>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'applied'): void
}>()

const toast = useToast()
const content = ref('')
const saving = ref(false)

const drawerTitle = computed(() => (props.approvalType === 'MODEL' ? '提交模型上架申请' : '提交功能绑定上架申请'))
const targetTitle = computed(() => (props.approvalType === 'MODEL' ? '申请模型' : '申请功能'))
const placeholder = computed(() =>
  props.approvalType === 'MODEL'
    ? '请说明模型用途、联调与连通性测试结论、风险评估等，便于治理岗审批（必填，512 字内）'
    : '请说明功能业务价值、灰度门店范围、prompt 与参数调优结论等，便于治理岗审批（必填，512 字内）',
)
const contentError = computed(() => content.value.trim().length === 0 || content.value.trim().length > 512)
const remain = computed(() => 512 - content.value.length)

watch(
  () => props.show,
  (v) => {
    if (v) content.value = ''
  },
)

async function submit() {
  if (content.value.trim().length === 0) {
    toast.warning('请填写申请说明')
    return
  }
  if (content.value.trim().length > 512) {
    toast.warning('申请说明不能超过 512 字')
    return
  }
  if (props.targetId == null) {
    toast.warning('申请目标缺失')
    return
  }
  saving.value = true
  try {
    await applyApproval({
      approvalType: props.approvalType,
      targetId: props.targetId,
      content: content.value.trim(),
    })
    toast.success('申请已提交，请等待治理岗审批')
    emit('update:show', false)
    emit('applied')
  } catch (e) {
    toast.error('申请提交失败：' + errMsg(e))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <CDrawer :show="show" size="md" :title="drawerTitle" @update:show="emit('update:show', $event)">
    <div class="apply-form">
      <div class="apply-row">
        <label class="fld-label">{{ targetTitle }}</label>
        <div class="apply-target">{{ targetLabel }}</div>
      </div>
      <div class="apply-row">
        <CTextarea
          v-model="content"
          label="申请说明"
          :placeholder="placeholder"
          :rows="6"
          :error="content.trim().length > 512"
        />
        <div class="apply-count" :class="{ over: remain < 0 }">{{ remain }} 字</div>
      </div>
    </div>
    <template #footer>
      <CButton variant="secondary" @click="emit('update:show', false)">取消</CButton>
      <CButton variant="primary" :disabled="saving || contentError" @click="submit">
        {{ saving ? '提交中…' : '提交申请' }}
      </CButton>
    </template>
  </CDrawer>
</template>

<style scoped>
.apply-form { display: flex; flex-direction: column; gap: var(--s-md); }
.apply-row { display: flex; flex-direction: column; gap: 6px; }
.fld-label { font-size: 13px; color: var(--c-text); line-height: 18px; }
.apply-target {
  padding: 10px 12px; border-radius: var(--r-md); background: #f5f6fa;
  font-size: 13px; color: var(--c-text-2); line-height: 20px;
}
.apply-count { align-self: flex-end; font-size: 12px; color: var(--c-text-3); }
.apply-count.over { color: var(--c-danger-fg); }
</style>
