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
  padding: 28rpx;
  background: #ffffff;
  border: 1rpx solid $mobile-line;
  border-radius: 14rpx;
  box-shadow: 0 6rpx 18rpx rgba(30, 77, 71, 0.035);
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
.app-card__eyebrow { color: $mobile-orange; font-size: 22rpx; font-weight: 700; letter-spacing: 0; }
.app-card__title { color: $mobile-ink; font-size: 32rpx; font-weight: 700; line-height: 1.35; }
</style>
