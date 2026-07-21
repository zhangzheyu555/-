<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { askMobileEmployeeAssistant, createMobileEmployeeAssistantHandoff, getMobileEmployeeAssistantStatus, getMyMobileEmployeeAssistantHandoffs, sendMobileEmployeeAssistantFeedback } from '../../api/business'
import SafeActionBar from '../../components/SafeActionBar.vue'
import { canUseMobileCapability, useSessionStore } from '../../stores'
import type { EmployeeAssistantHandoff, EmployeeAssistantReply, EmployeeAssistantStatus } from '../../types/business'

interface ChatMessage {
  id: string
  side: 'USER' | 'ASSISTANT'
  text: string
  source?: EmployeeAssistantReply['answerSource']
  needsHuman?: boolean
  question?: string
  feedbackSent?: boolean
  handoffCreated?: boolean
}

const session = useSessionStore()
const status = ref<EmployeeAssistantStatus | null>(null)
const messages = ref<ChatMessage[]>([])
const handoffs = ref<EmployeeAssistantHandoff[]>([])
const input = ref('')
const loading = ref(false)
const sending = ref(false)
const errorMessage = ref('')
const scrollTarget = ref('')
const actingMessageId = ref('')
const chatSessionId = `mobile-assistant-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`

const canUse = computed(() => canUseMobileCapability(session.user, 'assistant'))
const canAsk = computed(() => canUse.value && status.value?.enabled !== false && status.value?.canAsk !== false)

onShow(() => {
  if (!canUse.value) return denyAndReturn()
  void loadStatus()
})

async function loadStatus() {
  if (!canUse.value || loading.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [serviceStatus, mine] = await Promise.all([getMobileEmployeeAssistantStatus(), getMyMobileEmployeeAssistantHandoffs()])
    status.value = serviceStatus
    handoffs.value = mine || []
    if (!messages.value.length) {
      messages.value.push({
        id: messageId(),
        side: 'ASSISTANT',
        text: status.value.message || '你好，我可以协助查询员工制度、培训与日常服务问题。',
      })
    }
  } catch (error) {
    errorMessage.value = friendlyError(error, '员工服务助手暂时无法连接，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function send() {
  const question = input.value.trim()
  if (!question || !canAsk.value || sending.value) return
  messages.value.push({ id: messageId(), side: 'USER', text: question })
  input.value = ''
  await scrollToLatest()
  sending.value = true
  errorMessage.value = ''
  try {
    const reply = await askMobileEmployeeAssistant({ sessionId: chatSessionId, message: question })
    messages.value.push({
      id: messageId(),
      side: 'ASSISTANT',
      text: reply.answer || '暂时没有找到合适答案，请联系负责人。',
      source: reply.answerSource,
      needsHuman: reply.needsHuman,
      question,
    })
  } catch (error) {
    errorMessage.value = friendlyError(error, '问题未发送成功，请检查网络后重试。')
  } finally {
    sending.value = false
    await scrollToLatest()
  }
}

async function createHandoff(message: ChatMessage) {
  if (!message.question || actingMessageId.value) return
  actingMessageId.value = message.id
  errorMessage.value = ''
  try {
    const created = await createMobileEmployeeAssistantHandoff({ question: message.question, storeId: session.user?.boundStoreId || undefined })
    message.handoffCreated = true
    handoffs.value.unshift(created)
    uni.showToast({ title: '已转交负责人', icon: 'success' })
  } catch (error) {
    errorMessage.value = friendlyError(error, '转人工失败，请稍后重试。')
  } finally {
    actingMessageId.value = ''
  }
}

async function sendFeedback(message: ChatMessage, helpful: boolean) {
  if (!message.source || message.feedbackSent || actingMessageId.value) return
  actingMessageId.value = message.id
  errorMessage.value = ''
  try {
    await sendMobileEmployeeAssistantFeedback({ answerSource: message.source, helpful, reasonCode: helpful ? 'MOBILE_HELPFUL' : 'MOBILE_NOT_HELPFUL' })
    message.feedbackSent = true
    uni.showToast({ title: '感谢反馈', icon: 'none' })
  } catch (error) {
    errorMessage.value = friendlyError(error, '反馈提交失败，请稍后重试。')
  } finally {
    actingMessageId.value = ''
  }
}

function handoffStatus(statusValue: string) {
  return ({ OPEN: '待接单', CLAIMED: '处理中', RESPONDED: '已回复', CLOSED: '已关闭', EXPIRED: '已超时' } as Record<string, string>)[statusValue] || statusValue
}

async function scrollToLatest() {
  await nextTick()
  scrollTarget.value = messages.value.at(-1)?.id || ''
}

function sourceText(source: ChatMessage['source']) {
  if (source === 'KNOWLEDGE') return '制度知识库'
  if (source === 'ASSISTANT') return '智能助手'
  if (source === 'HUMAN_REQUIRED') return '建议人工处理'
  return ''
}

function messageId() {
  return `msg-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function friendlyError(error: unknown, fallback: string) {
  const statusCode = Number((error as { status?: number })?.status || 0)
  if (statusCode === 403) return '当前账号没有员工服务助手权限。'
  if (statusCode === 401) return '登录已过期，请重新登录。'
  return fallback
}

function denyAndReturn() {
  uni.showToast({ title: '员工服务助手仅向员工开放', icon: 'none' })
  setTimeout(() => uni.reLaunch({ url: '/pages/home/index' }), 500)
}
</script>

<template>
  <view class="page">
    <view class="page-head">
      <view><text class="eyebrow">员工服务</text><text class="title">服务助手</text></view>
      <button class="status-button" :loading="loading" :disabled="loading || !canUse" @click="loadStatus">检查服务</button>
    </view>

    <view v-if="!canUse" class="state-card">员工服务助手仅向员工开放，并需具备后端使用权限。</view>
    <template v-else>
      <view v-if="status && !canAsk" class="message warning">{{ status.message || '助手暂未开放，请联系管理员。' }}</view>
      <view v-if="errorMessage" class="message error">{{ errorMessage }}</view>

      <view v-if="handoffs.length" class="handoff-section">
        <view class="section-head"><text class="section-title">我的人工事项</text><text class="section-count">{{ handoffs.length }} 条</text></view>
        <scroll-view class="handoff-list" scroll-x :show-scrollbar="false">
          <view v-for="item in handoffs.slice(0, 10)" :key="item.id" class="handoff-card">
            <view class="handoff-head"><text>{{ handoffStatus(item.status) }}</text><text>{{ item.createdAt }}</text></view>
            <text class="handoff-question">{{ item.question }}</text>
            <text v-if="item.resolution" class="handoff-resolution">负责人回复：{{ item.resolution }}</text>
            <text v-else class="handoff-resolution">{{ item.handledByName ? `${item.handledByName} 正在处理` : '等待负责人接单' }}</text>
          </view>
        </scroll-view>
      </view>

      <scroll-view class="messages" scroll-y :scroll-into-view="scrollTarget" :show-scrollbar="false">
        <view
          v-for="message in messages"
          :id="message.id"
          :key="message.id"
          class="message-row"
          :class="message.side === 'USER' ? 'user' : 'assistant'"
        >
          <view class="bubble">
            <text class="message-text">{{ message.text }}</text>
            <text v-if="message.source" class="source">{{ sourceText(message.source) }}</text>
            <text v-if="message.needsHuman" class="human-note">该问题建议由负责人继续处理。</text>
            <view v-if="message.side === 'ASSISTANT' && message.question" class="bubble-actions">
              <button v-if="message.needsHuman" :loading="actingMessageId === message.id" :disabled="message.handoffCreated || Boolean(actingMessageId)" @click="createHandoff(message)">{{ message.handoffCreated ? '已转人工' : '转人工处理' }}</button>
              <template v-if="message.source && message.source !== 'HUMAN_REQUIRED'">
                <text v-if="message.feedbackSent" class="feedback-done">已反馈</text>
                <template v-else><button :disabled="Boolean(actingMessageId)" @click="sendFeedback(message, true)">有帮助</button><button :disabled="Boolean(actingMessageId)" @click="sendFeedback(message, false)">没解决</button></template>
              </template>
            </view>
          </view>
        </view>
        <view v-if="sending" class="message-row assistant"><view class="bubble typing">正在查找可靠答案…</view></view>
      </scroll-view>
    </template>

    <SafeActionBar v-if="canUse">
      <input
        v-model="input"
        class="chat-input"
        :disabled="!canAsk || sending"
        :placeholder="canAsk ? '输入员工服务问题' : '助手暂不可用'"
        confirm-type="send"
        :adjust-position="false"
        :cursor-spacing="150"
        @confirm="send"
      >
      <button class="send-button" :loading="sending" :disabled="!canAsk || sending || !input.trim()" @click="send">发送</button>
    </SafeActionBar>
  </view>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; box-sizing: border-box; padding: 24rpx 24rpx 0; background: #f2f6f5; color: #1c1d22; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 18rpx; margin-bottom: 18rpx; }
.eyebrow, .title, .message-text, .source, .human-note { display: block; }
.eyebrow { color: #71807d; font-size: 24rpx; letter-spacing: 0; }
.title { margin-top: 6rpx; font-size: 38rpx; font-weight: 700; }
.status-button, .send-button { min-height: 88rpx; line-height: 88rpx; border-radius: 16rpx; font-size: 27rpx; }
.status-button { min-width: 160rpx; padding: 0 18rpx; background: #fff; color: #27655f; border: 1px solid #d9e6e3; }
.messages { height: calc(100vh - 260rpx - env(safe-area-inset-bottom)); }
.handoff-section { margin-bottom: 10rpx; }
.section-head, .handoff-head { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.section-title { font-size: 28rpx; font-weight: 700; }
.section-count { color: #71807d; font-size: 23rpx; }
.handoff-list { width: 100%; margin-top: 12rpx; white-space: nowrap; }
.handoff-card { display: inline-block; width: 520rpx; min-height: 150rpx; box-sizing: border-box; margin-right: 14rpx; padding: 18rpx; vertical-align: top; white-space: normal; border: 1px solid #d9e6e3; border-radius: 14rpx; background: #fff; }
.handoff-head { color: #71807d; font-size: 21rpx; }
.handoff-head text:first-child { color: #27655f; font-weight: 700; }
.handoff-question, .handoff-resolution { display: block; margin-top: 10rpx; font-size: 24rpx; line-height: 1.45; }
.handoff-question { font-weight: 650; }
.handoff-resolution { color: #59616c; }
.message-row { display: flex; margin: 16rpx 0; }
.message-row.user { justify-content: flex-end; }
.message-row.assistant { justify-content: flex-start; }
.bubble { max-width: 82%; padding: 20rpx 22rpx; border-radius: 16rpx; background: #fff; box-shadow: 0 8rpx 22rpx rgba(37,39,45,.05); }
.user .bubble { background: #27655f; color: #fff; border-bottom-right-radius: 6rpx; }
.assistant .bubble { border-bottom-left-radius: 6rpx; }
.bubble-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10rpx; margin-top: 14rpx; padding-top: 12rpx; border-top: 1px solid #edf0f3; }
.bubble-actions button { min-height: 62rpx; margin: 0; padding: 0 16rpx; line-height: 62rpx; border-radius: 12rpx; background: #e6f3f1; color: #27655f; font-size: 22rpx; }
.feedback-done { color: #65706a; font-size: 22rpx; }
.message-text { white-space: pre-wrap; font-size: 27rpx; line-height: 1.65; }
.source { margin-top: 12rpx; color: #718076; font-size: 21rpx; }
.human-note { margin-top: 12rpx; padding-top: 10rpx; border-top: 1px solid #e4e9e4; color: #9a5d17; font-size: 23rpx; }
.typing { color: #71807d; font-size: 24rpx; }
.chat-input { flex: 1; min-width: 0; min-height: 88rpx; box-sizing: border-box; padding: 0 22rpx; border: 1px solid #d9e6e3; border-radius: 16rpx; background: #f6f8f5; font-size: 27rpx; }
.send-button { flex: 0 0 150rpx; margin: 0; background: #27655f; color: #fff; }
.message, .state-card { margin-bottom: 18rpx; padding: 18rpx 20rpx; border-radius: 14rpx; font-size: 25rpx; }
.message.error { background: #fff0ed; color: #963b30; }
.message.warning { background: #fff2d8; color: #855b17; }
.state-card { background: #fff; text-align: center; color: #71807d; }
button::after { border: 0; }
</style>
