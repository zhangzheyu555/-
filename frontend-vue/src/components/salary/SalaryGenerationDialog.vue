<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { X } from 'lucide-vue-next'
import type { SalaryGenerateReport } from '../../api/finance'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'

const props = defineProps<{
  show: boolean
  previewData: SalaryGenerateReport | null
  previewLoading: boolean
  generating: boolean
  canGenerate: boolean
}>()

const emit = defineEmits<{
  close: []
  generate: []
}>()

onMounted(() => document.addEventListener('keydown', handleEscape))
onBeforeUnmount(() => document.removeEventListener('keydown', handleEscape))

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
          <span class="preview-stat warn">跳过 {{ previewData?.skipped || 0 }} 人</span>
          <span v-if="previewData?.errors" class="preview-stat bad">异常 {{ previewData?.errors || 0 }} 人</span>
        </div>
        <div v-if="previewData?.skipDetails?.length" class="preview-list">
          <h4>跳过明细</h4>
          <div v-for="d in previewData.skipDetails" :key="d.employeeId" class="skip-item"><b>{{ d.employeeName }}</b> <span class="muted">{{ d.reason }}</span></div>
        </div>
        <div v-else-if="!previewLoading" class="empty-state compact">所有符合条件员工都可生成工资记录。</div>
      </div>
      <ModalFooter>
        <UiButton variant="secondary" type="button" :disabled="generating" @click="requestClose">关闭</UiButton>
        <UiButton variant="primary" type="button" :disabled="!canGenerate" :loading="generating" @click="emit('generate')">确认生成本月工资</UiButton>
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
.preview-stat.warn { color: #a16207; }
.preview-stat.bad { color: var(--bad); }
.preview-list { max-height: 260px; overflow-y: auto; margin-bottom: 16px; }
.preview-list h4 { margin: 0 0 8px; font-size: 14px; }
.skip-item { padding: 8px 10px; border-bottom: 1px solid var(--line); font-size: 13px; display: flex; gap: 8px; }
</style>
