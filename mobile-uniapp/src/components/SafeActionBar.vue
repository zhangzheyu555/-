<script setup lang="ts">
import { useKeyboardInset } from '@/platform'

withDefaults(defineProps<{
  primaryText?: string
  secondaryText?: string
  disabled?: boolean
  loading?: boolean
}>(), {
  primaryText: '确认',
  secondaryText: '',
  disabled: false,
  loading: false,
})

defineEmits<{ primary: []; secondary: [] }>()
const { keyboardStyle } = useKeyboardInset()
</script>

<template>
  <view class="safe-action-placeholder" aria-hidden="true" />
  <view class="safe-action-bar" :style="keyboardStyle">
    <view class="safe-action-bar__inner">
      <slot>
        <button v-if="secondaryText" class="safe-action-bar__button safe-action-bar__button--secondary" @click="$emit('secondary')">
          {{ secondaryText }}
        </button>
        <button
          class="safe-action-bar__button safe-action-bar__button--primary"
          :disabled="disabled || loading"
          :loading="loading"
          @click="$emit('primary')"
        >
          {{ primaryText }}
        </button>
      </slot>
    </view>
  </view>
</template>

<style scoped lang="scss">
.safe-action-placeholder { height: calc(136rpx + env(safe-area-inset-bottom)); flex-shrink: 0; }
.safe-action-bar {
  position: fixed;
  z-index: 50;
  right: 0;
  bottom: 0;
  left: 0;
  padding: 20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.96);
  border-top: 1rpx solid $mobile-line;
  transition: transform 120ms ease-out;
}
.safe-action-bar__inner { display: flex; width: min(100%, 960rpx); margin: 0 auto; gap: 16rpx; min-width: 0; }
.safe-action-bar__button { min-height: 88rpx; margin: 0; padding: 0 28rpx; border-radius: 16rpx; font-size: 28rpx; font-weight: 700; line-height: 1.25; }
.safe-action-bar__button::after { border: 0; }
.safe-action-bar__button--secondary { flex: 0 0 auto; color: $mobile-ink; background: $mobile-surface-strong; }
.safe-action-bar__button--primary { flex: 1; color: #ffffff; background: $mobile-orange; }
.safe-action-bar__button[disabled] { opacity: 0.52; }
</style>
