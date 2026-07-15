<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { Clipboard, MessageSquare, RefreshCw, Send, ThumbsDown, ThumbsUp, UserRound } from 'lucide-vue-next'
import UiButton from '../components/ui/UiButton.vue'
import { ApiError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import {
  askEmployeeAssistant,
  createEmployeeAssistantHandoff,
  getEmployeeAssistantStatus,
  submitEmployeeAssistantFeedback,
  type EmployeeAssistantAnswerSource,
  type EmployeeAssistantServiceState,
  type EmployeeAssistantStatus,
} from '../api/employeeAssistant'

interface ChatTurn {
  id: number
  role: 'user' | 'assistant'
  content: string
  renderedHtml: string
  answerSource?: EmployeeAssistantAnswerSource
  knowledgeId?: number | null
  knowledgeVersion?: number | null
  knowledgeTitle?: string | null
  needsHuman?: boolean
  handoffCategory?: string | null
  questionId?: number
  handoffState?: 'idle' | 'sending' | 'created' | 'failed'
  feedbackState?: 'idle' | 'helpful' | 'inaccurate' | 'sending'
  copied?: boolean
  deliveryState?: 'idle' | 'failed'
  failureMessage?: string
  customerSpeech?: string
  speechHtml?: string
  stepsHtml?: string
  handoffHtml?: string
}

interface StructuredAnswer {
  customerSpeech: string
  speechHtml: string
  stepsHtml: string
  handoffHtml: string
}

const QUICK_QUESTIONS = [
  '顾客投诉等待太久，怎么回应？',
  '交班时需要核对哪些事项？',
  '会员券不能使用时怎么解释？',
  '顾客说饮品太甜，怎样礼貌处理？',
  '如何处理顾客退款请求？',
]

const WELCOME_QUESTIONS = [
  '顾客情绪不好时，第一句话怎么说？',
  '顾客要求退款时，员工应怎样回应？',
  '交班前有哪些服务事项要确认？',
]

const status = ref<EmployeeAssistantStatus | null>(null)
const auth = useAuthStore()
const loading = ref(true)
const sending = ref(false)
const pageError = ref('')
const message = ref('')
const turns = ref<ChatTurn[]>([])
const handoffNotice = ref('')
const sessionId = createSessionId()
const inputRef = ref<HTMLTextAreaElement | null>(null)
const waitingBeyondFiveSeconds = ref(false)
let waitingTimer: ReturnType<typeof setTimeout> | undefined
let nextTurnId = 0

const serviceState = computed<EmployeeAssistantServiceState>(() => {
  const explicitState = status.value?.state
  if (explicitState === 'UNCONFIGURED' || explicitState === 'AUTH_FAILED' || explicitState === 'UNAVAILABLE' || explicitState === 'READY') {
    return explicitState
  }
  if (status.value?.configured === false) return 'UNCONFIGURED'
  if (status.value?.configured && status.value.enabled) return 'READY'
  return 'UNAVAILABLE'
})

const canAsk = computed(() => !loading.value && (status.value?.canAsk ?? serviceState.value === 'READY'))
const canViewDeploymentGuide = computed(() => serviceState.value === 'UNCONFIGURED' && auth.role === 'BOSS')
const pendingUserTurn = computed(() => [...turns.value].reverse().find((turn) => turn.role === 'user'))

const stateBadge = computed(() => {
  if (loading.value && !status.value) return { text: '检查中', cls: 'badge--checking' }
  const map: Record<string, { text: string; cls: string }> = {
    READY: { text: '已就绪', cls: 'badge--ready' },
    UNCONFIGURED: { text: '未配置', cls: 'badge--unconfigured' },
    AUTH_FAILED: { text: '授权异常', cls: 'badge--auth-failed' },
    UNAVAILABLE: { text: '暂不可用', cls: 'badge--unavailable' },
  }
  return map[serviceState.value] || map.UNAVAILABLE
})

const stateMessage = computed(() => {
  if (loading.value) return '正在检查服务状态…'
  const map: Record<string, string> = {
    READY: '',
    UNCONFIGURED: '服务未配置，请联系管理员。',
    AUTH_FAILED: '授权未通过，请联系管理员检查。',
    UNAVAILABLE: '服务暂时无法连接，请稍后重试。',
  }
  return map[serviceState.value] || ''
})

onMounted(() => { void loadStatus() })
onUnmounted(() => clearWaitingTimer())

async function loadStatus() {
  loading.value = true
  pageError.value = ''
  try { status.value = await getEmployeeAssistantStatus() }
  catch (error) { status.value = serviceStatusFromFailure(error) || unavailableStatus(error) }
  finally { loading.value = false }
}

function pickQuestion(question: string) {
  message.value = question
  nextTick(() => {
    inputRef.value?.focus()
    inputRef.value?.setSelectionRange(question.length, question.length)
  })
}

async function send(retryTurn?: ChatTurn) {
  const question = retryTurn?.content || message.value.trim()
  if (!question || sending.value || (!canAsk.value && !retryTurn)) return
  pageError.value = ''
  if (!retryTurn) message.value = ''
  const userTurn = retryTurn || {
    id: ++nextTurnId, role: 'user' as const, content: question, renderedHtml: escapeHtml(question),
    handoffState: 'idle' as const, deliveryState: 'idle' as const,
  }
  if (!retryTurn) turns.value.push(userTurn)
  userTurn.deliveryState = 'idle'
  userTurn.failureMessage = ''
  sending.value = true
  startWaitingTimer()
  try {
    const response = await askEmployeeAssistant({ sessionId, message: question })
    if (!response.configured) {
      status.value = { enabled: false, configured: false, state: 'UNCONFIGURED', message: '员工服务助手未配置，请联系管理员完成服务配置。' }
      userTurn.deliveryState = 'failed'
      userTurn.failureMessage = '服务未配置，暂时无法生成答复。'
      return
    }
    const raw = response.answer?.trim() || '暂未获得可用答复，请稍后再试。'
    const structured = structureAssistantAnswer(raw, Boolean(response.needsHuman))
    turns.value.push({
      id: ++nextTurnId, role: 'assistant', content: raw, renderedHtml: renderMarkdown(raw), ...structured,
      answerSource: response.answerSource || 'ASSISTANT', knowledgeId: response.knowledgeId,
      knowledgeVersion: response.knowledgeVersion, knowledgeTitle: response.knowledgeTitle,
      needsHuman: Boolean(response.needsHuman), handoffCategory: response.handoffCategory,
      questionId: userTurn.id, handoffState: 'idle', feedbackState: 'idle',
    })
  } catch (error) {
    const failureMessage = error instanceof Error && error.message ? error.message : '员工服务助手暂时无法处理，请稍后再试。'
    pageError.value = failureMessage
    userTurn.deliveryState = 'failed'
    userTurn.failureMessage = failureMessage
    const failureStatus = serviceStatusFromFailure(error)
    if (failureStatus) status.value = failureStatus
  } finally {
    clearWaitingTimer()
    sending.value = false
  }
}

function submitMessage() {
  void send()
}

function startWaitingTimer() {
  clearWaitingTimer()
  waitingTimer = setTimeout(() => { waitingBeyondFiveSeconds.value = true }, 5_000)
}

function clearWaitingTimer() {
  if (waitingTimer) clearTimeout(waitingTimer)
  waitingTimer = undefined
  waitingBeyondFiveSeconds.value = false
}

function continueWaiting() {
  startWaitingTimer()
}

function createPendingHandoff() {
  if (pendingUserTurn.value) void createHandoff(pendingUserTurn.value)
}

function retryTurn(turn: ChatTurn) {
  void send(turn)
}

function retryAssistantTurn(turn: ChatTurn) {
  const question = turns.value.find((item) => item.id === turn.questionId && item.role === 'user')
  if (!question) return
  turns.value = turns.value.filter((item) => item.id !== turn.id)
  void send(question)
}

async function copyAnswer(turn: ChatTurn) {
  try { await navigator.clipboard.writeText(turn.customerSpeech || turn.content); turn.copied = true; setTimeout(() => { turn.copied = false }, 2000) }
  catch { /* fallback */ }
}

async function createHandoff(turn: ChatTurn) {
  if (turn.handoffState === 'sending' || turn.handoffState === 'created') return
  const question = turn.role === 'user'
    ? turn.content
    : turns.value.find((item) => item.id === turn.questionId && item.role === 'user')?.content
  if (!question) return
  handoffNotice.value = ''
  turn.handoffState = 'sending'
  try {
    const record = await createEmployeeAssistantHandoff({ question })
    turn.handoffState = 'created'
    handoffNotice.value = `已创建人工事项 ${record.id}，请留意处理进度。`
  } catch (error) {
    turn.handoffState = 'failed'
    pageError.value = error instanceof Error ? error.message : '人工转接创建失败，请稍后重试。'
  }
}

async function submitFeedback(turn: ChatTurn, helpful: boolean) {
  if (turn.feedbackState === 'sending' || turn.feedbackState === 'helpful' || turn.feedbackState === 'inaccurate') return
  turn.feedbackState = 'sending'
  try {
    await submitEmployeeAssistantFeedback({
      answerSource: turn.answerSource || 'ASSISTANT', knowledgeId: turn.knowledgeId,
      knowledgeVersion: turn.knowledgeVersion, helpful, reasonCode: helpful ? 'HELPFUL' : 'INACCURATE',
    })
    turn.feedbackState = helpful ? 'helpful' : 'inaccurate'
  } catch { turn.feedbackState = 'idle' }
}

function sourceLabel(source?: EmployeeAssistantAnswerSource) {
  if (source === 'KNOWLEDGE') return '标准话术'
  if (source === 'HUMAN_REQUIRED') return '需人工处理'
  return ''
}

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); submitMessage() }
}

function serviceStatusFromFailure(error: unknown): EmployeeAssistantStatus | null {
  const apiError = error instanceof ApiError ? error : null
  const code = apiError?.code
  const message = apiError?.message || '员工服务助手暂时不可用，请稍后点击"检查服务"。'
  if (code === 'EMPLOYEE_ASSISTANT_NOT_CONFIGURED') return { enabled: false, configured: false, state: 'UNCONFIGURED', message }
  if (code === 'EMPLOYEE_ASSISTANT_AUTH_FAILED' || code === 'EMPLOYEE_ASSISTANT_UPSTREAM_FORBIDDEN') return { enabled: false, configured: true, state: 'AUTH_FAILED', message }
  if (code && /EMPLOYEE_ASSISTANT_(TIMEOUT|UNAVAILABLE|UPSTREAM_UNAVAILABLE|CANCELLED|RESPONSE_INVALID)/.test(code)) return { enabled: false, configured: true, state: 'UNAVAILABLE', message }
  return null
}

function unavailableStatus(error: unknown): EmployeeAssistantStatus {
  const message = error instanceof Error && error.message ? error.message : '员工服务助手暂时不可用，请稍后点击"检查服务"。'
  return { enabled: false, configured: true, state: 'UNAVAILABLE', message }
}

function createSessionId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `employee-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function escapeHtml(text: string) { return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') }

function renderMarkdown(raw: string): string {
  let html = escapeHtml(raw)
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/^[\-\*]\s+(.+)$/gm, '<li>$1</li>')
  html = html.replace(/((?:<li>.*<\/li>\n?)+)/g, '<ul class="md-list">$1</ul>')
  html = html.replace(/^\d+\.\s+(.+)$/gm, '<li>$1</li>')
  html = html.replace(/\n\n+/g, '</p><p>')
  html = html.replace(/\n/g, '<br>')
  if (!html.startsWith('<')) html = '<p>' + html + '</p>'
  return html
}

function structureAssistantAnswer(raw: string, needsHuman: boolean): StructuredAnswer {
  const sections = { speech: [] as string[], steps: [] as string[], handoff: [] as string[] }
  const unsectioned: string[] = []
  let current: keyof typeof sections | null = null
  let recognizedHeading = false

  for (const originalLine of raw.replace(/\r/g, '').split('\n')) {
    const line = originalLine.trim()
    if (!line) continue
    const heading = line.replace(/^[#*\s]+|[:：*\s]+$/g, '')
    if (/^(?:\d+[.、]\s*)?可以这样说/.test(heading)) {
      current = 'speech'; recognizedHeading = true; continue
    }
    if (/^(?:\d+[.、]\s*)?(?:员工怎么处理|操作原则)/.test(heading)) {
      current = 'steps'; recognizedHeading = true; continue
    }
    if (/^(?:\d+[.、]\s*)?(?:什么时候转人工|转人工条件)/.test(heading)) {
      current = 'handoff'; recognizedHeading = true; continue
    }
    const cleaned = cleanAnswerLine(line)
    if (!cleaned) continue
    if (current) sections[current].push(cleaned)
    else unsectioned.push(cleaned)
  }

  if (!recognizedHeading) {
    const units = raw.replace(/([。！？；])/g, '$1\n').split('\n').map(cleanAnswerLine).filter(Boolean)
    const candidates = units.length ? units : unsectioned
    sections.speech.push(candidates[0] || '我先帮您确认处理方式，请稍等。')
    for (const unit of candidates.slice(1)) {
      if (isHandoffText(unit)) sections.handoff.push(unit)
      else sections.steps.push(unit)
    }
  } else if (unsectioned.length) {
    sections.speech.unshift(unsectioned[0])
    sections.steps.push(...unsectioned.slice(1))
  }

  const customerSpeech = (sections.speech[0] || '我先帮您确认处理方式，请稍等。').slice(0, 360)
  const steps = uniqueShortItems(sections.steps, 3)
  const handoffs = uniqueShortItems(sections.handoff, 3)
  if (!steps.length) steps.push('在现有业务系统内按门店规则核验，不在聊天中补充顾客或订单隐私。')
  if (!handoffs.length) handoffs.push(needsHuman
    ? '需要判断具体情况或无法确认门店规则时，转值班负责人。'
    : '超出本人权限或无法确认门店规则时，转值班负责人。')

  return {
    customerSpeech,
    speechHtml: renderMarkdown(customerSpeech),
    stepsHtml: renderMarkdown(steps.map((item) => `- ${item}`).join('\n')),
    handoffHtml: renderMarkdown(handoffs.map((item) => `- ${item}`).join('\n')),
  }
}

function cleanAnswerLine(value: string) {
  return value.replace(/^\s*(?:[-*•]|\d+[.、])\s*/, '').replace(/^['“”]|['“”]$/g, '').trim()
}

function uniqueShortItems(values: string[], limit: number) {
  return Array.from(new Set(values.map(cleanAnswerLine).filter(Boolean))).slice(0, limit).map((item) => item.slice(0, 220))
}

function isHandoffText(value: string) {
  return /(转人工|值班负责人|值班经理|负责人|无法确认|不能判断|不确定|超出.*权限)/.test(value)
}
</script>

<template>
  <div class="ea-page">
    <header class="ea-topbar">
      <div class="ea-topbar__left">
        <MessageSquare :size="18" />
        <h1 class="ea-topbar__title">员工服务助手</h1>
        <span class="ea-badge" :class="stateBadge.cls" data-testid="employee-assistant-status">{{ stateBadge.text }}</span>
        <span v-if="stateMessage" class="ea-topbar__hint">{{ stateMessage }}</span>
      </div>
      <div class="ea-topbar__right">
        <details v-if="canViewDeploymentGuide" class="ea-deploy-guide" data-testid="employee-assistant-deployment-guide">
          <summary>部署说明</summary>
          <div class="ea-deploy-guide__body">
            <p>请由维护人员使用统一安全启动器注入变量，本页不显示或写入任何配置值。</p>
          </div>
        </details>
        <UiButton variant="ghost" size="sm" :loading="loading" @click="loadStatus"><template #icon><RefreshCw :size="15" /></template>检查</UiButton>
      </div>
    </header>

    <div v-if="pageError" class="ea-error" role="alert">{{ pageError }}</div>

    <div class="ea-shell">
      <aside class="ea-sidebar">
        <p class="ea-sidebar__label">常见问题</p>
        <button v-for="q in QUICK_QUESTIONS" :key="q" class="ea-quick-btn" :disabled="!canAsk" @click="pickQuestion(q)">{{ q }}</button>
      </aside>

      <nav class="ea-quick-tags" aria-label="快捷问题">
        <button v-for="q in QUICK_QUESTIONS" :key="q" class="ea-tag" :disabled="!canAsk" @click="pickQuestion(q)">{{ q.length > 12 ? q.slice(0, 12) + '…' : q }}</button>
      </nav>

      <div class="ea-chat">
        <div class="ea-chat__history" aria-live="polite">
          <div v-if="!turns.length && !sending" class="ea-empty">
            <MessageSquare :size="28" />
            <h2>先说好第一句话</h2>
            <p>只描述通用服务场景，我会帮你整理可直接对顾客说的话。</p>
            <div class="ea-empty__questions" aria-label="欢迎快捷问题">
              <button v-for="q in WELCOME_QUESTIONS" :key="q" class="ea-empty__question" :disabled="!canAsk" @click="pickQuestion(q)">{{ q }}</button>
            </div>
          </div>

          <article v-for="turn in turns" :key="turn.id" class="ea-msg" :class="`ea-msg--${turn.role}`">
            <div class="ea-msg__avatar"><UserRound v-if="turn.role === 'user'" :size="16" /><MessageSquare v-else :size="16" /></div>
            <div class="ea-msg__body">
              <div v-if="turn.role === 'user'" class="ea-msg__text" v-html="turn.renderedHtml" />
              <div v-else class="ea-answer">
                <section class="ea-answer__section ea-answer__section--speech">
                  <h3>可以这样说</h3>
                  <div class="ea-answer__speech ea-msg__text--md" v-html="turn.speechHtml" />
                </section>
                <section class="ea-answer__section">
                  <h3>员工怎么处理</h3>
                  <div class="ea-answer__content ea-msg__text--md" v-html="turn.stepsHtml" />
                </section>
                <section class="ea-answer__section">
                  <h3>什么时候转人工</h3>
                  <div class="ea-answer__content ea-msg__text--md" v-html="turn.handoffHtml" />
                </section>
              </div>
              <div v-if="turn.role === 'user' && turn.deliveryState === 'failed'" class="ea-msg__recovery" role="status">
                <span>{{ turn.failureMessage || '本次答复未完成。' }}</span>
                <div class="ea-msg__acts">
                  <UiButton variant="ghost" size="sm" @click="retryTurn(turn)"><template #icon><RefreshCw :size="14" /></template>重新发送</UiButton>
                  <UiButton variant="secondary" size="sm" :loading="turn.handoffState === 'sending'" :disabled="turn.handoffState === 'created'" @click="createHandoff(turn)">{{ turn.handoffState === 'created' ? '已转人工' : '转人工' }}</UiButton>
                </div>
              </div>
              <template v-if="turn.role === 'assistant'">
                <div class="ea-msg__meta">
                  <span v-if="sourceLabel(turn.answerSource)" class="ea-msg__src">{{ sourceLabel(turn.answerSource) }}</span>
                  <small v-if="turn.knowledgeTitle">{{ turn.knowledgeTitle }}</small>
                </div>
                <div class="ea-msg__acts">
                  <UiButton variant="ghost" size="sm" @click="copyAnswer(turn)"><template #icon><Clipboard :size="14" /></template>{{ turn.copied ? '已复制' : '复制话术' }}</UiButton>
                  <UiButton v-if="turn.handoffCategory === 'UPSTREAM_TIMEOUT'" variant="ghost" size="sm" @click="retryAssistantTurn(turn)"><template #icon><RefreshCw :size="14" /></template>重新发送</UiButton>
                  <UiButton v-if="turn.needsHuman" variant="secondary" size="sm" :loading="turn.handoffState === 'sending'" :disabled="turn.handoffState === 'created'" @click="createHandoff(turn)">{{ turn.handoffState === 'created' ? '已转人工' : '转人工处理' }}</UiButton>
                  <UiButton variant="ghost" size="sm" icon-only :disabled="turn.feedbackState !== 'idle'" :class="{ 'ea-fb--on': turn.feedbackState === 'helpful' }" aria-label="有帮助" @click="submitFeedback(turn, true)"><template #icon><ThumbsUp :size="14" /></template></UiButton>
                  <UiButton variant="ghost" size="sm" icon-only :disabled="turn.feedbackState !== 'idle'" :class="{ 'ea-fb--on': turn.feedbackState === 'inaccurate' }" aria-label="不准确" @click="submitFeedback(turn, false)"><template #icon><ThumbsDown :size="14" /></template></UiButton>
                </div>
              </template>
            </div>
          </article>

          <article v-if="sending" class="ea-msg ea-msg--assistant ea-msg--pending">
            <div class="ea-msg__avatar"><MessageSquare :size="16" /></div>
            <div class="ea-msg__body">
              <p class="ea-msg__typing">正在查找标准话术<span class="ea-typing-dots" aria-hidden="true"><i></i><i></i><i></i></span></p>
              <div v-if="waitingBeyondFiveSeconds" class="ea-waiting-slow" role="status">
                <span>回复稍慢，仍在处理中</span>
                <div class="ea-msg__acts">
                  <UiButton variant="ghost" size="sm" @click="continueWaiting">继续等待</UiButton>
                  <UiButton variant="secondary" size="sm" @click="createPendingHandoff">转人工</UiButton>
                </div>
              </div>
            </div>
          </article>
        </div>

        <p v-if="handoffNotice" class="ea-handoff" role="status">{{ handoffNotice }}</p>

        <form class="ea-input" @submit.prevent="submitMessage">
          <div class="ea-input__row">
            <textarea ref="inputRef" v-model="message" :disabled="!canAsk || sending" rows="2" maxlength="800" placeholder="例如：顾客说饮品太甜，怎样礼貌处理？" @keydown="onKeydown" />
            <UiButton variant="primary" type="submit" :loading="sending" :disabled="!canAsk || !message.trim()" aria-label="发送"><template #icon><Send :size="17" /></template>发送</UiButton>
          </div>
          <p class="ea-input__hint">请勿发送顾客姓名、电话、订单号等信息 · Ctrl + Enter 发送</p>
        </form>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ea-page { display: grid; grid-template-rows: auto auto minmax(0, 1fr); height: calc(100vh - 56px); height: calc(100dvh - 56px); min-height: 420px; overflow: hidden; background: var(--ds-bg, #fafbfc); }

.ea-topbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px 20px; border-bottom: 1px solid var(--ds-line, #e2e8e7); background: #fff; flex-wrap: wrap; }
.ea-topbar__left { display: flex; align-items: center; gap: 8px; color: var(--ds-primary, #2b8063); }
.ea-topbar__title { margin: 0; font-size: 15px; font-weight: 750; color: var(--ds-ink, #1a2825); }
.ea-topbar__hint { font-size: 12px; color: var(--ds-muted, #6b7e7a); }
.ea-topbar__right { display: flex; align-items: center; gap: 8px; }
.ea-badge { display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 800; }
.ea-badge::before { content: ''; width: 6px; height: 6px; border-radius: 50%; }
.badge--ready { background: #e7f7ef; color: #237153; } .badge--ready::before { background: #237153; }
.badge--checking { background: #edf2f3; color: #566f6b; } .badge--checking::before { background: #566f6b; }
.badge--unconfigured { background: #fff1db; color: #9a6814; } .badge--unconfigured::before { background: #9a6814; }
.badge--auth-failed { background: #ffebed; color: #9f2734; } .badge--auth-failed::before { background: #9f2734; }
.badge--unavailable { background: #edf2f3; color: #566f6b; } .badge--unavailable::before { background: #566f6b; }
.ea-deploy-guide { font-size: 12px; color: #6a531e; }
.ea-deploy-guide summary { cursor: pointer; font-weight: 700; color: #765816; }
.ea-deploy-guide__body { padding: 6px 0; color: #6a5a35; }
.ea-error { padding: 8px 20px; background: #fff5f5; border-bottom: 1px solid #efc9cf; color: #9f2734; font-size: 13px; font-weight: 650; }

.ea-shell { display: grid; grid-template-columns: 260px 1fr; min-height: 0; overflow: hidden; }
.ea-sidebar { display: flex; flex-direction: column; gap: 6px; padding: 14px 16px; border-right: 1px solid var(--ds-line); background: #f8fbfa; overflow-y: auto; }
.ea-sidebar__label { margin: 0 0 4px; font-size: 11px; font-weight: 800; text-transform: uppercase; letter-spacing: .04em; color: var(--ds-muted); }
.ea-quick-btn { all: unset; display: block; padding: 8px 12px; border-radius: 6px; font-size: 13px; line-height: 1.4; color: var(--ds-secondary, #3a5a51); cursor: pointer; transition: background .12s; }
.ea-quick-btn:hover:not(:disabled) { background: #e5f2ee; }
.ea-quick-btn:disabled { opacity: .4; cursor: not-allowed; }

.ea-quick-tags { display: none; gap: 6px; padding: 8px 16px; overflow-x: auto; scrollbar-width: none; border-bottom: 1px solid var(--ds-line); background: #fff; }
.ea-quick-tags::-webkit-scrollbar { display: none; }
.ea-tag { all: unset; flex: 0 0 auto; padding: 5px 11px; border: 1px solid #bfe0dc; border-radius: 999px; font-size: 12px; color: #276b65; cursor: pointer; white-space: nowrap; transition: background .12s; }
.ea-tag:hover:not(:disabled) { background: #e7f4f1; }
.ea-tag:disabled { opacity: .4; }

.ea-chat { display: grid; grid-template-rows: 1fr auto auto; min-width: 0; min-height: 0; overflow: hidden; }
.ea-chat__history { overflow-y: auto; padding: 16px 20px; display: grid; align-content: start; gap: 14px; scroll-behavior: smooth; }
.ea-empty { display: grid; place-items: center; align-content: center; gap: 10px; padding: 60px 20px; color: var(--ds-muted); text-align: center; }
.ea-empty h2 { margin: 0; color: var(--ds-ink); font-size: 18px; letter-spacing: -.01em; }
.ea-empty p { max-width: 280px; margin: 0; font-size: 14px; line-height: 1.6; }
.ea-empty__questions { display: grid; gap: 7px; width: min(100%, 350px); margin-top: 6px; }
.ea-empty__question { padding: 9px 12px; border: 1px solid #c7e3dc; border-radius: 8px; background: #fff; color: #276b65; font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.ea-empty__question:hover:not(:disabled), .ea-empty__question:focus-visible { border-color: #73b6a9; background: #eff9f5; outline: none; }
.ea-empty__question:disabled { opacity: .45; cursor: not-allowed; }

.ea-msg { display: flex; gap: 10px; max-width: min(760px, 94%); }
.ea-msg--user { align-self: flex-end; flex-direction: row-reverse; }
.ea-msg--assistant { align-self: flex-start; }
.ea-msg__avatar { flex: 0 0 auto; width: 30px; height: 30px; display: grid; place-items: center; border-radius: 50%; }
.ea-msg--user .ea-msg__avatar { background: #e5f4f1; color: #276b65; }
.ea-msg--assistant .ea-msg__avatar { background: #eef5f3; color: #4a7b71; }
.ea-msg__body { display: grid; gap: 6px; min-width: 0; }
.ea-msg__text { padding: 10px 14px; border-radius: 10px; font-size: 14px; line-height: 1.65; word-break: break-word; }
.ea-msg--user .ea-msg__text { background: #e5f4f1; color: var(--ds-ink); border-bottom-right-radius: 4px; }
.ea-msg--assistant .ea-msg__text { background: #fff; border: 1px solid var(--ds-line); color: var(--ds-ink); border-bottom-left-radius: 4px; }
.ea-msg--pending .ea-msg__text { background: #f5f8f7; color: var(--ds-muted); }
.ea-msg__text--md :deep(strong) { font-weight: 750; }
.ea-msg__text--md :deep(p) { margin: 0 0 6px; }
.ea-msg__text--md :deep(p:last-child) { margin-bottom: 0; }
.ea-msg__text--md :deep(.md-list) { margin: 4px 0; padding-left: 18px; }
.ea-msg__text--md :deep(.md-list li) { margin-bottom: 3px; }
.ea-msg__text--md :deep(br) { display: block; content: ''; margin-top: 4px; }
.ea-msg__typing { margin: 0; color: var(--ds-muted); }
.ea-typing-dots { display: inline-flex; gap: 3px; margin-left: 5px; vertical-align: middle; }
.ea-typing-dots i { width: 4px; height: 4px; border-radius: 50%; background: currentColor; animation: ea-typing 1.05s infinite ease-in-out; }
.ea-typing-dots i:nth-child(2) { animation-delay: .15s; }
.ea-typing-dots i:nth-child(3) { animation-delay: .3s; }
.ea-waiting-slow, .ea-msg__recovery { display: grid; gap: 6px; padding: 8px 10px; border-left: 3px solid #e2b261; background: #fffbf3; color: #765816; font-size: 12px; line-height: 1.5; }
.ea-msg__recovery { border-left-color: #d98791; background: #fff7f7; color: #8e3d4a; }
.ea-msg__meta { display: flex; align-items: center; gap: 6px; padding: 0 4px; }
.ea-msg__src { padding: 2px 7px; border-radius: 999px; background: #e7f4f1; color: #276b65; font-size: 11px; font-weight: 750; }
.ea-msg__meta small { color: var(--ds-muted); font-size: 11px; }
.ea-msg__acts { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; padding: 0 2px; }
.ea-msg__acts :deep(.ui-button) { height: 30px; min-width: auto; padding-inline: 9px; font-size: 12px; }
.ea-msg__acts :deep(.ui-button--icon-only) { width: 30px; padding: 0; }
.ea-fb--on { color: #276b65 !important; }

.ea-handoff { margin: 0; padding: 8px 20px; border-top: 1px solid var(--ds-line); background: #f1fbf7; color: #276b65; font-size: 13px; }

.ea-input { padding: 12px 20px 14px; border-top: 1px solid var(--ds-line); background: #fff; }
.ea-input__row { display: flex; align-items: flex-end; gap: 10px; }
.ea-input textarea { flex: 1; min-height: 48px; max-height: 120px; resize: none; padding: 10px 13px; border: 1px solid var(--ds-line); border-radius: 8px; background: #fafcfb; color: var(--ds-ink); font: inherit; font-size: 14px; line-height: 1.5; }
.ea-input textarea:focus { outline: 3px solid rgba(39,107,101,.14); border-color: var(--ds-primary); background: #fff; }
.ea-input textarea:disabled { cursor: not-allowed; background: #f3f6f6; color: #71817e; }
.ea-input__hint { margin: 6px 0 0; color: var(--ds-muted); font-size: 11px; line-height: 1.4; }

@keyframes ea-typing { 0%, 60%, 100% { transform: translateY(0); opacity: .35; } 30% { transform: translateY(-3px); opacity: 1; } }

@media (max-width: 820px) {
  .ea-shell { grid-template-columns: 1fr; }
  .ea-sidebar { display: none; }
  .ea-quick-tags { display: flex; }
  .ea-msg { max-width: 96%; }
}
@media (max-width: 560px) {
  .ea-topbar { padding: 8px 14px; }
  .ea-chat__history { padding: 12px 14px; gap: 10px; }
  .ea-empty { padding: 32px 14px; }
  .ea-input { padding: 10px 14px 12px; }
  .ea-input__row { flex-direction: column; }
  .ea-input__row :deep(.ui-button) { width: 100%; }
}
@media (max-width: 390px) {
  .ea-page { min-height: 0; }
  .ea-topbar { padding: 6px 10px; }
  .ea-msg { max-width: 100%; }
  .ea-chat__history { padding: 10px; }
  .ea-input { padding: 8px 10px 10px; }
}
</style>
