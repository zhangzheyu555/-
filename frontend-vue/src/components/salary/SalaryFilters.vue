<script setup lang="ts">
import type { StoreInfo } from '../../api/operations'
import SearchInput from '../common/SearchInput.vue'

defineProps<{
  selectedMonth: string
  selectedStoreId: string
  selectedBrandId?: number
  statusFilter: string
  keyword: string
  stores: StoreInfo[]
  accessibleStores: StoreInfo[]
  brands: Array<{ id: number; name: string }>
  storesLoading: boolean
  authRole: string
  statusOptions: Array<{ value: string; label: string }>
}>()

const emit = defineEmits<{
  'update:selectedMonth': [value: string]
  'update:selectedStoreId': [value: string]
  'update:selectedBrandId': [value: number | undefined]
  'update:statusFilter': [value: string]
  'update:keyword': [value: string]
}>()
</script>

<template>
  <section class="content-card filter-row" :class="{ 'filter-row--single-store': authRole === 'STORE_MANAGER' }">
    <label>月份 <input type="month" :value="selectedMonth" @input="emit('update:selectedMonth', ($event.target as HTMLInputElement).value)" /></label>
    <label v-if="authRole !== 'STORE_MANAGER'">品牌 <select :value="selectedBrandId ?? ''" @change="emit('update:selectedBrandId', ($event.target as HTMLSelectElement).value ? Number(($event.target as HTMLSelectElement).value) : undefined)">
      <option value="">全部品牌</option><option v-for="brand in brands" :key="brand.id" :value="brand.id">{{ brand.name }}</option>
    </select></label>
    <label v-if="authRole !== 'STORE_MANAGER'">门店 <select :value="selectedStoreId" :disabled="storesLoading" @change="emit('update:selectedStoreId', ($event.target as HTMLSelectElement).value)">
        <option value="all">全部门店</option>
        <option v-for="s in accessibleStores" :key="s.id" :value="s.id">{{ s.name }}</option>
      </select></label>
    <label>状态 <select :value="statusFilter" @change="emit('update:statusFilter', ($event.target as HTMLSelectElement).value)">
        <option v-for="opt in statusOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
      </select></label>
    <SearchInput
      :model-value="keyword"
      placeholder="搜索姓名、工号或岗位"
      aria-label="搜索工资记录"
      @update:model-value="emit('update:keyword', $event)"
    />
  </section>
</template>

<style scoped>
.filter-row { display: grid; grid-template-columns: 140px 170px 210px 140px minmax(200px, 1fr); align-items: end; gap: 12px; padding: 16px; }
.filter-row--single-store { grid-template-columns: 140px 140px minmax(220px, 1fr); }
.filter-row > label:not(.search-field) { display: grid; gap: 6px; color: var(--muted); font-size: 12px; font-weight: 900; }
.filter-row select, .filter-row input { min-height: 38px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 10px; background: #fff; color: var(--ink); font-size: 13px; font-weight: 800; }

@media (max-width: 1080px) { .filter-row { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) { .filter-row { grid-template-columns: 1fr; width: 100%; } }
</style>
