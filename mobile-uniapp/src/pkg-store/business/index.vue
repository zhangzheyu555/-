<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileFinanceMonths, getMobileProfitEntries, getMobileStoreManagerWorkbench, getMobileStores } from '@/api/business'
import { useSessionStore } from '@/stores'
import type { ProfitEntry, StoreInfo, StoreManagerWorkbench } from '@/types/business'

const session = useSessionStore()
const workbench = ref<StoreManagerWorkbench | null>(null)
const loading = ref(false)
const message = ref('')
const months = ref<string[]>([])
const selectedMonth = ref('')
const entries = ref<ProfitEntry[]>([])
const storeInfo = ref<StoreInfo | null>(null)
const canRead = computed(() => session.user?.role === 'STORE_MANAGER' && session.hasPermission('finance.profit.read'))

onShow(() => { void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (!canRead.value || loading.value) return
  loading.value = true
  message.value = ''
  try {
    workbench.value = await getMobileStoreManagerWorkbench()
    storeInfo.value = (await getMobileStores()).find((store) => store.id === workbench.value?.store.storeId) || null
    months.value = await getMobileFinanceMonths()
    selectedMonth.value = selectedMonth.value || workbench.value.businessReminder.month || months.value[0] || ''
    entries.value = await getMobileProfitEntries({ storeId: workbench.value.store.storeId })
  }
  catch (cause) { message.value = friendlyError(cause) }
  finally { loading.value = false }
}

function money(value: unknown) { const amount = Number(value || 0); return Math.abs(amount) >= 10000 ? `¥${(amount / 10000).toFixed(1)}万` : `¥${amount.toFixed(2)}` }
function percent(value: unknown) { return `${Number(value || 0).toFixed(1)}%` }
function openTodos() { uni.switchTab({ url: '/pages/todo/index' }) }
function openExpense() { uni.navigateTo({ url: '/pkg-finance/expenses/index' }) }
function openRectification() { uni.navigateTo({ url: '/pkg-inspection/rectification/index' }) }
function chooseMonth(event: { detail: { value: number | string } }) { selectedMonth.value = months.value[Number(event.detail.value)] || selectedMonth.value }
const visibleEntries = computed(() => entries.value.filter((entry) => !selectedMonth.value || entry.month === selectedMonth.value))
function friendlyError(cause: unknown) { const status = Number((cause as { status?: number })?.status || 0); return status === 403 ? '当前账号无权查看本店经营结果。' : status === 401 ? '登录已过期，请重新登录。' : '本店经营详情暂时无法加载。' }
</script>

<template>
  <view class="page">
    <view class="head"><view><text class="eyebrow">店长只读</text><text class="title">本店经营</text></view><button :loading="loading" :disabled="loading || !canRead" @click="refresh">刷新</button></view>
    <view v-if="!canRead" class="state">本页只向具备经营只读权限的店长开放。</view>
    <view v-if="message" class="notice">{{ message }}</view>
    <template v-if="workbench">
      <view class="store"><text class="store-name">{{ workbench.store.storeName }}</text><text class="copy">{{ workbench.businessReminder.month }} · 数据由正式后端计算</text></view>
      <view class="section"><text class="section-title">门店资料</text><view class="profile"><text>门店编号：{{ storeInfo?.code || workbench.store.storeId }}</text><text>所属品牌：{{ storeInfo?.brandName || '待补充' }}</text><text>负责人：{{ storeInfo?.manager || '待补充' }}</text><text>门店状态：{{ storeInfo?.status || '待确认' }}</text></view></view>
      <view v-if="months.length" class="month-picker"><text>查看月份</text><picker :range="months" :value="Math.max(0, months.indexOf(selectedMonth))" @change="chooseMonth"><view>{{ selectedMonth || '选择月份' }} ›</view></picker></view>
      <view class="hero"><text class="hero-label">本期净利润</text><text :class="['hero-value',{negative:workbench.businessReminder.net<0}]">{{ money(workbench.businessReminder.net) }}</text><text class="hero-copy">营业额 {{ money(workbench.businessReminder.income) }} · 利润率 {{ percent(workbench.businessReminder.margin) }}</text></view>
      <view class="metrics"><view><text class="metric">{{ percent(workbench.businessReminder.costRatio) }}</text><text class="label">成本占比</text></view><view><text class="metric">{{ percent(workbench.businessReminder.incomeChangeRate) }}</text><text class="label">营收环比</text></view><view><text class="metric">{{ workbench.todayFocus.pendingCount }}</text><text class="label">待处理事项</text></view><view><text class="metric">{{ workbench.todayFocus.pendingReceiptCount }}</text><text class="label">待确认收货</text></view></view>
      <view v-if="workbench.businessReminder.reminders.length" class="section warning"><text class="section-title">经营提醒</text><text v-for="reminder in workbench.businessReminder.reminders" :key="reminder" class="reminder">{{ reminder }}</text></view>
      <view class="section"><view class="section-head"><text class="section-title">需要我处理</text><button @click="openTodos">全部待办</button></view><text v-if="!workbench.needMyAction.length" class="copy empty">当前没有待处理事项</text><view v-for="item in workbench.needMyAction.slice(0,5)" :key="item.id" class="task" @click="openTodos"><view><text class="task-title">{{ item.title }}</text><text class="copy">{{ item.summary || item.nextActionLabel || '进入待办处理' }}</text></view><text class="priority">P{{ item.priority }}</text></view></view>
      <view class="section"><view class="section-head"><text class="section-title">{{ selectedMonth || '本月' }}经营明细</text><text class="copy">只读</text></view><view v-if="!visibleEntries.length" class="copy empty">该月份暂无经营数据。</view><view v-for="entry in visibleEntries" :key="`${entry.storeId}-${entry.month}`" class="detail-row"><text>实收收入 {{ money(entry.income) }} · 成本 {{ money(entry.costSum) }}</text><text :class="{negative: entry.net < 0}">净利润 {{ money(entry.net) }} · {{ percent(entry.margin) }}</text></view></view>
      <view class="section"><text class="section-title">最新巡检</text><view v-if="!workbench.records?.inspections?.length" class="copy empty">暂无可查看的巡检记录。</view><view v-for="record in workbench.records?.inspections?.slice(0, 1)" :key="record.id" class="detail-row"><text>{{ record.inspectionDate || '巡检日期待确认' }} · {{ record.inspector || '巡检人员' }}</text><text>得分 {{ record.displayScore ?? record.score ?? '—' }} · {{ record.displayResultCode || '待处理' }}</text></view></view>
      <view class="section shortcuts"><button @click="openExpense">提交/补充报销</button><button @click="openRectification">处理巡检整改</button></view>
      <view class="desktop-note">经营数据录入、月度导入和删除只能在 PC 管理端完成；本页只展示后端计算结果。</view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:24rpx;background:#f2f6f5;color:#202124}.head,.section-head,.task{display:flex;align-items:center;justify-content:space-between;gap:16rpx}.eyebrow,.title,.store-name,.copy,.hero-label,.hero-value,.hero-copy,.metric,.label,.section-title,.reminder,.task-title{display:block}.eyebrow,.copy,.label{color:#71807d;font-size:23rpx}.title{margin-top:6rpx;font-size:38rpx;font-weight:750}.head button,.section-head button{margin:0;background:#fff;color:#1f5752}.store,.hero,.section,.state,.notice,.desktop-note,.month-picker{margin-top:16rpx;padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.month-picker{display:flex;align-items:center;justify-content:space-between;color:#71807d;font-size:24rpx}.month-picker picker{color:#1f5752;font-weight:700}.store-name,.section-title{font-size:29rpx;font-weight:700}.profile{display:grid;grid-template-columns:repeat(2,1fr);gap:14rpx;margin-top:16rpx}.profile text{color:#59606b;font-size:23rpx;line-height:1.6}.hero{background:#27655f;color:#fff}.hero-label,.hero-copy{color:#d9eeea;font-size:24rpx}.hero-value{margin-top:10rpx;font-size:52rpx;font-weight:780}.hero-value.negative,.detail-row .negative{color:#b84c40}.hero-copy{margin-top:8rpx}.metrics{display:grid;grid-template-columns:repeat(2,1fr);gap:12rpx;margin-top:16rpx}.metrics view{padding:22rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:14rpx}.metric{font-size:32rpx;font-weight:740}.warning{background:#fff9e8;border-color:#e7d5a6}.reminder{margin-top:12rpx;color:#755d2d;font-size:24rpx;line-height:1.6}.task,.detail-row{margin-top:14rpx;padding-top:14rpx;border-top:1rpx solid #edf0f3}.detail-row text{display:block;font-size:24rpx;line-height:1.7}.task>view{min-width:0;flex:1}.task-title{font-size:27rpx;font-weight:680}.priority{padding:7rpx 10rpx;background:#e6f3f1;color:#1f5752;border-radius:10rpx;font-size:22rpx}.empty{text-align:center}.shortcuts{display:flex;gap:14rpx}.shortcuts button{flex:1;margin:0;background:#e6f3f1;color:#1f5752;font-size:24rpx}.desktop-note{color:#755d2d;background:#fff9e8;font-size:24rpx;line-height:1.6}.state,.notice{text-align:center;color:#71807d}
</style>
