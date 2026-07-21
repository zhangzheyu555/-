<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileRequisitions, getMobileWarehouseOverview, getMobileWarehouseReturns, receiveMobileWarehouseReturn, reviewMobileRequisition, reviewMobileWarehouseReturn, shipMobileRequisition } from '@/api/business'
import { canUseMobileCapability, useSessionStore } from '@/stores'
import { canPerformMobileAction, hasPermission } from '@/permissions'
import { openProtectedFile } from '@/platform'
import type { WarehouseOverview, WarehouseRequisition, WarehouseReturn } from '@/types/business'

const session = useSessionStore()
const requisitions = ref<WarehouseRequisition[]>([])
const returns = ref<WarehouseReturn[]>([])
const loading = ref(false)
const actingId = ref('')
const error = ref('')
const notice = ref('')
const overview = ref<WarehouseOverview | null>(null)
const approvedDrafts = ref<Record<string, string>>({})
const requisitionFilter = ref<'PENDING'|'SUBMITTED'|'APPROVED'|'SHIPPED'|'ALL'>('PENDING')
const returnFilter = ref<'PENDING'|'SUBMITTED'|'APPROVED'|'DONE'|'ALL'>('PENDING')
const requisitionFilters = [{k:'PENDING',l:'待处理'},{k:'SUBMITTED',l:'待审核'},{k:'APPROVED',l:'待发货'},{k:'SHIPPED',l:'已发货'},{k:'ALL',l:'全部'}] as const
const returnFilters = [{k:'PENDING',l:'待处理'},{k:'SUBMITTED',l:'待审核'},{k:'APPROVED',l:'待收货'},{k:'DONE',l:'已完成'},{k:'ALL',l:'全部'}] as const
const canUse = computed(() => canUseMobileCapability(session.user, 'warehouse'))
const canShip = computed(() => canUse.value && canPerformMobileAction(session.user, 'warehouse.requisition.ship'))
const canReview = computed(() => canUse.value && canPerformMobileAction(session.user, 'warehouse.requisition.review'))
const canReadFiles = computed(() => hasPermission(session.user, 'attachment.read'))
const filteredRequisitions = computed(() => requisitions.value.filter(record => requisitionFilter.value === 'ALL' || requisitionFilter.value === 'PENDING' ? ['SUBMITTED','APPROVED'].includes(record.status) : record.status === requisitionFilter.value))
const filteredReturns = computed(() => returns.value.filter(record => returnFilter.value === 'ALL' || returnFilter.value === 'PENDING' ? ['SUBMITTED','APPROVED'].includes(record.status) : returnFilter.value === 'DONE' ? ['RECEIVED','REJECTED'].includes(record.status) : record.status === returnFilter.value))
const pickingItems = computed(() => {
  const inventory = new Map((overview.value?.items || []).map(item => [item.id, item]))
  const grouped = new Map<number, { itemId:number; itemName:string; unit:string; required:number; available:number; stores:string[] }>()
  requisitions.value.filter(record => record.status === 'APPROVED').forEach(record => record.lines.forEach(line => {
    const required = Number(line.approvedQuantity || 0)
    if (required <= 0) return
    const current = grouped.get(line.itemId)
    const item = inventory.get(line.itemId)
    if (current) { current.required += required; if (!current.stores.includes(record.storeName)) current.stores.push(record.storeName); return }
    grouped.set(line.itemId, { itemId: line.itemId, itemName: line.itemName, unit: line.unit || item?.unit || '', required, available: Number(item?.warehouseAvailableQuantity || 0), stores: [record.storeName] })
  }))
  return [...grouped.values()].sort((left, right) => (right.required - right.available) - (left.required - left.available) || left.itemName.localeCompare(right.itemName, 'zh-CN'))
})
const pickingShortageCount = computed(() => pickingItems.value.filter(item => item.available < item.required).length)

onShow(() => { void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (!canUse.value || loading.value) return
  loading.value = true; error.value = ''
    try {
      [requisitions.value, returns.value, overview.value] = await Promise.all([getMobileRequisitions(), getMobileWarehouseReturns(), getMobileWarehouseOverview()])
      for (const record of requisitions.value) for (const line of record.lines) {
        const key = requisitionLineKey(record.id, line.itemId)
        if (approvedDrafts.value[key] === undefined) approvedDrafts.value[key] = String(line.approvedQuantity || line.requestedQuantity)
      }
    }
  catch (cause) { error.value = Number((cause as { status?: number })?.status) === 403 ? '当前账号无权查看仓库范围内的单据。' : '仓库待办暂时无法加载，请检查网络后重试。' }
  finally { loading.value = false }
}

async function reviewRequisition(record: WarehouseRequisition, approved: boolean) {
  if (!canReview.value || actingId.value) return
  const note = await promptText(approved ? '审核通过说明' : '驳回原因', approved ? '移动端按行核定通过' : '请填写驳回原因')
  if (note === null) return
  const lines = record.lines.map(line => ({ itemId: line.itemId, approvedQuantity: approved ? Number(approvedDrafts.value[requisitionLineKey(record.id, line.itemId)]) : 0 }))
  if (approved && lines.some(line => {
    const requested = record.lines.find(item => item.itemId === line.itemId)?.requestedQuantity
    return requested === undefined || !Number.isFinite(line.approvedQuantity) || line.approvedQuantity < 0 || line.approvedQuantity > Number(requested)
  })) {
    error.value = '核定数量必须大于等于 0，且不能超过申请数量。'
    return
  }
  actingId.value = record.id
  try { await reviewMobileRequisition(record.id, approved, note, lines); await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '叫货审核未完成。' }
  finally { actingId.value = '' }
}

async function reviewReturn(record: WarehouseReturn, approved: boolean) {
  if (!canReview.value || actingId.value) return
  const note = await promptText(approved ? '审核通过说明' : '驳回原因', approved ? '移动端审核通过' : '请填写驳回原因')
  if (note === null) return
  actingId.value = record.id
  try { await reviewMobileWarehouseReturn(record.id, approved, note); await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '退货审核未完成。' }
  finally { actingId.value = '' }
}

async function ship(record: WarehouseRequisition) {
  if (!canShip.value || actingId.value) return
  if (!await confirmAction('确认发货', `确认按已核定数量向“${record.storeName || '门店'}”发货吗？`)) return
  actingId.value = record.id; error.value = ''; notice.value = ''
  try { await shipMobileRequisition(record.id); notice.value = '已发货，库存与操作日志已由服务端同步。'; await refresh() }
  catch (cause) { error.value = Number((cause as { status?: number })?.status) === 409 ? '单据状态已变化，请刷新后重试。' : '发货未完成，请检查网络或单据状态。' }
  finally { actingId.value = '' }
}

async function receiveReturn(record: WarehouseReturn) {
  if (!canShip.value || actingId.value) return
  if (!await confirmAction('确认退货收货', `确认已收到退货单“${record.returnNo || record.id}”的全部物料吗？`)) return
  actingId.value = record.id; error.value = ''; notice.value = ''
  try { await receiveMobileWarehouseReturn(record.id, '移动端确认退货收货'); notice.value = '退货已收货，库存与日志已由服务端同步。'; await refresh() }
  catch { error.value = '退货收货未完成，请刷新后重试。' }
  finally { actingId.value = '' }
}

async function openDelivery(record: WarehouseRequisition) {
  try { await openProtectedFile({ path: `/api/warehouse/print/requisitions/${encodeURIComponent(record.id)}/delivery`, fileName: `配送出库单-${record.id}.pdf`, contentType: 'application/pdf' }) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '配送出库单打开失败。' }
}

async function openReturnPdf(record: WarehouseReturn) {
  try { await openProtectedFile({ path: `/api/warehouse/print/returns/${encodeURIComponent(record.id)}`, fileName: `配送退货单-${record.returnNo || record.id}.pdf`, contentType: 'application/pdf' }) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '配送退货单打开失败。' }
}

function requisitionLineKey(requisitionId: string, itemId: number) { return `${requisitionId}:${itemId}` }
function setRequisitionFilter(value:'PENDING'|'SUBMITTED'|'APPROVED'|'SHIPPED'|'ALL'){requisitionFilter.value=value}
function setReturnFilter(value:'PENDING'|'SUBMITTED'|'APPROVED'|'DONE'|'ALL'){returnFilter.value=value}
function openPendingShipment(){requisitionFilter.value='APPROVED';uni.pageScrollTo({scrollTop:360,duration:220})}
function confirmAction(title:string,content:string){return new Promise<boolean>(resolve=>uni.showModal({title,content,success:result=>resolve(Boolean(result.confirm)),fail:()=>resolve(false)}))}
function promptText(title:string,placeholder:string){return new Promise<string|null>(resolve=>uni.showModal({title,editable:true,content:placeholder,placeholderText:placeholder,success:result=>{if(!result.confirm){resolve(null);return}resolve(String(result.content||'').trim()||placeholder)},fail:()=>resolve(null)}))}
</script>

<template>
  <view class="page"><view class="head"><view><text class="eyebrow">仓库管理员</text><text class="title">仓库待办</text></view><button :loading="loading" :disabled="loading" @click="refresh">刷新</button></view>
    <view v-if="!canUse" class="state">本页只向拥有仓库处理权限的仓库管理员开放。</view><template v-else><view v-if="error" class="message error">{{ error }}</view><view v-if="notice" class="message ok">{{ notice }}</view>
      <text class="section">库存预警</text><view class="card"><text class="card-title">{{ overview?.summary.lowStockCount || 0 }} 项库存不足 · {{ overview?.summary.expiringCount || 0 }} 项临期</text><text class="copy">库存余额由服务端实时计算，处理后下拉刷新。</text></view>
      <text class="section">拣货清单</text><view class="card"><view class="row"><view><text class="card-title">待发物料 {{pickingItems.length}} 种</text><text class="copy">{{pickingShortageCount?`${pickingShortageCount} 种物料库存不足，请先处理缺口`:'库存数量可满足当前待发货单'}}</text></view><button class="compact-button" @click="openPendingShipment">处理待发单</button></view><view v-if="!pickingItems.length" class="state small">暂无已审核待发货的叫货单</view><view v-for="item in pickingItems" :key="item.itemId" class="picking-line"><view><text class="line-name">{{item.itemName}}</text><text class="copy">门店：{{item.stores.join('、')}}</text></view><view class="picking-amount"><text :class="{shortage:item.available<item.required}">待拣 {{item.required}} {{item.unit}}</text><text class="copy">可用 {{item.available}} {{item.unit}}<template v-if="item.available<item.required"> · 缺 {{item.required-item.available}} {{item.unit}}</template></text></view></view></view>
      <text class="section">叫货单</text><scroll-view scroll-x class="filters"><view class="filter-row"><button v-for="item in requisitionFilters" :key="item.k" :class="{active:requisitionFilter===item.k}" @click="setRequisitionFilter(item.k)">{{item.l}}</button></view></scroll-view><view v-if="!filteredRequisitions.length && !loading" class="state small">当前筛选条件下暂无叫货单</view><view v-for="record in filteredRequisitions" :key="record.id" class="card"><text class="card-title">{{ record.storeName || '门店叫货' }}</text><text class="copy">{{ record.id }} · {{ record.statusLabel || record.status }} · {{ record.lines.length }} 种物料</text><text v-if="record.note" class="copy">备注：{{record.note}}</text><view v-for="line in record.lines" :key="line.itemId" class="line"><view><text class="line-name">{{ line.itemName }}</text><text class="copy">申请 {{ line.requestedQuantity }} {{ line.unit || '' }}<template v-if="record.status !== 'SUBMITTED'"> · 核定 {{ line.approvedQuantity }} · 实发 {{ line.shippedQuantity || 0 }} · 已收 {{line.receivedQuantity || 0}}</template></text></view><input v-if="record.status === 'SUBMITTED' && canReview" v-model="approvedDrafts[requisitionLineKey(record.id,line.itemId)]" class="quantity-input" type="digit" :placeholder="String(line.requestedQuantity)"/></view><view v-if="record.status === 'SUBMITTED' && canReview" class="button-row"><button @click="reviewRequisition(record,true)">按核定量通过</button><button @click="reviewRequisition(record,false)">驳回</button></view><button v-if="record.status === 'APPROVED' && canShip" :loading="actingId === record.id" :disabled="Boolean(actingId)" @click="ship(record)">确认发货</button><button v-if="canReadFiles && ['SHIPPED','RECEIVED'].includes(record.status)" class="file-button" @click="openDelivery(record)">查看配送出库单</button></view>
      <text class="section">退货单</text><scroll-view scroll-x class="filters"><view class="filter-row"><button v-for="item in returnFilters" :key="item.k" :class="{active:returnFilter===item.k}" @click="setReturnFilter(item.k)">{{item.l}}</button></view></scroll-view><view v-if="!filteredReturns.length && !loading" class="state small">当前筛选条件下暂无退货单</view><view v-for="record in filteredReturns" :key="record.id" class="card"><text class="card-title">{{ record.returnNo || '配送退货单' }}</text><text class="copy">{{ record.returnStoreName || '门店' }} · {{ record.statusLabel || record.status }} · {{record.returnDate||record.createdAt||'—'}}</text><text v-if="record.reason" class="copy">退货原因：{{record.reason}}</text><text v-if="record.note" class="copy">备注：{{record.note}}</text><view v-for="line in record.lines||[]" :key="`${record.id}-${line.itemId}-${line.batchNo||''}`" class="line"><text class="line-name">{{line.itemName}} · {{line.quantity}} {{line.unit||''}}</text><text class="copy">批次 {{line.batchNo||'—'}}<template v-if="line.reason"> · {{line.reason}}</template></text></view><view v-if="record.status === 'SUBMITTED' && canReview" class="button-row"><button @click="reviewReturn(record,true)">审核通过</button><button @click="reviewReturn(record,false)">驳回</button></view><button v-if="record.status === 'APPROVED' && canShip" :loading="actingId === record.id" :disabled="Boolean(actingId)" @click="receiveReturn(record)">确认退货收货</button><button v-if="canReadFiles" class="file-button" @click="openReturnPdf(record)">查看配送退货单</button></view>
      <view class="hint">手机端不提供商品、仓库和采购基础配置；请使用桌面端维护。</view></template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:28rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));background:#f2f6f5;color:#1c1d22}.head{display:flex;align-items:center;justify-content:space-between;margin-bottom:30rpx}.eyebrow,.title,.card-title,.copy{display:block}.eyebrow{color:#71807d;font-size:23rpx;letter-spacing: 0}.title{margin-top:6rpx;font-size: 38rpx;font-weight:750}.head button,.card button{min-height:88rpx;border-radius: 16rpx;font-size:27rpx}.head button{min-width:128rpx;margin:0;background:#fff;color:#1f5752;border:1rpx solid #d9e6e3}.card button{margin:18rpx 0 0;background:#27655f;color:#fff}.head button::after,.card button::after{border:0}.section{display:block;margin:32rpx 2rpx 14rpx;font-size:30rpx;font-weight:700}.card,.state,.hint{margin-top:16rpx;padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius: 16rpx}.card-title{font-size:29rpx;font-weight:700}.copy{margin-top:8rpx;color:#71807d;font-size:24rpx}.state{text-align:center;color:#71807d}.small{padding:30rpx}.hint{color:#755d2d;background:#fff9e8;border-color:#e7d5a6;font-size:24rpx;line-height:1.6}.message{margin-bottom:16rpx;padding:18rpx;border-radius:14rpx;font-size:25rpx}.error{background:#fff0ed;color:#963b30}.ok{background:#eaf5ed;color:#24663e}
.button-row{display:flex;gap:12rpx}.button-row button{flex:1}
.line{display:flex;align-items:center;justify-content:space-between;gap:16rpx;margin-top:16rpx;padding-top:16rpx;border-top:1rpx solid #edf0f3}.line-name{display:block;font-size:27rpx;font-weight:650}.quantity-input{box-sizing:border-box;width:180rpx;padding:16rpx;background:#f7faf9;border:1rpx solid #dfe3e8;border-radius:12rpx;text-align:right}
.filters{margin-top:12rpx;white-space:nowrap}.filter-row{display:inline-flex;gap:10rpx}.filter-row button{min-height:64rpx;margin:0;padding:0 20rpx;background:#fff;color:#59606b;border:1rpx solid #d9e6e3;font-size:23rpx}.filter-row button.active{background:#e6f3f1;color:#1f5752;border-color:#9fc8c1}.compact-button{min-height:64rpx!important;margin:0!important;padding:0 18rpx!important;white-space:nowrap;font-size:23rpx!important}.picking-line{display:flex;align-items:center;justify-content:space-between;gap:16rpx;margin-top:16rpx;padding-top:16rpx;border-top:1rpx solid #edf0f3}.picking-amount{text-align:right}.picking-amount text{display:block;font-size:25rpx;font-weight:650}.picking-amount .copy{font-weight:400}.shortage{color:#b03a2e}.card .file-button{background:#f7faf9;color:#4e5966}
</style>
