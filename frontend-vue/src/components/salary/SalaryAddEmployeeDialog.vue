<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Search, X } from 'lucide-vue-next'
import type { EmployeeUpsert } from '../../api/employees'
import type { SalaryAssignmentCandidate } from '../../api/finance'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'
import UnsavedChangesDialog from '../ui/UnsavedChangesDialog.vue'

const props = defineProps<{
  show: boolean
  candidates: SalaryAssignmentCandidate[]
  loading: boolean
  saving: boolean
  error: string
  retryable: boolean
  canCreateEmployee: boolean
  targetStoreId: string
  targetStoreName: string
  month: string
}>()

const emit = defineEmits<{
  close: []
  submit: [employeeId: string]
  create: [payload: EmployeeUpsert]
  retry: []
  clearError: []
}>()

type AddMode = 'new' | 'existing'
type NewEmployeeField = 'name' | 'position' | 'phone' | 'hourlyRate'

const POSITION_OPTIONS = ['店长', '领班', '训练员', '营业员', '实习', '兼职', '长期兼职', '水果阿姨', '长期阿姨', '办公室']
const dialogRef = ref<HTMLElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const nameInput = ref<HTMLInputElement | null>(null)
const searchTerm = ref('')
const selectedEmployeeId = ref('')
const mode = ref<AddMode>('new')
const discardPromptOpen = ref(false)
const formError = ref('')
const newEmployee = reactive<EmployeeUpsert>(emptyNewEmployee())
const fieldErrors = reactive<Record<NewEmployeeField, string>>({
  name: '',
  position: '',
  phone: '',
  hourlyRate: '',
})
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

const visibleError = computed(() => props.error && (!props.retryable || mode.value === 'existing'))
const canSubmit = computed(() => {
  if (props.saving) return false
  if (mode.value === 'new') return props.canCreateEmployee
  return Boolean(selectedEmployeeId.value) && !props.loading
})
const newEmployeeDirty = computed(() => Boolean(
  newEmployee.name?.trim()
  || newEmployee.position
  || newEmployee.phone
  || newEmployee.hireDate
  || newEmployee.employmentType !== '全职'
  || (newEmployee.hourlyRate !== null && newEmployee.hourlyRate !== undefined),
))
const descriptionText = computed(() => mode.value === 'new'
  ? `新人员将建立正式员工档案，所属门店为${props.targetStoreName}，并自动进入 ${props.month} 工资名单。`
  : `从其他门店选择员工加入${props.targetStoreName}的 ${props.month} 工资名单；不会修改其档案归属和岗位。`)

watch(() => props.show, async (show) => {
  if (show) {
    searchTerm.value = ''
    selectedEmployeeId.value = ''
    mode.value = props.canCreateEmployee ? 'new' : 'existing'
    discardPromptOpen.value = false
    resetNewEmployee()
    previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    appRoot = document.getElementById('app')
    appWasInert = Boolean(appRoot?.inert)
    if (appRoot) appRoot.inert = true
    document.addEventListener('keydown', handleKeydown, true)
    await nextTick()
    const preferredControl = mode.value === 'new' ? nameInput.value : searchInput.value
    const initialControl = preferredControl?.disabled
      ? dialogRef.value?.querySelector<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled)')
      : preferredControl
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

watch(() => props.canCreateEmployee, (allowed) => {
  if (!allowed && mode.value === 'new') mode.value = 'existing'
})

onBeforeUnmount(releaseDialogFocus)

function requestClose() {
  if (props.saving) return
  if (newEmployeeDirty.value) {
    discardPromptOpen.value = true
    return
  }
  emit('close')
}

function submit() {
  if (!canSubmit.value) return
  if (mode.value === 'new') {
    if (!validateNewEmployee()) return
    emit('create', {
      storeId: props.targetStoreId,
      name: String(newEmployee.name || '').trim(),
      phone: String(newEmployee.phone || '').trim(),
      position: String(newEmployee.position || '').trim(),
      employmentType: newEmployee.employmentType || '全职',
      status: '在职',
      hireDate: newEmployee.hireDate || '',
      hourlyRate: typeof newEmployee.hourlyRate === 'number' && newEmployee.hourlyRate > 0
        ? newEmployee.hourlyRate
        : null,
    })
    return
  }
  emit('submit', selectedEmployeeId.value)
}

function emptyNewEmployee(): EmployeeUpsert {
  return {
    storeId: props.targetStoreId,
    name: '',
    phone: '',
    position: '',
    employmentType: '全职',
    status: '在职',
    hireDate: '',
    hourlyRate: null,
  }
}

function resetNewEmployee() {
  Object.assign(newEmployee, emptyNewEmployee())
  formError.value = ''
  for (const field of Object.keys(fieldErrors) as NewEmployeeField[]) fieldErrors[field] = ''
}

async function selectMode(nextMode: AddMode) {
  if (nextMode === 'new' && !props.canCreateEmployee) return
  mode.value = nextMode
  formError.value = ''
  if (!props.retryable) emit('clearError')
  await nextTick()
  ;(nextMode === 'new' ? nameInput.value : searchInput.value)?.focus()
}

function clearFieldError(field: NewEmployeeField) {
  fieldErrors[field] = ''
  formError.value = ''
  if (!props.retryable) emit('clearError')
}

function onPhoneInput(event: Event) {
  newEmployee.phone = (event.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 11)
  clearFieldError('phone')
}

function validateNewEmployee() {
  for (const field of Object.keys(fieldErrors) as NewEmployeeField[]) fieldErrors[field] = ''
  const name = String(newEmployee.name || '').trim()
  const position = String(newEmployee.position || '').trim()
  const phone = String(newEmployee.phone || '').trim()
  const hourlyRate = newEmployee.hourlyRate

  if (!name) fieldErrors.name = '请填写员工姓名。'
  else if (name.length > 120) fieldErrors.name = '员工姓名不能超过120个字。'
  if (!position) fieldErrors.position = '请选择岗位，工资计算会使用该岗位。'
  if (phone && !/^\d{11}$/.test(phone)) fieldErrors.phone = '手机号码必须是11位数字。'
  if (hourlyRate !== null && hourlyRate !== undefined
      && (!Number.isFinite(Number(hourlyRate)) || Number(hourlyRate) <= 0 || Number(hourlyRate) > 9999)) {
    fieldErrors.hourlyRate = '时薪需大于0且不超过9999元。'
  }

  const firstError = (Object.keys(fieldErrors) as NewEmployeeField[]).find((field) => fieldErrors[field])
  if (!firstError) return true
  formError.value = '请检查标红的必填项或格式。'
  nextTick(() => {
    dialogRef.value?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
  })
  return false
}

function keepDraft() {
  discardPromptOpen.value = false
}

function discardDraftAndClose() {
  discardPromptOpen.value = false
  resetNewEmployee()
  emit('close')
}

function handleKeydown(event: KeyboardEvent) {
  if (!props.show) return
  if (discardPromptOpen.value) return
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
  discardPromptOpen.value = false
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
            <p :id="descriptionId">{{ descriptionText }}</p>
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
            <div v-if="canCreateEmployee" class="salary-add-tabs" role="tablist" aria-label="添加人员方式">
              <button
                type="button"
                role="tab"
                :aria-selected="mode === 'new'"
                :tabindex="mode === 'new' ? 0 : -1"
                :disabled="saving"
                @click="selectMode('new')"
              >
                新建人员
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="mode === 'existing'"
                :tabindex="mode === 'existing' ? 0 : -1"
                :disabled="saving"
                @click="selectMode('existing')"
              >
                选择已有人员
              </button>
            </div>

            <div v-if="visibleError" class="salary-add-error" role="alert">
              <span>{{ error }}</span>
              <UiButton
                v-if="retryable && mode === 'existing'"
                variant="secondary"
                type="button"
                :disabled="loading || saving"
                @click="emit('retry')"
              >
                重新加载
              </UiButton>
            </div>

            <template v-if="mode === 'new'">
              <div class="salary-new-note" role="note">
                <strong>新建后立即进入当前工资名单</strong>
                <span>系统会同步建立正式员工档案；身份证、健康证等资料可稍后在“员工档案”中补充。</span>
              </div>

              <div v-if="formError" class="salary-add-form-error" role="alert">{{ formError }}</div>

              <div class="salary-new-grid">
                <label class="salary-new-field">
                  <span>员工姓名 <b aria-hidden="true">*</b></span>
                  <input
                    ref="nameInput"
                    v-model="newEmployee.name"
                    type="text"
                    maxlength="120"
                    autocomplete="name"
                    placeholder="请输入员工姓名"
                    :disabled="saving"
                    :aria-invalid="Boolean(fieldErrors.name)"
                    @input="clearFieldError('name')"
                  />
                  <small v-if="fieldErrors.name" role="alert">{{ fieldErrors.name }}</small>
                </label>

                <label class="salary-new-field">
                  <span>岗位 <b aria-hidden="true">*</b></span>
                  <select
                    v-model="newEmployee.position"
                    :disabled="saving"
                    :aria-invalid="Boolean(fieldErrors.position)"
                    @change="clearFieldError('position')"
                  >
                    <option value="" disabled>请选择岗位</option>
                    <option v-for="position in POSITION_OPTIONS" :key="position" :value="position">{{ position }}</option>
                  </select>
                  <small v-if="fieldErrors.position" role="alert">{{ fieldErrors.position }}</small>
                </label>

                <label class="salary-new-field">
                  <span>用工类型</span>
                  <select
                    v-model="newEmployee.employmentType"
                    :disabled="saving"
                    @change="emit('clearError')"
                  >
                    <option value="全职">全职</option>
                    <option value="长期兼职">长期兼职</option>
                    <option value="兼职">兼职</option>
                  </select>
                </label>

                <label class="salary-new-field">
                  <span>手机号码（选填）</span>
                  <input
                    :value="newEmployee.phone"
                    type="tel"
                    inputmode="numeric"
                    maxlength="11"
                    autocomplete="tel"
                    placeholder="请输入11位手机号码"
                    :disabled="saving"
                    :aria-invalid="Boolean(fieldErrors.phone)"
                    @input="onPhoneInput"
                  />
                  <small v-if="fieldErrors.phone" role="alert">{{ fieldErrors.phone }}</small>
                </label>

                <label class="salary-new-field">
                  <span>入职日期（选填）</span>
                  <input
                    v-model="newEmployee.hireDate"
                    type="date"
                    :disabled="saving"
                    @change="emit('clearError')"
                  />
                </label>

                <label class="salary-new-field">
                  <span>时薪（选填）</span>
                  <div class="salary-new-money-input">
                    <input
                      v-model.number="newEmployee.hourlyRate"
                      type="number"
                      min="0.01"
                      max="9999"
                      step="0.5"
                      inputmode="decimal"
                      placeholder="按默认规则计算"
                      :disabled="saving"
                      :aria-invalid="Boolean(fieldErrors.hourlyRate)"
                      @input="clearFieldError('hourlyRate')"
                    />
                    <span>元/小时</span>
                  </div>
                  <small v-if="fieldErrors.hourlyRate" role="alert">{{ fieldErrors.hourlyRate }}</small>
                </label>
              </div>
            </template>

            <template v-else>
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
            </template>
          </div>

          <ModalFooter>
            <template #info>
              <template v-if="mode === 'new'">
                <span>所属门店：{{ targetStoreName }}；状态：在职。</span>
              </template>
              <template v-else>
                <span v-if="selectedEmployeeId">已选择 1 人，添加后可继续填写当月工资。</span>
                <span v-else>请先选择一名员工。</span>
              </template>
            </template>
            <UiButton variant="secondary" type="button" :disabled="saving" @click="requestClose">取消</UiButton>
            <UiButton variant="primary" type="submit" :disabled="!canSubmit" :loading="saving">
              {{ mode === 'new' ? '新建并加入名单' : '添加到工资名单' }}
            </UiButton>
          </ModalFooter>
        </form>
      </section>
    </div>
  </Teleport>

  <UnsavedChangesDialog
    :open="discardPromptOpen"
    title="尚未保存新人员"
    message="关闭后，当前填写的员工姓名、岗位等内容将被清空。"
    discard-label="放弃并关闭"
    keep-label="继续填写"
    @keep-editing="keepDraft"
    @discard="discardDraftAndClose"
  />
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

.salary-add-tabs {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px;
  margin-bottom: 16px;
  padding: 4px;
  border: 1px solid var(--ds-line, #d8e4e2);
  border-radius: 7px;
  background: var(--ds-surface-muted, #f5f8f7);
}

.salary-add-tabs button {
  min-height: 38px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--ds-secondary, #526765);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
}

.salary-add-tabs button[aria-selected='true'] {
  background: var(--ds-surface, #fff);
  color: var(--ds-action-primary, #276b65);
  box-shadow: 0 1px 4px rgba(24, 36, 36, .1);
}

.salary-add-tabs button:focus-visible {
  outline: 3px solid rgba(39, 107, 101, .2);
  outline-offset: 1px;
}

.salary-add-tabs button:disabled {
  cursor: not-allowed;
  opacity: .6;
}

.salary-add-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  padding: 10px 12px;
  border: 1px solid var(--ds-danger, #c33f4d);
  border-radius: 6px;
  background: var(--ds-danger-soft, #fff0f1);
  color: var(--ds-danger, #a52f3b);
  font-size: 13px;
  line-height: 1.55;
}

.salary-add-error span {
  min-width: 0;
}

.salary-new-note {
  display: grid;
  gap: 4px;
  margin-bottom: 16px;
  padding: 11px 12px;
  border: 1px solid var(--ds-primary, #9fcfcb);
  border-radius: 6px;
  background: var(--ds-primary-soft, #edf8f7);
  color: var(--ds-secondary, #526765);
  font-size: 12px;
  line-height: 1.55;
}

.salary-new-note strong {
  color: var(--ds-action-primary, #276b65);
  font-size: 13px;
}

.salary-add-form-error {
  margin-bottom: 12px;
  color: var(--ds-danger, #a52f3b);
  font-size: 13px;
  font-weight: 700;
}

.salary-new-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 16px;
}

.salary-new-field {
  display: grid;
  min-width: 0;
  align-content: start;
  gap: 6px;
  color: var(--ds-secondary, #526765);
  font-size: 13px;
  font-weight: 650;
}

.salary-new-field > span b {
  color: var(--ds-danger, #a52f3b);
}

.salary-new-field input,
.salary-new-field select {
  width: 100%;
  min-width: 0;
  height: 42px;
  padding: 0 11px;
  border: 1px solid var(--ds-action-secondary-border, #c9d7d5);
  border-radius: 6px;
  outline: 0;
  background: var(--ds-surface, #fff);
  color: var(--ds-text, #182424);
  font: inherit;
  font-size: 14px;
  font-weight: 400;
}

.salary-new-field input:focus,
.salary-new-field select:focus {
  border-color: var(--ds-action-primary, #276b65);
  box-shadow: 0 0 0 3px rgba(39, 107, 101, .16);
}

.salary-new-field input[aria-invalid='true'],
.salary-new-field select[aria-invalid='true'] {
  border-color: var(--ds-danger, #c33f4d);
}

.salary-new-field input:disabled,
.salary-new-field select:disabled {
  cursor: not-allowed;
  background: var(--ds-surface-muted, #f5f8f7);
  opacity: .7;
}

.salary-new-field small {
  color: var(--ds-danger, #a52f3b);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.4;
}

.salary-new-money-input {
  position: relative;
  min-width: 0;
}

.salary-new-money-input input {
  padding-right: 64px;
}

.salary-new-money-input > span {
  position: absolute;
  top: 50%;
  right: 11px;
  color: var(--ds-muted, #7a8b89);
  font-size: 12px;
  font-weight: 500;
  pointer-events: none;
  transform: translateY(-50%);
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

  .salary-new-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .salary-add-error {
    align-items: stretch;
    flex-direction: column;
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
