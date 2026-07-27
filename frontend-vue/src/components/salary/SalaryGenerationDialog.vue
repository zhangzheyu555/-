<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Loader2, RefreshCw, X } from 'lucide-vue-next'
import type { SalaryGenerateReport } from '../../api/finance'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'

const props = defineProps<{
  show: boolean
  previewData: SalaryGenerateReport | null
  previewLoading: boolean
  previewError: string
  generating: boolean
  canGenerate: boolean
  storeName: string
  month: string
}>()

const emit = defineEmits<{
  close: []
  generate: []
  retry: []
}>()

const dialogRef = ref<HTMLElement | null>(null)
let previouslyFocused: HTMLElement | null = null
let appRoot: HTMLElement | null = null
let appWasInert = false

function requestClose() {
  if (props.generating) return
  emit('close')
}

function handleKeydown(event: KeyboardEvent) {
  if (!props.show) return
  if (event.key === 'Escape') {
    if (props.generating) return
    event.preventDefault()
    event.stopPropagation()
    requestClose()
    return
  }
  if (event.key !== 'Tab') return
  const focusable = Array.from(dialogRef.value?.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [href], [tabindex]:not([tabindex="-1"])',
  ) || [])
  if (!focusable.length) {
    event.preventDefault()
    dialogRef.value?.focus()
    return
  }
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const active = document.activeElement
  if (event.shiftKey && (active === first || !dialogRef.value?.contains(active))) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && (active === last || !dialogRef.value?.contains(active))) {
    event.preventDefault()
    first.focus()
  }
}

function releaseDialogFocus() {
  document.removeEventListener('keydown', handleKeydown, true)
  if (appRoot && !appWasInert) appRoot.inert = false
  previouslyFocused?.focus()
  previouslyFocused = null
  appRoot = null
}

watch(() => props.show, async (show) => {
  if (!show) {
    releaseDialogFocus()
    return
  }
  previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
  appRoot = document.getElementById('app')
  appWasInert = Boolean(appRoot?.inert)
  if (appRoot) appRoot.inert = true
  document.addEventListener('keydown', handleKeydown, true)
  await nextTick()
  dialogRef.value?.querySelector<HTMLElement>('button:not(:disabled)')?.focus()
}, { immediate: true })

onBeforeUnmount(releaseDialogFocus)
</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="modal-backdrop" @click.self="requestClose">
      <section
        ref="dialogRef"
        class="preview-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="salary-generation-title"
        aria-describedby="salary-generation-scope"
        tabindex="-1"
      >
        <div class="modal-head">
          <div>
            <h3 id="salary-generation-title">工资生成预览</h3>
            <p id="salary-generation-scope">{{ storeName }} · {{ month }}</p>
          </div>
          <UiButton variant="ghost" icon-only aria-label="关闭工资生成预览" title="关闭" :disabled="generating" @click="requestClose">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </div>
        <div class="preview-modal__body">
          <div v-if="previewLoading" class="preview-state" role="status">
            <Loader2 :size="24" class="spin" />
            <b>正在核对员工、考勤和工资配置</b>
            <span>完成后会列出可生成和跳过的人员。</span>
          </div>
          <div v-else-if="previewError" class="preview-error" role="alert">
            <span>{{ previewError }}</span>
            <UiButton variant="secondary" type="button" :disabled="generating" @click="emit('retry')">
              <template #icon><RefreshCw :size="15" /></template>
              重新预览
            </UiButton>
          </div>
          <template v-else>
            <div class="preview-stats">
              <span class="preview-stat good">可生成 {{ previewData?.generated || 0 }} 人</span>
              <span class="preview-stat warn">跳过 {{ previewData?.skipped || 0 }} 人</span>
              <span v-if="previewData?.errors" class="preview-stat bad">异常 {{ previewData?.errors || 0 }} 人</span>
            </div>
            <div v-if="previewData?.skipDetails?.length" class="preview-list">
              <h4>跳过明细</h4>
              <div v-for="d in previewData.skipDetails" :key="d.employeeId" class="skip-item"><b>{{ d.employeeName }}</b> <span class="muted">{{ d.reason }}</span></div>
            </div>
            <div v-else-if="previewData && previewData.generated > 0" class="empty-state compact">所有符合条件员工都可生成工资记录。</div>
            <div v-else class="empty-state compact">当前没有可生成的员工，请补齐考勤或检查员工范围。</div>
          </template>
        </div>
        <ModalFooter>
          <template #info>
            <span v-if="previewData?.generated">本次只生成预览中的 {{ previewData.generated }} 名员工，已有工资记录不会重复生成。</span>
            <span v-else>生成前必须先完成预览。</span>
          </template>
          <UiButton variant="secondary" type="button" :disabled="generating" @click="requestClose">关闭</UiButton>
          <UiButton variant="primary" type="button" :disabled="!canGenerate" :loading="generating" @click="emit('generate')">确认生成本月工资</UiButton>
        </ModalFooter>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-backdrop { position: fixed; inset: 0; z-index: var(--ds-z-modal, 1400); display: grid; place-items: center; padding: 20px; background: rgba(17,24,39,0.32); }
.preview-modal { display: grid; width: min(560px, 100%); max-height: calc(100vh - 44px); grid-template-rows: auto minmax(0, 1fr) auto; overflow: hidden; border: 1px solid #d8e4e2; border-radius: 6px; background: #fff; box-shadow: 0 12px 28px rgba(24,36,36,0.14); }
.modal-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 20px 20px 0; }
.modal-head h3 { margin: 0; font-size: 18px; }
.modal-head p { margin: 5px 0 0; color: #6f817f; font-size: 13px; }
.preview-modal__body { min-height: 0; overflow-y: auto; padding: 16px 20px 20px; }
.preview-stats { display: flex; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
.preview-stat { font-size: 15px; font-weight: 800; }
.preview-stat.good { color: var(--good); }
.preview-stat.warn { color: #a16207; }
.preview-stat.bad { color: var(--bad); }
.preview-list { max-height: 260px; overflow-y: auto; margin-bottom: 16px; }
.preview-list h4 { margin: 0 0 8px; font-size: 14px; }
.skip-item { padding: 8px 10px; border-bottom: 1px solid var(--line); font-size: 13px; display: flex; gap: 8px; }
.preview-state { display: grid; min-height: 150px; place-content: center; justify-items: center; gap: 8px; color: #526765; text-align: center; }
.preview-state b { color: #263a38; font-size: 14px; }
.preview-state span { font-size: 12px; }
.preview-error { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 12px; border: 1px solid #efc9c2; border-radius: 6px; background: #fff5f3; color: #a93f31; font-size: 13px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 520px) {
  .modal-backdrop { align-items: end; padding: 0; }
  .preview-modal { max-height: 92dvh; border-radius: 10px 10px 0 0; }
  .preview-error { align-items: stretch; flex-direction: column; }
}
</style>
