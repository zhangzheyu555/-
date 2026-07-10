<script setup lang="ts">
import { X } from 'lucide-vue-next'
import type { SalaryGenerateReport } from '../../api/finance'

defineProps<{
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
</script>

<template>
  <div v-if="show" class="modal-backdrop" @click.self="emit('close')">
    <div class="preview-modal">
      <div class="modal-head"><h3>工资生成预览</h3><button class="icon-button" @click="emit('close')"><X :size="17" /></button></div>
      <div class="preview-stats">
        <span class="preview-stat warn">跳过 {{ previewData?.skipped || 0 }} 人</span>
        <span v-if="previewData?.errors" class="preview-stat bad">异常 {{ previewData?.errors || 0 }} 人</span>
      </div>
      <div v-if="previewData?.skipDetails?.length" class="preview-list">
        <h4>跳过明细</h4>
        <div v-for="d in previewData.skipDetails" :key="d.employeeId" class="skip-item"><b>{{ d.employeeName }}</b> <span class="muted">{{ d.reason }}</span></div>
      </div>
      <div v-else-if="!previewLoading" class="empty-state compact">所有符合条件员工都可生成工资记录。</div>
      <div class="modal-actions">
        <button class="ghost-button" @click="emit('close')">关闭</button>
        <button class="primary-button submit-inline" :disabled="!canGenerate" @click="emit('generate')">{{ generating ? '生成中...' : '确认生成本月工资' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop { position: fixed; inset: 0; z-index: 70; display: grid; place-items: center; padding: 20px; background: rgba(17,24,39,0.32); }
.preview-modal { width: min(560px, 100%); max-height: calc(100vh - 44px); overflow: auto; padding: 20px; border-radius: 16px; background: #fff; box-shadow: 0 24px 60px rgba(17,24,39,0.22); }
.modal-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 16px; }
.modal-head h3 { margin: 0; font-size: 18px; }
.preview-stats { display: flex; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
.preview-stat { font-size: 15px; font-weight: 800; }
.preview-stat.good { color: var(--good); }
.preview-stat.warn { color: #a16207; }
.preview-stat.bad { color: var(--bad); }
.preview-list { max-height: 260px; overflow-y: auto; margin-bottom: 16px; }
.preview-list h4 { margin: 0 0 8px; font-size: 14px; }
.skip-item { padding: 8px 10px; border-bottom: 1px solid var(--line); font-size: 13px; display: flex; gap: 8px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
.icon-button { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border: 1px solid var(--line); border-radius: 8px; background: #fff; color: var(--muted); cursor: pointer; }
</style>
