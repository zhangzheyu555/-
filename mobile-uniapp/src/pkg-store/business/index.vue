<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileStoreManagerWorkbench } from '@/api/business'
import { useSessionStore } from '@/stores'
import type { StoreManagerWorkbench } from '@/types/business'

const session = useSessionStore()
const workbench = ref<StoreManagerWorkbench | null>(null)
const loading = ref(false)
const message = ref('')
const canRead = computed(() => session.user?.role === 'STORE_MANAGER' && session.hasPermission('finance.profit.read'))

onShow(() => { void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (!canRead.value || loading.value) return
  loading.value = true
  message.value = ''
  try { workbench.value = await getMobileStoreManagerWorkbench() }
  catch (cause) { message.value = friendlyError(cause) }
  finally { loading.value = false }
}

function money(value: unknown) { const amount = Number(value || 0); return Math.abs(amount) >= 10000 ? `¥${(amount / 10000).toFixed(1)}万` : `¥${amount.toFixed(2)}` }
function percent(value: unknown) { return `${Number(value || 0).toFixed(1)}%` }
function openTodos() { uni.switchTab({ url: '/pages/todo/index' }) }
function friendlyError(cause: unknown) { const status = Number((cause as { status?: number })?.status || 0); return status === 403 ? '当前账号无权查看本店经营结果。' : status === 401 ? '登录已过期，请重新登录。' : '本店经营详情暂时无法加载。' }
</script>

<template>
  <view class="page">
    <view class="head"><view><text class="eyebrow">店长只读</text><text class="title">本店经营</text></view><button :loading="loading" :disabled="loading || !canRead" @click="refresh">刷新</button></view>
    <view v-if="!canRead" class="state">本页只向具备经营只读权限的店长开放。</view>
    <view v-if="message" class="notice">{{ message }}</view>
    <template v-if="workbench">
      <view class="store"><text class="store-name">{{ workbench.store.storeName }}</text><text class="copy">{{ workbench.businessReminder.month }} · 数据由正式后端计算</text></view>
      <view class="hero"><text class="hero-label">本期净利润</text><text :class="['hero-value',{negative:workbench.businessReminder.net<0}]">{{ money(workbench.businessReminder.net) }}</text><text class="hero-copy">营业额 {{ money(workbench.businessReminder.income) }} · 利润率 {{ percent(workbench.businessReminder.margin) }}</text></view>
      <view class="metrics"><view><text class="metric">{{ percent(workbench.businessReminder.costRatio) }}</text><text class="label">成本占比</text></view><view><text class="metric">{{ percent(workbench.businessReminder.incomeChangeRate) }}</text><text class="label">营收环比</text></view><view><text class="metric">{{ workbench.todayFocus.pendingCount }}</text><text class="label">待处理事项</text></view><view><text class="metric">{{ workbench.todayFocus.pendingReceiptCount }}</text><text class="label">待确认收货</text></view></view>
      <view v-if="workbench.businessReminder.reminders.length" class="section warning"><text class="section-title">经营提醒</text><text v-for="reminder in workbench.businessReminder.reminders" :key="reminder" class="reminder">{{ reminder }}</text></view>
      <view class="section"><view class="section-head"><text class="section-title">需要我处理</text><button @click="openTodos">全部待办</button></view><text v-if="!workbench.needMyAction.length" class="copy empty">当前没有待处理事项</text><view v-for="item in workbench.needMyAction.slice(0,5)" :key="item.id" class="task" @click="openTodos"><view><text class="task-title">{{ item.title }}</text><text class="copy">{{ item.summary || item.nextActionLabel || '进入待办处理' }}</text></view><text class="priority">P{{ item.priority }}</text></view></view>
      <view class="desktop-note">经营数据录入、月度导入和删除只能在 PC 管理端完成；本页只展示后端计算结果。</view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:24rpx;background:#f2f6f5;color:#202124}.head,.section-head,.task{display:flex;align-items:center;justify-content:space-between;gap:16rpx}.eyebrow,.title,.store-name,.copy,.hero-label,.hero-value,.hero-copy,.metric,.label,.section-title,.reminder,.task-title{display:block}.eyebrow,.copy,.label{color:#71807d;font-size:23rpx}.title{margin-top:6rpx;font-size:38rpx;font-weight:750}.head button,.section-head button{margin:0;background:#fff;color:#1f5752}.store,.hero,.section,.state,.notice,.desktop-note{margin-top:16rpx;padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.store-name,.section-title{font-size:29rpx;font-weight:700}.hero{background:#27655f;color:#fff}.hero-label,.hero-copy{color:#d9eeea;font-size:24rpx}.hero-value{margin-top:10rpx;font-size:52rpx;font-weight:780}.hero-value.negative{color:#ffd2ca}.hero-copy{margin-top:8rpx}.metrics{display:grid;grid-template-columns:repeat(2,1fr);gap:12rpx;margin-top:16rpx}.metrics view{padding:22rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:14rpx}.metric{font-size:32rpx;font-weight:740}.warning{background:#fff9e8;border-color:#e7d5a6}.reminder{margin-top:12rpx;color:#755d2d;font-size:24rpx;line-height:1.6}.task{margin-top:14rpx;padding-top:14rpx;border-top:1rpx solid #edf0f3}.task>view{min-width:0;flex:1}.task-title{font-size:27rpx;font-weight:680}.priority{padding:7rpx 10rpx;background:#e6f3f1;color:#1f5752;border-radius:10rpx;font-size:22rpx}.empty{text-align:center}.desktop-note{color:#755d2d;background:#fff9e8;font-size:24rpx;line-height:1.6}.state,.notice{text-align:center;color:#71807d}
</style>
