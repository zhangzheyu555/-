<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileRoleTodos } from '@/api/business'
import PageHeader from '@/components/PageHeader.vue'
import StatePanel from '@/components/StatePanel.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useSessionStore } from '@/stores'
import type { RoleTodoItem } from '@/types/business'

const session = useSessionStore()
const items = ref<RoleTodoItem[]>([])
const loading = ref(false)
const error = ref('')
const activeItems = computed(() => items.value.filter((item) => !['COMPLETED', 'REJECTED'].includes(item.status)))
const resultItems = computed(() => items.value.filter((item) => ['COMPLETED', 'REJECTED'].includes(item.status)))

onShow(() => { void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (loading.value) return
  if (!session.user && !await session.restore()) { uni.reLaunch({ url: '/pages/login/index' }); return }
  loading.value = true
  error.value = ''
  try { items.value = (await getMobileRoleTodos(session.user?.role || '', true)).items || [] }
  catch (cause) {
    error.value = Number((cause as { status?: number })?.status) === 403
      ? '当前账号暂无业务消息查看权限。'
      : '业务消息暂时无法加载，请检查网络后重试。'
  } finally { loading.value = false }
}

function open(item: RoleTodoItem) {
  uni.navigateTo({ url: `/pages/todo-detail/index?id=${encodeURIComponent(item.id)}` })
}

function statusLabel(status: string) {
  const labels: Record<string, string> = { PENDING: '待处理', IN_PROGRESS: '处理中', PENDING_REVIEW: '待复核', COMPLETED: '已完成', REJECTED: '已驳回' }
  return labels[status] || status
}

function statusTone(status: string): 'warning' | 'info' | 'success' | 'danger' | 'neutral' {
  const tones: Record<string, 'warning' | 'info' | 'success' | 'danger' | 'neutral'> = {
    PENDING: 'danger', IN_PROGRESS: 'info', PENDING_REVIEW: 'warning', COMPLETED: 'success', REJECTED: 'neutral',
  }
  return tones[status] || 'neutral'
}
</script>

<template>
  <view class="mobile-page message-page">
    <PageHeader eyebrow="服务端实时同步" title="业务消息" description="待办分派、复核结果和业务状态变化会汇总在这里">
      <template #action><button class="mobile-ghost-button refresh-button" :loading="loading" :disabled="loading" @click="refresh">刷新</button></template>
    </PageHeader>
    <StatePanel v-if="loading && !items.length" type="loading" title="正在读取业务消息" />
    <StatePanel v-else-if="error" type="error" title="消息加载失败" :description="error" action-text="重新加载" @action="refresh" />
    <StatePanel v-else-if="!items.length" type="empty" title="暂无业务消息" description="新的待办分派和处理结果会显示在这里。" />
    <template v-else>
      <view class="section">
        <view class="section-head"><text>待处理提醒</text><text>{{ activeItems.length }} 项</text></view>
        <button v-for="item in activeItems" :key="item.id" class="message-card mobile-feedback" @click="open(item)">
          <view class="message-card__top"><text class="message-card__title">{{ item.title }}</text><StatusChip :label="statusLabel(item.status)" :tone="statusTone(item.status)" /></view>
          <text class="message-card__copy">{{ item.summary || '请进入对应业务中心处理' }}</text>
          <text class="message-card__meta">{{ item.storeName || '权限范围内事项' }}<template v-if="item.updatedAt"> · {{ item.updatedAt }}</template></text>
        </button>
        <view v-if="!activeItems.length" class="empty-line">当前没有待处理提醒</view>
      </view>
      <view v-if="resultItems.length" class="section">
        <view class="section-head"><text>最近处理结果</text><text>{{ resultItems.length }} 项</text></view>
        <button v-for="item in resultItems.slice(0, 20)" :key="item.id" class="message-card result-card mobile-feedback" @click="open(item)">
          <view class="message-card__top"><text class="message-card__title">{{ item.title }}</text><StatusChip :label="statusLabel(item.status)" :tone="statusTone(item.status)" /></view>
          <text class="message-card__meta">{{ item.storeName || '权限范围内事项' }}<template v-if="item.updatedAt"> · {{ item.updatedAt }}</template></text>
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.message-page { display: flex; flex-direction: column; gap: 24rpx; }
.refresh-button { min-width: 116rpx; padding: 0 20rpx; }
.section { display: flex; flex-direction: column; gap: 14rpx; }
.section-head { display: flex; padding: 0 2rpx; align-items: center; justify-content: space-between; }
.section-head text:first-child { color: $mobile-ink; font-size: 29rpx; font-weight: 800; }
.section-head text:last-child { color: $mobile-muted; font-size: 22rpx; }
.message-card { display: flex; width: 100%; min-height: 126rpx; margin: 0; padding: 22rpx 24rpx; flex-direction: column; align-items: stretch; text-align: left; background: #fff; border: 1rpx solid $mobile-line; border-radius: 16rpx; box-shadow: 0 8rpx 22rpx rgba(37,39,45,.04); }
.message-card::after { border: 0; }
.message-card__top { display: flex; align-items: flex-start; justify-content: space-between; gap: 16rpx; }
.message-card__title { min-width: 0; flex: 1; color: $mobile-ink; font-size: 27rpx; font-weight: 800; line-height: 1.45; }
.message-card__copy { display: block; margin-top: 8rpx; color: #59606b; font-size: 23rpx; line-height: 1.55; }
.message-card__meta { display: block; margin-top: 10rpx; color: $mobile-muted; font-size: 21rpx; }
.result-card { min-height: 104rpx; background: #fafbfc; box-shadow: none; }
.empty-line { padding: 34rpx 24rpx; color: $mobile-muted; text-align: center; background: #fff; border-radius: 16rpx; }
</style>
