<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { askMobileEmployeeAssistant, getMobileEmployeeAssistantStatus } from '../../api/business'
import SafeActionBar from '../../components/SafeActionBar.vue'
import { canUseMobileCapability, useSessionStore } from '../../stores'
import type { EmployeeAssistantReply, EmployeeAssistantStatus } from '../../types/business'

interface ChatMessage {
  id: string
  side: 'USER' | 'ASSISTANT'
  text: string
  source?: EmployeeAssistantReply['answerSource']
  needsHuman?: boolean
}

const session = useSessionStore()
const status = ref<EmployeeAssistantStatus | null>(null)
const messages = ref<ChatMessage[]>([])
const input = ref('')
const loading = ref(false)
const sending = ref(false)
const errorMessage = ref('')
const scrollTarget = ref('')
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
    status.value = await getMobileEmployeeAssistantStatus()
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
    })
  } catch (error) {
    errorMessage.value = friendlyError(error, '问题未发送成功，请检查网络后重试。')
  } finally {
    sending.value = false
    await scrollToLatest()
  }
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
.page { min-height: 100vh; box-sizing: border-box; padding: 24rpx 24rpx 0; background: #eef2ed; color: #172019; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 18rpx; margin-bottom: 18rpx; }
.eyebrow, .title, .message-text, .source, .human-note { display: block; }
.eyebrow { color: #657168; font-size: 24rpx; letter-spacing: 2rpx; }
.title { margin-top: 6rpx; font-size: 44rpx; font-weight: 700; }
.status-button, .send-button { min-height: 88rpx; line-height: 88rpx; border-radius: 18rpx; font-size: 27rpx; }
.status-button { min-width: 160rpx; padding: 0 18rpx; background: #fff; color: #264c36; border: 1px solid #cbd6cd; }
.messages { height: calc(100vh - 260rpx - env(safe-area-inset-bottom)); }
.message-row { display: flex; margin: 16rpx 0; }
.message-row.user { justify-content: flex-end; }
.message-row.assistant { justify-content: flex-start; }
.bubble { max-width: 82%; padding: 20rpx 22rpx; border-radius: 22rpx; background: #fff; box-shadow: 0 8rpx 22rpx rgba(27,45,32,.05); }
.user .bubble { background: #1f6741; color: #fff; border-bottom-right-radius: 6rpx; }
.assistant .bubble { border-bottom-left-radius: 6rpx; }
.message-text { white-space: pre-wrap; font-size: 27rpx; line-height: 1.65; }
.source { margin-top: 12rpx; color: #718076; font-size: 21rpx; }
.human-note { margin-top: 12rpx; padding-top: 10rpx; border-top: 1px solid #e4e9e4; color: #9a5d17; font-size: 23rpx; }
.typing { color: #657168; font-size: 24rpx; }
.chat-input { flex: 1; min-width: 0; min-height: 88rpx; box-sizing: border-box; padding: 0 22rpx; border: 1px solid #cbd6cd; border-radius: 20rpx; background: #f6f8f5; font-size: 27rpx; }
.send-button { flex: 0 0 150rpx; margin: 0; background: #1f6741; color: #fff; }
.message, .state-card { margin-bottom: 18rpx; padding: 18rpx 20rpx; border-radius: 14rpx; font-size: 25rpx; }
.message.error { background: #fff0ed; color: #963b30; }
.message.warning { background: #fff2d8; color: #855b17; }
.state-card { background: #fff; text-align: center; color: #657168; }
button::after { border: 0; }
</style>
