<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { AlertTriangle, X } from 'lucide-vue-next'
import ModalFooter from './ModalFooter.vue'
import UiButton from './UiButton.vue'

const props = withDefaults(defineProps<{
  open: boolean
  title?: string
  message?: string
  discardLabel?: string
  keepLabel?: string
}>(), {
  title: '有尚未保存的内容',
  message: '关闭后，本次填写的内容将不会保留。',
  discardLabel: '放弃修改',
  keepLabel: '继续填写',
})

const emit = defineEmits<{
  (event: 'keep-editing'): void
  (event: 'discard'): void
}>()

const dialogRef = ref<HTMLElement | null>(null)
const instanceId = `unsaved-${Math.random().toString(36).slice(2, 9)}`
const titleId = `${instanceId}-title`
const descriptionId = `${instanceId}-description`
let previouslyFocused: HTMLElement | null = null
let appRoot: HTMLElement | null = null
let appWasInert = false

function keepEditing() {
  emit('keep-editing')
}

function handleKeydown(event: KeyboardEvent) {
  if (!props.open) return
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    keepEditing()
    return
  }
  if (event.key === 'Tab') {
    const focusable = Array.from(dialogRef.value?.querySelectorAll<HTMLElement>(
      'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])',
    ) || [])
    if (!focusable.length) {
      event.preventDefault()
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
}

watch(() => props.open, async (open) => {
  if (open) {
    previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    appRoot = document.getElementById('app')
    appWasInert = Boolean(appRoot?.inert)
    if (appRoot) appRoot.inert = true
    document.addEventListener('keydown', handleKeydown, true)
    await nextTick()
    dialogRef.value?.querySelector<HTMLElement>('button:not(:disabled)')?.focus()
    return
  }

  document.removeEventListener('keydown', handleKeydown, true)
  if (appRoot && !appWasInert) appRoot.inert = false
  previouslyFocused?.focus()
  previouslyFocused = null
  appRoot = null
}, { immediate: true })

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleKeydown, true)
  if (appRoot && !appWasInert) appRoot.inert = false
})
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="unsaved-overlay" @click.self="keepEditing">
      <section
        ref="dialogRef"
        class="unsaved-dialog"
        role="alertdialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        :aria-describedby="descriptionId"
      >
        <header class="unsaved-dialog__header">
          <span class="unsaved-dialog__icon" aria-hidden="true"><AlertTriangle :size="21" /></span>
          <div>
            <h2 :id="titleId">{{ title }}</h2>
            <p :id="descriptionId">{{ message }}</p>
          </div>
          <UiButton
            class="unsaved-dialog__close"
            variant="ghost"
            icon-only
            aria-label="关闭提示并继续填写"
            title="关闭"
            @click="keepEditing"
          >
            <template #icon><X :size="20" /></template>
          </UiButton>
        </header>

        <ModalFooter>
          <UiButton variant="secondary" @click="keepEditing">{{ keepLabel }}</UiButton>
          <UiButton variant="danger" @click="emit('discard')">{{ discardLabel }}</UiButton>
        </ModalFooter>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.unsaved-overlay {
  position: fixed;
  z-index: var(--ds-z-confirm, 1600);
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(19, 39, 38, .42);
}

.unsaved-dialog {
  width: min(100%, 480px);
  overflow: hidden;
  border: 1px solid var(--ds-line, #dce7e5);
  border-radius: 8px;
  background: var(--ds-surface, #fff);
  box-shadow: 0 18px 45px rgba(27, 54, 52, .16);
}

.unsaved-dialog__header {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 44px;
  gap: 12px;
  align-items: start;
  padding: 24px 20px 20px;
}

.unsaved-dialog__icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 50%;
  background: var(--ds-warning-soft, #fff7e8);
  color: var(--ds-warning, #a56714);
}

.unsaved-dialog h2 {
  margin: 1px 0 7px;
  color: var(--ds-text, #182424);
  font-size: 18px;
  line-height: 1.35;
}

.unsaved-dialog p {
  margin: 0;
  color: var(--ds-secondary, #526765);
  font-size: 14px;
  line-height: 1.65;
}

.unsaved-dialog__close {
  margin-top: -8px;
  margin-right: -8px;
}

@media (prefers-reduced-motion: no-preference) {
  .unsaved-dialog { animation: unsaved-fade-in 160ms ease-out; }
}

@keyframes unsaved-fade-in {
  from { opacity: 0; transform: translateY(4px); }
}
</style>
