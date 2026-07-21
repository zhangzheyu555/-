<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileProfitDashboard } from '@/api/business'
import { useContextStore, useSessionStore } from '@/stores'
import type { ProfitDashboard, ProfitEntry } from '@/types/business'

const session = useSessionStore()
const context = useContextStore()
const dashboard = ref<ProfitDashboard | null>(null)
const selectedMonth = ref('')
const selectedStoreId = ref('')
const selectedEntryId = ref<number | null>(null)
const loading = ref(false)
const message = ref('')
const canRead = computed(() => session.hasPermission('finance.profit.read'))
const storeOptions = computed(() => [{ id: '', name: '全部授权门店' }, ...context.stores])
const storeIndex = computed(() => Math.max(0, storeOptions.value.findIndex(store => store.id === selectedStoreId.value)))
const monthOptions = computed(() => dashboard.value?.months || (selectedMonth.value ? [selectedMonth.value] : []))
const monthIndex = computed(() => Math.max(0, monthOptions.value.indexOf(selectedMonth.value)))
const entries = computed(() => dashboard.value?.entries || [])
const trendMax = computed(() => Math.max(1, ...(dashboard.value?.trend || []).flatMap(point => [Math.abs(Number(point.income || point.sales || 0)), Math.abs(Number(point.net || 0))])))

onLoad(query => { selectedStoreId.value = String(query?.storeId || ''); selectedMonth.value = String(query?.month || '') })
onShow(async () => { if (!context.stores.length && session.user) await context.load(session.user); await refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (!canRead.value || loading.value) return
  loading.value = true
  message.value = ''
  try {
    dashboard.value = await getMobileProfitDashboard(selectedMonth.value || undefined, selectedStoreId.value || undefined)
    if (!selectedMonth.value) selectedMonth.value = dashboard.value.summary.month
    if (selectedEntryId.value && !entries.value.some(entry => entry.id === selectedEntryId.value)) selectedEntryId.value = null
  } catch (cause) { message.value = friendlyError(cause) }
  finally { loading.value = false }
}

async function chooseMonth(event: { detail: { value: string | number } }) { selectedMonth.value = monthOptions.value[Number(event.detail.value)] || ''; await refresh() }
async function chooseStore(event: { detail: { value: string | number } }) { selectedStoreId.value = storeOptions.value[Number(event.detail.value)]?.id || ''; selectedEntryId.value = null; await refresh() }
function toggleEntry(entry: ProfitEntry) { selectedEntryId.value = selectedEntryId.value === entry.id ? null : entry.id }
function money(value: unknown) { const amount = Number(value || 0); return Math.abs(amount) >= 10000 ? `¥${(amount / 10000).toFixed(1)}万` : `¥${amount.toFixed(2)}` }
function percent(value: unknown) { return `${Number(value || 0).toFixed(1)}%` }
function barWidth(value: unknown) { return `${Math.max(2, Math.round(Math.abs(Number(value || 0)) / trendMax.value * 100))}%` }
function friendlyError(cause: unknown) { const status = Number((cause as { status?: number })?.status || 0); return status === 403 ? '当前账号无权查看该门店经营数据。' : status === 401 ? '登录已过期，请重新登录。' : '利润详情暂时无法加载。' }
</script>

<template>
  <view class="page">
    <view class="head"><view><text class="eyebrow">经营财务</text><text class="title">利润详情</text></view><button :loading="loading" :disabled="loading || !canRead" @click="refresh">刷新</button></view>
    <view class="filters"><picker :range="monthOptions" :value="monthIndex" @change="chooseMonth"><view class="picker">{{ selectedMonth || '选择月份' }}</view></picker><picker :range="storeOptions" range-key="name" :value="storeIndex" @change="chooseStore"><view class="picker">{{ storeOptions[storeIndex]?.name }}</view></picker></view>
    <view v-if="message" class="notice">{{ message }}</view>
    <template v-if="dashboard">
      <view class="hero"><text class="hero-label">本期净利润</text><text :class="['hero-value',{negative:dashboard.summary.net<0}]">{{ money(dashboard.summary.net) }}</text><text class="hero-copy">营业收入 {{ money(dashboard.summary.income) }} · 利润率 {{ percent(dashboard.summary.margin) }}</text></view>
      <view class="metrics"><view><text class="metric">{{ money(dashboard.summary.sales) }}</text><text class="label">营业额</text></view><view><text class="metric">{{ money(dashboard.summary.costSum) }}</text><text class="label">营业成本</text></view><view><text class="metric">{{ money(dashboard.summary.expenseSum) }}</text><text class="label">期间费用</text></view><view><text class="metric">{{ dashboard.summary.riskStoreCount }}</text><text class="label">风险门店</text></view></view>
      <text class="section-title">经营趋势</text><view class="trend"><view v-for="point in dashboard.trend" :key="point.month" class="trend-row"><text class="trend-month">{{ point.month }}</text><view class="bars"><view class="bar income" :style="{width:barWidth(point.income||point.sales)}"></view><view :class="['bar','net',{negative:point.net<0}]" :style="{width:barWidth(point.net)}"></view></view><view class="trend-values"><text>{{ money(point.income||point.sales) }}</text><text>{{ money(point.net) }}</text></view></view></view>
      <text class="section-title">门店利润</text><view v-if="!entries.length" class="state">当前范围暂无经营数据</view><view v-for="entry in entries" :key="entry.id" class="entry"><view class="entry-head" @click="toggleEntry(entry)"><view><text class="entry-name">{{ entry.storeName }}</text><text class="copy">收入 {{ money(entry.income) }} · 成本率 {{ percent(entry.costRatio) }}</text></view><view class="entry-result"><text :class="{negative:entry.net<0}">{{ money(entry.net) }}</text><text class="copy">{{ percent(entry.margin) }}</text></view></view><view v-if="selectedEntryId===entry.id" class="entry-detail"><view><text>材料</text><text>{{ money(entry.material) }}</text></view><view><text>包装</text><text>{{ money(entry.packaging) }}</text></view><view><text>损耗</text><text>{{ money(entry.loss) }}</text></view><view><text>人工</text><text>{{ money(entry.labor) }}</text></view><view><text>租金</text><text>{{ money(entry.rent) }}</text></view><view><text>水电</text><text>{{ money(entry.utility) }}</text></view><view><text>平台佣金</text><text>{{ money(entry.commission) }}</text></view><view><text>推广</text><text>{{ money(entry.promo) }}</text></view><text v-if="entry.risk" class="risk">风险：{{ entry.risk }}</text><text v-if="entry.note" class="note">备注：{{ entry.note }}</text></view></view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:24rpx;background:#f2f6f5;color:#202124}.head,.entry-head{display:flex;align-items:center;justify-content:space-between;gap:16rpx}.eyebrow,.title,.hero-label,.hero-value,.hero-copy,.metric,.label,.section-title,.trend-month,.entry-name,.copy,.risk,.note{display:block}.eyebrow,.copy,.label{color:#71807d;font-size:23rpx}.title{margin-top:6rpx;font-size:38rpx;font-weight:750}.head button{margin:0;background:#fff;color:#1f5752}.filters{display:grid;grid-template-columns:1fr 1fr;gap:12rpx;margin-top:18rpx}.picker{padding:18rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:14rpx;text-align:center;font-size:25rpx}.hero,.entry,.trend,.notice,.state{margin-top:16rpx;padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.hero{background:#27655f;color:#fff}.hero-label,.hero-copy{color:#d9eeea;font-size:24rpx}.hero-value{margin-top:10rpx;font-size:52rpx;font-weight:780}.negative{color:#c94f43!important}.hero .negative{color:#ffd2ca!important}.hero-copy{margin-top:8rpx}.metrics{display:grid;grid-template-columns:repeat(2,1fr);gap:12rpx;margin-top:16rpx}.metrics view{padding:20rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:14rpx}.metric{font-size:30rpx;font-weight:730}.section-title{margin:28rpx 2rpx 12rpx;font-size:29rpx;font-weight:720}.trend-row{display:grid;grid-template-columns:108rpx minmax(0,1fr) 150rpx;gap:12rpx;align-items:center;margin-top:15rpx}.trend-month{font-size:23rpx}.bars{display:flex;flex-direction:column;gap:7rpx}.bar{height:12rpx;border-radius:6rpx}.bar.income{background:#27655f}.bar.net{background:#287a48}.bar.net.negative{background:#c94f43}.trend-values{text-align:right;font-size:20rpx;color:#71807d}.trend-values text{display:block}.entry-name{font-size:28rpx;font-weight:700}.entry-result{text-align:right;font-size:27rpx;font-weight:700}.entry-detail{display:grid;grid-template-columns:repeat(2,1fr);gap:10rpx;margin-top:16rpx;padding-top:16rpx;border-top:1rpx solid #edf0f3}.entry-detail view{display:flex;justify-content:space-between;gap:8rpx;color:#4f5661;font-size:23rpx}.risk,.note{grid-column:1/-1;padding:12rpx;background:#fff9e8;color:#755d2d;font-size:23rpx}.notice,.state{text-align:center;color:#71807d}
</style>
