<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import StatusChip from '@/components/StatusChip.vue'
import { closeMobileBossTodo, escalateMobileRoleTodo, getMobileRoleTodos, resolveMobileRoleTodo } from '@/api/business'
import { useSessionStore } from '@/stores'
import type { RoleTodoItem } from '@/types/business'
import { canPerformMobileAction } from '@/permissions'

const session = useSessionStore()
const todos = ref<RoleTodoItem[]>([])
const loading = ref(false)
const error = ref('')
const activeStatus = ref('')
const actingId = ref('')
const canResolve = computed(() => canPerformMobileAction(session.user, 'todo.resolve'))
const canEscalate = computed(() => canPerformMobileAction(session.user, 'todo.escalate'))
const canClose = computed(() => canPerformMobileAction(session.user, 'todo.close'))
const statusTabs = [
  { value: '', label: '全部' },
  { value: 'PENDING', label: '待处理' },
  { value: 'IN_PROGRESS', label: '处理中' },
  { value: 'PENDING_REVIEW', label: '待复核' },
  { value: 'COMPLETED', label: '已完成' },
]
const filteredTodos = computed(() => activeStatus.value
  ? todos.value.filter((todo) => todo.status === activeStatus.value)
  : todos.value)
const pendingCount = computed(() => todos.value.filter((todo) => !['COMPLETED', 'REJECTED'].includes(todo.status)).length)
const completedCount = computed(() => todos.value.filter((todo) => todo.status === 'COMPLETED').length)

onShow(() => { void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (loading.value) return
  if (!session.user && !await session.restore()) { uni.reLaunch({ url: '/pages/login/index' }); return }
  loading.value = true; error.value = ''
  try { todos.value = (await getMobileRoleTodos(session.user?.role || '', true)).items || [] }
  catch (cause) { error.value = Number((cause as { status?: number })?.status) === 403 ? '当前账号暂无待办查看权限。' : '待办暂时无法加载，请检查网络后重试。' }
  finally { loading.value = false }
}

function open(todo: RoleTodoItem) {
  uni.navigateTo({ url: `/pages/todo-detail/index?id=${encodeURIComponent(todo.id)}` })
}

async function transition(todo: RoleTodoItem, action: 'resolve' | 'escalate' | 'close') {
  if (action === 'resolve' && !canResolve.value) return
  if (action === 'escalate' && !canEscalate.value) return
  if (action === 'close' && !canClose.value) return
  const note = await askNote(action === 'escalate' ? '填写升级原因' : '填写处理说明')
  if (!note) return
  actingId.value = todo.id
  try {
    if (action === 'escalate') await escalateMobileRoleTodo(session.user?.role || '', todo.id, note)
    else if (action === 'close') await closeMobileBossTodo(todo.id, note)
    else await resolveMobileRoleTodo(session.user?.role || '', todo.id, note)
    await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '待办处理失败，请稍后重试。' }
  finally { actingId.value = '' }
}

function askNote(title: string) {
  return new Promise<string>((resolve) => uni.showModal({ title, editable: true, placeholderText: '请输入说明', success: (result) => resolve(result.confirm ? String(result.content || '').trim() : ''), fail: () => resolve('') }))
}

function statusLabel(status: string) {
  return {
    PENDING: '待处理',
    IN_PROGRESS: '处理中',
    PENDING_REVIEW: '待复核',
    COMPLETED: '已完成',
    REJECTED: '已驳回',
  }[status] || status
}

function statusTone(status: string): 'warning' | 'info' | 'success' | 'danger' | 'neutral' {
  return {
    PENDING: 'danger',
    IN_PROGRESS: 'info',
    PENDING_REVIEW: 'warning',
    COMPLETED: 'success',
    REJECTED: 'neutral',
  }[status] as 'warning' | 'info' | 'success' | 'danger' | 'neutral' || 'neutral'
}
</script>

<template>
  <view class="mobile-page todo-page">
    <view class="todo-topbar"><text>待办中心</text><button class="refresh-button" :loading="loading" :disabled="loading" @click="refresh">刷新</button></view>
    <view class="todo-heading"><view><text class="todo-heading__eyebrow">我的任务</text><text class="todo-heading__title">今日待办</text><text class="todo-heading__description">按优先级处理，复杂事项将跳转至对应业务中心</text></view></view>
    <view class="todo-summary"><view class="todo-summary__item todo-summary__item--main"><text>待处理</text><text>{{ pendingCount }}<text> 项</text></text></view><view class="todo-summary__item"><text>全部待办</text><text>{{ todos.length }}<text> 项</text></text></view><view class="todo-summary__item"><text>已完成</text><text>{{ completedCount }}<text> 项</text></text></view></view>
    <scroll-view scroll-x class="status-scroll" :show-scrollbar="false">
      <view class="status-tabs">
        <button v-for="tab in statusTabs" :key="tab.value" class="status-tab" :class="{ active: activeStatus === tab.value }" @click="activeStatus = tab.value">{{ tab.label }}</button>
      </view>
    </scroll-view>
    <view v-if="error" class="message">{{ error }}</view>
    <view v-else-if="loading" class="state">正在读取待办…</view>
    <view v-else-if="!filteredTodos.length" class="state">当前筛选下没有待办事项</view>
    <view v-for="todo in filteredTodos" :key="todo.id" class="todo mobile-feedback" role="button" @click="open(todo)">
      <view class="todo-main">
        <view class="todo-top"><text class="todo-title">{{ todo.title }}</text><StatusChip :label="statusLabel(todo.status)" :tone="statusTone(todo.status)" /></view>
        <text class="todo-copy">{{ todo.summary || '请进入对应应用处理' }}</text>
        <view class="todo-foot"><text class="todo-meta">{{ todo.storeName || todo.storeId || '权限范围内事项' }}</text><text v-if="todo.dueAt" class="todo-meta">{{ todo.dueAt }}</text></view>
        <view v-if="(canResolve || canEscalate || canClose) && !['COMPLETED','REJECTED'].includes(todo.status)" class="todo-actions" @click.stop>
          <button v-if="canResolve" :disabled="Boolean(actingId)" @click.stop="transition(todo,'resolve')">完成</button>
          <button v-if="canEscalate && !todo.escalatedToBoss" :disabled="Boolean(actingId)" @click.stop="transition(todo,'escalate')">升级</button>
          <button v-if="canClose" :disabled="Boolean(actingId)" @click.stop="transition(todo,'close')">关闭</button>
        </view>
      </view>
      <text class="priority">P{{ todo.priority }}</text>
    </view>
  </view>
</template>

<style scoped lang="scss">
.todo-page { display: flex; flex-direction: column; gap: 20rpx; color: #1f2b2a; }
.todo-topbar { display: flex; min-height: 68rpx; align-items: center; justify-content: space-between; color: #5f7672; font-size: 23rpx; font-weight: 750; }
.refresh-button { min-width: 88rpx; min-height: 68rpx; margin: 0; padding: 0 20rpx; color: #315b57; background: #fff; border: 1rpx solid #cfe0dc; border-radius: 12rpx; font-size: 23rpx; font-weight: 700; line-height: 66rpx; }
.refresh-button::after { border: 0; }
.todo-heading > view { display: flex; flex-direction: column; gap: 7rpx; }
.todo-heading__eyebrow { color: #6e8682; font-size: 22rpx; font-weight: 700; }
.todo-heading__title { color: #1f2b2a; font-family: $mobile-font-display; font-size: 42rpx; font-weight: 850; line-height: 1.18; }
.todo-heading__description { color: #788b87; font-size: 23rpx; line-height: 1.55; }
.todo-summary { display: grid; grid-template-columns: 1.2fr 1fr 1fr; gap: 14rpx; }
.todo-summary__item { display: flex; min-height: 134rpx; padding: 20rpx; flex-direction: column; justify-content: space-between; background: #fff; border: 1rpx solid #d9e6e3; border-radius: 14rpx; }
.todo-summary__item > text:first-child { color: #758984; font-size: 20rpx; font-weight: 700; }
.todo-summary__item > text:last-child { color: #293b37; font-size: 34rpx; font-weight: 850; }
.todo-summary__item > text:last-child text { font-size: 19rpx; font-weight: 650; }
.todo-summary__item--main { color: #fff; background: #27655f; border-color: #27655f; }
.todo-summary__item--main > text:first-child { color: rgba(255,255,255,.72); }
.todo-summary__item--main > text:last-child { color: #fff; }
.status-scroll { width: 100%; white-space: nowrap; }
.status-tabs { display: inline-flex; gap: 10rpx; }
.status-tab { min-height: 68rpx; margin: 0; padding: 0 22rpx; color: #718581; background: #fff; border: 1rpx solid #d9e6e3; border-radius: 10rpx; font-size: 23rpx; line-height: 66rpx; }
.status-tab::after, .todo::after { border: 0; }
.status-tab.active { color: #27655f; background: #e4f3f0; border-color: #b9d9d3; }
.state,.message { padding: 44rpx 24rpx; text-align: center; background: #fff; border: 1rpx solid #d9e6e3; border-radius: 14rpx; color: #718581; }
.message { color: #a25145; background: #fff5f2; border-color: #f1d6cf; }
.todo { position: relative; display: flex; width: 100%; min-height: 148rpx; margin: 0; padding: 22rpx 20rpx 22rpx 28rpx; align-items: flex-start; justify-content: space-between; gap: 18rpx; text-align: left; background: #fff; border: 1rpx solid #d9e6e3; border-radius: 14rpx; box-shadow: 0 5rpx 14rpx rgba(46,79,73,.035); }
.todo::before { position: absolute; top: 22rpx; bottom: 22rpx; left: 0; width: 6rpx; content: ''; background: #79b7ad; border-radius: 0 6rpx 6rpx 0; }
.todo-main { min-width: 0; flex: 1; }
.todo-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 12rpx; }
.todo-title,.todo-copy,.todo-meta { display: block; }
.todo-title { min-width: 0; flex: 1; color: #263633; font-size: 28rpx; font-weight: 800; line-height: 1.45; }
.todo-copy { margin-top: 8rpx; color: #6e817d; font-size: 23rpx; line-height: 1.55; }
.todo-foot { display: flex; margin-top: 12rpx; justify-content: space-between; gap: 12rpx; }
.todo-meta { color: #849490; font-size: 21rpx; }
.priority { flex: 0 0 auto; padding: 6rpx 10rpx; color: #27655f; background: #e4f3f0; border-radius: 8rpx; font-size: 21rpx; font-weight: 750; }
.todo-actions { display: flex; gap: 12rpx; margin-top: 16rpx; }
.todo-actions button { min-height: 64rpx; margin: 0; padding: 0 20rpx; color: #27655f; background: #e4f3f0; border-radius: 10rpx; font-size: 23rpx; font-weight: 700; line-height: 64rpx; }
</style>
