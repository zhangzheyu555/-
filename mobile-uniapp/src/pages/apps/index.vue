<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import PageHeader from '@/components/PageHeader.vue'
import { useMenuStore, useSessionStore } from '@/stores'
import { createEdgeSwipeToHomeHandlers } from '@/platform/edgeSwipeHome'
import type { MobileMenuItem } from '@/types/navigation'

const session = useSessionStore()
const menu = useMenuStore()
const { onTouchStart, onTouchEnd } = createEdgeSwipeToHomeHandlers()

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
  <view class="mobile-page apps-page" @touchstart="onTouchStart" @touchend="onTouchEnd">
    <PageHeader eyebrow="按账号权限开放" title="全部应用" description="只显示当前角色和数据范围内可使用的移动功能" />
    <view v-if="!menu.groups.length" class="state">当前账号暂无可用移动应用。</view>
    <view v-for="group in menu.groups" :key="group.key" class="group">
      <text class="group-title">{{ group.title }}</text>
      <view class="app-grid">
        <button v-for="item in group.items" :key="item.key" class="app mobile-feedback" @click="open(item)">
          <view class="app-icon" :class="`app-icon--${item.tone}`">{{ item.icon }}</view>
          <text class="app-title">{{ item.label }}</text>
          <text class="app-desc">{{ item.description }}</text>
          <text class="arrow">›</text>
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.apps-page { display: flex; flex-direction: column; gap: 28rpx; }
.group { display: flex; flex-direction: column; gap: 16rpx; }
.group-title { font-size: 29rpx; font-weight: 800; }
.app-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16rpx; }
.app { position: relative; display: flex; min-height: 222rpx; margin: 0; padding: 22rpx; flex-direction: column; align-items: flex-start; text-align: left; background: #fff; border: 1rpx solid $mobile-line; border-radius: 16rpx; box-shadow: 0 8rpx 22rpx rgba(37,39,45,.04); }
.app::after { border: 0; }
.app-icon { display: flex; width: 58rpx; height: 58rpx; margin-bottom: 16rpx; align-items: center; justify-content: center; color: $mobile-green; background: $mobile-green-soft; border-radius: 14rpx; font-size: 29rpx; font-weight: 800; }
.app-icon--orange { color: $mobile-orange-dark; background: $mobile-orange-soft; }
.app-icon--blue { color: $mobile-blue; background: $mobile-blue-soft; }
.app-icon--slate { color: #59606b; background: #eef0f3; }
.app-title, .app-desc { display: block; }
.app-title { color: $mobile-ink; font-size: 28rpx; font-weight: 800; }
.app-desc { margin-top: 7rpx; padding-right: 14rpx; color: $mobile-muted; font-size: 22rpx; line-height: 1.5; }
.arrow { position: absolute; right: 20rpx; bottom: 16rpx; color: $mobile-orange; font-size: 38rpx; }
.state { padding: 48rpx 24rpx; color: $mobile-muted; text-align: center; background: #fff; border-radius: 16rpx; }
</style>
