<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { useMenuStore, useSessionStore } from '@/stores'
import type { MobileMenuItem } from '@/types/navigation'

const session = useSessionStore()
const menu = useMenuStore()

onShow(() => { void initialize() })
onPullDownRefresh(async () => {
  await initialize()
  uni.stopPullDownRefresh()
})

async function initialize() {
  if (!session.user && !await session.restore()) {
    uni.reLaunch({ url: '/pages/login/index' })
    return
  }
  menu.rebuild(session.user)
}

function open(item: MobileMenuItem) {
  uni.navigateTo({ url: item.path })
}
</script>

<template>
  <view class="page">
    <view class="head"><text class="eyebrow">按权限开放</text><text class="title">应用</text></view>
    <view v-if="!menu.groups.length" class="state">当前账号暂无可用移动应用。</view>
    <view v-for="group in menu.groups" :key="group.key" class="group">
      <text class="group-title">{{ group.title }}</text>
      <button v-for="item in group.items" :key="item.key" class="app" @click="open(item)">
        <view><text class="app-title">{{ item.label }}</text><text class="app-desc">{{ item.description }}</text></view>
        <text class="arrow">›</text>
      </button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; box-sizing: border-box; padding: 28rpx 24rpx calc(48rpx + env(safe-area-inset-bottom)); background: #f4f6f2; color: #172019; }
.head, .group { display: flex; flex-direction: column; }.head { margin-bottom: 32rpx; }.eyebrow { color: #657168; font-size: 23rpx; letter-spacing: 2rpx; }.title { margin-top: 6rpx; font-size: 46rpx; font-weight: 750; }.group { gap: 14rpx; margin-bottom: 32rpx; }.group-title { font-size: 30rpx; font-weight: 700; }.app { display: flex; min-height: 96rpx; padding: 22rpx 24rpx; align-items: center; justify-content: space-between; text-align: left; background: #fff; border: 1rpx solid #dce2db; border-radius: 20rpx; }.app::after { border: 0; }.app-title, .app-desc { display: block; }.app-title { font-size: 28rpx; font-weight: 700; }.app-desc { margin-top: 6rpx; color: #68756d; font-size: 23rpx; }.arrow { color: #1f6741; font-size: 42rpx; }.state { padding: 48rpx 24rpx; color: #657168; text-align: center; background: #fff; border-radius: 22rpx; }
</style>
