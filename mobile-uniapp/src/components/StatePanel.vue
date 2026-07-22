<script setup lang="ts">
withDefaults(defineProps<{
  type?: 'loading' | 'empty' | 'error' | 'permission'
  title?: string
  description?: string
  actionText?: string
}>(), {
  type: 'empty',
  title: '',
  description: '',
  actionText: '',
})

defineEmits<{ action: [] }>()
</script>

<template>
  <view class="state-panel" :class="`state-panel--${type}`">
    <view class="state-panel__mark" aria-hidden="true">
      <text>{{ type === 'loading' ? '···' : type === 'error' ? '!' : type === 'permission' ? '×' : '—' }}</text>
    </view>
    <text class="state-panel__title">{{ title || (type === 'loading' ? '正在加载' : '暂无内容') }}</text>
    <text v-if="description" class="state-panel__description">{{ description }}</text>
    <button v-if="actionText" class="state-panel__action" @click="$emit('action')">{{ actionText }}</button>
  </view>
</template>

<style scoped lang="scss">
.state-panel {
  display: flex;
  min-height: 320rpx;
  padding: 48rpx 32rpx;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.state-panel__mark {
  display: flex;
  width: 84rpx;
  height: 84rpx;
  margin-bottom: 24rpx;
  align-items: center;
  justify-content: center;
  color: $mobile-green;
  background: $mobile-green-soft;
  border-radius: 30rpx 30rpx 30rpx 10rpx;
  font-size: 32rpx;
  font-weight: 800;
}

.state-panel--error .state-panel__mark,
.state-panel--permission .state-panel__mark { color: $mobile-orange; background: $mobile-orange-soft; }
.state-panel__title { color: $mobile-ink; font-size: 30rpx; font-weight: 700; }
.state-panel__description { max-width: 560rpx; margin-top: 12rpx; color: $mobile-muted; font-size: 26rpx; line-height: 1.65; }
.state-panel__action { min-height: 88rpx; margin-top: 28rpx; padding: 0 36rpx; color: #ffffff; background: $mobile-green; border-radius: 20rpx; font-size: 28rpx; line-height: 88rpx; }
.state-panel__action::after { border: 0; }
</style>
