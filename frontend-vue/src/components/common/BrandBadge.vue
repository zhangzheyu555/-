<script setup lang="ts">
import { computed } from 'vue'
import { getBrandTheme, normalizeBrandName } from '../../utils/brand'

const props = withDefaults(
  defineProps<{
    brandName?: string
    size?: 'sm' | 'md'
    solid?: boolean
  }>(),
  {
    brandName: '',
    size: 'sm',
    solid: false,
  },
)

const theme = computed(() => getBrandTheme(props.brandName))
const displayName = computed(() => normalizeBrandName(props.brandName) || '未分品牌')
</script>

<template>
  <span
    class="brand-badge"
    :class="[`size-${size}`, { solid }]"
    :style="{
      '--brand-main': theme.main,
      '--brand-dark': theme.dark,
      '--brand-soft': theme.soft,
    }"
  >
    <i />
    {{ displayName }}
  </span>
</template>

<style scoped>
.brand-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: fit-content;
  min-height: 24px;
  padding: 3px 10px;
  border: 1px solid color-mix(in srgb, var(--brand-main) 22%, transparent);
  border-radius: 999px;
  background: var(--brand-soft);
  color: var(--brand-main);
  font-size: 12px;
  font-weight: 900;
  line-height: 1.2;
  white-space: nowrap;
}

.brand-badge.size-md {
  min-height: 30px;
  padding: 5px 12px;
  font-size: 13px;
}

.brand-badge.solid {
  border-color: var(--brand-main);
  background: var(--brand-main);
  color: #fff;
}

.brand-badge i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}
</style>
