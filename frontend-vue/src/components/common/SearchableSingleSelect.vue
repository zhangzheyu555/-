<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Check, ChevronDown, Search, X } from 'lucide-vue-next'

export type SearchableSelectValue = string | number

export interface SearchableSelectOption {
  value: SearchableSelectValue
  label: string
  /** Additional searchable/display text, for example code, region, status, or unit. */
  description?: string
  /** Extra text that should match a search but does not need to be displayed. */
  searchText?: string
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  modelValue?: SearchableSelectValue | null
  options: SearchableSelectOption[]
  placeholder?: string
  searchPlaceholder?: string
  ariaLabel?: string
  disabled?: boolean
  loading?: boolean
  emptyOptionLabel?: string
  emptyValue?: SearchableSelectValue
  emptyMessage?: string
  /** A safe display label when a persisted current value is not present in the current option page. */
  fallbackLabel?: string
}>(), {
  modelValue: null,
  placeholder: '请选择',
  searchPlaceholder: '搜索名称、编号或关键词',
  ariaLabel: '搜索选择项',
  disabled: false,
  loading: false,
  emptyOptionLabel: undefined,
  emptyValue: '',
  emptyMessage: '没有匹配的选项',
  fallbackLabel: '',
})

const emit = defineEmits<{
  'update:modelValue': [value: SearchableSelectValue]
  open: []
  close: []
}>()

const root = ref<HTMLElement | null>(null)
const input = ref<HTMLInputElement | null>(null)
const open = ref(false)
const keyword = ref('')
const activeIndex = ref(-1)
const committedValue = ref<SearchableSelectValue | null>(props.modelValue ?? null)
const listboxId = `searchable-single-select-${Math.random().toString(36).slice(2)}`
let suppressFocusOpen = false
let focusReleaseTimer: ReturnType<typeof setTimeout> | undefined

const normalizedKeyword = computed(() => keyword.value.trim().toLocaleLowerCase('zh-CN'))
const selectedOption = computed(() => props.options.find((option) => sameValue(option.value, committedValue.value)))
const visibleOptions = computed(() => {
  const query = normalizedKeyword.value
  if (!query) return props.options
  return props.options.filter((option) => searchableText(option).includes(query))
})
const hasEmptyOption = computed(() => props.emptyOptionLabel !== undefined)
const displayValue = computed(() => (open.value
  ? keyword.value
  : selectedOption.value?.label || emptyLabel.value || props.fallbackLabel || (committedValue.value == null ? '' : String(committedValue.value))))
const emptyLabel = computed(() => sameValue(committedValue.value, props.emptyValue) ? props.emptyOptionLabel : '')

function sameValue(left: SearchableSelectValue | null | undefined, right: SearchableSelectValue | null | undefined) {
  return left !== null && left !== undefined && right !== null && right !== undefined && String(left) === String(right)
}

function searchableText(option: SearchableSelectOption) {
  return [option.label, option.description, option.searchText]
    .filter((value): value is string => Boolean(value))
    .join(' ')
    .toLocaleLowerCase('zh-CN')
}

function openMenu() {
  if (props.disabled || suppressFocusOpen) return
  if (!open.value) {
    open.value = true
    keyword.value = ''
    activeIndex.value = visibleOptions.value.findIndex((option) => sameValue(option.value, committedValue.value))
    emit('open')
  }
  void nextTick(() => {
    if (open.value && document.activeElement !== input.value) input.value?.focus()
  })
}

function handleInputFocus() {
  if (!suppressFocusOpen) openMenu()
}

function allowInputOpen() {
  suppressFocusOpen = false
  if (focusReleaseTimer) {
    clearTimeout(focusReleaseTimer)
    focusReleaseTimer = undefined
  }
}

function preventImmediateFocusReopen() {
  suppressFocusOpen = true
  if (focusReleaseTimer) clearTimeout(focusReleaseTimer)
  focusReleaseTimer = setTimeout(() => {
    suppressFocusOpen = false
    focusReleaseTimer = undefined
  }, 120)
}

function closeMenu() {
  if (!open.value) return
  open.value = false
  keyword.value = ''
  activeIndex.value = -1
  emit('close')
}

function updateKeyword(event: Event) {
  keyword.value = (event.target as HTMLInputElement).value
  activeIndex.value = visibleOptions.value.findIndex((option) => sameValue(option.value, committedValue.value))
}

function selectOption(option: SearchableSelectOption) {
  if (option.disabled) return
  preventImmediateFocusReopen()
  committedValue.value = option.value
  emit('update:modelValue', option.value)
  closeMenu()
}

function selectEmpty() {
  preventImmediateFocusReopen()
  committedValue.value = props.emptyValue
  emit('update:modelValue', props.emptyValue)
  closeMenu()
}

function moveActive(direction: 1 | -1) {
  const enabledOptions = visibleOptions.value.filter((option) => !option.disabled)
  if (!enabledOptions.length) return
  const current = activeIndex.value < 0
    ? -1
    : enabledOptions.findIndex((option) => sameValue(option.value, visibleOptions.value[activeIndex.value]?.value))
  const next = current < 0
    ? (direction === 1 ? 0 : enabledOptions.length - 1)
    : (current + direction + enabledOptions.length) % enabledOptions.length
  activeIndex.value = visibleOptions.value.findIndex((option) => sameValue(option.value, enabledOptions[next].value))
}

function handleKeydown(event: KeyboardEvent) {
  if (props.disabled) return
  if (!open.value && ['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(event.key)) {
    event.preventDefault()
    openMenu()
    return
  }
  if (!open.value) return
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    moveActive(1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    moveActive(-1)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    if (activeIndex.value >= 0) selectOption(visibleOptions.value[activeIndex.value])
    else if (hasEmptyOption.value && !normalizedKeyword.value) selectEmpty()
  } else if (event.key === 'Escape') {
    event.preventDefault()
    closeMenu()
  }
}

function handleDocumentPointerDown(event: PointerEvent) {
  if (root.value && !root.value.contains(event.target as Node)) closeMenu()
}

onMounted(() => document.addEventListener('pointerdown', handleDocumentPointerDown))
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  if (focusReleaseTimer) clearTimeout(focusReleaseTimer)
})

watch(() => props.modelValue, (value) => {
  committedValue.value = value ?? null
})

defineExpose({ focus: openMenu, close: closeMenu })
</script>

<template>
  <div ref="root" class="searchable-single-select" :class="{ 'is-open': open, 'is-disabled': disabled }">
    <div class="searchable-single-select__control">
      <Search :size="16" aria-hidden="true" />
      <input
        ref="input"
        type="search"
        role="combobox"
        :value="displayValue"
        :placeholder="open ? searchPlaceholder : placeholder"
        :disabled="disabled"
        :aria-label="ariaLabel"
        :aria-expanded="open ? 'true' : 'false'"
        :aria-controls="listboxId"
        :aria-activedescendant="activeIndex >= 0 ? `${listboxId}-option-${activeIndex}` : undefined"
        autocomplete="off"
        @pointerdown="allowInputOpen"
        @focus="handleInputFocus"
        @click="openMenu"
        @input="updateKeyword"
        @keydown="handleKeydown"
      />
      <button
        v-if="open && keyword"
        class="searchable-single-select__clear-search"
        type="button"
        aria-label="清空搜索"
        @pointerdown.prevent
        @click="keyword = ''"
      >
        <X :size="15" aria-hidden="true" />
      </button>
      <ChevronDown class="searchable-single-select__chevron" :size="17" aria-hidden="true" />
    </div>

    <div v-if="open" :id="listboxId" class="searchable-single-select__menu" role="listbox" :aria-label="ariaLabel">
      <button
        v-if="hasEmptyOption && !normalizedKeyword"
        type="button"
        class="searchable-single-select__option"
        :class="{ selected: sameValue(committedValue, emptyValue) }"
        role="option"
        :aria-selected="sameValue(committedValue, emptyValue) ? 'true' : 'false'"
        @pointerdown.prevent
        @click="selectEmpty"
      >
        <span>{{ emptyOptionLabel }}</span>
        <Check v-if="sameValue(committedValue, emptyValue)" :size="16" aria-hidden="true" />
      </button>
      <div v-if="loading" class="searchable-single-select__status" role="status">正在加载选项…</div>
      <template v-else-if="visibleOptions.length">
        <button
          v-for="(option, index) in visibleOptions"
          :id="`${listboxId}-option-${index}`"
          :key="String(option.value)"
          type="button"
          class="searchable-single-select__option"
          :class="{ selected: sameValue(option.value, committedValue), active: index === activeIndex }"
          :disabled="option.disabled"
          role="option"
          :aria-selected="sameValue(option.value, committedValue) ? 'true' : 'false'"
          @pointerdown.prevent
          @mouseenter="activeIndex = index"
          @click="selectOption(option)"
        >
          <span class="searchable-single-select__option-copy">
            <slot name="option" :option="option" :selected="sameValue(option.value, committedValue)">
              <b>{{ option.label }}</b>
              <small v-if="option.description">{{ option.description }}</small>
            </slot>
          </span>
          <Check v-if="sameValue(option.value, committedValue)" :size="16" aria-hidden="true" />
        </button>
      </template>
      <div v-else class="searchable-single-select__status" role="status">{{ emptyMessage }}</div>
    </div>
  </div>
</template>

<style scoped>
.searchable-single-select { position: relative; min-width: 0; }
.searchable-single-select__control { display: flex; align-items: center; gap: 8px; min-height: 38px; padding: 0 10px; border: 1px solid var(--ds-line, #d7e2e0); border-radius: 6px; background: #fff; color: var(--ds-muted, #6f817f); transition: border-color 160ms ease, box-shadow 160ms ease; }
.searchable-single-select.is-open .searchable-single-select__control { border-color: var(--ds-primary, #276b65); box-shadow: 0 0 0 2px rgba(39, 107, 101, .12); }
.searchable-single-select__control input { min-width: 0; flex: 1; height: 36px; margin: 0; padding: 0; border: 0 !important; outline: 0; background: transparent !important; color: var(--ds-ink, #182424); font: inherit; }
.searchable-single-select__control input::placeholder { color: var(--ds-secondary, #526765); opacity: 1; }
.searchable-single-select__clear-search { display: inline-grid; width: 24px; height: 24px; place-items: center; border: 0; border-radius: 4px; background: transparent; color: var(--ds-secondary, #526765); cursor: pointer; }
.searchable-single-select__clear-search:hover { background: var(--ds-surface-muted, #eef4f3); }
.searchable-single-select__chevron { flex: 0 0 auto; transition: transform 160ms ease; }
.is-open .searchable-single-select__chevron { transform: rotate(180deg); }
.searchable-single-select__menu { position: absolute; z-index: 40; top: calc(100% + 4px); right: 0; left: 0; max-height: min(320px, 48vh); overflow: auto; border: 1px solid var(--ds-line, #d7e2e0); border-radius: 8px; background: #fff; box-shadow: 0 14px 28px rgba(32, 59, 56, .16); }
.searchable-single-select__option { display: flex; width: 100%; min-height: 42px; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 11px; border: 0; border-bottom: 1px solid var(--ds-line, #e5ecea); background: #fff; color: var(--ds-ink, #182424); text-align: left; cursor: pointer; }
.searchable-single-select__option:last-child { border-bottom: 0; }
.searchable-single-select__option:hover, .searchable-single-select__option.active { background: var(--ds-surface-muted, #eef4f3); }
.searchable-single-select__option.selected { color: var(--ds-primary, #276b65); }
.searchable-single-select__option:disabled { color: var(--ds-muted, #6f817f); cursor: not-allowed; }
.searchable-single-select__option-copy { display: grid; gap: 2px; min-width: 0; }
.searchable-single-select__option-copy b, .searchable-single-select__option-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.searchable-single-select__option-copy b { font-size: 13px; }
.searchable-single-select__option-copy small { color: var(--ds-secondary, #526765); font-size: 12px; }
.searchable-single-select__status { padding: 14px 12px; color: var(--ds-muted, #6f817f); font-size: 13px; text-align: center; }
.searchable-single-select.is-disabled .searchable-single-select__control { background: var(--ds-surface-muted, #f3f7f6); color: #879693; cursor: not-allowed; }
.searchable-single-select.is-disabled input { cursor: not-allowed; }
@media (max-width: 768px) {
  .searchable-single-select__control {
    min-height: 44px;
  }

  .searchable-single-select__control input {
    height: 44px;
    min-height: 44px;
  }

  .searchable-single-select__option {
    min-height: 44px;
  }
}
@media (prefers-reduced-motion: reduce) { .searchable-single-select__control, .searchable-single-select__chevron { transition: none; } }
</style>
