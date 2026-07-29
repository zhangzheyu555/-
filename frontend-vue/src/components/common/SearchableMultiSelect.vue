<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ChevronDown, Search } from 'lucide-vue-next'
import type { SearchableSelectOption, SearchableSelectValue } from './SearchableSingleSelect.vue'

const props = withDefaults(defineProps<{
  modelValue?: SearchableSelectValue[]
  options: SearchableSelectOption[]
  searchPlaceholder?: string
  ariaLabel?: string
  selectedNoun?: string
  disabled?: boolean
  loading?: boolean
  emptyMessage?: string
  selectAllLabel?: string
  clearLabel?: string
  compact?: boolean
  placeholder?: string
}>(), {
  modelValue: () => [],
  searchPlaceholder: '搜索名称、编号或关键词',
  ariaLabel: '搜索并多选',
  selectedNoun: '项',
  disabled: false,
  loading: false,
  emptyMessage: '没有匹配的选项',
  selectAllLabel: '全选当前结果',
  clearLabel: '清空已选',
  compact: false,
  placeholder: '请选择',
})

const emit = defineEmits<{
  'update:modelValue': [value: SearchableSelectValue[]]
  open: []
  close: []
}>()

const root = ref<HTMLElement | null>(null)
const trigger = ref<HTMLButtonElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const panelOpen = ref(false)
const keyword = ref('')
const committedValues = ref<SearchableSelectValue[]>([...props.modelValue])
const panelId = `searchable-multi-select-${Math.random().toString(36).slice(2, 9)}`
const normalizedKeyword = computed(() => keyword.value.trim().toLocaleLowerCase('zh-CN'))
const selectedKeys = computed(() => new Set(committedValues.value.map((value) => String(value))))
const filteredOptions = computed(() => {
  const query = normalizedKeyword.value
  if (!query) return props.options
  return props.options.filter((option) => searchableText(option).includes(query))
})
const selectableFilteredOptions = computed(() => filteredOptions.value.filter((option) => !option.disabled))
const selectedCount = computed(() => selectedKeys.value.size)
const allVisibleSelected = computed(() => selectableFilteredOptions.value.length > 0
  && selectableFilteredOptions.value.every((option) => selectedKeys.value.has(String(option.value))))
const triggerText = computed(() => selectedCount.value
  ? `已选择 ${selectedCount.value} ${props.selectedNoun}`
  : props.placeholder)

function searchableText(option: SearchableSelectOption) {
  return [option.label, option.description, option.searchText]
    .filter((value): value is string => Boolean(value))
    .join(' ')
    .toLocaleLowerCase('zh-CN')
}

function isSelected(option: SearchableSelectOption) {
  return selectedKeys.value.has(String(option.value))
}

function commitSelection(next: SearchableSelectValue[]) {
  committedValues.value = next
  emit('update:modelValue', next)
}

function updateSelection(option: SearchableSelectOption, checked: boolean) {
  if (props.disabled || option.disabled) return
  const key = String(option.value)
  const next = committedValues.value.filter((value) => String(value) !== key)
  if (checked) next.push(option.value)
  commitSelection(next)
}

function toggleAllVisible() {
  if (props.disabled || !selectableFilteredOptions.value.length) return
  if (allVisibleSelected.value) {
    const keys = new Set(selectableFilteredOptions.value.map((option) => String(option.value)))
    commitSelection(committedValues.value.filter((value) => !keys.has(String(value))))
    return
  }
  const next = [...committedValues.value]
  const existing = new Set(next.map((value) => String(value)))
  for (const option of selectableFilteredOptions.value) {
    if (!existing.has(String(option.value))) next.push(option.value)
  }
  commitSelection(next)
}

function clearSelection() {
  if (props.disabled || !committedValues.value.length) return
  commitSelection([])
}

function openPanel() {
  if (props.disabled || !props.compact || panelOpen.value) return
  panelOpen.value = true
  emit('open')
  void nextTick(() => searchInput.value?.focus())
}

function closePanel(restoreFocus = false) {
  if (!panelOpen.value) return
  panelOpen.value = false
  keyword.value = ''
  emit('close')
  if (restoreFocus) void nextTick(() => trigger.value?.focus())
}

function togglePanel() {
  if (panelOpen.value) closePanel()
  else openPanel()
}

function handleTriggerKeydown(event: KeyboardEvent) {
  if (['ArrowDown', 'Enter', ' '].includes(event.key)) {
    event.preventDefault()
    openPanel()
  } else if (event.key === 'Escape') {
    closePanel(true)
  }
}

function handlePanelKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  event.preventDefault()
  closePanel(true)
}

function handleDocumentPointerDown(event: PointerEvent) {
  if (props.compact && root.value && !root.value.contains(event.target as Node)) closePanel()
}

watch(() => props.disabled, (disabled) => {
  if (disabled) closePanel()
})
watch(() => props.modelValue, (values) => {
  committedValues.value = [...values]
}, { deep: true })

onMounted(() => document.addEventListener('pointerdown', handleDocumentPointerDown))
onBeforeUnmount(() => document.removeEventListener('pointerdown', handleDocumentPointerDown))

defineExpose({ open: openPanel, close: closePanel })
</script>

<template>
  <section
    ref="root"
    class="searchable-multi-select"
    :class="{ 'is-disabled': disabled, 'is-compact': compact, 'is-open': panelOpen }"
  >
    <button
      v-if="compact"
      ref="trigger"
      class="searchable-multi-select__trigger"
      type="button"
      :disabled="disabled"
      :aria-label="`${ariaLabel}：${triggerText}`"
      :aria-expanded="panelOpen ? 'true' : 'false'"
      :aria-controls="panelId"
      @click="togglePanel"
      @keydown="handleTriggerKeydown"
    >
      <span>{{ loading ? '正在加载选项…' : triggerText }}</span>
      <ChevronDown :size="17" aria-hidden="true" />
    </button>

    <div
      v-if="!compact || panelOpen"
      :id="compact ? panelId : undefined"
      class="searchable-multi-select__panel"
      @keydown="handlePanelKeydown"
    >
      <div class="searchable-multi-select__search">
        <Search :size="16" aria-hidden="true" />
        <input
          ref="searchInput"
          v-model="keyword"
          type="search"
          :placeholder="searchPlaceholder"
          :disabled="disabled"
          :aria-label="ariaLabel"
          autocomplete="off"
          @keydown.enter.prevent
        />
      </div>
      <div class="searchable-multi-select__summary" aria-live="polite">
        <slot name="summary" :selected-count="selectedCount" :visible-count="filteredOptions.length">
          <strong>已选择 {{ selectedCount }} {{ selectedNoun }}</strong>
          <span>当前结果 {{ filteredOptions.length }} 项</span>
        </slot>
      </div>
      <div class="searchable-multi-select__actions">
        <button type="button" :disabled="disabled || !selectableFilteredOptions.length" @click="toggleAllVisible">
          {{ allVisibleSelected ? '取消全选当前结果' : selectAllLabel }}
        </button>
        <button type="button" :disabled="disabled || !committedValues.length" @click="clearSelection">{{ clearLabel }}</button>
      </div>
      <div v-if="loading" class="searchable-multi-select__status" role="status">正在加载选项…</div>
      <div v-else-if="filteredOptions.length" class="searchable-multi-select__list">
        <label
          v-for="option in filteredOptions"
          :key="String(option.value)"
          class="searchable-multi-select__option"
          :class="{ selected: isSelected(option), disabled: option.disabled }"
        >
          <input
            type="checkbox"
            :checked="isSelected(option)"
            :disabled="disabled || option.disabled"
            @change="updateSelection(option, ($event.target as HTMLInputElement).checked)"
          />
          <span class="searchable-multi-select__option-copy">
            <slot name="option" :option="option" :selected="isSelected(option)">
              <b>{{ option.label }}</b>
              <small v-if="option.description">{{ option.description }}</small>
            </slot>
          </span>
        </label>
      </div>
      <div v-else class="searchable-multi-select__status" role="status">{{ emptyMessage }}</div>
      <button
        v-if="compact"
        class="searchable-multi-select__done"
        type="button"
        @click="closePanel(true)"
      >
        完成选择
      </button>
    </div>
  </section>
</template>

<style scoped>
.searchable-multi-select { position: relative; display: grid; min-width: 0; }
.searchable-multi-select__panel { display: grid; gap: 9px; min-width: 0; padding: 10px; border: 1px solid var(--ds-line, #d7e2e0); border-radius: 8px; background: #fff; }
.searchable-multi-select__trigger { display: flex; width: 100%; min-height: 40px; align-items: center; justify-content: space-between; gap: 10px; padding: 0 11px; border: 1px solid var(--ds-line, #d7e2e0); border-radius: 6px; background: #fff; color: var(--ds-ink, #182424); font: inherit; text-align: left; cursor: pointer; }
.searchable-multi-select__trigger span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.searchable-multi-select__trigger svg { flex: 0 0 auto; color: var(--ds-muted, #6f817f); transition: transform 160ms ease; }
.searchable-multi-select.is-open .searchable-multi-select__trigger { border-color: var(--ds-primary, #276b65); box-shadow: 0 0 0 2px rgba(39, 107, 101, .12); }
.searchable-multi-select.is-open .searchable-multi-select__trigger svg { transform: rotate(180deg); }
.searchable-multi-select.is-compact .searchable-multi-select__panel { position: absolute; z-index: 45; top: calc(100% + 5px); right: 0; left: 0; min-width: min(420px, calc(100vw - 32px)); max-height: min(520px, 70vh); box-shadow: 0 16px 34px rgba(32, 59, 56, .18); }
.searchable-multi-select__search { display: flex; min-width: 0; align-items: center; gap: 8px; min-height: 38px; padding: 0 10px; border: 1px solid var(--ds-line, #d7e2e0); border-radius: 6px; color: var(--ds-muted, #6f817f); }
.searchable-multi-select__search:focus-within { border-color: var(--ds-primary, #276b65); box-shadow: 0 0 0 2px rgba(39, 107, 101, .12); }
.searchable-multi-select__search input { flex: 1; min-width: 0; height: 36px; margin: 0; padding: 0; border: 0 !important; outline: 0; background: transparent !important; color: var(--ds-ink, #182424); font: inherit; }
.searchable-multi-select__summary { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 4px 10px; color: var(--ds-secondary, #526765); font-size: 12px; }
.searchable-multi-select__summary strong { color: var(--ds-ink, #182424); font-size: 13px; }
.searchable-multi-select__actions { display: flex; flex-wrap: wrap; gap: 8px; }
.searchable-multi-select__actions button { min-height: 30px; padding: 4px 9px; border: 1px solid var(--ds-line, #d7e2e0); border-radius: 5px; background: #fff; color: var(--ds-primary, #276b65); font: inherit; font-size: 12px; font-weight: 800; cursor: pointer; }
.searchable-multi-select__actions button:hover:not(:disabled) { border-color: var(--ds-primary, #276b65); background: var(--ds-surface-muted, #eef4f3); }
.searchable-multi-select__actions button:disabled { color: var(--ds-muted, #97a5a2); cursor: not-allowed; }
.searchable-multi-select__list { display: grid; max-height: 245px; overflow: auto; overscroll-behavior: contain; border-top: 1px solid var(--ds-line, #e5ecea); }
.searchable-multi-select.is-compact .searchable-multi-select__list { max-height: min(300px, 42vh); }
.searchable-multi-select__option { display: flex; align-items: flex-start; gap: 8px; min-width: 0; padding: 8px 2px; border-bottom: 1px solid var(--ds-line, #edf2f1); cursor: pointer; }
.searchable-multi-select__option:last-child { border-bottom: 0; }
.searchable-multi-select__option.selected { color: var(--ds-primary, #276b65); }
.searchable-multi-select__option.disabled { color: var(--ds-muted, #879693); cursor: not-allowed; }
.searchable-multi-select__option input { flex: 0 0 auto; margin-top: 3px; }
.searchable-multi-select__option-copy { display: grid; min-width: 0; gap: 2px; }
.searchable-multi-select__option-copy b, .searchable-multi-select__option-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.searchable-multi-select__option-copy b { font-size: 13px; }
.searchable-multi-select__option-copy small { color: var(--ds-secondary, #526765); font-size: 12px; }
.searchable-multi-select__status { padding: 14px 8px; color: var(--ds-muted, #6f817f); font-size: 13px; text-align: center; }
.searchable-multi-select__done { min-height: 36px; border: 1px solid var(--ds-primary, #276b65); border-radius: 6px; background: var(--ds-primary, #276b65); color: #fff; font: inherit; font-weight: 800; cursor: pointer; }
.searchable-multi-select.is-disabled .searchable-multi-select__panel,
.searchable-multi-select.is-disabled .searchable-multi-select__trigger { background: var(--ds-surface-muted, #f3f7f6); color: var(--ds-muted, #879693); cursor: not-allowed; }
@media (max-width: 480px) {
  .searchable-multi-select__actions button { flex: 1; }
  .searchable-multi-select.is-compact .searchable-multi-select__panel { right: auto; left: 0; width: calc(100vw - 32px); min-width: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .searchable-multi-select__trigger svg { transition: none; }
}
</style>
