<script setup lang="ts">
/* ============================================================
 * C-PhotoCompare 术前/术后对比照查看
 * 拖动中间分隔线对比 before / after；缺图时展示占位。
 * ============================================================ */
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    before?: string
    after?: string
    beforeLabel?: string
    afterLabel?: string
  }>(),
  { before: '', after: '', beforeLabel: '术前 / 面诊', afterLabel: '术后 / 复查' },
)

const pos = ref(50)
const wrapRef = ref<HTMLDivElement | null>(null)
let dragging = false

function update(e: PointerEvent) {
  const wrap = wrapRef.value
  if (!wrap) return
  const rect = wrap.getBoundingClientRect()
  const x = Math.min(Math.max(e.clientX - rect.left, 0), rect.width)
  pos.value = Math.round((x / rect.width) * 100)
}
function down(e: PointerEvent) {
  dragging = true
  wrapRef.value?.setPointerCapture(e.pointerId)
  update(e)
}
function move(e: PointerEvent) {
  if (dragging) update(e)
}
function up() {
  dragging = false
}

const hasBoth = computed(() => !!props.before && !!props.after)
</script>

<script lang="ts">
export default { name: 'CPhotoCompare' }
</script>

<template>
  <div class="cmp">
    <div
      v-if="hasBoth"
      ref="wrapRef"
      class="cmp__wrap"
      @pointerdown="down"
      @pointermove="move"
      @pointerup="up"
      @pointercancel="up"
    >
      <img :src="after" :alt="afterLabel" class="cmp__img" draggable="false" />
      <div class="cmp__before" :style="{ width: pos + '%' }">
        <img :src="before" :alt="beforeLabel" class="cmp__img" draggable="false" />
      </div>
      <div class="cmp__line" :style="{ left: pos + '%' }">
        <span class="cmp__handle">‹ ›</span>
      </div>
      <span class="cmp__badge cmp__badge--before">{{ beforeLabel }}</span>
      <span class="cmp__badge cmp__badge--after">{{ afterLabel }}</span>
    </div>

    <div v-else-if="before || after" class="cmp__single">
      <img :src="before || after" class="cmp__single-img" alt="档案照" />
      <span class="cmp__badge cmp__badge--before">{{ before ? beforeLabel : afterLabel }}</span>
      <span class="cmp__tip">仅一张照片，完成术后复查后可生成前后对比</span>
    </div>

    <div v-else class="cmp__empty">
      <span class="cmp__empty-icon">◱</span>
      <span>暂无档案照 / 对比照，可在面诊或术前上传</span>
    </div>
  </div>
</template>

<style scoped>
.cmp { border: 1px solid var(--c-border-light); border-radius: var(--r-md); overflow: hidden; background: var(--c-bg-page, #f7f8fa); }
.cmp__wrap { position: relative; width: 100%; aspect-ratio: 4 / 3; cursor: ew-resize; user-select: none; touch-action: none; }
.cmp__img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.cmp__before { position: absolute; top: 0; left: 0; bottom: 0; overflow: hidden; }
.cmp__before .cmp__img { width: 100%; }
.cmp__line { position: absolute; top: 0; bottom: 0; width: 2px; background: var(--c-surface); transform: translateX(-50%); box-shadow: 0 0 0 1px rgba(20,21,43,.15); }
.cmp__handle {
  position: absolute; top: 50%; left: 0; transform: translate(-50%, -50%);
  width: 30px; height: 30px; border-radius: 50%; background: var(--c-surface); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 700;
  box-shadow: var(--shadow-card); white-space: nowrap;
}
.cmp__badge {
  position: absolute; top: 8px; font-size: 11px; color: #fff; padding: 2px 8px; border-radius: var(--r-sm);
  background: rgba(20, 21, 43, 0.62);
}
.cmp__badge--before { left: 8px; }
.cmp__badge--after { right: 8px; }
.cmp__single { position: relative; aspect-ratio: 4 / 3; }
.cmp__single-img { width: 100%; height: 100%; object-fit: cover; display: block; }
.cmp__tip { position: absolute; left: 8px; bottom: 8px; font-size: 11px; color: #fff; background: rgba(20,21,43,.62); padding: 2px 8px; border-radius: var(--r-sm); }
.cmp__empty { aspect-ratio: 4 / 3; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: var(--s-sm); color: var(--c-text-3); font-size: var(--t-sm); }
.cmp__empty-icon { font-size: 28px; color: var(--c-text-4); }
</style>
