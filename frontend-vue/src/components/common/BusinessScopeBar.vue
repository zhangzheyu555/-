<script setup lang="ts">
import { computed } from 'vue'
import { AlertTriangle, MapPin } from 'lucide-vue-next'
import { useBusinessScope } from '../../composables/useBusinessScope'

export interface BusinessScopeOption {
  id: string | number
  name: string
  brandId?: string | number
  brandName?: string
}

const props = withDefaults(defineProps<{
  brands?: BusinessScopeOption[]
  stores?: BusinessScopeOption[]
  brandId?: string
  storeId?: string
  disabled?: boolean
  showManagerLabel?: boolean
  allowAll?: boolean
}>(), {
  brands: () => [],
  stores: () => [],
  brandId: '',
  storeId: '',
  disabled: false,
  showManagerLabel: true,
  allowAll: true,
})

const emit = defineEmits<{
  'update:brandId': [value: string]
  'update:storeId': [value: string]
}>()

const scope = useBusinessScope()
const visibleStores = computed(() => props.stores.filter((store) => {
  if (!props.brandId) return true
  return String(store.brandId ?? '') === props.brandId || store.brandName === selectedBrandName.value
}))
const selectedBrandName = computed(() => props.brands.find((brand) => String(brand.id) === props.brandId)?.name || '')

function updateBrand(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  emit('update:brandId', value)
  const nextBrandName = props.brands.find((brand) => String(brand.id) === value)?.name || ''
  if (props.storeId && !props.stores.some((store) => String(store.id) === props.storeId && (!value || String(store.brandId ?? '') === value || store.brandName === nextBrandName))) {
    emit('update:storeId', '')
  }
}
</script>

<template>
  <div v-if="scope.isStoreManager.value && scope.configurationError.value" class="business-scope-error" role="alert">
    <AlertTriangle :size="17" />
    <span>{{ scope.configurationError.value }}</span>
  </div>
  <div v-else-if="scope.isStoreManager.value && showManagerLabel" class="business-scope-static" aria-label="当前经营范围">
    <MapPin :size="16" />
    <strong>{{ scope.managerScopeLabel.value }}</strong>
  </div>
  <div v-else-if="!scope.isStoreManager.value" class="business-scope-controls" aria-label="经营范围">
    <label>
      <span>品牌</span>
      <select :value="brandId" :disabled="disabled" aria-label="品牌" @change="updateBrand">
        <option v-if="allowAll" value="">全部品牌</option>
        <option v-for="brand in brands" :key="String(brand.id)" :value="String(brand.id)">{{ brand.name }}</option>
      </select>
    </label>
    <label>
      <span>门店</span>
      <select :value="storeId" :disabled="disabled" aria-label="门店" @change="emit('update:storeId', ($event.target as HTMLSelectElement).value)">
        <option v-if="allowAll" value="">全部门店</option>
        <option v-for="store in visibleStores" :key="String(store.id)" :value="String(store.id)">
          {{ store.brandName ? `${store.brandName} · ` : '' }}{{ store.name }}
        </option>
      </select>
    </label>
  </div>
</template>

<style scoped>
.business-scope-controls,
.business-scope-static,
.business-scope-error {
  min-width: 0;
}

.business-scope-controls {
  display: flex;
  align-items: end;
  gap: 12px;
  flex-wrap: wrap;
}

.business-scope-controls label {
  display: grid;
  min-width: 154px;
  gap: 5px;
  color: var(--ds-muted);
  font-size: 12px;
  font-weight: 700;
}

.business-scope-controls select {
  width: 100%;
  min-width: 154px;
  min-height: 38px;
}

.business-scope-static,
.business-scope-error {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 6px;
  font-size: 14px;
}

.business-scope-static {
  border: 1px solid var(--ds-line);
  background: var(--ds-surface);
  color: var(--ds-secondary);
}

.business-scope-static svg {
  color: var(--ds-primary-hover);
}

.business-scope-error {
  width: 100%;
  border: 1px solid #efddb9;
  background: var(--ds-warning-soft);
  color: #87500f;
}

@media (max-width: 640px) {
  .business-scope-controls,
  .business-scope-controls label {
    width: 100%;
  }
}
</style>
