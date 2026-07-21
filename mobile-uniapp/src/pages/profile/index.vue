<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import AppCard from '@/components/AppCard.vue'
import StatusChip from '@/components/StatusChip.vue'
import { getMobileEmployeeProfile } from '@/api/business'
import { bindWeChat as bindWeChatApi, weChatBindingStatus } from '@/api/auth'
import { checkVersion, promptVersionUpdate, requestNotification, runtimePlatform } from '@/platform'
import { useContextStore, useMenuStore, useSessionStore } from '@/stores'
import type { EmployeeSelfProfile } from '@/types/business'
import type { WeChatBindingStatus } from '@/types/auth'

const session = useSessionStore()
const menu = useMenuStore()
const context = useContextStore()
const versionMessage = ref('')
const notificationMessage = ref('')
const employeeProfile = ref<EmployeeSelfProfile | null>(null)
const employeeProfileLoading = ref(false)
const employeeProfileMessage = ref('')
const weChatBinding = ref<WeChatBindingStatus | null>(null)
const weChatBindingLoading = ref(false)
const weChatBindingMessage = ref('')
const platformLabels = { h5: 'H5 移动网页', 'mp-weixin': '微信小程序', app: '原生 App' }
const isEmployee = computed(() => session.user?.role === 'EMPLOYEE')
const isWeChatMiniProgram = runtimePlatform() === 'mp-weixin'

onShow(async () => {
  if (!session.user && !await session.restore()) uni.reLaunch({ url: '/pages/login/index' })
  if (isEmployee.value) await loadEmployeeProfile()
  if (isWeChatMiniProgram && session.user) await loadWeChatBinding()
})

async function loadEmployeeProfile(): Promise<void> {
  if (!isEmployee.value || employeeProfileLoading.value) return
  employeeProfileLoading.value = true
  employeeProfileMessage.value = ''
  try {
    employeeProfile.value = await getMobileEmployeeProfile()
  } catch (error) {
    employeeProfileMessage.value = error instanceof Error ? error.message : '个人资料加载失败，请稍后重试。'
  } finally {
    employeeProfileLoading.value = false
  }
}

function formatMoney(value?: number | null): string {
  if (value === undefined || value === null) return '未生成'
  return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

async function loadWeChatBinding(): Promise<void> {
  if (!isWeChatMiniProgram || weChatBindingLoading.value) return
  weChatBindingLoading.value = true
  weChatBindingMessage.value = ''
  try {
    weChatBinding.value = await weChatBindingStatus()
  } catch (error) {
    weChatBindingMessage.value = error instanceof Error ? error.message : '微信绑定状态加载失败，请稍后重试。'
  } finally {
    weChatBindingLoading.value = false
  }
}

async function bindWeChat(): Promise<void> {
  if (weChatBindingLoading.value) return
  weChatBindingMessage.value = ''
  weChatBindingLoading.value = true
  try {
    const code = await requestWeChatCode()
    weChatBinding.value = await bindWeChatApi(code)
    weChatBindingMessage.value = '微信已绑定，下次可直接一键登录。'
  } catch (error) {
    weChatBindingMessage.value = error instanceof Error ? error.message : '微信绑定失败，请稍后重试。'
  } finally {
    weChatBindingLoading.value = false
  }
}

function requestWeChatCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    // #ifdef MP-WEIXIN
    uni.login({
      provider: 'weixin',
      success: ({ code }) => code ? resolve(code) : reject(new Error('未获取到微信授权凭据，请重试')),
      fail: () => reject(new Error('微信授权未完成，请重试')),
    })
    // #endif
    // #ifndef MP-WEIXIN
    reject(new Error('请在微信小程序中绑定微信'))
    // #endif
  })
}

async function inspectVersion(): Promise<void> {
  const result = await checkVersion()
  versionMessage.value = `${result.currentVersion} · ${result.message}`
  await promptVersionUpdate(result)
}

async function enableNotifications(): Promise<void> {
  const result = await requestNotification()
  notificationMessage.value = result.message
}

async function logout(): Promise<void> {
  await session.logout()
  menu.clear()
  context.clear()
  uni.reLaunch({ url: '/pages/login/index' })
}
</script>

<template>
  <view class="mobile-page profile-page mobile-stack">
    <view class="profile-hero">
      <view class="profile-avatar">{{ session.user?.displayName?.charAt(0) || '?' }}</view>
      <view class="profile-copy">
        <text class="profile-name">{{ session.user?.displayName || '我的账号' }}</text>
        <view class="profile-tags"><StatusChip :label="session.user?.roleLabel || '未登录'" tone="info" /><text>{{ session.scopeLabel }}</text></view>
      </view>
    </view>

    <AppCard title="账号信息">
      <view class="identity-list">
        <view class="identity-row"><text>角色</text><text class="identity-row__value">{{ session.user?.roleLabel || '—' }}</text></view>
        <view class="identity-row"><text>组织</text><text class="identity-row__value">{{ session.user?.tenantName || '—' }}</text></view>
        <view class="identity-row"><text>门店范围</text><text class="identity-row__value">{{ session.scopeLabel }}</text></view>
        <view class="identity-row"><text>当前入口</text><StatusChip :label="platformLabels[runtimePlatform()]" tone="info" /></view>
      </view>
    </AppCard>

    <AppCard v-if="isWeChatMiniProgram" title="微信一键登录">
      <view v-if="weChatBindingLoading && !weChatBinding" class="employee-state">正在读取微信绑定状态...</view>
      <view v-else class="wechat-binding">
        <view><text class="wechat-binding__title">{{ weChatBinding?.bound ? '已绑定微信' : '尚未绑定微信' }}</text><text class="wechat-binding__copy">{{ weChatBinding?.configured ? (weChatBinding?.bound ? '下次打开小程序可使用微信一键登录。' : '首次绑定后，下次可直接微信一键登录。') : '管理员尚未配置微信登录，请继续使用账号密码登录。' }}</text></view>
        <StatusChip :label="weChatBinding?.bound ? '已绑定' : '未绑定'" :tone="weChatBinding?.bound ? 'success' : 'warning'" />
      </view>
      <text v-if="weChatBindingMessage" class="setting-result">{{ weChatBindingMessage }}</text>
      <button v-if="weChatBinding?.configured && !weChatBinding.bound" class="wechat-bind-button" :loading="weChatBindingLoading" :disabled="weChatBindingLoading" @click="bindWeChat">绑定当前微信</button>
      <button v-else-if="weChatBindingMessage && !weChatBindingLoading" class="wechat-retry-button" @click="loadWeChatBinding">刷新状态</button>
    </AppCard>

    <AppCard v-if="isEmployee" title="我的资料与工资">
      <view v-if="employeeProfileLoading && !employeeProfile" class="employee-state">正在加载个人资料...</view>
      <view v-else-if="employeeProfileMessage" class="employee-error">
        <text>{{ employeeProfileMessage }}</text><button size="mini" @click="loadEmployeeProfile">重新加载</button>
      </view>
      <view v-else-if="employeeProfile" class="employee-profile">
        <view class="employee-profile__head">
          <view><text class="employee-profile__name">{{ employeeProfile.archive.name || employeeProfile.profile.displayName }}</text><text>{{ employeeProfile.store.storeName }} · {{ employeeProfile.profile.username }}</text></view>
          <StatusChip :label="employeeProfile.archive.linked ? '已关联档案' : '待补档案'" :tone="employeeProfile.archive.linked ? 'success' : 'warning'" />
        </view>
        <view class="employee-grid">
          <view><text>员工编号</text><strong>{{ employeeProfile.archive.employeeId || '待匹配' }}</strong></view>
          <view><text>岗位</text><strong>{{ employeeProfile.archive.position || '待填写' }}</strong></view>
          <view><text>入职日期</text><strong>{{ employeeProfile.archive.hireDate || '待填写' }}</strong></view>
          <view><text>基础工资</text><strong>{{ formatMoney(employeeProfile.archive.baseSalary) }}</strong></view>
        </view>
        <view class="salary-card">
          <view class="salary-card__head"><view><text class="salary-card__eyebrow">最近一笔工资</text><text class="salary-card__month">{{ employeeProfile.salary.month || '暂无工资记录' }}</text></view><StatusChip :label="employeeProfile.salary.statusLabel" :tone="employeeProfile.salary.available ? 'success' : 'warning'" /></view>
          <text class="salary-card__total">{{ employeeProfile.salary.available ? formatMoney(employeeProfile.salary.netPay ?? employeeProfile.salary.gross) : '未生成' }}</text>
          <view class="employee-grid employee-grid--salary">
            <view><text>应发工资</text><strong>{{ formatMoney(employeeProfile.salary.gross) }}</strong></view>
            <view><text>提成/绩效</text><strong>{{ formatMoney((employeeProfile.salary.commission || 0) + (employeeProfile.salary.performance || 0)) }}</strong></view>
            <view><text>出勤</text><strong>{{ employeeProfile.salary.attendance || '待录入' }}</strong></view>
            <view><text>发放时间</text><strong>{{ employeeProfile.salary.paidAt || '未发放' }}</strong></view>
          </view>
          <text class="salary-card__message">{{ employeeProfile.salary.message }}</text>
        </view>
        <text class="employee-tip">{{ employeeProfile.archive.message }}</text>
        <button class="employee-refresh" :loading="employeeProfileLoading" @click="loadEmployeeProfile">刷新个人资料</button>
      </view>
    </AppCard>

    <AppCard title="提醒与版本">
      <view class="setting-list">
        <button class="setting-row" @click="enableNotifications">
          <view><text class="setting-row__title">待办提醒</text><text class="setting-row__description">按当前平台申请通知能力</text></view>
          <text>›</text>
        </button>
        <text v-if="notificationMessage" class="setting-result">{{ notificationMessage }}</text>
        <button class="setting-row" @click="inspectVersion">
          <view><text class="setting-row__title">检查版本</text><text class="setting-row__description">App 读取受认证版本策略</text></view>
          <text>›</text>
        </button>
        <text v-if="versionMessage" class="setting-result">{{ versionMessage }}</text>
      </view>
    </AppCard>

    <AppCard compact>
      <text class="session-note">为保护账号安全，退出会清除本机登录凭据。业务记录从未保存在本机。</text>
      <button class="logout-button" :disabled="session.submitting" @click="logout">退出登录</button>
    </AppCard>
  </view>
</template>

<style scoped lang="scss">
.identity-list, .setting-list { display: flex; flex-direction: column; }
.wechat-binding { display: flex; align-items: center; justify-content: space-between; gap: 20rpx; }.wechat-binding > view { display: flex; min-width: 0; flex-direction: column; gap: 8rpx; }.wechat-binding__title { color: $mobile-ink; font-size: 28rpx; font-weight: 800; }.wechat-binding__copy { color: $mobile-muted; font-size: 23rpx; line-height: 1.5; }.wechat-bind-button, .wechat-retry-button { min-height: 80rpx; margin: 22rpx 0 0; color: #fff; background: #07c160; border-radius: 14rpx; font-size: 26rpx; line-height: 80rpx; }.wechat-bind-button::after, .wechat-retry-button::after { border: 0; }.wechat-retry-button { color: $mobile-green; background: #e6f3f1; }
.employee-profile, .employee-profile__head, .employee-grid, .salary-card, .salary-card__head, .employee-error { display: flex; }
.employee-profile { flex-direction: column; gap: 20rpx; }
.employee-profile__head, .salary-card__head, .employee-error { align-items: center; justify-content: space-between; gap: 16rpx; }
.employee-profile__head > view, .salary-card__head > view { display: flex; min-width: 0; flex-direction: column; gap: 6rpx; }
.employee-profile__head > view > text:last-child, .salary-card__eyebrow, .employee-tip, .salary-card__message { color: $mobile-muted; font-size: 23rpx; line-height: 1.5; }
.employee-profile__name, .salary-card__month { color: $mobile-ink; font-size: 30rpx; font-weight: 800; }
.employee-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14rpx; }
.employee-grid > view { display: flex; min-height: 104rpx; padding: 16rpx; flex-direction: column; justify-content: center; gap: 8rpx; background: #f4f8f7; border-radius: 12rpx; }
.employee-grid text { color: $mobile-muted; font-size: 22rpx; }.employee-grid strong { overflow: hidden; color: $mobile-ink; font-size: 25rpx; text-overflow: ellipsis; white-space: nowrap; }
.salary-card { padding: 20rpx; flex-direction: column; gap: 16rpx; background: $mobile-green-soft; border-radius: 14rpx; }.salary-card__total { color: $mobile-green; font-size: 46rpx; font-weight: 850; }.employee-grid--salary > view { background: rgba(255,255,255,.7); }.employee-tip { display: block; }.employee-refresh { margin: 0; color: $mobile-green; background: #e6f3f1; font-size: 25rpx; }.employee-error { padding: 16rpx; color: #9d3c35; background: #fff0ed; border-radius: 12rpx; font-size: 24rpx; }.employee-error button { margin: 0; color: #9d3c35; background: transparent; font-size: 22rpx; }.employee-state { padding: 24rpx 0; color: $mobile-muted; font-size: 25rpx; text-align: center; }
.profile-hero { display: flex; min-height: 176rpx; padding: 30rpx; align-items: center; gap: 24rpx; color: #fff; background: #27655f; border-radius: 14rpx; box-shadow: 0 12rpx 30rpx rgba(30,77,71,.12); }
.profile-avatar { display: flex; width: 104rpx; height: 104rpx; flex: 0 0 104rpx; align-items: center; justify-content: center; background: rgba(255,255,255,.2); border: 2rpx solid rgba(255,255,255,.3); border-radius: 50%; font-size: 42rpx; font-weight: 800; }
.profile-copy { display: flex; min-width: 0; flex-direction: column; gap: 12rpx; }
.profile-name { overflow: hidden; color: #fff; font-size: 36rpx; font-weight: 850; text-overflow: ellipsis; white-space: nowrap; }
.profile-tags { display: flex; align-items: center; gap: 14rpx; }
.profile-tags > text { color: rgba(255,255,255,.8); font-size: 22rpx; }
.identity-row { display: flex; min-height: 76rpx; padding: 14rpx 0; align-items: center; justify-content: space-between; gap: 24rpx; border-bottom: 1rpx solid $mobile-line; }
.identity-row:last-child { border-bottom: 0; }
.identity-row > text { color: $mobile-muted; font-size: 25rpx; }
.identity-row__value { color: $mobile-ink; font-size: 26rpx; font-weight: 700; text-align: right; }
.setting-row { display: flex; width: 100%; min-height: 104rpx; margin: 0; padding: 20rpx 0; align-items: center; justify-content: space-between; gap: 24rpx; color: $mobile-ink; background: transparent; border-radius: 0; line-height: 1.4; text-align: left; }
.setting-row + .setting-row, .setting-result + .setting-row { border-top: 1rpx solid $mobile-line; }
.setting-row::after { border: 0; }
.setting-row > view { display: flex; flex-direction: column; gap: 8rpx; }
.setting-row__title { color: $mobile-ink; font-size: 28rpx; font-weight: 700; }
.setting-row__description { color: $mobile-muted; font-size: 23rpx; }
.setting-row > text { color: $mobile-muted; font-size: 34rpx; }
.setting-result { display: block; padding: 16rpx 20rpx; color: $mobile-orange-dark; background: $mobile-orange-soft; border-radius: 14rpx; font-size: 23rpx; line-height: 1.55; }
.session-note { display: block; color: $mobile-muted; font-size: 24rpx; line-height: 1.65; }
.logout-button { min-height: 88rpx; margin: 28rpx 0 0; color: #9d3c35; background: #fff0ed; border-radius: 16rpx; font-size: 28rpx; font-weight: 700; line-height: 88rpx; }
.logout-button::after { border: 0; }
</style>
