<script setup lang="ts">
import { X } from 'lucide-vue-next'
import type { SalaryRecord, SalaryRecordPayload } from '../../api/finance'
import type { StoreInfo } from '../../api/operations'
import { money, isEditable } from '../../composables/useSalaryPage'
import { WAGE_FIELDS } from '../../composables/useSalaryWorkflow'

defineProps<{
  show: boolean
  mode: 'view' | 'edit'
  record: SalaryRecord | null
  form: SalaryRecordPayload
  formError: string
  saving: boolean
  canEdit: boolean
  storeMap: Map<string, StoreInfo>
}>()

const emit = defineEmits<{
  close: []
  edit: []
  save: []
  'update:form': []
}>()

function onFieldInput() {
  // recalcGross is handled by parent via the @input handler
  emit('update:form')
}
</script>

<template>
  <div v-if="show" class="drawer-backdrop" @click.self="emit('close')">
    <div class="drawer-panel">
      <div class="drawer-head"><div><h3>{{ mode === 'view' ? '工资详情' : '编辑工资' }}</h3><span>{{ form.employeeName }} · {{ form.month }}</span></div><button class="icon-button" @click="emit('close')"><X :size="17" /></button></div>
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
      <div class="drawer-section-title">工时 / 假期</div>
      <div class="drawer-grid">
        <label>上班时长 <input v-model.number="form.normalHours" type="number" step="0.01" :disabled="mode==='view'" @input="onFieldInput()" /></label>
        <label>加班时长 <input v-model.number="form.otHours" type="number" step="0.01" :disabled="mode==='view'" @input="onFieldInput()" /></label>
        <label>总工时 <input v-model.number="form.workHours" type="number" step="0.01" disabled /></label>
        <label>假期剩余 <input v-model.number="form.vacationLeft" type="number" step="1" :disabled="mode==='view'" /></label>
        <label class="wide">备注 <input v-model="form.vacationNote" :disabled="mode==='view'" placeholder="假期、工资核对说明" /></label>
      </div>
      <div class="drawer-actions">
        <button class="ghost-button" @click="emit('close')">关闭</button>
        <button v-if="mode==='view' && canEdit && isEditable(record?.status)" class="primary-button" @click="emit('edit')">编辑</button>
        <button v-if="mode==='edit'" class="primary-button submit-inline" :disabled="saving" @click="emit('save')">{{ saving ? '保存中...' : '保存工资记录' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.drawer-backdrop { position: fixed; inset: 0; z-index: 75; display: grid; place-items: center end; padding: 0; background: rgba(17,24,39,0.28); }
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
.drawer-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--line); }
.icon-button { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border: 1px solid var(--line); border-radius: 8px; background: #fff; color: var(--muted); cursor: pointer; }
.error-box { padding: 10px 12px; margin-bottom: 14px; border-left: 3px solid var(--bad); background: rgba(220,38,38,0.06); color: var(--bad); font-size: 13px; font-weight: 700; }

@media (max-width: 720px) {
  .drawer-grid, .wage-grid { grid-template-columns: 1fr; width: 100%; }
  .drawer-panel { width: 100vw; padding: 16px; }
}
</style>
