<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  tone?: 'success' | 'warning' | 'info' | 'neutral'
}>(), { tone: 'neutral' })
</script>

<template>
  <text class="status-chip" :class="`status-chip--${tone}`">{{ label }}</text>
</template>

<style scoped lang="scss">
.status-chip { display: inline-flex; min-height: 44rpx; padding: 4rpx 16rpx; align-items: center; border-radius: 999rpx; font-size: 22rpx; font-weight: 700; line-height: 36rpx; }
.status-chip--success { color: $mobile-green; background: $mobile-green-soft; }
.status-chip--warning { color: $mobile-orange; background: $mobile-orange-soft; }
.status-chip--info { color: $mobile-blue; background: $mobile-blue-soft; }
.status-chip--neutral { color: $mobile-muted; background: $mobile-surface-strong; }
</style>

