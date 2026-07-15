<script setup lang="ts">
withDefaults(defineProps<{
  title?: string
  eyebrow?: string
  compact?: boolean
}>(), {
  title: '',
  eyebrow: '',
  compact: false,
})
</script>

<template>
  <view class="app-card" :class="{ 'app-card--compact': compact }">
    <view v-if="eyebrow || title || $slots.action" class="app-card__head">
      <view class="app-card__heading">
        <text v-if="eyebrow" class="app-card__eyebrow">{{ eyebrow }}</text>
        <text v-if="title" class="app-card__title">{{ title }}</text>
      </view>
      <slot name="action" />
    </view>
    <slot />
  </view>
</template>

<style scoped lang="scss">
.app-card {
  padding: 32rpx;
  background: #ffffff;
  border: 1rpx solid $mobile-line;
  border-radius: 28rpx;
  box-shadow: 0 12rpx 32rpx rgba(26, 55, 43, 0.06);
}

.app-card--compact { padding: 24rpx; }

.app-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20rpx;
  margin-bottom: 24rpx;
}

.app-card__heading { display: flex; flex-direction: column; gap: 6rpx; }
.app-card__eyebrow { color: $mobile-green; font-size: 22rpx; font-weight: 700; letter-spacing: 2rpx; }
.app-card__title { color: $mobile-ink; font-size: 32rpx; font-weight: 700; line-height: 1.35; }
</style>

