<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileBossTodoDashboard, getMobileRoleTodos } from '@/api/business'
import PageHeader from '@/components/PageHeader.vue'
import StatePanel from '@/components/StatePanel.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useSessionStore } from '@/stores'
import { createEdgeSwipeToHomeHandlers } from '@/platform/edgeSwipeHome'
import type { RoleTodoItem } from '@/types/business'
import { navigateToTodo } from '@/utils/todoRoute'
import { isTodoResult, todoStatusLabel, todoStatusTone } from '@/utils/todoStatus'

const session = useSessionStore()
const { onTouchStart, onTouchEnd } = createEdgeSwipeToHomeHandlers()
const items = ref<RoleTodoItem[]>([])
const loading = ref(false)
const error = ref('')
const isBoss = computed(() => session.user?.role === 'BOSS')
const attentionItems = computed(() => items.value.filter((item) => !isTodoResult(item)))
const resultItems = computed(() => items.value
  .filter(isTodoResult)
  .sort((left, right) => String(right.occurredAt || '').localeCompare(String(left.occurredAt || ''))))

onShow(() => { void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (loading.value) return
  if (!session.user && !await session.restore()) { uni.reLaunch({ url: '/pages/login/index' }); return }
  loading.value = true
  error.value = ''
  try {
    const role = String(session.user?.role || '').toUpperCase()
    if (role === 'BOSS') {
      const dashboard = await getMobileBossTodoDashboard()
      items.value = dashboard.needsBossAction || []
    } else {
      items.value = (await getMobileRoleTodos(role, true)).items || []
    }
  }
  catch (cause) {
    error.value = Number((cause as { status?: number })?.status) === 403
      ? '当前账号暂无业务动态查看权限。'
      : '业务动态暂时无法加载，请检查网络后重试。'
  } finally { loading.value = false }
}

function open(item: RoleTodoItem) {
  navigateToTodo(item, session.user?.role || '')
}

function businessTime(item: RoleTodoItem): string {
  const value = String(item.occurredAt || '').trim()
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}
</script>

<template>
  <view class="mobile-page message-page" @touchstart="onTouchStart" @touchend="onTouchEnd">
    <PageHeader eyebrow="服务端实时同步" title="业务动态" :description="isBoss ? '只显示必须由老板确认、拍板或关闭的业务提醒' : '查看业务变化和处理结果，点击后直接进入对应业务页面'">
      <template #action><button class="mobile-ghost-button refresh-button" :loading="loading" :disabled="loading" @click="refresh">刷新</button></template>
    </PageHeader>
    <StatePanel v-if="loading && !items.length" type="loading" title="正在读取业务动态" />
    <StatePanel v-else-if="error" type="error" title="业务动态加载失败" :description="error" action-text="重新加载" @action="refresh" />
    <StatePanel v-else-if="!items.length" type="empty" title="暂无业务动态" :description="isBoss ? '当前没有需要老板处理的业务提醒。' : '新的业务提醒和处理结果会显示在这里。'" />
    <template v-else>
      <view class="summary-grid" :class="{ 'summary-grid--single': isBoss }">
        <view class="summary-card summary-card--attention"><text>{{ isBoss ? '需要老板处理' : '需要关注' }}</text><text class="summary-card__value">{{ attentionItems.length }}</text></view>
        <view v-if="!isBoss" class="summary-card"><text>处理结果</text><text class="summary-card__value">{{ resultItems.length }}</text></view>
      </view>
      <view class="scope-note">{{ isBoss ? '岗位日常事项由对应岗位处理，只有升级到老板的事项才会显示在这里。' : '这里用于查看业务变化；审批、收货、发货等操作仍在对应业务页面完成。' }}</view>
      <view class="section">
        <view class="section-head"><text>{{ isBoss ? '需要我处理' : '需要关注' }}</text><text>{{ attentionItems.length }} 项</text></view>
        <button v-for="item in attentionItems" :key="item.id" class="message-card mobile-feedback" @click="open(item)">
          <view class="message-card__top"><text class="message-card__title">{{ item.title }}</text><StatusChip :label="todoStatusLabel(item)" :tone="todoStatusTone(item)" /></view>
          <text class="message-card__copy">{{ item.summary || '请进入对应业务中心处理' }}</text>
          <view class="message-card__foot"><text class="message-card__meta">{{ item.sourceModule || '业务事项' }} · {{ item.storeName || item.storeId || '权限范围内' }}<template v-if="businessTime(item)"> · {{ businessTime(item) }}</template></text><text class="message-card__action">{{ item.action?.label || '进入业务页面' }} ›</text></view>
        </button>
        <view v-if="!attentionItems.length" class="empty-line">当前没有需要关注的业务动态</view>
      </view>
      <view v-if="!isBoss" class="section">
        <view class="section-head"><text>处理结果</text><text>{{ resultItems.length }} 项</text></view>
        <button v-for="item in resultItems.slice(0, 20)" :key="item.id" class="message-card result-card mobile-feedback" @click="open(item)">
          <view class="message-card__top"><text class="message-card__title">{{ item.title }}</text><StatusChip :label="todoStatusLabel(item)" :tone="todoStatusTone(item)" /></view>
          <text class="message-card__copy">{{ item.summary || item.processStatus || '业务事项已有处理结果' }}</text>
          <view class="message-card__foot"><text class="message-card__meta">{{ item.sourceModule || '业务事项' }} · {{ item.storeName || item.storeId || '权限范围内' }}<template v-if="businessTime(item)"> · {{ businessTime(item) }}</template></text><text class="message-card__action">查看业务 ›</text></view>
        </button>
        <view v-if="!resultItems.length" class="empty-line">当前没有可查看的处理结果</view>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.message-page { display: flex; flex-direction: column; gap: 24rpx; }
.refresh-button { min-width: 116rpx; padding: 0 20rpx; }
.summary-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14rpx; }
.summary-grid--single { grid-template-columns: minmax(0, 1fr); }
.summary-card { display: flex; min-height: 116rpx; padding: 20rpx 22rpx; flex-direction: column; justify-content: space-between; background: #fff; border: 1rpx solid $mobile-line; border-radius: 16rpx; }
.summary-card text { color: $mobile-muted; font-size: 22rpx; }
.summary-card__value { color: $mobile-ink; font-size: 38rpx; font-weight: 850; }
.summary-card--attention { background: $mobile-green; border-color: $mobile-green; }
.summary-card--attention text { color: rgba(255,255,255,.72); }
.summary-card--attention .summary-card__value { color: #fff; }
.scope-note { padding: 18rpx 20rpx; color: #657773; background: #eaf4f2; border-radius: 14rpx; font-size: 23rpx; line-height: 1.55; }
.section { display: flex; flex-direction: column; gap: 14rpx; }
.section-head { display: flex; padding: 0 2rpx; align-items: center; justify-content: space-between; }
.section-head text:first-child { color: $mobile-ink; font-size: 29rpx; font-weight: 800; }
.section-head text:last-child { color: $mobile-muted; font-size: 22rpx; }
.message-card { display: flex; width: 100%; min-height: 126rpx; margin: 0; padding: 22rpx 24rpx; flex-direction: column; align-items: stretch; text-align: left; background: #fff; border: 1rpx solid $mobile-line; border-radius: 16rpx; box-shadow: 0 8rpx 22rpx rgba(37,39,45,.04); }
.message-card::after { border: 0; }
.message-card__top { display: flex; align-items: flex-start; justify-content: space-between; gap: 16rpx; }
.message-card__title { min-width: 0; flex: 1; color: $mobile-ink; font-size: 27rpx; font-weight: 800; line-height: 1.45; }
.message-card__copy { display: block; margin-top: 8rpx; color: #59606b; font-size: 23rpx; line-height: 1.55; }
.message-card__foot { display: flex; margin-top: 12rpx; align-items: flex-end; justify-content: space-between; gap: 16rpx; }
.message-card__meta { min-width: 0; color: $mobile-muted; font-size: 22rpx; line-height: 1.45; }
.message-card__action { flex: 0 0 auto; color: $mobile-green; font-size: 22rpx; font-weight: 700; }
.result-card { min-height: 104rpx; background: #fafbfc; box-shadow: none; }
.empty-line { padding: 34rpx 24rpx; color: $mobile-muted; text-align: center; background: #fff; border-radius: 16rpx; }
</style>
