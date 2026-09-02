<script setup lang="ts">
/* ============================================================
 * C-SignaturePad 手写电子签名板（canvas）
 * 知情同意书客户签名：pointer 事件手写 → toDataURL 输出。
 * v-model 绑定签名图片 dataURL（空串=未签）；提供清除/重写。
 * ============================================================ */
import { onMounted, ref, watch } from 'vue'
import CButton from './CButton.vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    height?: number
    /** 签名人姓名（展示在签名区下方） */
    signerName?: string
  }>(),
  { modelValue: '', height: 140, signerName: '' },
)
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const canvasRef = ref<HTMLCanvasElement | null>(null)
let ctx: CanvasRenderingContext2D | null = null
let drawing = false
let hasInk = false
const signedAt = ref('')

function setupCanvas() {
  const canvas = canvasRef.value
  if (!canvas) return
  const ratio = window.devicePixelRatio || 1
  const rect = canvas.getBoundingClientRect()
  canvas.width = rect.width * ratio
  canvas.height = props.height * ratio
  ctx = canvas.getContext('2d')
  if (ctx) {
    ctx.scale(ratio, ratio)
    ctx.lineWidth = 2.2
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    ctx.strokeStyle = '#1a1a2e'
  }
}

function pos(e: PointerEvent) {
  const rect = canvasRef.value!.getBoundingClientRect()
  return { x: e.clientX - rect.left, y: e.clientY - rect.top }
}

function start(e: PointerEvent) {
  if (!ctx) return
  drawing = true
  hasInk = true
  const { x, y } = pos(e)
  ctx.beginPath()
  ctx.moveTo(x, y)
  canvasRef.value?.setPointerCapture(e.pointerId)
}
function move(e: PointerEvent) {
  if (!drawing || !ctx) return
  const { x, y } = pos(e)
  ctx.lineTo(x, y)
  ctx.stroke()
}
function end() {
  if (!drawing || !ctx) return
  drawing = false
  ctx.closePath()
  if (hasInk) {
    const url = canvasRef.value!.toDataURL('image/png')
    signedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
    emit('update:modelValue', url)
  }
}

function clear() {
  const canvas = canvasRef.value
  if (!canvas || !ctx) return
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  hasInk = false
  signedAt.value = ''
  emit('update:modelValue', '')
}

/** 外部回填已有签名（如打开抽屉时回显） */
watch(
  () => props.modelValue,
  (v) => {
    if (!v && canvasRef.value && ctx) {
      ctx.clearRect(0, 0, canvasRef.value.width, canvasRef.value.height)
      hasInk = false
      signedAt.value = ''
    }
  },
)

onMounted(() => {
  setupCanvas()
  window.addEventListener('resize', setupCanvas)
})
</script>

<script lang="ts">
export default { name: 'CSignaturePad' }
</script>

<template>
  <div class="csig">
    <div class="csig__canvas-wrap">
      <canvas
        ref="canvasRef"
        class="csig__canvas"
        :style="{ height: height + 'px' }"
        @pointerdown="start"
        @pointermove="move"
        @pointerup="end"
        @pointerleave="end"
      />
      <div v-if="!modelValue" class="csig__placeholder">请客户在此区域手写签名</div>
    </div>
    <div class="csig__foot">
      <div class="csig__meta">
        <template v-if="modelValue">
          <span class="csig__signed">已签署</span>
          <span v-if="signerName" class="csig__name">签署人：{{ signerName }}</span>
          <span v-if="signedAt" class="csig__time">{{ signedAt }}</span>
        </template>
        <span v-else class="csig__hint">签名将随《知情同意书》归档，具法律效力</span>
      </div>
      <CButton variant="ghost" size="sm" @click="clear">清除重写</CButton>
    </div>
  </div>
</template>

<style scoped>
.csig { border: 1px solid var(--c-border); border-radius: var(--r-md); background: var(--c-surface); overflow: hidden; }
.csig__canvas-wrap { position: relative; background:
  linear-gradient(var(--c-border-light) 1px, transparent 1px) 0 100% / 100% 28px repeat-x; }
.csig__canvas { display: block; width: 100%; touch-action: none; cursor: crosshair; background: var(--c-surface); }
.csig__placeholder {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  color: var(--c-text-4); font-size: var(--t-sm); pointer-events: none;
}
.csig__foot {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm);
  padding: var(--s-xs) var(--s-sm); background: var(--c-bg-page, #f7f8fa); border-top: 1px solid var(--c-border-light);
}
.csig__meta { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; font-size: var(--t-xs); }
.csig__signed { color: var(--c-success-fg, #389e0d); font-weight: 700; }
.csig__name { color: var(--c-text-2); font-weight: 600; }
.csig__time { color: var(--c-text-3); }
.csig__hint { color: var(--c-text-3); }
.csig__foot :deep(.cbtn) { padding: 2px 8px; font-size: var(--t-xs); }
</style>
