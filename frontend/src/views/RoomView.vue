<script setup lang="ts">
/* ============================================================
 * 床位/房间管理 /m2-rooms（M2-04）
 * 左：房间卡片列表；右：选中房间的床位状态矩阵 + 操作。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CDrawer from '@/components/CDrawer.vue'
import {
  useRoomStore,
  type Room,
  type Bed,
  type BedStatus,
  type RoomType,
} from '@/stores/room'
import { useToast } from '@/composables/useToast'

const store = useRoomStore()
const toast = useToast()
onMounted(() => store.seed())

const selectedRoomId = ref<string | null>(null)
const selectedRoom = computed<Room | null>(() => {
  if (selectedRoomId.value) return store.getRoom(selectedRoomId.value) ?? null
  return store.filteredRooms[0] ?? null
})
const selectedBedId = ref<string | null>(null)
const selectedBed = computed<Bed | null>(() => {
  if (!selectedRoom.value || !selectedBedId.value) return null
  return selectedRoom.value.beds.find((b) => b.id === selectedBedId.value) ?? null
})

const kpis = computed(() => [
  { label: '总床位', icon: 'home', value: String(store.total), tone: 'text' as const },
  { label: '使用中', icon: 'home', value: String(store.inUse), tone: 'brand' as const },
  { label: '空闲', icon: 'home', value: String(store.free), tone: 'success' as const },
  { label: '消毒中', icon: 'home', value: String(store.sanitizing), tone: 'warning' as const },
])

const typeOptions = [
  { value: 'ALL', label: '全部房间' },
  { value: 'TREATMENT', label: '治疗室' },
  { value: 'CONSULT', label: '咨询室' },
  { value: 'OBSERVE', label: '观察室' },
  { value: 'RECOVERY', label: '恢复室' },
]
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'FREE', label: '空闲' },
  { value: 'IN_USE', label: '使用中' },
  { value: 'SANITIZING', label: '消毒中' },
  { value: 'MAINTENANCE', label: '维护中' },
]

function countByStatus(room: Room, status: BedStatus) {
  return room.beds.filter((b) => b.status === status).length
}

function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  const diff = Math.max(0, Date.now() - d.getTime())
  const min = Math.floor(diff / 60000)
  if (min < 60) return `${min} 分钟`
  const h = Math.floor(min / 60)
  return `${h} 小时 ${min % 60} 分`
}

function fmtFull(iso: string) {
  const d = new Date(iso)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 入住弹层
const occupyForm = ref({ show: false, roomId: '', bedId: '', customerName: '', project: '' })
function openOccupy(roomId: string, bedId: string) {
  occupyForm.value = { show: true, roomId, bedId, customerName: '', project: '' }
}
function submitOccupy() {
  if (!occupyForm.value.customerName.trim()) return
  const ok = store.occupy(
    occupyForm.value.roomId,
    occupyForm.value.bedId,
    occupyForm.value.customerName.trim(),
    occupyForm.value.project.trim() || undefined,
  )
  if (ok) occupyForm.value.show = false
}

// 维护原因弹层
const maintForm = ref({ show: false, roomId: '', bedId: '', reason: '' })
function openMaintenance(roomId: string, bedId: string) {
  maintForm.value = { show: true, roomId, bedId, reason: '' }
}
function submitMaintenance() {
  if (!maintForm.value.reason.trim()) return
  const ok = store.setMaintenance(maintForm.value.roomId, maintForm.value.bedId, maintForm.value.reason.trim())
  if (ok) maintForm.value.show = false
}

// 通用确认
const confirm = ref<{ show: boolean; title: string; action: () => void } | null>(null)
function ask(title: string, action: () => void) {
  confirm.value = { show: true, title, action }
}
function runConfirm() {
  confirm.value?.action()
  confirm.value = null
}

function doRelease() {
  if (!selectedRoom.value || !selectedBed.value) return
  store.release(selectedRoom.value.id, selectedBed.value.id)
}
function doClean() {
  if (!selectedRoom.value || !selectedBed.value) return
  store.clean(selectedRoom.value.id, selectedBed.value.id)
}
function doRestore() {
  if (!selectedRoom.value || !selectedBed.value) return
  store.restore(selectedRoom.value.id, selectedBed.value.id)
}

function selectRoom(r: Room) {
  selectedRoomId.value = r.id
  selectedBedId.value = r.beds[0]?.id ?? null
}

// 新建房间
const showCreate = ref(false)
const createForm = ref({ code: '', name: '', type: 'TREATMENT' as RoomType, bedCount: '' })
const createTypeOptions = [
  { value: 'TREATMENT', label: '治疗室' },
  { value: 'CONSULT', label: '咨询室' },
  { value: 'OBSERVE', label: '观察室' },
  { value: 'RECOVERY', label: '恢复室' },
]
function openCreateRoom() {
  createForm.value = { code: '', name: '', type: 'TREATMENT', bedCount: '' }
  showCreate.value = true
}
function doCreateRoom() {
  const f = createForm.value
  if (!f.code.trim() || !f.name.trim()) return
  const ok = store.addRoom({ code: f.code.trim(), name: f.name.trim(), type: f.type, bedCount: f.bedCount ? Number(f.bedCount) : undefined })
  if (ok) {
    showCreate.value = false
    toast.success(`已创建房间「${f.name}」`)
  }
}
</script>

<template>
  <div class="rm">
    <!-- 新建房间抽屉 -->
    <CDrawer v-model:show="showCreate" title="新建房间" size="sm">
      <div class="opform">
        <CInput v-model="createForm.code" label="房间编号 *" placeholder="如：E01" />
        <CInput v-model="createForm.name" label="房间名称 *" placeholder="如：激光治疗室 3" />
        <div class="opform__field">
          <label class="opform__label">房间类型 *</label>
          <CSelect v-model="createForm.type" :options="createTypeOptions" width="100%" />
        </div>
        <CInput v-model="createForm.bedCount" label="床位数量" placeholder="默认 2" />
      </div>
      <div class="drawer__ops">
        <CButton variant="ghost" size="sm" @click="showCreate = false">取消</CButton>
        <CButton variant="primary" size="sm" :disabled="!createForm.code.trim() || !createForm.name.trim()" @click="doCreateRoom">
          确认创建
        </CButton>
      </div>
    </CDrawer>

    <div class="rm__head">
      <div class="rm__kpis">
        <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
      </div>
    </div>

    <div class="rm__body">
      <CCard class="rm__list rm__list--fab" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterType" :options="typeOptions" width="120px" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="120px" />
        </div>
        <div class="list">
          <div v-if="store.filteredRooms.length === 0" class="empty">
            <CIcon name="home" :size="28" class="empty__icon" />
            <div>暂无房间</div>
          </div>
          <button
            v-for="r in store.filteredRooms" :key="r.id"
            class="room" :class="{ 'room--active': selectedRoom?.id === r.id }"
            @click="selectRoom(r)"
          >
            <div class="room__top">
              <span class="room__code">{{ r.code }}</span>
              <span class="room__type">{{ store.ROOM_TYPE_LABEL[r.type as RoomType] }}</span>
            </div>
            <div class="room__name">{{ r.name }}</div>
            <div class="room__stats">
              <span class="dot dot--use">{{ countByStatus(r, 'IN_USE') }} 使用</span>
              <span class="dot dot--free">{{ countByStatus(r, 'FREE') }} 空闲</span>
              <span class="dot dot--sani">{{ countByStatus(r, 'SANITIZING') }} 消毒</span>
              <span v-if="countByStatus(r, 'MAINTENANCE')" class="dot dot--maint">{{ countByStatus(r, 'MAINTENANCE') }} 维护</span>
            </div>
          </button>
        </div>
        <button class="rm__fab" v-perm.disable="'room:edit'" @click="openCreateRoom" title="新建房间">
          <CIcon name="plus" :size="20" />
        </button>
      </CCard>

      <CCard v-if="selectedRoom" class="rm__detail" :title="`${selectedRoom.code} ${selectedRoom.name}`">
        <template #header>
          <h3 class="rm__detail-title">{{ selectedRoom.code }} {{ selectedRoom.name }}</h3>
          <CStatusPill status="info">{{ store.ROOM_TYPE_LABEL[selectedRoom.type as RoomType] }}</CStatusPill>
        </template>

        <div class="beds">
          <button
            v-for="b in selectedRoom.beds" :key="b.id"
            class="bed"
            :class="[`bed--${b.status.toLowerCase()}`, { 'bed--active': selectedBed?.id === b.id }]"
            @click="selectedBedId = b.id"
          >
            <div class="bed__head">
              <span class="bed__code">{{ b.code }}</span>
              <CStatusPill :status="store.BED_STATUS_PILL[b.status]">{{ store.BED_STATUS_LABEL[b.status] }}</CStatusPill>
            </div>
            <div v-if="b.status === 'IN_USE'" class="bed__body">
              <div class="bed__cust">{{ b.customerName }}</div>
              <div v-if="b.project" class="bed__proj">{{ b.project }}</div>
              <div class="bed__time"><CIcon name="clock" :size="12" /> 已入住 {{ fmtTime(b.occupiedAt) }}</div>
            </div>
            <div v-else-if="b.status === 'MAINTENANCE'" class="bed__body">
              <div class="bed__maint"><CIcon name="alert" :size="12" /> {{ b.note || '维护中' }}</div>
            </div>
            <div v-else class="bed__body bed__body--empty">
              <span>{{ b.status === 'FREE' ? '可安排使用' : '清洁消毒中' }}</span>
            </div>
          </button>
        </div>

        <div v-if="selectedBed" class="bed-detail">
          <div class="detail__sec-title">床位操作 · {{ selectedBed.code }}</div>
          <div class="bed-detail__row">
            <div class="field"><span class="field__label">当前状态</span><span class="field__val">{{ store.BED_STATUS_LABEL[selectedBed.status] }}</span></div>
            <div v-if="selectedBed.customerName" class="field"><span class="field__label">当前客户</span><span class="field__val">{{ selectedBed.customerName }}</span></div>
            <div v-if="selectedBed.project" class="field"><span class="field__label">项目</span><span class="field__val">{{ selectedBed.project }}</span></div>
            <div v-if="selectedBed.occupiedAt" class="field"><span class="field__label">入住时间</span><span class="field__val">{{ fmtFull(selectedBed.occupiedAt) }}</span></div>
            <div v-if="selectedBed.note" class="field field--full"><span class="field__label">备注</span><span class="field__val">{{ selectedBed.note }}</span></div>
          </div>
          <div class="bed-detail__ops">
            <template v-if="selectedBed.status === 'FREE'">
              <CButton variant="primary" v-perm.disable="'room:edit'" @click="openOccupy(selectedRoom.id, selectedBed.id)">
                <CIcon name="check" :size="16" />安排入住
              </CButton>
              <CButton variant="ghost" v-perm.disable="'room:edit'" @click="openMaintenance(selectedRoom.id, selectedBed.id)">
                <CIcon name="settings" :size="16" />设为维护
              </CButton>
            </template>
            <template v-else-if="selectedBed.status === 'IN_USE'">
              <CButton variant="primary" v-perm.disable="'room:edit'" @click="ask('确认退房？退房后床位进入消毒状态', doRelease)">
                <CIcon name="check" :size="16" />退房
              </CButton>
            </template>
            <template v-else-if="selectedBed.status === 'SANITIZING'">
              <CButton variant="primary" v-perm.disable="'room:edit'" @click="ask('确认清洁完成？床位将恢复空闲', doClean)">
                <CIcon name="check-square" :size="16" />清洁确认
              </CButton>
              <CButton variant="ghost" v-perm.disable="'room:edit'" @click="openMaintenance(selectedRoom.id, selectedBed.id)">
                <CIcon name="settings" :size="16" />设为维护
              </CButton>
            </template>
            <template v-else-if="selectedBed.status === 'MAINTENANCE'">
              <CButton variant="primary" v-perm.disable="'room:edit'" @click="ask('维护完成？床位将进入消毒状态', doRestore)">
                <CIcon name="check" :size="16" />维护完成
              </CButton>
            </template>
          </div>
        </div>

        <div v-if="store.logs.length" class="logs">
          <div class="detail__sec-title">最近操作</div>
          <div v-for="l in store.logs.slice(0, 6)" :key="l.id" class="log">
            <span class="log__time">{{ fmtFull(l.at) }}</span>
            <span class="log__who">{{ l.by }}</span>
            <span class="log__text">{{ l.text }}</span>
          </div>
        </div>
      </CCard>

      <CCard v-else class="rm__detail rm__detail--empty" title="房间详情">
        <div class="detail-empty">
          <CIcon name="home" :size="40" class="detail-empty__icon" />
          <p>请选择一个房间</p>
        </div>
      </CCard>
    </div>

    <!-- 入住弹层 -->
    <div v-if="occupyForm.show" class="modal-mask" @click.self="occupyForm.show = false">
      <CCard class="modal modal--sm" title="安排入住" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">客户姓名 *</label>
            <CInput v-model="occupyForm.customerName" placeholder="如：陈美玲" />
          </div>
          <div class="form__row">
            <label class="form__label">项目</label>
            <CInput v-model="occupyForm.project" placeholder="如：皮秒激光" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="occupyForm.show = false">取消</CButton>
          <CButton variant="primary" :disabled="!occupyForm.customerName.trim()" @click="submitOccupy">确认入住</CButton>
        </template>
      </CCard>
    </div>

    <!-- 维护弹层 -->
    <div v-if="maintForm.show" class="modal-mask" @click.self="maintForm.show = false">
      <CCard class="modal modal--sm" title="设为维护" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">维护原因 *</label>
            <CInput v-model="maintForm.reason" placeholder="如：治疗床导轨异响" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="maintForm.show = false">取消</CButton>
          <CButton variant="primary" :disabled="!maintForm.reason.trim()" @click="submitMaintenance">确认</CButton>
        </template>
      </CCard>
    </div>

    <div v-if="confirm?.show" class="modal-mask" @click.self="confirm = null">
      <CCard class="modal modal--sm" title="确认操作" padding="lg">
        <p class="confirm__text">{{ confirm.title }}</p>
        <template #footer>
          <CButton variant="ghost" @click="confirm = null">取消</CButton>
          <CButton variant="primary" @click="runConfirm">确认</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.rm { display: flex; flex-direction: column; gap: var(--s-lg); }
.rm__head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); flex-wrap: wrap; }
.rm__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); flex: 1; min-width: 480px; }

.rm__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.rm__list { min-width: 0; }
.rm__list--fab { position: relative; }
.rm__fab {
  position: absolute; right: 12px; bottom: 12px; z-index: 10;
  width: 42px; height: 42px; border-radius: 50%;
  background: var(--c-brand); color: #fff; border: none;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 4px 14px rgba(0,0,0,.2); cursor: pointer;
  transition: transform .15s, box-shadow .15s;
}
.rm__fab:hover { transform: scale(1.08); box-shadow: 0 6px 18px rgba(0,0,0,.28); }
.filters { display: flex; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.list { max-height: 620px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.room {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.room:hover { background: var(--c-brand-soft); }
.room--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.room__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xxs); }
.room__code { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.room__type { font-size: var(--t-xs); color: var(--c-text-3); }
.room__name { font-size: var(--t-sm); color: var(--c-text-2); margin-bottom: var(--s-xs); }
.room__stats { display: flex; flex-wrap: wrap; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); }
.dot { display: inline-flex; align-items: center; gap: 3px; }
.dot::before { content: ''; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.dot--use { color: var(--c-brand); }
.dot--free { color: var(--c-success-fg); }
.dot--sani { color: var(--c-warning-fg); }
.dot--maint { color: var(--c-danger-fg); }

.rm__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }

.beds { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: var(--s-md); }
.bed {
  text-align: left; padding: var(--s-md);
  border: 1px solid var(--c-border-light); border-radius: var(--r-md);
  background: var(--c-surface); cursor: pointer; transition: border-color .15s, box-shadow .15s;
  display: flex; flex-direction: column; gap: var(--s-sm);
}
.bed:hover { border-color: var(--c-brand-border); }
.bed--active { border-color: var(--c-brand); box-shadow: 0 0 0 2px var(--c-brand-soft); }
.bed--free { border-left: 3px solid var(--c-success-fg); }
.bed--in_use { border-left: 3px solid var(--c-brand); background: var(--c-brand-soft); }
.bed--sanitizing { border-left: 3px solid var(--c-warning-fg); }
.bed--maintenance { border-left: 3px solid var(--c-danger-fg); background: var(--c-danger-bg); }
.bed__head { display: flex; justify-content: space-between; align-items: center; }
.bed__code { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.bed__body { font-size: var(--t-xs); color: var(--c-text-2); display: flex; flex-direction: column; gap: 2px; }
.bed__body--empty { color: var(--c-text-3); }
.bed__cust { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.bed__proj { color: var(--c-text-3); }
.bed__time { display: inline-flex; align-items: center; gap: 3px; color: var(--c-text-3); margin-top: 2px; }
.bed__maint { display: inline-flex; align-items: center; gap: 4px; color: var(--c-danger-fg); }

.bed-detail { margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.bed-detail__row { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); margin-bottom: var(--s-md); }
.field { display: flex; flex-direction: column; gap: 2px; }
.field--full { grid-column: 1 / -1; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.bed-detail__ops { display: flex; gap: var(--s-sm); flex-wrap: wrap; }

.logs { margin-top: var(--s-lg); }
.log { display: grid; grid-template-columns: 48px 90px 1fr; gap: var(--s-sm); padding: var(--s-xs) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); align-items: baseline; }
.log:last-child { border-bottom: none; }
.log__time { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.log__who { font-weight: 600; color: var(--c-text); }
.log__text { color: var(--c-text-2); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.opform { display: flex; flex-direction: column; gap: var(--s-md); }
.opform__field { display: flex; flex-direction: column; gap: var(--s-xs); }
.opform__label { font-size: var(--t-xs); color: var(--c-text-3); }
.drawer__ops { display: flex; justify-content: flex-end; gap: var(--s-xs); margin-top: var(--s-lg); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 420px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--sm { width: 420px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.confirm__text { font-size: var(--t-sm); color: var(--c-text); text-align: center; margin: var(--s-md) 0; }

@media (max-width: 1024px) {
  .rm__body { grid-template-columns: 1fr; }
  .rm__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .bed-detail__row { grid-template-columns: 1fr 1fr; }
  .list { max-height: 320px; }
}
</style>
