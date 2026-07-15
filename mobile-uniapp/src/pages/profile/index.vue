<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import AppCard from '@/components/AppCard.vue'
import StatusChip from '@/components/StatusChip.vue'
import { checkVersion, promptVersionUpdate, requestNotification, runtimePlatform } from '@/platform'
import { useContextStore, useMenuStore, useSessionStore } from '@/stores'

const session = useSessionStore()
const menu = useMenuStore()
const context = useContextStore()
const versionMessage = ref('')
const notificationMessage = ref('')
const platformLabels = { h5: 'H5 移动网页', 'mp-weixin': '微信小程序', app: '原生 App' }

onShow(async () => {
  if (!session.user && !await session.restore()) uni.reLaunch({ url: '/pages/login/index' })
})

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
    <AppCard eyebrow="登录身份" :title="session.user?.displayName || '我的账号'">
      <view class="identity-list">
        <view class="identity-row"><text>角色</text><text class="identity-row__value">{{ session.user?.roleLabel || '—' }}</text></view>
        <view class="identity-row"><text>组织</text><text class="identity-row__value">{{ session.user?.tenantName || '—' }}</text></view>
        <view class="identity-row"><text>门店范围</text><text class="identity-row__value">{{ session.scopeLabel }}</text></view>
        <view class="identity-row"><text>当前入口</text><StatusChip :label="platformLabels[runtimePlatform()]" tone="info" /></view>
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
.setting-result { display: block; padding: 16rpx 20rpx; color: $mobile-green; background: $mobile-green-soft; border-radius: 14rpx; font-size: 23rpx; line-height: 1.55; }
.session-note { display: block; color: $mobile-muted; font-size: 24rpx; line-height: 1.65; }
.logout-button { min-height: 88rpx; margin: 28rpx 0 0; color: #9d3c35; background: #fff0ed; border-radius: 20rpx; font-size: 28rpx; font-weight: 700; line-height: 88rpx; }
.logout-button::after { border: 0; }
</style>
