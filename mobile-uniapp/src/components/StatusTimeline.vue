<script setup lang="ts">
export interface TimelineItem { label: string; time?: string; done?: boolean }
defineProps<{ items: TimelineItem[] }>()
</script>

<template>
  <view class="timeline">
    <view v-for="(item, index) in items" :key="`${item.label}-${index}`" class="event">
      <view class="rail"><text :class="['dot', { done: item.done }]" /><text v-if="index < items.length - 1" class="line" /></view>
      <view><text class="label">{{ item.label }}</text><text v-if="item.time" class="time">{{ item.time }}</text></view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.event { display: flex; min-height: 72rpx; gap: 16rpx; }
.rail { display: flex; width: 20rpx; align-items: center; flex-direction: column; }
.dot { width: 16rpx; height: 16rpx; margin-top: 8rpx; background: #cddbd8; border-radius: 50%; }
.dot.done { background: #27655f; }
.line { width: 2rpx; flex: 1; background: #dce9e6; }
.label, .time { display: block; }
.label { font-size: 25rpx; }
.time { margin-top: 5rpx; color: #71807d; font-size: 22rpx; }
</style>
