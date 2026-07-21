<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { AlertTriangle, HelpCircle, X } from 'lucide-vue-next'
import ModalFooter from './ModalFooter.vue'
import UiButton from './UiButton.vue'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  message?: string
  confirmLabel?: string
  cancelLabel?: string
  confirmVariant?: 'primary' | 'danger'
  busy?: boolean
  modelValue?: string
  noteLabel?: string
  notePlaceholder?: string
  noteMaxLength?: number
  noteRequired?: boolean
}>(), {
  message: '',
  confirmLabel: '确认',
  cancelLabel: '取消',
  confirmVariant: 'primary',
  busy: false,
  modelValue: '',
  noteLabel: '',
  notePlaceholder: '',
  noteMaxLength: 0,
  noteRequired: false,
})

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void
  (event: 'cancel'): void
  (event: 'confirm'): void
}>()

const dialogRef = ref<HTMLElement | null>(null)
const instanceId = `action-confirm-${Math.random().toString(36).slice(2, 9)}`
const titleId = `${instanceId}-title`
const descriptionId = `${instanceId}-description`
const noteId = `${instanceId}-note`
let previouslyFocused: HTMLElement | null = null
let appRoot: HTMLElement | null = null
let appWasInert = false

const normalizedNoteMaxLength = computed(() => {
  const value = Number(props.noteMaxLength)
  return Number.isSafeInteger(value) && value > 0 ? value : 0
})
const noteTooLong = computed(() => Boolean(normalizedNoteMaxLength.value && props.modelValue.length > normalizedNoteMaxLength.value))
const confirmDisabled = computed(() => props.busy || (props.noteRequired && !props.modelValue.trim()) || noteTooLong.value)

function cancel() {
  if (!props.busy) emit('cancel')
}

function handleKeydown(event: KeyboardEvent) {
  if (!props.open) return
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    cancel()
    return
  }
  if (event.key !== 'Tab') return
  const focusable = Array.from(dialogRef.value?.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [href], [tabindex]:not([tabindex="-1"])',
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

watch(() => props.open, async (open) => {
  if (open) {
    previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    appRoot = document.getElementById('app')
    appWasInert = Boolean(appRoot?.inert)
    if (appRoot) appRoot.inert = true
    document.addEventListener('keydown', handleKeydown, true)
    await nextTick()
    const preferred = dialogRef.value?.querySelector<HTMLElement>('[data-autofocus]')
    ;(preferred || dialogRef.value?.querySelector<HTMLElement>('button:not(:disabled)'))?.focus()
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
    <div v-if="open" class="action-confirm-overlay" @click.self="cancel">
      <section
        ref="dialogRef"
        class="action-confirm-dialog"
        role="alertdialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        :aria-describedby="message ? descriptionId : undefined"
      >
        <header class="action-confirm-dialog__header">
          <span class="action-confirm-dialog__icon" :class="{ danger: confirmVariant === 'danger' }" aria-hidden="true">
            <AlertTriangle v-if="confirmVariant === 'danger'" :size="21" />
            <HelpCircle v-else :size="21" />
          </span>
          <div>
            <h2 :id="titleId">{{ title }}</h2>
            <p v-if="message" :id="descriptionId">{{ message }}</p>
          </div>
          <UiButton
            variant="ghost"
            icon-only
            type="button"
            aria-label="关闭确认窗口"
            title="关闭"
            :disabled="busy"
            @click="cancel"
          >
            <template #icon><X :size="20" /></template>
          </UiButton>
        </header>

        <div v-if="noteLabel" class="action-confirm-dialog__body">
          <label :for="noteId">{{ noteLabel }}</label>
          <textarea
            :id="noteId"
            data-autofocus
            rows="4"
            :value="modelValue"
            :placeholder="notePlaceholder"
            :disabled="busy"
            :maxlength="normalizedNoteMaxLength || undefined"
            @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
          />
          <span v-if="normalizedNoteMaxLength" class="action-confirm-dialog__hint">
            最多 {{ normalizedNoteMaxLength }} 个字符（当前 {{ modelValue.length }}/{{ normalizedNoteMaxLength }}）。
          </span>
          <span v-if="noteRequired && !modelValue.trim()" class="action-confirm-dialog__hint">请填写后再确认。</span>
        </div>

        <ModalFooter>
          <UiButton variant="secondary" type="button" :disabled="busy" @click="cancel">{{ cancelLabel }}</UiButton>
          <UiButton
            :variant="confirmVariant"
            type="button"
            :loading="busy"
            :disabled="confirmDisabled"
            @click="emit('confirm')"
          >
            {{ confirmLabel }}
          </UiButton>
        </ModalFooter>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.action-confirm-overlay {
  position: fixed;
  z-index: var(--ds-z-confirm, 1600);
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(19, 39, 38, .42);
}

.action-confirm-dialog {
  width: min(100%, 500px);
  overflow: hidden;
  border: 1px solid var(--ds-line, #dce7e5);
  border-radius: 8px;
  background: var(--ds-surface, #fff);
  box-shadow: 0 18px 45px rgba(27, 54, 52, .16);
}

.action-confirm-dialog__header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 44px;
  gap: 12px;
  align-items: start;
  padding: 24px 20px 20px;
}

.action-confirm-dialog__icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 50%;
  background: var(--ds-primary-soft, #e9f6f5);
  color: var(--ds-action-primary, #276b65);
}

.action-confirm-dialog__icon.danger {
  background: var(--ds-danger-soft, #fff0f1);
  color: var(--ds-danger, #c33f4d);
}

.action-confirm-dialog h2 {
  margin: 1px 0 7px;
  color: var(--ds-text, #182424);
  font-size: 18px;
  line-height: 1.35;
}

.action-confirm-dialog p {
  margin: 0;
  color: var(--ds-secondary, #526765);
  font-size: 14px;
  line-height: 1.65;
}

.action-confirm-dialog__body {
  display: grid;
  gap: 8px;
  padding: 0 20px 20px 72px;
}

.action-confirm-dialog__body label {
  color: var(--ds-text, #182424);
  font-size: 14px;
  font-weight: 600;
}

.action-confirm-dialog__body textarea {
  width: 100%;
  min-height: 96px;
  resize: vertical;
  border: 1px solid var(--ds-action-secondary-border, #c9d7d5);
  border-radius: 6px;
  padding: 11px 12px;
  color: var(--ds-text, #182424);
  font: inherit;
  line-height: 1.55;
}

.action-confirm-dialog__body textarea:focus-visible {
  border-color: var(--ds-action-primary, #276b65);
  outline: 3px solid rgba(39, 107, 101, .18);
  outline-offset: 1px;
}

.action-confirm-dialog__hint {
  color: var(--ds-danger, #c33f4d);
  font-size: 12px;
}

@media (max-width: 560px) {
  .action-confirm-dialog__body {
    padding: 0 16px 16px;
  }
}

@media (prefers-reduced-motion: no-preference) {
  .action-confirm-dialog { animation: action-confirm-fade-in 160ms ease-out; }
}

@keyframes action-confirm-fade-in {
  from { opacity: 0; transform: translateY(4px); }
}
</style>
