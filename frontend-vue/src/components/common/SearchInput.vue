<script setup lang="ts">
import { computed, ref } from 'vue'
import { Search } from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  modelValue?: string
  placeholder?: string
  disabled?: boolean
  invalid?: boolean
  ariaLabel?: string
  ariaDescribedby?: string
  inputId?: string
  name?: string
  autocomplete?: string
}>(), {
  modelValue: '',
  placeholder: '搜索',
  disabled: false,
  invalid: false,
  ariaLabel: '',
  ariaDescribedby: undefined,
  inputId: undefined,
  name: undefined,
  autocomplete: 'off',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  focus: [event: FocusEvent]
  blur: [event: FocusEvent]
  search: [event: Event]
}>()

const input = ref<HTMLInputElement | null>(null)
const accessibleLabel = computed(() => props.ariaLabel || props.placeholder || '搜索')

function updateValue(event: Event) {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}

function focus() {
  input.value?.focus()
}

function select() {
  input.value?.select()
}

defineExpose({ focus, select })
</script>

<template>
  <label
    class="search-field"
    :class="{ 'is-disabled': disabled, 'is-error': invalid }"
    :for="inputId"
  >
    <Search class="search-field__icon" :size="18" aria-hidden="true" />
    <input
      :id="inputId"
      ref="input"
      class="search-field__input"
      type="search"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :name="name"
      :autocomplete="autocomplete"
      :aria-label="accessibleLabel"
      :aria-describedby="ariaDescribedby"
      :aria-invalid="invalid ? 'true' : undefined"
      @input="updateValue"
      @focus="emit('focus', $event)"
      @blur="emit('blur', $event)"
      @search="emit('search', $event)"
    />
  </label>
</template>

<style scoped>
.search-field {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 44px;
  min-width: 0;
  padding: 0 12px;
  border: 1px solid #d7e2e0;
  border-radius: 6px;
  background: #fff;
  color: var(--ds-muted, #6f817f);
  cursor: text;
  transition: border-color 160ms ease-out, box-shadow 160ms ease-out, background-color 160ms ease-out;
}

.search-field:not(.is-disabled):not(.is-error):hover {
  border-color: #b9ccc8;
}

.search-field:focus-within {
  border-color: #276b65;
  box-shadow: 0 0 0 2px rgba(39, 107, 101, 0.12);
}

.search-field__icon {
  flex: 0 0 auto;
  pointer-events: none;
}

.search-field > .search-field__input {
  flex: 1;
  width: 100%;
  min-width: 0;
  min-height: 0;
  height: auto;
  margin: 0;
  padding: 0;
  border: 0 !important;
  border-radius: 0 !important;
  outline: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  color: var(--ds-ink, #182424);
  font: inherit;
  cursor: text;
  appearance: none;
  -webkit-appearance: none;
}

.search-field > .search-field__input:focus,
.search-field > .search-field__input:focus-visible {
  border: 0 !important;
  outline: 0 !important;
  box-shadow: none !important;
}

.search-field > .search-field__input::placeholder {
  color: var(--ds-secondary, #526765);
  opacity: 1;
}

.search-field > .search-field__input::-webkit-search-decoration,
.search-field > .search-field__input::-webkit-search-cancel-button,
.search-field > .search-field__input::-webkit-search-results-button,
.search-field > .search-field__input::-webkit-search-results-decoration {
  appearance: none;
  -webkit-appearance: none;
}

.search-field.is-disabled {
  border-color: #dfe8e6;
  background: #f3f7f6;
  color: #879693;
  cursor: not-allowed;
  box-shadow: none;
}

.search-field.is-disabled > .search-field__input {
  color: #6f817f;
  cursor: not-allowed;
}

.search-field.is-error {
  border-color: var(--ds-danger, #dc3545);
}

.search-field.is-error:focus-within {
  border-color: var(--ds-danger, #dc3545);
  box-shadow: 0 0 0 2px rgba(220, 53, 69, 0.12);
}

@media (prefers-reduced-motion: reduce) {
  .search-field {
    transition: none;
  }
}
</style>
