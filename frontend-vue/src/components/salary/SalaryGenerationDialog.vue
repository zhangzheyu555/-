<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { X } from 'lucide-vue-next'
import type { SalaryGenerateReport } from '../../api/finance'
import SearchableMultiSelect from '../common/SearchableMultiSelect.vue'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'

const props = defineProps<{
  show: boolean
  previewData: SalaryGenerateReport | null
  previewLoading: boolean
  generating: boolean
  canGenerate: boolean
  selectedEmployeeIds: string[]
  scopeLabel: string
}>()

const emit = defineEmits<{
  close: []
  generate: []
  'update:selectedEmployeeIds': [employeeIds: string[]]
}>()

const candidates = computed(() => props.previewData?.candidates || [])
const candidateIds = computed(() => new Set(candidates.value.map((candidate) => candidate.employeeId)))
const candidateOptions = computed(() => candidates.value.map((candidate) => ({
  value: candidate.employeeId,
  label: candidate.employeeName,
  description: [
    candidate.storeName || candidate.storeId,
    candidate.employeeId,
    candidate.position,
  ].filter(Boolean).join(' · '),
  searchText: [
    candidate.employeeName,
    candidate.storeName,
    candidate.storeId,
    candidate.employeeId,
    candidate.position,
  ].filter(Boolean).join(' '),
})))
const selectedCount = computed(() => props.selectedEmployeeIds
  .filter((employeeId) => candidateIds.value.has(employeeId)).length)
const canConfirm = computed(() => props.canGenerate
  && candidates.value.length > 0
  && selectedCount.value > 0)

onMounted(() => document.addEventListener('keydown', handleEscape))
onBeforeUnmount(() => document.removeEventListener('keydown', handleEscape))

function updateSelection(values: Array<string | number>) {
  const next = Array.from(new Set(values
    .map((value) => String(value))
    .filter((employeeId) => candidateIds.value.has(employeeId))))
  emit('update:selectedEmployeeIds', next)
}

function selectAllCandidates() {
  emit('update:selectedEmployeeIds', candidates.value.map((candidate) => candidate.employeeId))
}

function clearSelection() {
  emit('update:selectedEmployeeIds', [])
}

function requestClose() {
  if (props.generating) return
  emit('close')
}

function handleEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !props.show || props.generating) return
  event.preventDefault()
  requestClose()
}
</script>

<template>
  <div v-if="show" class="modal-backdrop" @click.self="requestClose">
    <section class="preview-modal" role="dialog" aria-modal="true" aria-labelledby="salary-generation-title">
      <div class="modal-head">
        <h3 id="salary-generation-title">工资生成预览</h3>
        <UiButton variant="ghost" icon-only aria-label="关闭工资生成预览" title="关闭" :disabled="generating" @click="requestClose">
          <template #icon><X :size="18" /></template>
        </UiButton>
      </div>
      <div class="preview-modal__body">
        <div class="preview-stats">
          <span class="preview-stat good">可生成 {{ previewData?.generated || 0 }} 人</span>
          <span class="preview-stat selected">已选择 {{ selectedCount }} 人</span>
          <span class="preview-stat warn">跳过 {{ previewData?.skipped || 0 }} 人</span>
          <span v-if="previewData?.errors" class="preview-stat bad">异常 {{ previewData?.errors || 0 }} 人</span>
        </div>
        <section class="candidate-section" aria-labelledby="salary-generation-candidates-title">
          <header>
            <div>
              <h4 id="salary-generation-candidates-title">选择生成人员</h4>
              <span>名单包含{{ scopeLabel }}、当前月份全部可生成人员，不受工资表分页影响。</span>
            </div>
            <div class="candidate-actions">
              <button type="button" :disabled="generating || !candidates.length" @click="selectAllCandidates">一键全选全部</button>
              <button type="button" :disabled="generating || !selectedEmployeeIds.length" @click="clearSelection">清空</button>
            </div>
          </header>
          <SearchableMultiSelect
            :model-value="selectedEmployeeIds"
            :options="candidateOptions"
            :disabled="generating"
            search-placeholder="搜索员工、门店、工号或岗位"
            aria-label="选择生成工资的员工"
            selected-noun="人"
            select-all-label="全选当前搜索结果"
            clear-label="清空当前已选"
            :empty-message="`${scopeLabel}暂无可生成工资的员工`"
            @update:model-value="updateSelection"
          />
        </section>
        <div v-if="previewData?.skipDetails?.length" class="preview-list">
          <h4>跳过明细</h4>
          <div v-for="d in previewData.skipDetails" :key="`${d.storeId || ''}:${d.employeeId}`" class="skip-item">
            <b>{{ d.employeeName }}</b>
            <span v-if="d.storeName || d.storeId" class="muted">{{ d.storeName || d.storeId }}</span>
            <span class="muted">{{ d.reason }}</span>
          </div>
        </div>
        <div v-else-if="!previewLoading && candidates.length" class="empty-state compact">所有可选员工都符合当前工资生成条件。</div>
      </div>
      <ModalFooter>
        <UiButton variant="secondary" type="button" :disabled="generating" @click="requestClose">关闭</UiButton>
        <UiButton variant="primary" type="button" :disabled="!canConfirm" :loading="generating" @click="emit('generate')">确认生成所选工资</UiButton>
      </ModalFooter>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop { position: fixed; inset: 0; z-index: var(--ds-z-modal, 1400); display: grid; place-items: center; padding: 20px; background: rgba(17,24,39,0.32); }
.preview-modal { display: grid; width: min(560px, 100%); max-height: calc(100vh - 44px); grid-template-rows: auto minmax(0, 1fr) auto; overflow: hidden; border: 1px solid #d8e4e2; border-radius: 6px; background: #fff; box-shadow: 0 12px 28px rgba(24,36,36,0.14); }
.modal-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 20px 20px 0; }
.modal-head h3 { margin: 0; font-size: 18px; }
.preview-modal__body { min-height: 0; overflow-y: auto; padding: 16px 20px 20px; }
.preview-stats { display: flex; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
.preview-stat { font-size: 15px; font-weight: 800; }
.preview-stat.good { color: var(--good); }
.preview-stat.selected { color: var(--ds-primary, #276b65); }
.preview-stat.warn { color: #a16207; }
.preview-stat.bad { color: var(--bad); }
.candidate-section { display: grid; gap: 10px; margin-bottom: 16px; }
.candidate-section > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.candidate-section h4 { margin: 0; font-size: 14px; }
.candidate-section header span { display: block; margin-top: 4px; color: var(--ds-muted, #6f817f); font-size: 12px; line-height: 1.45; }
.candidate-actions { display: flex; flex: none; gap: 7px; }
.candidate-actions button { min-height: 30px; padding: 4px 9px; border: 1px solid var(--ds-line, #d7e2e0); border-radius: 5px; background: #fff; color: var(--ds-primary, #276b65); font-size: 12px; font-weight: 700; cursor: pointer; }
.candidate-actions button:disabled { color: var(--ds-muted, #97a5a2); cursor: default; }
.preview-list { max-height: 260px; overflow-y: auto; margin-bottom: 16px; }
.preview-list h4 { margin: 0 0 8px; font-size: 14px; }
.skip-item { padding: 8px 10px; border-bottom: 1px solid var(--line); font-size: 13px; display: flex; gap: 8px; }
@media (max-width: 560px) { .candidate-section > header { display: grid; }.candidate-actions { width: 100%; }.candidate-actions button { flex: 1; } }
</style>
