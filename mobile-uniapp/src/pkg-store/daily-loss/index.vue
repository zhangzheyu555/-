<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { approveMobileDailyLoss, createMobileDailyLoss, getMobileDailyLossRecords, getMobileWarehouseOverview, submitMobileDailyLoss, uploadMobileDailyLossAttachment } from '@/api/business'
import ProtectedAttachmentList from '@/components/ProtectedAttachmentList.vue'
import { canPerformMobileAction, normalizeRole } from '@/permissions'
import { chooseImages, openProtectedFile, showOperationFailure, showOperationSuccess } from '@/platform'
import { useContextStore, useSessionStore } from '@/stores'
import type { DailyLossItem, DailyLossRecord } from '@/types/business'

const session = useSessionStore(); const context = useContextStore()
const items = ref<DailyLossItem[]>([]); const rows = ref<DailyLossRecord[]>([]); const selected = ref(0)
const qty = ref(''); const reason = ref(''); const lossDate = ref(new Date().toISOString().slice(0, 10)); const filterStatus = ref('ALL')
const message = ref(''); const loading = ref(false); const acting = ref(false); const photos = ref<Array<{ path: string }>>([]); const exporting = ref(false); const exportMonth = ref(new Date().toISOString().slice(0, 7))
const role = computed(() => normalizeRole(session.user?.role)); const canCreate = computed(() => canPerformMobileAction(session.user, 'dailyLoss.create')); const canReview = computed(() => canPerformMobileAction(session.user, 'dailyLoss.review'))
const canRead = computed(() => ['BOSS', 'FINANCE'].includes(role.value) && session.hasPermission('daily_loss.read')); const canExport = computed(() => ['BOSS', 'FINANCE'].includes(role.value) && session.hasPermission('daily_loss.export'))
const readRestrictionText = computed(() => canReview.value ? '当前后端未向督导开放报损列表查询，暂无法在此加载待复核单。' : '报损记录由财务或老板查询；本页提交完成后会明确提示结果。')
const selectedItem = computed(() => items.value[selected.value])
onShow(async () => { if (!context.stores.length && session.user) await context.load(session.user); await refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })
async function refresh() {
  if (loading.value) return
  loading.value = true; message.value = ''
  const errors: string[] = []
  try {
    const [loadedItems, loadedRows] = await Promise.all([
      canCreate.value
        ? getMobileWarehouseOverview().then(overview => (overview.items || []).filter(item => item.active).map(item => ({ id: item.id, code: item.code, name: item.name, stockUnit: item.stockUnit || item.unit || '件', unitPrice: Number(item.unitPrice || 0) }))).catch(error => { errors.push(error instanceof Error ? error.message : '报损物料加载失败'); return [] as DailyLossItem[] })
        : Promise.resolve([] as DailyLossItem[]),
      canRead.value
        ? getMobileDailyLossRecords({ storeId: context.currentStoreId || undefined, status: filterStatus.value === 'ALL' ? undefined : filterStatus.value }).catch(error => { errors.push(error instanceof Error ? error.message : '报损记录加载失败'); return [] as DailyLossRecord[] })
        : Promise.resolve([] as DailyLossRecord[]),
    ])
    items.value = loadedItems; rows.value = loadedRows
    if (selected.value >= items.value.length) selected.value = 0
    message.value = errors.join('；')
  } finally { loading.value = false }
}
async function selectPhotos() { photos.value = await chooseImages({ count: 3, source: 'both' }) }
async function create() {
  if (!canCreate.value || acting.value) return
  const storeId = context.currentStoreId || session.user?.boundStoreId || ''; const item = selectedItem.value; const quantity = Number(qty.value)
  if (!storeId || !item || !Number.isFinite(quantity) || quantity <= 0 || !reason.value.trim() || !lossDate.value) { const content = '请完整填写发生日期、物料、有效数量和原因。'; message.value = content; showOperationFailure('无法提交报损', new Error(content)); return }
  if (item.unitPrice <= 0) { const content = `${item.name}尚未配置损耗成本，请先在物料档案维护单价。`; message.value = content; showOperationFailure('无法提交报损', new Error(content)); return }
  acting.value = true
  try {
    let record = await createMobileDailyLoss({ storeId, lossDate: lossDate.value, itemId: item.id, lossQuantity: quantity, lossReason: reason.value.trim() })
    for (const photo of photos.value) record = await uploadMobileDailyLossAttachment(record.id, photo.path)
    await submitMobileDailyLoss(record.id); qty.value = ''; reason.value = ''; photos.value = []; await refresh()
    await showOperationSuccess('报损已提交', `${item.name} ${formatQuantity(quantity)} ${item.stockUnit}已提交，等待督导复核。`)
  } catch (error) { message.value = error instanceof Error ? error.message : '提交失败' } finally { acting.value = false }
}
async function approve(row: DailyLossRecord) { if (!canReview.value) return; try { await approveMobileDailyLoss(row.id, '移动端审核通过'); await refresh(); await showOperationSuccess('报损已复核', '报损单已审核通过，库存流水将以服务端结果为准。') } catch (error) { message.value = error instanceof Error ? error.message : '报损复核失败' } }
function formatQuantity(value: number) { return Number.isInteger(value) ? String(value) : value.toFixed(2) }
async function setFilter(status: string) { if (!canRead.value || loading.value) return; filterStatus.value = status; await refresh() }
function statusText(status: string) { return ({ SUBMITTED: '待复核', APPROVED: '已通过', REVIEWED: '已复核', REJECTED: '已驳回' } as Record<string, string>)[status] || '状态待确认' }
async function exportMonthFile() { if (!canExport.value || exporting.value) return; exporting.value = true; try { const storeId = context.currentStoreId || session.user?.boundStoreId || ''; const query = `month=${encodeURIComponent(exportMonth.value)}${storeId ? `&storeId=${encodeURIComponent(storeId)}` : ''}`; await openProtectedFile({ path: `/api/daily-loss/exports/monthly.xlsx?${query}`, fileName: `${exportMonth.value}报损.xlsx`, contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }) } catch (error) { message.value = error instanceof Error ? error.message : '报损 Excel 导出失败' } finally { exporting.value = false } }
</script>
<template>
  <view class="page">
    <view class="head"><text class="title">每日报损</text><button v-if="canExport" :loading="exporting" @click="exportMonthFile">导出 Excel</button></view>
    <view v-if="canExport" class="export"><picker mode="date" fields="month" :value="exportMonth" @change="exportMonth=String($event.detail.value)"><view>导出月份：{{exportMonth}} ›</view></picker></view>
    <view v-if="message" class="notice">{{message}}</view>
    <view v-if="canCreate" class="panel">
      <text class="section-title">提交本店报损</text>
      <text class="form-tip">物料来自本店库存，报损通过复核后才扣减库存。</text>
      <picker mode="date" :value="lossDate" @change="lossDate=String($event.detail.value)"><view class="input">发生日期：{{lossDate}}</view></picker>
      <picker :range="items" range-key="name" :disabled="!items.length" @change="selected=Number($event.detail.value)"><view class="input">{{loading&&!items.length?'正在加载物料…':selectedItem?.name||'暂无可用报损物料'}}</view></picker>
      <text v-if="selectedItem" class="item-tip">单位：{{selectedItem.stockUnit}}<template v-if="selectedItem.unitPrice<=0"> · 尚未配置损耗成本</template></text>
      <input v-model="qty" class="input" type="digit" placeholder="报损数量"/>
      <textarea v-model="reason" class="input area" placeholder="报损原因"/>
      <button class="plain" :disabled="acting" @click="selectPhotos">选择现场照片（{{photos.length}}/3）</button>
      <button class="primary" :loading="acting" :disabled="acting||loading||!items.length" @click="create">提交报损</button>
    </view>
    <template v-if="canRead">
      <view class="filters">
        <button v-for="status in ['ALL','SUBMITTED','APPROVED']" :key="status" :disabled="loading" :class="{active:filterStatus===status}" @click="setFilter(status)">{{status==='ALL'?'全部':status==='SUBMITTED'?'待复核':'已通过'}}</button>
      </view>
      <view v-if="!rows.length&&!loading" class="notice muted-notice">当前分类暂无报损记录</view>
      <view v-for="row in rows" :key="row.id" class="card">
        <view class="row"><text class="name">{{row.itemName}}</text><text>{{statusText(row.status)}}</text></view>
        <text class="copy">{{row.lossDate}} · {{row.lossQuantity}}{{row.stockUnit}} · ¥{{Number(row.amountSnapshot||0).toFixed(2)}}</text>
        <text class="copy">{{row.lossReason}}</text>
        <ProtectedAttachmentList :items="(row.attachments||[]).map(a=>({id:a.id,fileName:a.fileName,contentType:a.contentType,path:a.downloadUrl}))" @error="message=$event"/>
        <button v-if="canReview&&row.status==='SUBMITTED'" @click="approve(row)">审核通过</button>
      </view>
    </template>
    <view v-else class="notice permission-notice">{{readRestrictionText}}</view>
  </view>
</template>
<style scoped lang="scss">.page{min-height:100vh;padding:24rpx;background:#f2f6f5}.head,.row{display:flex;align-items:center;justify-content:space-between;gap:12rpx}.title{font-size:40rpx;font-weight:750}.section-title{display:block;font-size:30rpx;font-weight:700}.form-tip,.item-tip{display:block;margin-top:8rpx;color:#71807d;font-size:23rpx;line-height:1.5}.item-tip{padding:8rpx 4rpx 0;color:#876321}.head button{margin:0;background:#e6f3f1;color:#1f5752;font-size:23rpx}.export,.panel,.card,.notice{margin-top:18rpx;padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.export{color:#1f5752;font-size:24rpx}.input{box-sizing:border-box;width:100%;margin-top:14rpx;padding:20rpx;background:#f7faf9;border-radius:12rpx}.area{height:130rpx}.plain,.primary{min-height:84rpx;margin-top:16rpx}.primary{background:#27655f;color:#fff}.name,.copy{display:block}.name{font-weight:700}.copy{margin-top:10rpx;color:#71807d;font-size:24rpx}.filters{display:flex;gap:10rpx;margin-top:18rpx}.filters button{margin:0;background:#fff;color:#59606b;font-size:23rpx}.filters button.active{background:#e6f3f1;color:#1f5752}.permission-notice{color:#596c68;background:#f7faf9}.muted-notice{color:#71807d;text-align:center}</style>
