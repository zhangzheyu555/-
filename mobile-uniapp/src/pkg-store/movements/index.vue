<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileWarehouseMovements } from '@/api/business'
import { canUseMobileCapability, useSessionStore } from '@/stores'
import { MOBILE_PERMISSIONS, type WarehouseStockMovement } from '@/types/business'

const session = useSessionStore()
const rows = ref<WarehouseStockMovement[]>([])
const loading = ref(false)
const message = ref('')
const keyword = ref('')
const typeIndex = ref(0)
const canRead = computed(() => canUseMobileCapability(session.user, 'inventory') && session.hasAnyPermission([MOBILE_PERMISSIONS.warehouseRead, 'warehouse.read']))
const typeOptions = computed(() => [{ value: '', label: '全部类型' }, ...Array.from(new Map(rows.value.map(row => [row.movementType, row.movementTypeLabel || row.movementType])).entries()).map(([value, label]) => ({ value, label }))])
const filteredRows = computed(() => {
  const selectedType = typeOptions.value[typeIndex.value]?.value || ''
  const query = keyword.value.trim().toLowerCase()
  return rows.value.filter(row => (!selectedType || row.movementType === selectedType) && (!query || [row.itemName, row.movementTypeLabel, row.sourceId, row.note, row.operatorName].some(value => String(value || '').toLowerCase().includes(query))))
})

onShow(() => { void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (!canRead.value || loading.value) return
  loading.value = true
  message.value = ''
  try { rows.value = await getMobileWarehouseMovements() }
  catch (cause) { message.value = friendlyError(cause) }
  finally { loading.value = false }
}

function chooseType(event: { detail: { value: string | number } }) { typeIndex.value = Number(event.detail.value) }
function openSource(row: WarehouseStockMovement) {
  if (row.sourceType !== 'REQUISITION' || !row.sourceId) return
  uni.navigateTo({ url: `/pkg-store/requisition-detail/index?id=${encodeURIComponent(row.sourceId)}` })
}
function quantity(value: number) { const number = Number(value || 0); return `${number > 0 ? '+' : ''}${Number.isInteger(number) ? number : number.toFixed(2)}` }
function friendlyError(cause: unknown) { const status = Number((cause as { status?: number })?.status || 0); return status === 403 ? '当前账号无权查看该门店库存流水。' : status === 401 ? '登录已过期，请重新登录。' : '库存流水暂时无法加载，请稍后重试。' }
</script>

<template>
  <view class="page">
    <view class="head"><view><text class="eyebrow">店长工作台</text><text class="title">库存流水</text></view><button :loading="loading" :disabled="loading || !canRead" @click="refresh">刷新</button></view>
    <view v-if="!canRead" class="state">本页只显示当前店长授权门店的库存流水。</view>
    <template v-else>
      <view class="filters"><input v-model="keyword" placeholder="搜索物料、单号或备注"/><picker :range="typeOptions" range-key="label" @change="chooseType"><view class="picker">{{ typeOptions[typeIndex]?.label || '全部类型' }}</view></picker></view>
      <view v-if="message" class="notice">{{ message }}</view>
      <view v-if="!filteredRows.length && !loading" class="state">暂无符合条件的库存流水</view>
      <view v-for="row in filteredRows" :key="row.id" class="card">
        <view class="row"><view><text class="name">{{ row.itemName }}</text><text class="copy">{{ row.movementTypeLabel || row.movementType }}</text></view><text :class="['quantity',{positive:row.quantityDelta>0,negative:row.quantityDelta<0}]">{{ quantity(row.quantityDelta) }}</text></view>
        <text class="copy">{{ row.storeName || '本店' }} · {{ row.operatorName || '系统' }} · {{ row.createdAt || '—' }}</text>
        <text v-if="row.note" class="note">{{ row.note }}</text>
        <button v-if="row.sourceType==='REQUISITION'&&row.sourceId" class="source-button" @click="openSource(row)">查看关联叫货单</button>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:24rpx;background:#f2f6f5;color:#202124}.head,.row{display:flex;align-items:center;justify-content:space-between;gap:16rpx}.eyebrow,.title,.name,.copy,.note{display:block}.eyebrow,.copy{color:#71807d;font-size:23rpx}.title{margin-top:6rpx;font-size:38rpx;font-weight:750}.head button{min-width:120rpx;margin:0;background:#fff;color:#1f5752}.filters{display:grid;grid-template-columns:minmax(0,1fr) 210rpx;gap:12rpx;margin-top:20rpx}.filters input,.picker{box-sizing:border-box;min-height:80rpx;padding:18rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:14rpx}.picker{text-align:center;color:#4f5661;font-size:25rpx}.card,.state,.notice{margin-top:16rpx;padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.name{font-size:29rpx;font-weight:700}.quantity{font-size:32rpx;font-weight:750}.positive{color:#287a48}.negative{color:#a13a2f}.copy{margin-top:8rpx}.note{margin-top:12rpx;padding:14rpx;background:#f7faf9;color:#4f5661;font-size:24rpx}.source-button{min-height:80rpx;margin:16rpx 0 0;background:#e6f3f1;color:#1f5752;font-size:26rpx}.state{text-align:center;color:#71807d}
</style>
