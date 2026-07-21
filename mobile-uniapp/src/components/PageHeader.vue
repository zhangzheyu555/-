<script setup lang="ts">
withDefaults(defineProps<{
  eyebrow?: string
  title: string
  description?: string
}>(), {
  eyebrow: '',
  description: '',
})
</script>

<template>
  <view class="page-header">
    <view class="page-header__copy">
      <text v-if="eyebrow" class="page-header__eyebrow">{{ eyebrow }}</text>
      <text class="page-header__title">{{ title }}</text>
      <text v-if="description" class="page-header__description">{{ description }}</text>
    </view>
    <slot name="action" />
  </view>
</template>

<style scoped lang="scss">
.page-header { display: flex; min-height: 92rpx; align-items: flex-start; justify-content: space-between; gap: 24rpx; }
.page-header__copy { display: flex; min-width: 0; flex-direction: column; gap: 6rpx; }
.page-header__eyebrow { color: $mobile-orange; font-size: 22rpx; font-weight: 700; }
.page-header__title { color: $mobile-ink; font-size: 38rpx; font-weight: 850; line-height: 1.25; }
.page-header__description { color: $mobile-muted; font-size: 24rpx; line-height: 1.55; }
</style>
