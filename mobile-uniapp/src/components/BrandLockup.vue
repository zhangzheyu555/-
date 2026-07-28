<script setup lang="ts">
withDefaults(defineProps<{
  compact?: boolean
}>(), {
  compact: false,
})
</script>

<template>
  <view
    class="brand-lockup"
    :class="{ 'brand-lockup--compact': compact }"
    :aria-label="compact ? '茹菓 ifnot 品牌标识' : '茹菓品牌标识'"
  >
    <view class="brand-lockup__mark" aria-hidden="true"><text>茹</text></view>
    <view class="brand-lockup__copy">
      <text class="brand-lockup__name">茹菓*</text>
      <text v-if="compact" class="brand-lockup__alias">ifnot*</text>
    </view>
  </view>
</template>

<style scoped lang="scss">
.brand-lockup { display: flex; align-items: center; gap: 16rpx; }
.brand-lockup__mark { display: flex; width: 48rpx; height: 48rpx; align-items: center; justify-content: center; color: #fff; background: #71bcb4; border-radius: 12rpx; font-size: 26rpx; font-weight: 800; }
.brand-lockup__copy { display: flex; flex-direction: column; align-items: flex-start; }
.brand-lockup__name { color: $mobile-ink; font-size: 38rpx; font-weight: 900; }
.brand-lockup--compact { gap: 10rpx; }
.brand-lockup--compact .brand-lockup__copy { line-height: 1; }
.brand-lockup--compact .brand-lockup__name { font-size: 25rpx; }
.brand-lockup__alias { margin-top: 7rpx; color: $mobile-orange; font-size: 18rpx; font-weight: 750; letter-spacing: 1rpx; }
</style>
