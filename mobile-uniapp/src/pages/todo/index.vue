<script setup lang="ts">
import { ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileRoleTodos } from '@/api/business'
import { useSessionStore } from '@/stores'
import type { RoleTodoItem } from '@/types/business'

const session = useSessionStore()
const todos = ref<RoleTodoItem[]>([])
const loading = ref(false)
const error = ref('')

onShow(() => { void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (loading.value) return
  if (!session.user && !await session.restore()) { uni.reLaunch({ url: '/pages/login/index' }); return }
  loading.value = true; error.value = ''
  try { todos.value = (await getMobileRoleTodos(session.user?.role || '')).items || [] }
  catch (cause) { error.value = Number((cause as { status?: number })?.status) === 403 ? '当前账号暂无待办查看权限。' : '待办暂时无法加载，请检查网络后重试。' }
  finally { loading.value = false }
}

function open(todo: RoleTodoItem) {
  const path = todo.title.includes('巡检') ? '/pkg-inspection/rectification/index' : '/pages/apps/index'
  uni.navigateTo({ url: path })
}
</script>

<template>
  <view class="page"><view class="head"><view><text class="eyebrow">我的任务</text><text class="title">待办</text></view><button :loading="loading" :disabled="loading" @click="refresh">刷新</button></view>
    <view v-if="error" class="message">{{ error }}</view><view v-else-if="loading" class="state">正在读取待办…</view><view v-else-if="!todos.length" class="state">当前没有待处理事项</view>
    <button v-for="todo in todos" :key="todo.id" class="todo" @click="open(todo)"><view><text class="todo-title">{{ todo.title }}</text><text class="todo-copy">{{ todo.summary || '请进入对应应用处理' }}</text><text class="todo-meta">{{ todo.storeName || todo.storeId || '权限范围内事项' }}</text></view><text class="priority">P{{ todo.priority }}</text></button>
  </view>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; box-sizing: border-box; padding: 28rpx 24rpx calc(48rpx + env(safe-area-inset-bottom)); background: #f4f6f2; color: #172019; }.head { display:flex; align-items:center; justify-content:space-between; margin-bottom:30rpx; }.eyebrow,.title,.todo-title,.todo-copy,.todo-meta { display:block; }.eyebrow{color:#657168;font-size:23rpx;letter-spacing:2rpx}.title{margin-top:6rpx;font-size:46rpx;font-weight:750}.head button{min-width:128rpx;min-height:88rpx;margin:0;background:#fff;border:1rpx solid #cbd6cd;border-radius:18rpx;color:#264c36;font-size:26rpx}.head button::after,.todo::after{border:0}.state,.message{padding:44rpx 24rpx;text-align:center;background:#fff;border-radius:22rpx;color:#657168}.message{color:#963b30;background:#fff0ed}.todo{display:flex;min-height:112rpx;margin-top:16rpx;padding:22rpx 24rpx;align-items:flex-start;justify-content:space-between;text-align:left;background:#fff;border:1rpx solid #dce2db;border-radius:20rpx}.todo-title{font-size:28rpx;font-weight:700}.todo-copy{margin-top:6rpx;color:#526057;font-size:23rpx;line-height:1.5}.todo-meta{margin-top:8rpx;color:#7b877f;font-size:21rpx}.priority{padding:6rpx 10rpx;color:#9a621a;background:#fff0d8;border-radius:10rpx;font-size:22rpx}
</style>
