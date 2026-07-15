<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { AlertCircle, CircleHelp, LockKeyhole, RefreshCw, Send, ShieldCheck, ThumbsDown, ThumbsUp } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
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
  answerSource?: EmployeeAssistantAnswerSource
  knowledgeId?: number | null
  knowledgeVersion?: number | null
  knowledgeTitle?: string | null
  needsHuman?: boolean
  handoffCategory?: string | null
  questionId?: number
  handoffState?: 'idle' | 'sending' | 'created' | 'failed'
  feedbackState?: 'idle' | 'helpful' | 'inaccurate' | 'sending'
}

const status = ref<EmployeeAssistantStatus | null>(null)
const auth = useAuthStore()
const loading = ref(true)
const sending = ref(false)
const pageError = ref('')
const message = ref('')
const turns = ref<ChatTurn[]>([])
const handoffNotice = ref('')
const sessionId = createSessionId()
let nextTurnId = 0

const serviceState = computed<EmployeeAssistantServiceState>(() => {
  const explicitState = status.value?.state
  if (explicitState === 'UNCONFIGURED' || explicitState === 'AUTH_FAILED' || explicitState === 'UNAVAILABLE' || explicitState === 'READY') {
    return explicitState
  }

  // 兼容升级中的旧后端：只从结构化开关得出“未配置”或“就绪”，其余未知情况均按不可用处理。
  // 不能通过中文错误文案猜测授权状态，避免把网络故障误报为配置问题。
  if (status.value?.configured === false) return 'UNCONFIGURED'
  if (status.value?.configured && status.value.enabled) return 'READY'
  return 'UNAVAILABLE'
})

const serviceCopy = computed(() => {
  if (loading.value && !status.value) {
    return {
      title: '正在检查服务',
      badge: '检查中',
      empty: '正在检查员工服务助手，请稍候。',
      action: '请稍候，检查完成后会显示服务状态。',
    }
  }

  if (serviceState.value === 'UNCONFIGURED' && status.value?.canAsk) {
    return {
      title: '标准话术可用',
      badge: '仅标准话术',
      empty: '当前可查询已发布的标准话术；未命中时可转人工处理。',
      action: '上游服务未配置时，仅提供已发布标准话术与人工转接。',
    }
  }

  const copy: Record<EmployeeAssistantServiceState, { title: string; badge: string; empty: string; action: string }> = {
    UNCONFIGURED: {
      title: '服务未配置',
      badge: '未配置',
      empty: '当前未接入员工服务助手，不能发送问题。',
      action: '请联系管理员配置员工助手服务，完成后点击“检查服务”。',
    },
    AUTH_FAILED: {
      title: '服务授权异常',
      badge: '授权异常',
      empty: '员工服务助手授权未通过，暂不能发送问题。',
      action: '请联系管理员检查员工助手服务授权，完成后点击“检查服务”。',
    },
    UNAVAILABLE: {
      title: '服务暂不可用',
      badge: '暂不可用',
      empty: '员工服务助手暂时无法连接，不能发送问题。',
      action: '请稍后点击“检查服务”；持续异常请联系管理员处理。',
    },
    READY: {
      title: '服务已就绪',
      badge: '已就绪',
      empty: '输入一个服务问题，助手会给出可直接使用的中文建议。',
      action: '仅发送通用服务问题，不要填写客户隐私、财务数据、附件或密钥。',
    },
  }
  return copy[serviceState.value]
})

const isReady = computed(() => !loading.value && serviceState.value === 'READY')
const canAsk = computed(() => !loading.value && (status.value?.canAsk ?? serviceState.value === 'READY'))
const statusDetail = computed(() => safeStatusDetail(status.value?.message))
const canViewDeploymentGuide = computed(() => serviceState.value === 'UNCONFIGURED' && auth.role === 'BOSS')

onMounted(() => {
  void loadStatus()
})

async function loadStatus() {
  loading.value = true
  pageError.value = ''
  try {
    status.value = await getEmployeeAssistantStatus()
  } catch (error) {
    // 检查接口本身失败不是“未配置”。仅消费后端明确给出的安全业务码。
    status.value = serviceStatusFromFailure(error) || unavailableStatus(error)
  } finally {
    loading.value = false
  }
}

async function send() {
  const question = message.value.trim()
  if (!question || sending.value || !canAsk.value) return
  pageError.value = ''
  message.value = ''
  const userTurnId = ++nextTurnId
  turns.value.push({ id: userTurnId, role: 'user', content: question })
  sending.value = true
  try {
    const response = await askEmployeeAssistant({ sessionId, message: question })
    if (!response.configured) {
      status.value = {
        enabled: false,
        configured: false,
        state: 'UNCONFIGURED',
        message: '员工服务助手未配置，请联系管理员完成服务配置。',
      }
      message.value = question
      return
    }
    const answer = response.answer?.trim() || '暂未获得可用答复，请稍后再试。'
    turns.value.push({
      id: ++nextTurnId,
      role: 'assistant',
      content: answer,
      answerSource: response.answerSource || 'ASSISTANT',
      knowledgeId: response.knowledgeId,
      knowledgeVersion: response.knowledgeVersion,
      knowledgeTitle: response.knowledgeTitle,
      needsHuman: Boolean(response.needsHuman),
      handoffCategory: response.handoffCategory,
      questionId: userTurnId,
      handoffState: 'idle',
      feedbackState: 'idle',
    })
  } catch (error) {
    pageError.value = error instanceof Error && error.message
      ? error.message
      : '员工服务助手暂时无法处理，请稍后再试。'
    const failureStatus = serviceStatusFromFailure(error)
    if (failureStatus) {
      status.value = failureStatus
      message.value = question
    }
  } finally {
    sending.value = false
  }
}

async function createHandoff(turn: ChatTurn) {
  if (turn.handoffState === 'sending' || turn.handoffState === 'created') return
  const question = turns.value.find((item) => item.id === turn.questionId && item.role === 'user')?.content
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
      answerSource: turn.answerSource || 'ASSISTANT',
      knowledgeId: turn.knowledgeId,
      knowledgeVersion: turn.knowledgeVersion,
      helpful,
      reasonCode: helpful ? 'HELPFUL' : 'INACCURATE',
    })
    turn.feedbackState = helpful ? 'helpful' : 'inaccurate'
  } catch (error) {
    turn.feedbackState = 'idle'
    pageError.value = error instanceof Error ? error.message : '反馈提交失败，请稍后重试。'
  }
}

function sourceLabel(source?: EmployeeAssistantAnswerSource) {
  if (source === 'KNOWLEDGE') return '标准话术'
  if (source === 'HUMAN_REQUIRED') return '需人工处理'
  return '员工助手建议'
}

function serviceStatusFromFailure(error: unknown): EmployeeAssistantStatus | null {
  const apiError = error instanceof ApiError ? error : null
  const code = apiError?.code
  const message = apiError?.message || '员工服务助手暂时不可用，请稍后点击“检查服务”。'

  if (code === 'EMPLOYEE_ASSISTANT_NOT_CONFIGURED') {
    return { enabled: false, configured: false, state: 'UNCONFIGURED', message }
  }
  if (code === 'EMPLOYEE_ASSISTANT_AUTH_FAILED' || code === 'EMPLOYEE_ASSISTANT_UPSTREAM_FORBIDDEN') {
    return { enabled: false, configured: true, state: 'AUTH_FAILED', message }
  }
  if (code === 'EMPLOYEE_ASSISTANT_TIMEOUT'
    || code === 'EMPLOYEE_ASSISTANT_UNAVAILABLE'
    || code === 'EMPLOYEE_ASSISTANT_UPSTREAM_UNAVAILABLE'
    || code === 'EMPLOYEE_ASSISTANT_CANCELLED'
    || code === 'EMPLOYEE_ASSISTANT_RESPONSE_INVALID') {
    return { enabled: false, configured: true, state: 'UNAVAILABLE', message }
  }
  return null
}

function unavailableStatus(error: unknown): EmployeeAssistantStatus {
  const message = error instanceof Error && error.message
    ? error.message
    : '员工服务助手暂时不可用，请稍后点击“检查服务”。'
  return { enabled: false, configured: true, state: 'UNAVAILABLE', message }
}

function safeStatusDetail(rawMessage?: string) {
  const message = rawMessage?.trim() || ''
  if (!message) return ''
  // The backend contract already returns business-safe copy. Keep a narrow UI-side guard so an
  // accidental endpoint, query string, token, or authorization header can never appear here.
  if (/(https?:\/\/|[?&][^\s=&]+=[^\s]+|\b(?:bearer|token|api[ _-]?key|authorization|secret)\b|密钥|令牌|内部地址|上游地址)/i.test(message)) {
    return '服务状态已收到，请按上方指引联系管理员处理。'
  }
  return message
}

function createSessionId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `employee-${Date.now()}-${Math.random().toString(16).slice(2)}`
}
</script>

<template>
  <section class="employee-assistant-page">
    <PageHeader subtitle="面向员工常见问题和服务话术，与门店经营助手相互独立。">
      <template #actions>
        <UiButton :loading="loading" :disabled="loading" @click="loadStatus"><template #icon><RefreshCw :size="16" /></template>检查服务</UiButton>
      </template>
    </PageHeader>

    <section
      class="assistant-trust-card"
      :class="[`state--${serviceState.toLowerCase()}`, { 'is-checking': loading && !status }]"
      role="status"
      aria-live="polite"
      data-testid="employee-assistant-status"
    >
      <div class="trust-icon"><ShieldCheck v-if="isReady" :size="22" /><AlertCircle v-else :size="22" /></div>
      <div>
        <strong>{{ serviceCopy.title }}</strong>
        <p id="employee-assistant-status-help">{{ serviceCopy.action }}</p>
        <small v-if="statusDetail">{{ statusDetail }}</small>
        <details v-if="canViewDeploymentGuide" class="deployment-guide" data-testid="employee-assistant-deployment-guide">
          <summary>维护人员部署说明</summary>
          <div class="deployment-guide__body">
            <p>此页面不能配置服务。请由维护人员在启动 Java 的同一进程环境中注入变量；已经运行的 Java 不会自动读取后来新增的变量。</p>
            <p>请选择且只选择一种模式，不能混用，也不能复用 <code>DEEPSEEK_*</code> 变量：</p>
            <ul>
              <li><b>REMOTE</b>：<code>EMPLOYEE_ASSISTANT_PROVIDER=REMOTE</code>、<code>EMPLOYEE_ASSISTANT_URL</code>、<code>EMPLOYEE_ASSISTANT_API_TOKEN</code></li>
              <li><b>MODEL</b>：<code>EMPLOYEE_ASSISTANT_PROVIDER=MODEL</code>、<code>EMPLOYEE_ASSISTANT_MODEL_URL</code>、<code>EMPLOYEE_ASSISTANT_MODEL_API_KEY</code>、<code>EMPLOYEE_ASSISTANT_MODEL_NAME</code></li>
            </ul>
            <p>先运行 <code>scripts/verify-employee-assistant-config.ps1</code> 完成预检；获得维护授权后重启 Java 服务，再回到本页点击“检查服务”。本页不会显示或写入任何配置值。</p>
          </div>
        </details>
      </div>
    </section>

    <div v-if="pageError" class="error-box" role="alert">{{ pageError }}</div>

    <section class="assistant-shell content-card">
      <aside class="assistant-guide">
        <div class="guide-mark"><CircleHelp :size="22" /></div>
        <h2>可以问什么</h2>
        <p>员工常见问题、服务话术、交接说明和基础流程。</p>
        <ul>
          <li>“顾客投诉等待太久，怎么回应？”</li>
          <li>“交班时需要核对哪些事项？”</li>
          <li>“会员券不能使用时怎么解释？”</li>
        </ul>
        <div class="privacy-note"><LockKeyhole :size="16" /><span>请勿发送客户姓名、电话、订单号、附件、门店财务或任何密钥。</span></div>
      </aside>

      <div class="chat-panel">
        <div class="chat-heading">
          <div><h2>员工服务助手</h2><p>本次对话只保存在当前页面，不会写入浏览器业务数据。</p></div>
          <span class="connection-dot" :class="`state--${serviceState.toLowerCase()}`">{{ serviceCopy.badge }}</span>
        </div>
        <div class="chat-history" aria-live="polite">
          <div v-if="!turns.length" class="chat-empty"><CircleHelp :size="26" /><p>{{ serviceCopy.empty }}</p></div>
          <article v-for="turn in turns" :key="turn.id" class="chat-bubble" :class="turn.role">
            <span>{{ turn.role === 'user' ? '你' : '助手' }}</span>
            <p>{{ turn.content }}</p>
            <template v-if="turn.role === 'assistant'">
              <div class="answer-meta"><span class="answer-source">{{ sourceLabel(turn.answerSource) }}</span><small v-if="turn.knowledgeTitle">{{ turn.knowledgeTitle }}</small></div>
              <div class="answer-actions">
                <UiButton v-if="turn.needsHuman" variant="secondary" :loading="turn.handoffState === 'sending'" :disabled="turn.handoffState === 'created'" @click="createHandoff(turn)">
                  {{ turn.handoffState === 'created' ? '已转人工' : '转人工处理' }}
                </UiButton>
                <template v-if="turn.answerSource !== 'HUMAN_REQUIRED'">
                  <UiButton variant="ghost" icon-only :disabled="turn.feedbackState !== 'idle'" aria-label="有帮助" @click="submitFeedback(turn, true)"><template #icon><ThumbsUp :size="16" /></template></UiButton>
                  <UiButton variant="ghost" icon-only :disabled="turn.feedbackState !== 'idle'" aria-label="不准确" @click="submitFeedback(turn, false)"><template #icon><ThumbsDown :size="16" /></template></UiButton>
                  <small v-if="turn.feedbackState === 'helpful'">已记录“有帮助”</small>
                  <small v-else-if="turn.feedbackState === 'inaccurate'">已记录“不准确”</small>
                </template>
              </div>
            </template>
          </article>
          <article v-if="sending" class="chat-bubble assistant pending"><span>助手</span><p>正在整理回复…</p></article>
        </div>
        <p v-if="handoffNotice" class="handoff-notice" role="status">{{ handoffNotice }}</p>
        <form class="chat-input" @submit.prevent="send">
          <textarea v-model="message" :disabled="!canAsk || sending" :aria-describedby="!canAsk ? 'employee-assistant-status-help' : undefined" rows="3" maxlength="800" placeholder="例如：顾客说饮品太甜，怎样礼貌处理？" />
          <div><small>不要填写客户隐私、财务数据、附件内容或令牌。</small><UiButton variant="primary" type="submit" :loading="sending" :disabled="!canAsk || !message.trim()"><template #icon><Send :size="17" /></template>发送问题</UiButton></div>
        </form>
      </div>
    </section>
  </section>
</template>

<style scoped>
.employee-assistant-page { display: grid; gap: 18px; }
.assistant-trust-card { display: flex; align-items: center; gap: 12px; padding: 14px 16px; border: 1px solid #bfe5d8; border-radius: 8px; background: #f1fbf7; }
.assistant-trust-card.state--unconfigured { border-color: #eed9ae; background: #fff9ec; }
.assistant-trust-card.state--auth_failed { border-color: #efc9cf; background: #fff5f5; }
.assistant-trust-card.state--unavailable, .assistant-trust-card.is-checking { border-color: #d4dddf; background: #f7fafb; }
.trust-icon { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 50%; background: rgba(43, 128, 99, .12); color: #207258; }
.state--unconfigured .trust-icon { background: rgba(187, 122, 19, .12); color: #9c6310; }
.state--auth_failed .trust-icon { background: rgba(159, 39, 52, .10); color: #9f2734; }
.state--unavailable .trust-icon, .is-checking .trust-icon { background: rgba(77, 100, 108, .10); color: #566f6b; }
.assistant-trust-card strong { color: var(--ds-ink); font-size: 15px; }
.assistant-trust-card p { margin: 3px 0 0; color: var(--ds-secondary); font-size: 13px; line-height: 1.5; }
.assistant-trust-card small { display: block; margin-top: 4px; color: var(--ds-muted); font-size: 12px; line-height: 1.45; }
.deployment-guide { margin-top: 10px; border-top: 1px solid rgba(154, 104, 20, .24); color: #6a531e; font-size: 12px; line-height: 1.6; }
.deployment-guide summary { padding-top: 9px; cursor: pointer; color: #765816; font-weight: 800; }
.deployment-guide__body { display: grid; gap: 8px; padding: 8px 0 2px; }
.deployment-guide__body p { margin: 0; color: #6a5a35; font-size: 12px; }
.deployment-guide__body ul { display: grid; gap: 6px; margin: 0; padding-left: 18px; }
.deployment-guide__body code { overflow-wrap: anywhere; padding: 1px 3px; border-radius: 3px; background: rgba(154, 104, 20, .08); color: #705110; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11px; }
.error-box { padding: 11px 13px; border: 1px solid #efc9cf; border-radius: 8px; background: #fff5f5; color: #9f2734; font-size: 14px; font-weight: 650; }
.assistant-shell { display: grid; grid-template-columns: minmax(230px, .52fr) minmax(0, 1.48fr); min-height: 570px; padding: 0; overflow: hidden; }
.assistant-guide { display: grid; align-content: start; gap: 12px; padding: 28px 24px; border-right: 1px solid var(--ds-line); background: #f5faf9; }
.guide-mark { display: grid; width: 42px; height: 42px; place-items: center; border: 1px solid #bfe0dc; border-radius: 8px; background: #e8f5f3; color: var(--ds-primary-hover); }
.assistant-guide h2, .chat-heading h2 { margin: 0; color: var(--ds-ink); font-size: 17px; }
.assistant-guide p, .chat-heading p { margin: 0; color: var(--ds-muted); font-size: 13px; line-height: 1.55; }
.assistant-guide ul { display: grid; gap: 9px; margin: 4px 0; padding: 0; list-style: none; }
.assistant-guide li { padding-left: 12px; border-left: 2px solid #a6d4cf; color: var(--ds-secondary); font-size: 13px; line-height: 1.5; }
.privacy-note { display: flex; gap: 8px; padding-top: 13px; border-top: 1px solid var(--ds-line); color: #566f6b; font-size: 12px; line-height: 1.5; }
.privacy-note svg { flex: 0 0 auto; margin-top: 1px; color: #38786f; }
.chat-panel { display: grid; grid-template-rows: auto minmax(260px, 1fr) auto; min-width: 0; }
.chat-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 24px 24px 16px; border-bottom: 1px solid var(--ds-line); }
.chat-heading p { margin-top: 4px; }
.connection-dot { display: inline-flex; align-items: center; gap: 6px; flex: 0 0 auto; padding: 5px 8px; border-radius: 999px; background: #e7f7ef; color: #237153; font-size: 11px; font-weight: 800; }
.connection-dot::before { width: 6px; height: 6px; border-radius: 50%; background: currentColor; content: ''; }
.connection-dot.state--unconfigured { background: #fff1db; color: #9a6814; }
.connection-dot.state--auth_failed { background: #ffebed; color: #9f2734; }
.connection-dot.state--unavailable { background: #edf2f3; color: #566f6b; }
.chat-history { display: grid; align-content: start; gap: 12px; max-height: 440px; min-height: 0; padding: 22px 24px; overflow-y: auto; }
.chat-empty { display: grid; min-height: 205px; place-content: center; justify-items: center; gap: 8px; color: var(--ds-muted); text-align: center; }
.chat-empty p { max-width: 290px; margin: 0; font-size: 13px; line-height: 1.6; }
.chat-bubble { display: grid; max-width: min(620px, 92%); gap: 4px; padding: 11px 13px; border-radius: 9px; background: #f2f6f5; color: var(--ds-ink); }
.chat-bubble.user { justify-self: end; background: #e5f4f1; }
.chat-bubble.assistant { justify-self: start; border: 1px solid var(--ds-line); background: #fff; }
.chat-bubble.pending { color: var(--ds-muted); }
.chat-bubble span { color: var(--ds-muted); font-size: 11px; font-weight: 800; }
.chat-bubble p { margin: 0; white-space: pre-wrap; font-size: 14px; line-height: 1.65; }
.answer-meta, .answer-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; }
.answer-source { padding: 3px 6px; border-radius: 999px; background: #e7f4f1; color: #276b65; font-size: 11px; font-weight: 800; }
.answer-meta small, .answer-actions small { color: var(--ds-muted); font-size: 12px; }
.answer-actions { padding-top: 4px; }
.answer-actions :deep(.ui-button) { min-width: auto; height: 34px; padding-inline: 11px; font-size: 13px; }
.answer-actions :deep(.ui-button--icon-only) { width: 34px; padding: 0; }
.handoff-notice { margin: 0; padding: 10px 24px; border-top: 1px solid var(--ds-line); background: #f1fbf7; color: #276b65; font-size: 13px; }
.chat-input { display: grid; gap: 10px; padding: 16px 24px 22px; border-top: 1px solid var(--ds-line); background: #fbfcfc; }
.chat-input textarea { width: 100%; min-height: 84px; resize: vertical; padding: 11px; border: 1px solid var(--ds-line); border-radius: 8px; background: #fff; color: var(--ds-ink); font: inherit; line-height: 1.5; }
.chat-input textarea:focus { outline: 3px solid rgba(39, 107, 101, .16); border-color: var(--ds-primary); }
.chat-input textarea:disabled { cursor: not-allowed; background: #f3f6f6; color: #71817e; }
.chat-input > div { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.chat-input small { color: var(--ds-muted); font-size: 12px; line-height: 1.4; }
@media (max-width: 820px) { .assistant-shell { grid-template-columns: 1fr; } .assistant-guide { border-right: 0; border-bottom: 1px solid var(--ds-line); } .assistant-guide ul { display: none; } }
@media (max-width: 560px) { .assistant-trust-card, .chat-heading, .chat-input > div { align-items: flex-start; flex-direction: column; } .assistant-guide, .chat-heading, .chat-history, .chat-input { padding-left: 16px; padding-right: 16px; } .chat-input :deep(.ui-button) { width: 100%; } .connection-dot { align-self: stretch; justify-content: center; } .deployment-guide__body ul { padding-left: 16px; } }
</style>
