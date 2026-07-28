<script setup lang="ts">
import { computed } from 'vue'
import type { WarehouseRequisitionLine } from '@/types/business'

const props = defineProps<{
  lines: WarehouseRequisitionLine[]
  prices: Record<number, string>
  disabled: boolean
}>()

const emit = defineEmits<{
  updatePrice: [itemId: number, value: string]
}>()

const totalAmount = computed(() => props.lines.reduce((sum, line) => {
  const price = Number(props.prices[line.itemId])
  return sum + Number(line.requestedQuantity || 0) * (Number.isFinite(price) ? price : 0)
}, 0))

function onPriceInput(itemId: number, event: unknown) {
  const value = String((event as { detail?: { value?: string } })?.detail?.value ?? '')
  emit('updatePrice', itemId, value)
}

function priceError(value: string | undefined) {
  const normalized = String(value ?? '').trim()
  const price = Number(normalized)
  if (price < 0) return '价格不能小于 0'
  if (!normalized || !/^\d+(?:\.\d{0,2})?$/.test(normalized) || !Number.isFinite(price)) {
    return '请输入最多两位小数的有效价格'
  }
  if (price > 999999999999.99) return '价格超出可填写范围'
  return ''
}

function money(value: number) {
  return `¥${value.toFixed(2)}`
}
</script>

<template>
  <view class="price-editor">
    <view class="price-head">
      <view>
        <text class="price-title">审核单价</text>
        <text class="price-copy">审核通过后将按申请数量同步扣减仓库、增加门店库存</text>
      </view>
      <view class="total">
        <text>调整后总额</text>
        <text>{{ money(totalAmount) }}</text>
      </view>
    </view>
    <view v-for="line in lines" :key="line.itemId" class="price-line">
      <view class="item-copy">
        <text class="item-name">{{ line.itemName }}</text>
        <text class="item-meta">申请 {{ line.requestedQuantity }} {{ line.unit || '' }}</text>
      </view>
      <view class="price-field">
        <view class="price-input-wrap">
          <text>¥</text>
          <input
            :value="prices[line.itemId]"
            type="digit"
            inputmode="decimal"
            :disabled="disabled"
            aria-label="审核单价"
            @input="onPriceInput(line.itemId, $event)"
          >
        </view>
        <text v-if="priceError(prices[line.itemId])" class="price-error">
          {{ priceError(prices[line.itemId]) }}
        </text>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.price-editor{margin-top:16rpx;padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.price-head,.price-line{display:flex;align-items:flex-start;justify-content:space-between;gap:20rpx}.price-title,.price-copy,.total text,.item-name,.item-meta,.price-error{display:block}.price-title{font-size:28rpx;font-weight:750}.price-copy{max-width:420rpx;margin-top:7rpx;color:#71807d;font-size:22rpx;line-height:1.5}.total{flex:0 0 auto;text-align:right}.total text:first-child{color:#71807d;font-size:20rpx}.total text:last-child{margin-top:5rpx;color:#27655f;font-size:30rpx;font-weight:800}.price-line{margin-top:20rpx;padding-top:18rpx;border-top:1rpx solid #edf0f3}.item-copy{min-width:0;flex:1}.item-name{font-size:26rpx;font-weight:700}.item-meta{margin-top:6rpx;color:#71807d;font-size:22rpx}.price-field{width:230rpx}.price-input-wrap{display:flex;align-items:center;gap:6rpx;padding:13rpx 15rpx;background:#f4f8f7;border:1rpx solid #cfe0dc;border-radius:11rpx;color:#27655f}.price-input-wrap input{min-width:0;height:42rpx;flex:1;text-align:right;font-size:26rpx}.price-error{margin-top:6rpx;color:#a25145;font-size:20rpx;text-align:right}
</style>
