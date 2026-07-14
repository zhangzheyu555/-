<script setup lang="ts">
import { LoaderCircle } from 'lucide-vue-next'

withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost'
  type?: 'button' | 'submit' | 'reset'
  disabled?: boolean
  loading?: boolean
  iconOnly?: boolean
}>(), {
  variant: 'secondary',
  type: 'button',
  disabled: false,
  loading: false,
  iconOnly: false,
})
</script>

<template>
  <button
    class="ui-button"
    :class="[`ui-button--${variant}`, { 'ui-button--icon-only': iconOnly }]"
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
  >
    <LoaderCircle v-if="loading" class="ui-button__spinner" :size="17" aria-hidden="true" />
    <slot v-else name="icon" />
    <span v-if="!iconOnly" class="ui-button__label"><slot /></span>
  </button>
</template>

<style scoped>
.ui-button {
  display: inline-flex;
  flex: 0 0 auto;
  min-width: 88px;
  height: 44px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 20px;
  border: 1px solid var(--ds-action-secondary-border, #c9d7d5);
  border-radius: 6px;
  background: var(--ds-surface, #fff);
  color: var(--ds-action-secondary-text, #284746);
  font-size: 15px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
  box-shadow: none;
  cursor: pointer;
  transition: border-color 160ms ease-out, background-color 160ms ease-out, color 160ms ease-out;
}

.ui-button__label {
  min-width: 0;
  white-space: nowrap;
}

.ui-button:hover:not(:disabled) {
  border-color: var(--ds-primary, #76bdb8);
  background: var(--ds-action-secondary-hover, #f3f8f7);
  color: var(--ds-primary-hover, #285f5c);
}

.ui-button:focus-visible {
  outline: 3px solid rgba(39, 107, 101, .22);
  outline-offset: 2px;
}

.ui-button:active:not(:disabled) {
  background: var(--ds-primary-soft, #e9f6f5);
}

.ui-button:disabled {
  cursor: not-allowed;
  opacity: .55;
}

.ui-button--primary {
  min-width: 144px;
  border-color: var(--ds-action-primary, #276b65);
  background: var(--ds-action-primary, #276b65);
  color: #fff;
}

.ui-button--primary:hover:not(:disabled) {
  border-color: var(--ds-action-primary-hover, #1f5651);
  background: var(--ds-action-primary-hover, #1f5651);
  color: #fff;
}

.ui-button--primary:active:not(:disabled) {
  background: var(--ds-action-primary-active, #17433f);
}

.ui-button--danger {
  min-width: 112px;
  border-color: var(--ds-danger, #c33f4d);
  background: var(--ds-danger, #c33f4d);
  color: #fff;
}

.ui-button--danger:hover:not(:disabled) {
  border-color: var(--ds-danger, #dc3545);
  background: var(--ds-danger, #dc3545);
  color: #fff;
}

.ui-button--ghost {
  min-width: 44px;
  border-color: transparent;
  background: transparent;
  color: var(--ds-secondary, #526765);
}

.ui-button--ghost:hover:not(:disabled) {
  border-color: var(--ds-line, #d5e2e0);
  background: var(--ds-action-secondary-hover, #f3f8f7);
  color: var(--ds-primary-hover, #285f5c);
}

.ui-button--icon-only {
  width: 44px;
  min-width: 44px;
  padding: 0;
}

.ui-button__spinner {
  animation: ui-button-spin .8s linear infinite;
}

@keyframes ui-button-spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .ui-button { transition: none; }
  .ui-button__spinner { animation: none; }
}
</style>
