<script setup lang="ts">
import { computed } from 'vue'
import BrandBadge from './BrandBadge.vue'
import { displayBrandName, getBrandIdLike, type BrandLike } from '../../utils/brand'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    brands: BrandLike[]
    allowAll?: boolean
    disabled?: boolean
    ariaLabel?: string
  }>(),
  {
    modelValue: '',
    allowAll: true,
    disabled: false,
    ariaLabel: '品牌',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
}>()

const options = computed(() => {
  const seen = new Set<string>()
  return props.brands
    .map((brand) => ({
      value: getBrandIdLike(brand),
      label: displayBrandName(brand),
    }))
    .filter((brand) => {
      if (!brand.value || !brand.label || seen.has(brand.value)) return false
      seen.add(brand.value)
      return true
    })
})

const selectedLabel = computed(() => {
  if (!props.modelValue) return '全部品牌'
  return options.value.find((brand) => brand.value === props.modelValue)?.label || displayBrandName(props.modelValue)
})

function update(value: string) {
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<template>
  <div class="brand-select-wrap">
    <select :value="modelValue" :disabled="disabled" :aria-label="ariaLabel" @change="update(($event.target as HTMLSelectElement).value)">
      <option v-if="allowAll" value="">全部品牌</option>
      <option v-for="brand in options" :key="brand.value" :value="brand.value">{{ brand.label }}</option>
    </select>
    <BrandBadge v-if="modelValue" :brand-name="selectedLabel" />
  </div>
</template>

<style scoped>
.brand-select-wrap {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.brand-select-wrap select {
  min-width: 128px;
}

@media (max-width: 720px) {
  .brand-select-wrap,
  .brand-select-wrap select {
    width: 100%;
  }
}
</style>
