<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
import { getMobileWarehouseOverview } from '@/api/business'
import type { WarehouseItem, WarehouseOverview, WarehouseStockBatch, WarehouseStockMovement } from '@/types/business'

const itemId = ref(0)
const warehouseId = ref<string | number>('')
const overview = ref<WarehouseOverview | null>(null)
const loading = ref(false)
const message = ref('')
const item = computed<WarehouseItem | null>(() => overview.value?.items.find(row => row.id === itemId.value) || null)
const batches = computed<WarehouseStockBatch[]>(() => (overview.value?.stockBatches || []).filter(row => row.itemId === itemId.value))
const movements = computed<WarehouseStockMovement[]>(() => (overview.value?.movements || []).filter(row => row.itemId === itemId.value))

onLoad(query => {
  itemId.value = Number(query?.itemId || 0)
  warehouseId.value = String(query?.warehouseId || '')
  void refresh()
})
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (!itemId.value || !warehouseId.value || loading.value) return
  loading.value = true
  message.value = ''
  try { overview.value = await getMobileWarehouseOverview(warehouseId.value) }
  catch (cause) { message.value = cause instanceof Error ? cause.message : '物料详情加载失败。' }
  finally { loading.value = false }
}
</script>

<template>
  <view class="page">
    <view v-if="message" class="notice">{{ message }}</view>
    <view v-if="!item && !loading" class="empty">物料不存在或当前账号无权查看。</view>
    <template v-if="item">
      <view class="header"><text class="eyebrow">{{ item.code }}</text><text class="title">{{ item.name }}</text><text class="copy">{{ item.categoryName || '未分类' }} · {{ item.spec || '无规格' }}</text></view>
      <view class="metrics"><view><text class="metric">{{ item.warehouseAvailableQuantity }}</text><text class="label">仓库可用 {{ item.unit || '' }}</text></view><view><text class="metric">{{ item.storeStockQuantity }}</text><text class="label">门店库存 {{ item.unit || '' }}</text></view><view><text class="metric">{{ item.minStockQuantity || 0 }}</text><text class="label">最低库存</text></view></view>
      <view class="section"><text class="section-title">库存与预警</text><text class="field">状态：{{ item.alertText || item.stockStatus }}</text><text class="field">库位：{{ item.warehouseLocation || '未设置' }}</text><text class="field">最近到期：{{ item.nearestExpiryDate || '暂无' }}</text><text class="field">保质期：{{ item.shelfLifeDays || 0 }} 天 · 临期预警 {{ item.expiryAlertDays || 0 }} 天</text></view>
      <view class="section"><text class="section-title">单位与说明</text><text class="field">采购单位：{{ item.purchaseUnit || item.unit || '—' }}</text><text class="field">库存单位：{{ item.stockUnit || item.unit || '—' }}</text><text class="field">换算关系：{{ item.unitConversionText || '未设置' }}</text><text class="field">说明：{{ item.itemDescription || '暂无说明' }}</text></view>
      <text class="heading">库存批次</text><view v-if="!batches.length" class="empty small">暂无有效批次</view><view v-for="batch in batches" :key="batch.id" class="row-card"><text class="row-title">{{ batch.batchNo }}</text><text class="copy">库存 {{ batch.quantity }} · 入库 {{ batch.receivedDate }} · 到期 {{ batch.expiryDate || '未设置' }}</text></view>
      <text class="heading">近期流水</text><view v-if="!movements.length" class="empty small">暂无库存流水</view><view v-for="movement in movements" :key="movement.id" class="row-card"><view class="row"><text class="row-title">{{ movement.movementTypeLabel }}</text><text :class="{ positive: movement.quantityDelta > 0 }">{{ movement.quantityDelta > 0 ? '+' : '' }}{{ movement.quantityDelta }}</text></view><text class="copy">{{ movement.operatorName || '系统' }} · {{ movement.createdAt || '—' }}</text></view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:24rpx;background:#f2f6f5;color:#202124}.header,.section,.row-card,.empty,.notice{padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.eyebrow,.title,.copy,.metric,.label,.section-title,.field,.heading,.row-title{display:block}.eyebrow,.copy,.label{color:#71807d;font-size:23rpx}.title{margin-top:6rpx;font-size:38rpx;font-weight:750}.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:12rpx;margin-top:16rpx}.metrics view{padding:20rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:14rpx}.metric{font-size:32rpx;font-weight:750}.label{margin-top:6rpx}.section{margin-top:16rpx}.section-title,.heading,.row-title{font-size:28rpx;font-weight:700}.field{margin-top:12rpx;color:#4f5661;font-size:25rpx}.heading{margin:28rpx 2rpx 12rpx}.row-card{margin-top:12rpx}.row{display:flex;align-items:center;justify-content:space-between;gap:12rpx}.empty,.notice{text-align:center;color:#71807d}.small{padding:28rpx}.positive{color:#287a48}
</style>
