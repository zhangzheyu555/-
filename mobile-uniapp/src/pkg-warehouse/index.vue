<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileRequisitions, getMobileWarehouseReturns, receiveMobileWarehouseReturn, shipMobileRequisition } from '@/api/business'
import { canUseMobileCapability, useSessionStore } from '@/stores'
import { MOBILE_PERMISSIONS, type WarehouseRequisition, type WarehouseReturn } from '@/types/business'

const session = useSessionStore()
const requisitions = ref<WarehouseRequisition[]>([])
const returns = ref<WarehouseReturn[]>([])
const loading = ref(false)
const actingId = ref('')
const error = ref('')
const notice = ref('')
const canUse = computed(() => canUseMobileCapability(session.user, 'warehouse'))
const canShip = computed(() => canUse.value && session.hasPermission(MOBILE_PERMISSIONS.requisitionProcess))

onShow(() => { void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (!canUse.value || loading.value) return
  loading.value = true; error.value = ''
  try { [requisitions.value, returns.value] = await Promise.all([getMobileRequisitions(), getMobileWarehouseReturns()]) }
  catch (cause) { error.value = Number((cause as { status?: number })?.status) === 403 ? '当前账号无权查看仓库范围内的单据。' : '仓库待办暂时无法加载，请检查网络后重试。' }
  finally { loading.value = false }
}

async function ship(record: WarehouseRequisition) {
  if (!canShip.value || actingId.value) return
  actingId.value = record.id; error.value = ''; notice.value = ''
  try { await shipMobileRequisition(record.id); notice.value = '已发货，库存与操作日志已由服务端同步。'; await refresh() }
  catch (cause) { error.value = Number((cause as { status?: number })?.status) === 409 ? '单据状态已变化，请刷新后重试。' : '发货未完成，请检查网络或单据状态。' }
  finally { actingId.value = '' }
}

async function receiveReturn(record: WarehouseReturn) {
  if (!canShip.value || actingId.value) return
  actingId.value = record.id; error.value = ''; notice.value = ''
  try { await receiveMobileWarehouseReturn(record.id, '移动端确认退货收货'); notice.value = '退货已收货，库存与日志已由服务端同步。'; await refresh() }
  catch { error.value = '退货收货未完成，请刷新后重试。' }
  finally { actingId.value = '' }
}
</script>

<template>
  <view class="page"><view class="head"><view><text class="eyebrow">仓库管理员</text><text class="title">仓库待办</text></view><button :loading="loading" :disabled="loading" @click="refresh">刷新</button></view>
    <view v-if="!canUse" class="state">本页只向拥有仓库处理权限的仓库管理员开放。</view><template v-else><view v-if="error" class="message error">{{ error }}</view><view v-if="notice" class="message ok">{{ notice }}</view>
      <text class="section">待处理叫货</text><view v-if="!requisitions.length && !loading" class="state small">暂无待处理叫货单</view><view v-for="record in requisitions" :key="record.id" class="card"><text class="card-title">{{ record.storeName || '门店叫货' }}</text><text class="copy">{{ record.statusLabel || record.status }} · {{ record.lines.length }} 种物料</text><button v-if="record.status === 'APPROVED' && canShip" :loading="actingId === record.id" :disabled="Boolean(actingId)" @click="ship(record)">确认发货</button></view>
      <text class="section">退货收货</text><view v-if="!returns.length && !loading" class="state small">暂无待收退货单</view><view v-for="record in returns" :key="record.id" class="card"><text class="card-title">{{ record.returnNo || '配送退货单' }}</text><text class="copy">{{ record.returnStoreName || '门店' }} · {{ record.statusLabel || record.status }}</text><button v-if="record.status === 'APPROVED' && canShip" :loading="actingId === record.id" :disabled="Boolean(actingId)" @click="receiveReturn(record)">确认退货收货</button></view>
      <view class="hint">手机端不提供商品、仓库和采购基础配置；请使用桌面端维护。</view></template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:28rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));background:#f4f6f2;color:#172019}.head{display:flex;align-items:center;justify-content:space-between;margin-bottom:30rpx}.eyebrow,.title,.card-title,.copy{display:block}.eyebrow{color:#657168;font-size:23rpx;letter-spacing:2rpx}.title{margin-top:6rpx;font-size:46rpx;font-weight:750}.head button,.card button{min-height:88rpx;border-radius:18rpx;font-size:27rpx}.head button{min-width:128rpx;margin:0;background:#fff;color:#264c36;border:1rpx solid #cbd6cd}.card button{margin:18rpx 0 0;background:#1f6741;color:#fff}.head button::after,.card button::after{border:0}.section{display:block;margin:32rpx 2rpx 14rpx;font-size:30rpx;font-weight:700}.card,.state,.hint{margin-top:16rpx;padding:24rpx;background:#fff;border:1rpx solid #dce2db;border-radius:20rpx}.card-title{font-size:29rpx;font-weight:700}.copy{margin-top:8rpx;color:#657168;font-size:24rpx}.state{text-align:center;color:#657168}.small{padding:30rpx}.hint{color:#755d2d;background:#fff9e8;border-color:#e7d5a6;font-size:24rpx;line-height:1.6}.message{margin-bottom:16rpx;padding:18rpx;border-radius:14rpx;font-size:25rpx}.error{background:#fff0ed;color:#963b30}.ok{background:#eaf5ed;color:#24663e}
</style>
