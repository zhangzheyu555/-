<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Search, X } from 'lucide-vue-next'
import type { SalaryAssignmentCandidate } from '../../api/finance'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'

const props = defineProps<{
  show: boolean
  candidates: SalaryAssignmentCandidate[]
  loading: boolean
  saving: boolean
  error: string
  targetStoreName: string
  month: string
}>()

const emit = defineEmits<{
  close: []
  submit: [employeeId: string]
}>()

const dialogRef = ref<HTMLElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const searchTerm = ref('')
const selectedEmployeeId = ref('')
const instanceId = `salary-add-employee-${Math.random().toString(36).slice(2, 9)}`
const titleId = `${instanceId}-title`
const descriptionId = `${instanceId}-description`
const searchId = `${instanceId}-search`
const resultsId = `${instanceId}-results`
let previouslyFocused: HTMLElement | null = null
let appRoot: HTMLElement | null = null
let appWasInert = false

const filteredCandidates = computed(() => {
  const keyword = searchTerm.value.trim().toLocaleLowerCase()
  if (!keyword) return props.candidates
  return props.candidates.filter((candidate) => [
    candidate.employeeName,
    candidate.sourceStoreName,
    candidate.position || '',
  ].some((value) => value.toLocaleLowerCase().includes(keyword)))
})

const canSubmit = computed(() => Boolean(selectedEmployeeId.value) && !props.loading && !props.saving)

watch(() => props.show, async (show) => {
  if (show) {
    searchTerm.value = ''
    selectedEmployeeId.value = ''
    previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    appRoot = document.getElementById('app')
    appWasInert = Boolean(appRoot?.inert)
    if (appRoot) appRoot.inert = true
    document.addEventListener('keydown', handleKeydown, true)
    await nextTick()
    const initialControl = searchInput.value?.disabled
      ? dialogRef.value?.querySelector<HTMLElement>('button:not(:disabled), input:not(:disabled)')
      : searchInput.value
    ;(initialControl || dialogRef.value)?.focus()
    return
  }

  releaseDialogFocus()
}, { immediate: true })

watch(() => props.candidates, (candidates) => {
  if (selectedEmployeeId.value && !candidates.some((candidate) => candidate.employeeId === selectedEmployeeId.value)) {
    selectedEmployeeId.value = ''
  }
})

onBeforeUnmount(releaseDialogFocus)

function requestClose() {
  if (props.saving) return
  emit('close')
}

function submit() {
  if (!canSubmit.value) return
  emit('submit', selectedEmployeeId.value)
}

function handleKeydown(event: KeyboardEvent) {
  if (!props.show) return
  if (event.key === 'Escape') {
    if (props.saving) return
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
</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="salary-add-backdrop" @click.self="requestClose">
      <section
        ref="dialogRef"
        class="salary-add-dialog"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        :aria-describedby="descriptionId"
        tabindex="-1"
      >
        <header class="salary-add-dialog__head">
          <div>
            <h3 :id="titleId">添加人员</h3>
            <p :id="descriptionId">
              将员工加入 <b>{{ targetStoreName }}</b> 的 {{ month }} 工资名单。仅影响当月工资，不会修改员工档案所属门店，岗位也保持不变。
            </p>
          </div>
          <UiButton
            variant="ghost"
            icon-only
            type="button"
            aria-label="关闭添加人员窗口"
            title="关闭"
            :disabled="saving"
            @click="requestClose"
          >
            <template #icon><X :size="18" /></template>
          </UiButton>
        </header>

        <form class="salary-add-dialog__content" @submit.prevent="submit">
          <div class="salary-add-dialog__body">
            <div v-if="error" class="salary-add-error" role="alert">{{ error }}</div>

            <label class="salary-add-search" :for="searchId">
              <span class="visually-hidden">搜索员工</span>
              <Search :size="18" aria-hidden="true" />
              <input
                :id="searchId"
                ref="searchInput"
                v-model="searchTerm"
                type="search"
                autocomplete="off"
                placeholder="搜索姓名、原门店或岗位"
                :disabled="loading || saving"
                :aria-controls="resultsId"
              />
            </label>

            <div class="salary-add-results-summary" aria-live="polite">
              <span v-if="loading">正在加载可添加人员……</span>
              <span v-else-if="searchTerm.trim()">找到 {{ filteredCandidates.length }} 人</span>
              <span v-else>可添加 {{ candidates.length }} 人</span>
            </div>

            <div :id="resultsId" class="salary-add-results" :aria-busy="loading || undefined">
              <div v-if="loading" class="salary-add-state" role="status">
                <span class="salary-add-spinner" aria-hidden="true" />
                <span>正在加载员工名单……</span>
              </div>

              <div v-else-if="!filteredCandidates.length" class="salary-add-state">
                <template v-if="candidates.length">
                  <strong>没有找到匹配的员工</strong>
                  <span>请尝试其他姓名、门店或岗位关键词。</span>
                </template>
                <template v-else-if="error">
                  <strong>暂时无法获取人员名单</strong>
                  <span>请根据上方提示稍后重试。</span>
                </template>
                <template v-else>
                  <strong>暂无可添加人员</strong>
                  <span>当月符合条件的员工都已在工资名单中。</span>
                </template>
              </div>

              <fieldset v-else class="salary-add-list" :disabled="saving">
                <legend class="visually-hidden">选择一名要添加的员工</legend>
                <label
                  v-for="candidate in filteredCandidates"
                  :key="candidate.employeeId"
                  class="salary-add-candidate"
                  :class="{ 'salary-add-candidate--selected': selectedEmployeeId === candidate.employeeId }"
                >
                  <input v-model="selectedEmployeeId" type="radio" name="salary-add-employee" :value="candidate.employeeId" />
                  <span class="salary-add-candidate__details">
                    <strong>{{ candidate.employeeName }}</strong>
                    <span>
                      <span>{{ candidate.sourceStoreName }}</span>
                      <span aria-hidden="true">·</span>
                      <span>{{ candidate.position || '岗位未填写' }}</span>
                    </span>
                  </span>
                </label>
              </fieldset>
            </div>
          </div>

          <ModalFooter>
            <template #info>
              <span v-if="selectedEmployeeId">已选择 1 人，添加后可继续填写当月工资。</span>
              <span v-else>请先选择一名员工。</span>
            </template>
            <UiButton variant="secondary" type="button" :disabled="saving" @click="requestClose">取消</UiButton>
            <UiButton variant="primary" type="submit" :disabled="!canSubmit" :loading="saving">添加到工资名单</UiButton>
          </ModalFooter>
        </form>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.salary-add-backdrop {
  position: fixed;
  z-index: var(--ds-z-modal, 1400);
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(17, 24, 39, .36);
}

.salary-add-dialog {
  display: grid;
  width: min(640px, 100%);
  max-height: calc(100vh - 40px);
  max-height: calc(100dvh - 40px);
  grid-template-rows: auto minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid var(--ds-line, #d8e4e2);
  border-radius: 8px;
  outline: none;
  background: var(--ds-surface, #fff);
  box-shadow: 0 18px 45px rgba(24, 36, 36, .18);
}

.salary-add-dialog__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 20px 16px;
  border-bottom: 1px solid var(--ds-line, #e1ebe9);
}

.salary-add-dialog__head h3 {
  margin: 0 0 7px;
  color: var(--ds-text, #182424);
  font-size: 18px;
  line-height: 1.35;
}

.salary-add-dialog__head p {
  margin: 0;
  color: var(--ds-secondary, #526765);
  font-size: 13px;
  line-height: 1.65;
}

.salary-add-dialog__head p b {
  color: var(--ds-text, #182424);
}

.salary-add-dialog__content {
  display: grid;
  min-height: 0;
  grid-template-rows: minmax(0, 1fr) auto;
  overflow: hidden;
}

.salary-add-dialog__body {
  min-height: 0;
  overflow-y: auto;
  padding: 18px 20px 20px;
}

.salary-add-error {
  margin-bottom: 14px;
  padding: 10px 12px;
  border: 1px solid var(--ds-danger, #c33f4d);
  border-radius: 6px;
  background: var(--ds-danger-soft, #fff0f1);
  color: var(--ds-danger, #a52f3b);
  font-size: 13px;
  line-height: 1.55;
}

.salary-add-search {
  display: flex;
  height: 44px;
  align-items: center;
  gap: 9px;
  padding: 0 12px;
  border: 1px solid var(--ds-action-secondary-border, #c9d7d5);
  border-radius: 6px;
  background: var(--ds-surface, #fff);
  color: var(--ds-secondary, #526765);
}

.salary-add-search:focus-within {
  border-color: var(--ds-action-primary, #276b65);
  outline: 3px solid rgba(39, 107, 101, .18);
  outline-offset: 1px;
}

.salary-add-search:has(input:disabled) {
  background: var(--ds-surface-muted, #f5f8f7);
  opacity: .65;
}

.salary-add-search input {
  min-width: 0;
  flex: 1;
  height: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ds-text, #182424);
  font: inherit;
  font-size: 14px;
}

.salary-add-search input::placeholder {
  color: var(--ds-muted, #7a8b89);
}

.salary-add-results-summary {
  min-height: 32px;
  padding: 10px 2px 6px;
  color: var(--ds-secondary, #526765);
  font-size: 12px;
}

.salary-add-results {
  min-height: 190px;
  max-height: 340px;
  overflow-y: auto;
  border: 1px solid var(--ds-line, #d8e4e2);
  border-radius: 6px;
  background: var(--ds-surface, #fff);
}

.salary-add-list {
  display: grid;
  min-width: 0;
  margin: 0;
  padding: 6px;
  border: 0;
  gap: 4px;
}

.salary-add-candidate {
  display: grid;
  min-height: 58px;
  grid-template-columns: 20px minmax(0, 1fr);
  align-items: center;
  gap: 11px;
  padding: 9px 11px;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: border-color 140ms ease-out, background-color 140ms ease-out;
}

.salary-add-candidate:hover {
  background: var(--ds-action-secondary-hover, #f3f8f7);
}

.salary-add-candidate--selected {
  border-color: var(--ds-primary, #76bdb8);
  background: var(--ds-primary-soft, #e9f6f5);
}

.salary-add-candidate:has(input:focus-visible) {
  outline: 3px solid rgba(39, 107, 101, .2);
  outline-offset: -1px;
}

.salary-add-candidate input {
  width: 18px;
  height: 18px;
  margin: 0;
  accent-color: var(--ds-action-primary, #276b65);
}

.salary-add-candidate__details {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.salary-add-candidate__details strong {
  overflow: hidden;
  color: var(--ds-text, #182424);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.salary-add-candidate__details > span {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 5px;
  color: var(--ds-secondary, #526765);
  font-size: 12px;
  line-height: 1.45;
}

.salary-add-state {
  display: grid;
  min-height: 188px;
  place-content: center;
  justify-items: center;
  gap: 8px;
  padding: 28px 20px;
  color: var(--ds-secondary, #526765);
  text-align: center;
  font-size: 13px;
  line-height: 1.55;
}

.salary-add-state strong {
  color: var(--ds-text, #182424);
  font-size: 14px;
}

.salary-add-spinner {
  width: 24px;
  height: 24px;
  border: 3px solid var(--ds-line, #d8e4e2);
  border-top-color: var(--ds-action-primary, #276b65);
  border-radius: 50%;
  animation: salary-add-spin .8s linear infinite;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  margin: -1px;
  padding: 0;
  border: 0;
  clip: rect(0 0 0 0);
  clip-path: inset(50%);
  white-space: nowrap;
}

@keyframes salary-add-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 640px) {
  .salary-add-backdrop {
    align-items: end;
    padding: 0;
  }

  .salary-add-dialog {
    width: 100%;
    max-height: 92vh;
    max-height: 92dvh;
    border-right: 0;
    border-bottom: 0;
    border-left: 0;
    border-radius: 10px 10px 0 0;
  }

  .salary-add-dialog__head {
    padding: 16px 16px 14px;
  }

  .salary-add-dialog__body {
    padding: 14px 16px 16px;
  }

  .salary-add-results {
    max-height: none;
  }

  .salary-add-dialog__content :deep(.modal-footer__actions) {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.55fr);
  }

  .salary-add-dialog__content :deep(.ui-button) {
    width: 100%;
    min-width: 0;
    padding-inline: 12px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .salary-add-candidate { transition: none; }
  .salary-add-spinner { animation: none; }
}
</style>
