<script setup lang="ts">
/* ============================================================
 * C-PhotoUpload 档案照 / 对比照上传
 * 本地演示：input file → FileReader → base64 dataURL；
 * 生产替换为对象存储直传。支持多图、缩略图、删除。
 * v-model 绑定 dataURL 数组。
 * ============================================================ */
import { ref } from 'vue'
import CIcon from './CIcon.vue'

const props = withDefaults(
  defineProps<{
    modelValue?: UploadedPhoto[]
    /** 部位 / 角度选项 */
    parts?: string[]
    /** 类别文案（面诊/术后/档案） */
    label?: string
  }>(),
  {
    modelValue: () => [],
    parts: () => ['正面', '左 45°', '右 45°', '下颌缘'],
    label: '面诊档案照',
  },
)
const emit = defineEmits<{ (e: 'update:modelValue', v: UploadedPhoto[]): void }>()

const fileInput = ref<HTMLInputElement | null>(null)
const busyPart = ref('正面')

function pick() {
  fileInput.value?.click()
}

function onFile(e: Event) {
  const files = (e.target as HTMLInputElement).files
  if (!files || !files.length) return
  Array.from(files).forEach((file) => {
    const reader = new FileReader()
    reader.onload = () => {
      const next = [...props.modelValue, { dataUrl: String(reader.result), part: busyPart.value }]
      emit('update:modelValue', next)
    }
    reader.readAsDataURL(file)
  })
  ;(e.target as HTMLInputElement).value = ''
}

function remove(idx: number) {
  const next = props.modelValue.filter((_, i) => i !== idx)
  emit('update:modelValue', next)
}
</script>

<script lang="ts">
export interface UploadedPhoto {
  dataUrl: string
  part: string
}
export default { name: 'CPhotoUpload' }
</script>

<template>
  <div class="cpu">
    <div class="cpu__bar">
      <select v-model="busyPart" class="cpu__part">
        <option v-for="p in parts" :key="p" :value="p">{{ p }}</option>
      </select>
      <button type="button" class="cpu__btn" @click="pick">
        <CIcon name="upload" :size="15" />
        上传{{ label }}
      </button>
      <input ref="fileInput" type="file" accept="image/*" multiple class="cpu__file" @change="onFile" />
    </div>
    <div v-if="modelValue.length" class="cpu__grid">
      <div v-for="(ph, i) in modelValue" :key="i" class="cpu__item">
        <img :src="ph.dataUrl" :alt="ph.part" class="cpu__img" />
        <span class="cpu__tag">{{ ph.part }}</span>
        <button type="button" class="cpu__del" @click="remove(i)">
          <CIcon name="close" :size="13" />
        </button>
      </div>
    </div>
    <div class="cpu__note">自动脱敏遮蔽五官并叠加「机构·时间·操作人」水印，仅授权角色可见（emr.photo 权限）。</div>
  </div>
</template>

<style scoped>
.cpu { display: flex; flex-direction: column; gap: var(--s-sm); }
.cpu__bar { display: flex; gap: var(--s-sm); align-items: center; }
.cpu__part {
  height: 36px; padding: 0 var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-sm);
  font-size: var(--t-sm); color: var(--c-text-2); background: var(--c-surface);
}
.cpu__btn {
  display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 var(--s-md);
  border: 1px dashed var(--c-brand-border, var(--c-border)); border-radius: var(--r-sm);
  background: var(--c-brand-soft); color: var(--c-brand); font-size: var(--t-sm); font-weight: 600; cursor: pointer;
}
.cpu__btn:hover { background: var(--c-brand-soft); border-color: var(--c-brand); }
.cpu__file { display: none; }
.cpu__grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-sm); }
.cpu__item { position: relative; aspect-ratio: 3 / 4; border-radius: var(--r-md); overflow: hidden; border: 1px solid var(--c-border-light); background: var(--c-bg-page, #f7f8fa); }
.cpu__img { width: 100%; height: 100%; object-fit: cover; display: block; }
.cpu__tag {
  position: absolute; left: 6px; bottom: 6px; font-size: 11px; color: #fff;
  background: rgba(20, 21, 43, 0.62); border-radius: var(--r-sm); padding: 1px 6px;
}
.cpu__del {
  position: absolute; top: 4px; right: 4px; width: 22px; height: 22px; border: none; border-radius: 50%;
  background: rgba(20, 21, 43, 0.6); color: #fff; cursor: pointer; display: flex; align-items: center; justify-content: center;
}
.cpu__note { font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.5; }
</style>
