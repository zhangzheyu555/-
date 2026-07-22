<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { getMobileSalaryPage } from '@/api/business'
import { useContextStore, useSessionStore } from '@/stores'
import type { SalaryRecord } from '@/types/business'

const session = useSessionStore()
const context = useContextStore()
const rows = ref<SalaryRecord[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const message = ref('')
const month = ref(new Date().toISOString().slice(0, 7))
const selectedStoreId = ref('')
const selectedStatus = ref('')
const keyword = ref('')
const page = ref(1)
const totalPages = ref(0)

const canRead = computed(() => session.hasPermission('salary.read'))
const storeOptions = computed(() => [{ id: '', name: '全部门店' }, ...context.stores])
const storeIndex = computed(() => Math.max(0, storeOptions.value.findIndex(item => item.id === selectedStoreId.value)))
const statusOptions = [
  { value: '', label: '全部状态' }, { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '待审核' }, { value: 'APPROVED', label: '已审核' },
  { value: 'REJECTED', label: '已驳回' }, { value: 'PAID', label: '已发放' }, { value: 'LOCKED', label: '已锁定' },
]
const statusIndex = computed(() => Math.max(0, statusOptions.findIndex(item => item.value === selectedStatus.value)))
const hasMore = computed(() => page.value < totalPages.value)

onShow(async () => {
  if (!context.stores.length && session.user) await context.load(session.user)
  if (!selectedStoreId.value && context.currentStoreId) selectedStoreId.value = context.currentStoreId
  await refresh()
})
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })
onReachBottom(() => { void loadMore() })

async function refresh() {
  if (!canRead.value) { rows.value = []; message.value = '当前账号无权查看工资记录。'; return }
  page.value = 1
  await loadPage(false)
}

async function loadMore() {
  if (!canRead.value || loading.value || loadingMore.value || !hasMore.value) return
  page.value += 1
  await loadPage(true)
}

async function loadPage(append: boolean) {
  if (append) loadingMore.value = true
  else loading.value = true
  message.value = ''
  try {
    const result = await getMobileSalaryPage({
      month: month.value || undefined,
      storeId: selectedStoreId.value || undefined,
      status: selectedStatus.value || undefined,
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: 20,
    })
    rows.value = append ? [...rows.value, ...(result.content || result.rows || [])] : (result.content || result.rows || [])
    totalPages.value = result.totalPages || 0
  } catch (cause) {
    if (append) page.value = Math.max(1, page.value - 1)
    message.value = friendlyError(cause)
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function chooseMonth(event: { detail: { value: string } }) { month.value = String(event.detail.value || '').slice(0, 7); void refresh() }
function chooseStore(event: { detail: { value: string | number } }) { selectedStoreId.value = storeOptions.value[Number(event.detail.value)]?.id || ''; void refresh() }
function chooseStatus(event: { detail: { value: string | number } }) { selectedStatus.value = statusOptions[Number(event.detail.value)]?.value || ''; void refresh() }
function search() { void refresh() }
function openDetail(row: SalaryRecord) { uni.navigateTo({ url: `/pkg-finance/salary-detail/index?id=${encodeURIComponent(row.id)}` }) }
function amount(value: unknown) { return value === null || value === undefined ? '—' : `¥${Number(value).toFixed(2)}` }
function statusText(status?: string) { return ({ PENDING_GENERATION: '待生成', DRAFT: '草稿', SUBMITTED: '待审核', PENDING_REVIEW: '待审核', APPROVED: '已审核', REJECTED: '已驳回', PAID: '已发放', LOCKED: '已锁定' } as Record<string, string>)[String(status || '')] || status || '—' }
function friendlyError(cause: unknown) {
  const status = Number((cause as { status?: number })?.status || 0)
  if (status === 401) return '登录已过期，请重新登录。'
  if (status === 403) return '当前账号无权查看该范围的工资记录。'
  return cause instanceof Error ? cause.message : '工资记录暂时无法加载，请稍后重试。'
}
</script>

<template>
  <view class="page">
    <view class="head"><view><text class="eyebrow">经营财务</text><text class="title">工资查询</text></view><button @click="refresh">刷新</button></view>
    <view class="hint">小程序仅供查询，生成、编辑、审核、发放等操作请使用 PC 管理端。</view>
    <view class="filters">
      <picker mode="date" fields="month" :value="month" @change="chooseMonth"><view class="filter">月份：{{ month || '全部' }}</view></picker>
      <picker :range="storeOptions" range-key="name" :value="storeIndex" @change="chooseStore"><view class="filter">门店：{{ storeOptions[storeIndex]?.name }}</view></picker>
      <picker :range="statusOptions" range-key="label" :value="statusIndex" @change="chooseStatus"><view class="filter">状态：{{ statusOptions[statusIndex]?.label }}</view></picker>
      <view class="search"><input v-model="keyword" confirm-type="search" placeholder="搜索员工姓名或岗位" @confirm="search"/><button @click="search">查询</button></view>
    </view>
    <view v-if="message" class="notice">{{ message }}</view>
    <view v-if="!canRead" class="empty">工资记录仅对已授权账号开放。</view>
    <view v-else-if="!rows.length && !loading" class="empty">当前筛选条件下暂无工资记录</view>
    <view v-for="row in rows" :key="row.id" class="card" @click="openDetail(row)">
      <view class="row"><text class="name">{{ row.employeeName }}</text><text class="status">{{ statusText(row.status) }}</text></view>
      <text class="money">实发 {{ amount(row.netPay) }}</text>
      <text class="copy">{{ row.storeName || row.storeId }} · {{ row.month }} · {{ row.position || '员工' }}</text>
      <text class="detail">查看工资构成与审核信息 ›</text>
    </view>
    <view v-if="loading" class="state">正在加载…</view>
    <button v-else-if="hasMore" class="more" :loading="loadingMore" @click="loadMore">加载更多</button>
    <view v-else-if="rows.length" class="state">已加载全部记录</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:24rpx;background:#f2f6f5;color:#202124}.head,.row,.search{display:flex;align-items:center;justify-content:space-between;gap:12rpx}.eyebrow,.title,.name,.money,.copy,.detail{display:block}.eyebrow,.copy,.detail,.hint,.state{color:#71807d;font-size:24rpx}.title{font-size:40rpx;font-weight:750}.hint,.filters,.card,.empty,.notice,.state{margin-top:16rpx;padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.hint{line-height:1.5;background:#edf7f5;color:#32635e}.filters{padding:12rpx}.filter{padding:17rpx 12rpx;border-bottom:1rpx solid #edf1f0;font-size:26rpx}.search{padding:12rpx}.search input{flex:1;min-width:0;padding:14rpx;background:#f7faf9;border-radius:10rpx;font-size:25rpx}.search button{margin:0;background:#27655f;color:#fff;font-size:24rpx}.notice,.empty,.state{text-align:center}.notice{color:#a34b42;background:#fff5f3}.card{cursor:pointer}.name{font-size:30rpx;font-weight:700}.status{padding:8rpx 12rpx;background:#e6f3f1;color:#1f5752;border-radius:10rpx;font-size:22rpx}.money{margin-top:14rpx;font-size:36rpx;font-weight:750}.copy,.detail{margin-top:8rpx}.detail{color:#1f5752}.more{display:block;margin:20rpx auto;background:#e6f3f1;color:#1f5752;font-size:24rpx}
</style>
