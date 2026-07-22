<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'
import type { SalaryRecord, SalaryRecordPayload } from '../../api/finance'
import type { StoreInfo } from '../../api/operations'
import { money, isEditable, wholeNumber } from '../../composables/useSalaryPage'
import { WAGE_FIELDS } from '../../composables/useSalaryWorkflow'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'
import UnsavedChangesDialog from '../ui/UnsavedChangesDialog.vue'

const props = defineProps<{
  show: boolean
  mode: 'view' | 'edit'
  record: SalaryRecord | null
  form: SalaryRecordPayload
  formError: string
  saving: boolean
  canEdit: boolean
  storeMap: Map<string, StoreInfo>
  revenue?: number
}>()

const emit = defineEmits<{
  close: []
  edit: []
  save: []
  'update:form': []
}>()

const openingSnapshot = ref('')
const unsavedDialogOpen = ref(false)

const dirty = computed(() => (
  props.show
  && props.mode === 'edit'
  && Boolean(openingSnapshot.value)
  && snapshotForm() !== openingSnapshot.value
))

watch(
  [() => props.show, () => props.mode],
  ([show, mode], [previousShow, previousMode]) => {
    if (!show) {
      openingSnapshot.value = ''
      unsavedDialogOpen.value = false
      return
    }
    if (!previousShow || mode !== previousMode) openingSnapshot.value = snapshotForm()
  },
  { immediate: true },
)

onMounted(() => document.addEventListener('keydown', handleEscape))
onBeforeUnmount(() => document.removeEventListener('keydown', handleEscape))

function snapshotForm() {
  return JSON.stringify(props.form)
}

function requestClose() {
  if (props.saving) return
  if (dirty.value) {
    unsavedDialogOpen.value = true
    return
  }
  emit('close')
}

function discardChanges() {
  if (props.saving) return
  unsavedDialogOpen.value = false
  emit('close')
}

function handleEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !props.show || unsavedDialogOpen.value) return
  event.preventDefault()
  requestClose()
}

function onFieldInput() {
  // recalcGross is handled by parent via the @input handler
  emit('update:form')
}
</script>

<template>
  <div v-if="show" class="drawer-backdrop" @click.self="requestClose">
    <section class="drawer-panel" role="dialog" aria-modal="true" aria-labelledby="salary-detail-title">
      <div class="drawer-head">
        <div><h3 id="salary-detail-title">{{ mode === 'view' ? '工资详情' : '编辑工资' }}</h3><span>{{ form.employeeName }} · {{ form.month }}</span></div>
        <UiButton variant="ghost" icon-only aria-label="关闭工资详情" title="关闭" :disabled="saving" @click="requestClose">
          <template #icon><X :size="18" /></template>
        </UiButton>
      </div>
      <div v-if="formError" class="error-box">{{ formError }}</div>
      <div class="drawer-grid">
        <label>门店 <select :value="form.storeId" disabled><option :value="form.storeId">{{ storeMap.get(form.storeId)?.name || form.storeId }}</option></select></label>
        <label>月份 <input :value="form.month" type="month" disabled /></label>
        <label>员工 <input :value="form.employeeName" disabled /></label>
        <label>岗位 <input :value="form.position" disabled /></label>
        <label>出勤 <input :value="form.attendance" :disabled="mode==='view'" placeholder="例：26天" @input="(e) => { form.attendance = (e.target as HTMLInputElement).value; onFieldInput() }" /></label>
        <label>应发 <input :value="money(form.gross)" disabled class="gross-input" /></label>
      </div>
      <div class="drawer-section-title">工资明细</div>
      <div class="wage-grid">
        <label v-for="f in WAGE_FIELDS" :key="f.key">{{ f.label }}<input v-model.number="form[f.key]" type="number" step="1" :disabled="mode==='view'" @input="onFieldInput()" /></label>
      </div>
      <div class="drawer-section-title">经营与测算参考</div>
      <div class="detail-reference-grid">
        <div><span>当月营业额</span><b>{{ revenue ? money(revenue) : '-' }}</b></div>
        <div><span>工资人效比</span><b>{{ revenue && record?.gross !== undefined ? `${(Number(record.gross || 0) / revenue * 100).toFixed(1)}%` : '-' }}</b></div>
        <div><span>总工时</span><b>{{ wholeNumber(record?.workHours) }}</b></div>
        <div><span>提成测算</span><b>{{ money(record?.commission) }}</b></div>
        <div><span>补贴</span><b>{{ money(Number(record?.social || 0) + Number(record?.post || 0) + Number(record?.meal || 0) + Number(record?.subsidy || 0)) }}</b></div>
        <div><span>扣款</span><b>{{ money(Number(record?.deductUniform || 0) + Number(record?.returnUniform || 0)) }}</b></div>
      </div>
      <div class="drawer-section-title">工时 / 假期</div>
      <div class="drawer-grid">
        <label>上班时长 <input v-model.number="form.normalHours" type="number" step="0.01" :disabled="mode==='view'" @input="onFieldInput()" /></label>
        <label>加班时长 <input v-model.number="form.otHours" type="number" step="0.01" :disabled="mode==='view'" @input="onFieldInput()" /></label>
        <label>总工时 <input v-model.number="form.workHours" type="number" step="0.01" disabled /></label>
        <label>假期剩余 <input v-model.number="form.vacationLeft" type="number" step="1" :disabled="mode==='view'" /></label>
        <label class="wide">备注 <input v-model="form.vacationNote" :disabled="mode==='view'" placeholder="假期、工资核对说明" /></label>
      </div>
      <div class="drawer-section-title">审核记录</div>
      <div class="detail-reference-grid">
        <div><span>当前状态</span><b>{{ record?.status === 'PENDING_GENERATION' ? '待生成' : record?.status || '-' }}</b></div>
        <div><span>审核时间</span><b>{{ record?.reviewedAt || '-' }}</b></div>
        <div><span>发放时间</span><b>{{ record?.paidAt || '-' }}</b></div>
        <div><span>审核说明</span><b>{{ record?.reviewNote || '-' }}</b></div>
      </div>
      <ModalFooter sticky>
        <UiButton variant="secondary" type="button" :disabled="saving" @click="requestClose">{{ mode === 'edit' ? '取消' : '关闭' }}</UiButton>
        <UiButton v-if="mode==='view' && canEdit && isEditable(record?.status)" variant="primary" type="button" @click="emit('edit')">编辑</UiButton>
        <UiButton v-if="mode==='edit'" variant="primary" type="button" :loading="saving" @click="emit('save')">保存工资记录</UiButton>
      </ModalFooter>
    </section>
  </div>

  <UnsavedChangesDialog
    :open="unsavedDialogOpen"
    title="工资修改尚未保存"
    message="关闭后，本次工资明细和工时调整将不会保留。"
    @keep-editing="unsavedDialogOpen = false"
    @discard="discardChanges"
  />
</template>

<style scoped>
.drawer-backdrop { position: fixed; inset: 0; z-index: var(--ds-z-modal, 1400); display: grid; place-items: center end; padding: 0; background: rgba(17,24,39,0.28); }
.drawer-panel { width: min(680px, 90vw); height: 100vh; overflow-y: auto; padding: 24px; background: #fff; box-shadow: -8px 0 40px rgba(17,24,39,0.18); }
.drawer-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 16px; }
.drawer-head h3 { margin: 0 0 4px; font-size: 19px; }
.drawer-head span { color: var(--muted); font-size: 13px; }
.drawer-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px; }
.drawer-grid .wide { grid-column: 1 / -1; }
.drawer-grid label, .wage-grid label { display: grid; gap: 6px; color: var(--muted); font-size: 12px; font-weight: 900; }
.drawer-grid select, .drawer-grid input, .wage-grid input { min-height: 38px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 10px; background: #fff; color: var(--ink); font-size: 13px; font-weight: 800; }
.drawer-section-title { margin: 18px 0 8px; color: var(--muted); font-size: 12.5px; font-weight: 900; }
.wage-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; }
.gross-input { color: var(--ink) !important; font-weight: 900 !important; }
.detail-reference-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }
.detail-reference-grid > div { padding: 10px 11px; border: 1px solid var(--line); border-radius: 8px; background: #f8f9fa; }
.detail-reference-grid span { display: block; color: var(--muted); font-size: 12px; }.detail-reference-grid b { display: block; margin-top: 5px; overflow-wrap: anywhere; font-size: 13px; }
.error-box { padding: 10px 12px; margin-bottom: 14px; border-left: 3px solid var(--bad); background: rgba(220,38,38,0.06); color: var(--bad); font-size: 13px; font-weight: 700; }

@media (max-width: 720px) {
  .drawer-grid, .wage-grid { grid-template-columns: 1fr; width: 100%; }
  .drawer-panel { width: 100vw; padding: 16px; }
}
</style>
