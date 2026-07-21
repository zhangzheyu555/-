<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  value: string | number
  unit?: string
  hint?: string
  tone?: 'brand' | 'success' | 'info' | 'danger' | 'neutral'
}>(), {
  unit: '',
  hint: '',
  tone: 'neutral',
})
</script>

<template>
  <view class="metric-card" :class="`metric-card--${tone}`">
    <text class="metric-card__label">{{ label }}</text>
    <view class="metric-card__number">
      <text class="metric-card__value">{{ value }}</text>
      <text v-if="unit" class="metric-card__unit">{{ unit }}</text>
    </view>
    <text v-if="hint" class="metric-card__hint">{{ hint }}</text>
  </view>
</template>

<style scoped lang="scss">
.metric-card {
  min-width: 0;
  min-height: 154rpx;
  padding: 22rpx;
  background: $mobile-surface;
  border: 1rpx solid $mobile-line;
  border-radius: 14rpx;
  box-shadow: 0 6rpx 18rpx rgba(30, 77, 71, 0.03);
}
.metric-card__label { display: block; color: $mobile-muted; font-size: 23rpx; }
.metric-card__number { display: flex; min-width: 0; margin-top: 10rpx; align-items: baseline; gap: 6rpx; }
.metric-card__value { overflow: hidden; color: $mobile-ink; font-size: 38rpx; font-weight: 800; text-overflow: ellipsis; white-space: nowrap; }
.metric-card__unit { color: $mobile-muted; font-size: 22rpx; }
.metric-card__hint { display: block; margin-top: 8rpx; color: $mobile-muted; font-size: 21rpx; }
.metric-card--brand { color: #ffffff; background: $mobile-orange; border-color: $mobile-orange; }
.metric-card--brand .metric-card__label,
.metric-card--brand .metric-card__unit,
.metric-card--brand .metric-card__hint,
.metric-card--brand .metric-card__value { color: #ffffff; }
.metric-card--success .metric-card__value { color: $mobile-green; }
.metric-card--info .metric-card__value { color: $mobile-blue; }
.metric-card--danger .metric-card__value { color: $mobile-red; }
</style>
