<script setup lang="ts">
import { computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import AppCard from '@/components/AppCard.vue'
import StatePanel from '@/components/StatePanel.vue'
import { useContextStore, useMenuStore, useSessionStore } from '@/stores'
import type { MobileMenuItem } from '@/types/navigation'

const session = useSessionStore()
const menu = useMenuStore()
const context = useContextStore()
const storeNames = computed(() => context.stores.map((store) => store.name))
const currentStoreLabel = computed(() => context.currentStore?.name || session.user?.boundStoreName || '请选择当前门店')

onShow(initializeHome)

async function initializeHome(): Promise<void> {
  if (!session.user && !await session.restore()) {
    if (!session.error) uni.reLaunch({ url: '/pages/login/index' })
    return
  }
  menu.rebuild(session.user)
  if (!context.stores.length && !context.loading) await context.load(session.user)
}

function chooseStore(event: { detail: { value: string | number } }): void {
  const index = Number(event.detail.value)
  const store = context.stores[index]
  if (store) context.selectStore(store.id)
}

function openItem(item: MobileMenuItem): void {
  const storeId = context.currentStoreId
  const query = storeId ? `?storeId=${encodeURIComponent(storeId)}` : ''
  uni.navigateTo({ url: `${item.path}${query}` })
}

function openProfile(): void {
  uni.navigateTo({ url: '/pages/profile/index' })
}
</script>

<template>
  <view class="mobile-page home-page">
    <view class="work-pass">
      <view class="work-pass__rail" aria-hidden="true" />
      <view class="work-pass__body">
        <view class="work-pass__top">
          <view>
            <text class="work-pass__eyebrow">今日工作台</text>
            <text class="work-pass__name">{{ session.user?.displayName || '当前员工' }}</text>
          </view>
          <button class="profile-link" @click="openProfile">我的</button>
        </view>
        <view class="work-pass__facts">
          <view class="work-pass__fact">
            <text>身份</text>
            <text class="work-pass__value">{{ session.user?.roleLabel || '—' }}</text>
          </view>
          <view class="work-pass__fact">
            <text>数据范围</text>
            <text class="work-pass__value">{{ session.scopeLabel }}</text>
          </view>
        </view>
        <picker v-if="context.stores.length > 1" :range="storeNames" @change="chooseStore">
          <view class="store-picker tap-target">
            <view>
              <text class="store-picker__label">当前门店</text>
              <text class="store-picker__value">{{ currentStoreLabel }}</text>
            </view>
            <text class="store-picker__action">切换 ›</text>
          </view>
        </picker>
        <view v-else-if="currentStoreLabel" class="store-picker store-picker--locked">
          <view>
            <text class="store-picker__label">当前门店</text>
            <text class="store-picker__value">{{ currentStoreLabel }}</text>
          </view>
          <text class="store-picker__action">已按权限锁定</text>
        </view>
      </view>
    </view>

    <StatePanel
      v-if="session.error && !session.user"
      type="error"
      title="暂时无法连接工作台"
      :description="session.error"
      action-text="重新连接"
      @action="initializeHome"
    />

    <StatePanel
      v-else-if="!menu.groups.length"
      type="permission"
      title="暂无移动端功能"
      description="移动菜单按账号权限和数据范围生成。如需使用，请联系老板调整权限。"
    />

    <view v-else class="home-groups">
      <view v-for="group in menu.groups" :key="group.key" class="home-group">
        <text class="home-group__title">{{ group.title }}</text>
        <view class="menu-grid">
          <button
            v-for="item in group.items"
            :key="item.key"
            class="menu-card"
            :class="`menu-card--${item.tone}`"
            @click="openItem(item)"
          >
            <view class="menu-card__mark" aria-hidden="true" />
            <text class="menu-card__title">{{ item.label }}</text>
            <text class="menu-card__description">{{ item.description }}</text>
            <text class="menu-card__arrow" aria-hidden="true">→</text>
          </button>
        </view>
      </view>
    </view>

    <AppCard v-if="session.hasAnyPermission(['finance.profit.read', 'system.dashboard.read'])" compact>
      <view class="desktop-note">
        <text class="desktop-note__title">复杂操作请回桌面端</text>
        <text class="desktop-note__copy">经营录入、月度导入、权限管理、仓库配置和批量导出未开放手机操作。</text>
      </view>
    </AppCard>
  </view>
</template>

<style scoped lang="scss">
.home-page { display: flex; flex-direction: column; gap: 32rpx; }
.work-pass { display: flex; overflow: hidden; color: #ffffff; background: $mobile-ink; border-radius: 36rpx 36rpx 36rpx 12rpx; box-shadow: 0 24rpx 52rpx rgba(23, 48, 40, 0.18); }
.work-pass__rail { width: 14rpx; flex: 0 0 14rpx; background: linear-gradient(180deg, #45b983, #d8a141); }
.work-pass__body { width: 100%; padding: 34rpx 32rpx 30rpx; }
.work-pass__top { display: flex; align-items: flex-start; justify-content: space-between; gap: 24rpx; }
.work-pass__top > view { display: flex; flex-direction: column; gap: 8rpx; }
.work-pass__eyebrow { color: rgba(255,255,255,.62); font-size: 22rpx; font-weight: 700; letter-spacing: 3rpx; }
.work-pass__name { color: #ffffff; font-family: $mobile-font-display; font-size: 42rpx; font-weight: 800; }
.profile-link { min-width: 104rpx; min-height: 88rpx; margin: 0; padding: 0 22rpx; color: #ffffff; background: rgba(255,255,255,.12); border-radius: 18rpx; font-size: 25rpx; line-height: 88rpx; }
.profile-link::after, .menu-card::after { border: 0; }
.work-pass__facts { display: grid; margin: 34rpx 0 26rpx; grid-template-columns: 1fr 1fr; gap: 16rpx; }
.work-pass__fact { display: flex; flex-direction: column; gap: 8rpx; }
.work-pass__fact text { color: rgba(255,255,255,.58); font-size: 22rpx; }
.work-pass__value { color: #ffffff; font-size: 27rpx; font-weight: 650; }
.store-picker { display: flex; min-height: 96rpx; padding: 18rpx 22rpx; align-items: center; justify-content: space-between; gap: 20rpx; background: rgba(255,255,255,.09); border: 1rpx solid rgba(255,255,255,.12); border-radius: 20rpx; }
.store-picker > view { display: flex; min-width: 0; flex-direction: column; gap: 6rpx; }
.store-picker__label { color: rgba(255,255,255,.58); font-size: 21rpx; }
.store-picker__value { overflow: hidden; color: #ffffff; font-size: 28rpx; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }
.store-picker__action { color: #8be0b6; flex: 0 0 auto; font-size: 23rpx; }
.store-picker--locked { min-height: 88rpx; }
.home-groups { display: flex; flex-direction: column; gap: 36rpx; }
.home-group { display: flex; flex-direction: column; gap: 18rpx; }
.home-group__title { padding-left: 4rpx; color: $mobile-ink; font-size: 29rpx; font-weight: 800; }
.menu-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18rpx; }
.menu-card { position: relative; display: flex; min-height: 250rpx; margin: 0; padding: 28rpx; flex-direction: column; align-items: flex-start; color: $mobile-ink; background: #ffffff; border: 1rpx solid $mobile-line; border-radius: 28rpx; line-height: 1.4; text-align: left; box-shadow: 0 10rpx 26rpx rgba(23,48,40,.05); }
.menu-card__mark { width: 38rpx; height: 10rpx; margin-bottom: 26rpx; background: $mobile-green; border-radius: 99rpx; }
.menu-card--blue .menu-card__mark { background: $mobile-blue; }
.menu-card--orange .menu-card__mark { background: $mobile-orange; }
.menu-card--slate .menu-card__mark { background: #526863; }
.menu-card__title { color: $mobile-ink; font-size: 30rpx; font-weight: 800; }
.menu-card__description { margin-top: 10rpx; padding-right: 14rpx; color: $mobile-muted; font-size: 23rpx; line-height: 1.55; }
.menu-card__arrow { position: absolute; right: 24rpx; bottom: 20rpx; color: $mobile-green; font-size: 32rpx; font-weight: 700; }
.desktop-note { display: flex; flex-direction: column; gap: 8rpx; }
.desktop-note__title { color: $mobile-ink; font-size: 27rpx; font-weight: 750; }
.desktop-note__copy { color: $mobile-muted; font-size: 24rpx; line-height: 1.65; }
</style>
