<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
import { getMobileRoleTodos } from '@/api/business'
import StatusChip from '@/components/StatusChip.vue'
import StatePanel from '@/components/StatePanel.vue'
import StatusTimeline, { type TimelineItem } from '@/components/StatusTimeline.vue'
import { useSessionStore } from '@/stores'
import type { RoleTodoItem } from '@/types/business'
import { routeForTodo } from '@/utils/todoRoute'

const session = useSessionStore()
const todoId = ref('')
const item = ref<RoleTodoItem | null>(null)
const loading = ref(false)
const error = ref('')

const statusLabel = computed(() => ({
  PENDING: '待处理', IN_PROGRESS: '处理中', PENDING_REVIEW: '待复核',
  COMPLETED: '已完成', REJECTED: '已驳回', RISK: '风险待处理',
}[item.value?.status || ''] || item.value?.status || '未知状态'))
const timeline = computed<TimelineItem[]>(() => item.value ? [
  { label: '业务事项已产生', time: item.value.updatedAt, done: true },
  { label: item.value.processStatus || statusLabel.value, time: item.value.dueAt, done: ['COMPLETED','REJECTED'].includes(item.value.status) },
] : [])

onLoad((query) => {
  todoId.value = decodeURIComponent(String(query?.id || ''))
  void load()
})
onPullDownRefresh(async () => { await load(); uni.stopPullDownRefresh() })

async function load() {
  if (!todoId.value || loading.value) return
  if (!session.user && !await session.restore()) { uni.reLaunch({ url: '/pages/login/index' }); return }
  loading.value = true
  error.value = ''
  try {
    const response = await getMobileRoleTodos(session.user?.role || '', true)
    item.value = response.items.find((todo) => todo.id === todoId.value) || null
    if (!item.value) error.value = '该待办已处理、已撤回或不在当前账号的数据范围内。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '待办详情加载失败，请稍后重试。' }
  finally { loading.value = false }
}

function openBusiness() {
  if (!item.value) return
  uni.navigateTo({ url: routeForTodo(item.value, session.user?.role || '') })
}
</script>

<template>
  <view class="page">
    <StatePanel v-if="loading && !item" type="loading" title="正在读取待办详情" />
    <StatePanel v-else-if="error" type="error" title="待办不可用" :description="error" action-text="重新加载" @action="load" />
    <template v-else-if="item">
      <view class="hero">
        <view class="hero-top"><text class="eyebrow">{{ item.sourceModule || '业务待办' }}</text><StatusChip :label="statusLabel" tone="warning" /></view>
        <text class="title">{{ item.title }}</text>
        <text class="summary">{{ item.summary }}</text>
      </view>
      <view class="detail">
        <view class="row"><text>处理状态</text><text>{{ item.processStatus || statusLabel }}</text></view>
        <view class="row"><text>所属门店</text><text>{{ item.storeName || item.storeId || '权限范围内事项' }}</text></view>
        <view class="row"><text>截止时间</text><text>{{ item.dueAt || '未设置' }}</text></view>
        <view class="row"><text>业务编号</text><text>{{ item.sourceRecordId || item.id }}</text></view>
        <view class="row"><text>更新时间</text><text>{{ item.updatedAt || '—' }}</text></view>
      </view>
      <view class="detail"><StatusTimeline :items="timeline" /></view>
      <button class="primary" @click="openBusiness">{{ item.action?.label || '进入业务中心处理' }}</button>
      <text class="hint">待办仅负责提醒和跳转，审批、收货、发货、整改等操作在对应业务中心完成。</text>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:24rpx;background:#f2f6f5;color:#202124}.hero,.detail{padding:28rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.hero-top,.row{display:flex;align-items:flex-start;justify-content:space-between;gap:20rpx}.eyebrow,.title,.summary,.hint{display:block}.eyebrow{color:#71807d;font-size:23rpx}.title{margin-top:18rpx;font-size:38rpx;font-weight:750;line-height:1.4}.summary{margin-top:14rpx;color:#59606b;font-size:26rpx;line-height:1.65}.detail{margin-top:18rpx}.row{padding:18rpx 0;border-bottom:1rpx solid #eceef1;font-size:25rpx}.row:last-child{border-bottom:0}.row text:first-child{color:#71807d}.row text:last-child{max-width:65%;text-align:right}.primary{margin-top:24rpx;min-height:92rpx;background:#27655f;color:#fff;border-radius:16rpx}.hint{padding:18rpx 8rpx;color:#71807d;font-size:23rpx;line-height:1.6}
</style>
