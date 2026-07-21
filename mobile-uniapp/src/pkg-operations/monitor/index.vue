<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { claimMobileEmployeeAssistantHandoff, closeMobileEmployeeAssistantHandoff, getManagedMobileEmployeeAssistantHandoffs, getMobilePlatformStatuses, getMobileTrainingProgressReport, replyMobileEmployeeAssistantHandoff } from '@/api/business'
import { canPerformMobileAction, hasPermission } from '@/permissions'
import { useSessionStore } from '@/stores'
import type { EmployeeAssistantHandoff, PlatformAdapterStatus, TrainingProgressReport } from '@/types/business'

const session = useSessionStore()
const progress = ref<TrainingProgressReport[]>([])
const platforms = ref<PlatformAdapterStatus[]>([])
const handoffs = ref<EmployeeAssistantHandoff[]>([])
const loading = ref(false)
const reportError = ref('')
const platformError = ref('')
const handoffError = ref('')
const actingHandoffId = ref('')
const canReport = computed(() => hasPermission(session.user, 'exam.report'))
const canReadPlatform = computed(() => hasPermission(session.user, 'platform.read'))
const canManageHandoff = computed(() => canPerformMobileAction(session.user, 'employeeAssistant.handoff.claim'))
const completedCount = computed(() => progress.value.filter((item) => item.completed).length)
const learnerCount = computed(() => new Set(progress.value.map((item) => item.userId)).size)
const averagePercent = computed(() => progress.value.length ? Math.round(progress.value.reduce((sum, item) => sum + Number(item.percent || 0), 0) / progress.value.length) : 0)

onShow(() => void refresh())
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (loading.value) return
  loading.value = true
  reportError.value = ''
  platformError.value = ''
  handoffError.value = ''
  const tasks: Promise<void>[] = []
  if (canReport.value) tasks.push(getMobileTrainingProgressReport().then((rows) => { progress.value = rows || [] }).catch((error) => { reportError.value = friendlyError(error, '培训进度暂时无法加载。') }))
  if (canReadPlatform.value) tasks.push(getMobilePlatformStatuses().then((rows) => { platforms.value = rows || [] }).catch((error) => { platformError.value = friendlyError(error, '平台状态暂时无法加载。') }))
  if (canManageHandoff.value) tasks.push(getManagedMobileEmployeeAssistantHandoffs().then((rows) => { handoffs.value = rows || [] }).catch((error) => { handoffError.value = friendlyError(error, '人工事项暂时无法加载。') }))
  await Promise.all(tasks)
  loading.value = false
}

async function claim(item: EmployeeAssistantHandoff) {
  if (!canPerformMobileAction(session.user, 'employeeAssistant.handoff.claim') || actingHandoffId.value) return
  await runHandoff(item.id, () => claimMobileEmployeeAssistantHandoff(item.id), '人工事项已接单。')
}

async function reply(item: EmployeeAssistantHandoff) {
  if (!canPerformMobileAction(session.user, 'employeeAssistant.handoff.reply') || actingHandoffId.value) return
  const resolution = await promptResolution('回复员工', item.resolution || '')
  if (resolution) await runHandoff(item.id, () => replyMobileEmployeeAssistantHandoff(item.id, resolution), '回复已发送给员工。')
}

async function close(item: EmployeeAssistantHandoff) {
  if (!canPerformMobileAction(session.user, 'employeeAssistant.handoff.close') || actingHandoffId.value) return
  const resolution = await promptResolution('关闭人工事项', item.resolution || '')
  if (resolution) await runHandoff(item.id, () => closeMobileEmployeeAssistantHandoff(item.id, resolution), '人工事项已关闭。')
}

async function runHandoff(id: string, action: () => Promise<EmployeeAssistantHandoff>, successText: string) {
  actingHandoffId.value = id
  handoffError.value = ''
  try {
    const updated = await action()
    const index = handoffs.value.findIndex((item) => item.id === id)
    if (index >= 0) handoffs.value[index] = updated
    uni.showToast({ title: successText, icon: 'none' })
  } catch (error) {
    handoffError.value = friendlyError(error, '人工事项状态更新失败，请刷新后重试。')
  } finally {
    actingHandoffId.value = ''
  }
}

function promptResolution(title: string, value: string) {
  return new Promise<string>((resolve) => uni.showModal({ title, editable: true, content: value, placeholderText: '请输入处理结论', confirmText: '确认', success: (result) => resolve(result.confirm ? String(result.content || '').trim() : ''), fail: () => resolve('') }))
}

function handoffStatus(value: string) { return ({ OPEN: '待接单', CLAIMED: '处理中', RESPONDED: '已回复', CLOSED: '已关闭', EXPIRED: '已超时' } as Record<string, string>)[value] || value }

function percent(value: unknown) { return `${Math.round(Number(value || 0))}%` }
function seconds(value: unknown) { const total = Math.round(Number(value || 0)); return total >= 60 ? `${Math.floor(total / 60)} 分 ${total % 60} 秒` : `${total} 秒` }
function platformName(value: string) { return ({ MEITUAN: '美团', ELEME: '饿了么' } as Record<string, string>)[String(value).toUpperCase()] || value }
function stateText(value: string) { return value === 'READY' ? '正常' : value === 'NOT_CONFIGURED' ? '未配置' : value }
function friendlyError(error: unknown, fallback: string) {
  const status = Number((error as { status?: number })?.status || 0)
  if (status === 403) return '当前账号的数据范围不允许查看此项信息。'
  if (status === 401) return '登录已过期，请重新登录。'
  return fallback
}
</script>

<template>
  <view class="page">
    <view class="head"><view><text class="eyebrow">运营监测</text><text class="title">培训与平台</text></view><button :loading="loading" :disabled="loading" @click="refresh">刷新</button></view>

    <template v-if="canReport">
      <view class="section-head"><text class="section-title">培训完成情况</text><text class="muted">{{ progress.length }} 条学习记录</text></view>
      <view class="metrics"><view><text>{{ learnerCount }}</text><text>学习人数</text></view><view><text>{{ completedCount }}</text><text>已完成课程</text></view><view><text>{{ averagePercent }}%</text><text>平均进度</text></view></view>
      <view v-if="reportError" class="state error">{{ reportError }}</view><view v-else-if="!progress.length&&!loading" class="state">暂无培训进度</view>
      <view v-for="item in progress.slice(0, 100)" :key="`${item.userId}-${item.videoId}`" class="card">
        <view class="card-head"><text class="name">{{ item.userName }} · {{ item.videoTitle }}</text><text class="status" :class="{ ready: item.completed }">{{ item.completed ? '已完成' : percent(item.percent) }}</text></view>
        <text class="copy">{{ item.storeName || '未绑定门店' }} · 已学习 {{ seconds(item.watchedSeconds) }}</text><text v-if="item.lastWatchedAt" class="copy">最近学习：{{ item.lastWatchedAt }}</text>
      </view>
    </template>

    <template v-if="canReadPlatform">
      <view class="section-head platform-head"><text class="section-title">平台连接状态</text><text class="muted">只显示配置与连通状态</text></view>
      <view v-if="platformError" class="state error">{{ platformError }}</view><view v-else-if="!platforms.length&&!loading" class="state">暂无平台适配器</view>
      <view v-for="item in platforms" :key="item.platform" class="card">
        <view class="card-head"><text class="name">{{ platformName(item.platform) }}</text><text class="status" :class="{ ready: item.orderSync === 'READY' && item.webhook === 'READY' }">{{ item.orderSync === 'READY' && item.webhook === 'READY' ? '可用' : '待配置' }}</text></view>
        <view class="platform-row"><text>订单同步：{{ stateText(item.orderSync) }}</text><text>消息回调：{{ stateText(item.webhook) }}</text></view><text v-if="item.message" class="copy">{{ item.message }}</text>
      </view>
    </template>

    <template v-if="canManageHandoff">
      <view class="section-head platform-head"><text class="section-title">员工转人工事项</text><text class="muted">{{ handoffs.length }} 条</text></view>
      <view v-if="handoffError" class="state error">{{ handoffError }}</view><view v-else-if="!handoffs.length&&!loading" class="state">暂无人工事项</view>
      <view v-for="item in handoffs.slice(0, 100)" :key="item.id" class="card">
        <view class="card-head"><text class="name">{{ item.requestedByName || '员工' }} · {{ item.storeId || '未绑定门店' }}</text><text class="status" :class="{ ready: item.status === 'CLOSED' }">{{ handoffStatus(item.status) }}</text></view>
        <text class="handoff-question">{{ item.question }}</text><text v-if="item.resolution" class="copy">当前结论：{{ item.resolution }}</text><text class="copy">提交时间：{{ item.createdAt }}</text>
        <view class="handoff-actions"><button v-if="item.status === 'OPEN'" :loading="actingHandoffId === item.id" :disabled="Boolean(actingHandoffId)" @click="claim(item)">接单</button><button v-if="item.status === 'CLAIMED' || item.status === 'RESPONDED'" :disabled="Boolean(actingHandoffId)" @click="reply(item)">回复</button><button v-if="item.status === 'CLAIMED' || item.status === 'RESPONDED'" :disabled="Boolean(actingHandoffId)" @click="close(item)">关闭</button></view>
      </view>
    </template>

    <view v-if="!canReport&&!canReadPlatform&&!canManageHandoff" class="state">当前账号没有培训报表、平台状态或人工事项权限。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:24rpx;background:#f2f6f5;color:#202124}.head,.section-head,.card-head,.platform-row,.handoff-actions{display:flex;align-items:center;justify-content:space-between;gap:14rpx}.head button{margin:0;background:#fff;color:#1f5752}.eyebrow,.title,.section-title,.name,.copy,.handoff-question{display:block}.eyebrow,.muted,.copy{color:#71807d;font-size:23rpx}.title{margin-top:6rpx;font-size:38rpx;font-weight:750}.section-head{margin:28rpx 2rpx 14rpx}.section-title,.name{font-size:28rpx;font-weight:700}.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:12rpx}.metrics view,.card,.state{padding:20rpx;border:1rpx solid #d9e6e3;border-radius:14rpx;background:#fff}.metrics text{display:block;text-align:center}.metrics text:first-child{font-size:34rpx;font-weight:750;color:#2f6757}.metrics text:last-child{margin-top:6rpx;color:#71807d;font-size:22rpx}.card{margin-top:14rpx}.status{flex-shrink:0;padding:7rpx 11rpx;border-radius:10rpx;background:#fff0ed;color:#963b30;font-size:21rpx}.status.ready{background:#eaf5ed;color:#24663e}.copy{margin-top:9rpx;line-height:1.5}.platform-head{margin-top:38rpx}.platform-row{margin-top:14rpx;color:#4f5661;font-size:24rpx}.state{text-align:center;color:#71807d}.state.error{background:#fff0ed;color:#963b30}.handoff-question{margin-top:14rpx;font-size:26rpx;line-height:1.55}.handoff-actions{justify-content:flex-start;flex-wrap:wrap;margin-top:14rpx}.handoff-actions button{min-height:72rpx;margin:0;padding:0 22rpx;line-height:72rpx;background:#e6f3f1;color:#1f5752;font-size:24rpx}
</style>
