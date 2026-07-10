<script setup lang="ts">
import { Search } from 'lucide-vue-next'
import type { StoreInfo } from '../../api/operations'

defineProps<{
  selectedMonth: string
  selectedStoreId: string
  statusFilter: string
  keyword: string
  stores: StoreInfo[]
  accessibleStores: StoreInfo[]
  storesLoading: boolean
  authRole: string
  statusOptions: Array<{ value: string; label: string }>
}>()

const emit = defineEmits<{
  'update:selectedMonth': [value: string]
  'update:selectedStoreId': [value: string]
  'update:statusFilter': [value: string]
  'update:keyword': [value: string]
}>()
</script>

<template>
  <section class="content-card filter-row">
    <label>月份 <input type="month" :value="selectedMonth" @input="emit('update:selectedMonth', ($event.target as HTMLInputElement).value)" /></label>
    <label>门店 <select :value="selectedStoreId" :disabled="storesLoading || authRole === 'STORE_MANAGER'" @change="emit('update:selectedStoreId', ($event.target as HTMLSelectElement).value)">
        <option v-if="authRole !== 'STORE_MANAGER'" value="all">全部门店</option>
        <option v-for="s in accessibleStores" :key="s.id" :value="s.id">{{ s.name }}</option>
      </select></label>
    <label>状态 <select :value="statusFilter" @change="emit('update:statusFilter', ($event.target as HTMLSelectElement).value)">
        <option v-for="opt in statusOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
      </select></label>
    <label class="search-field"><Search :size="16" /><input :value="keyword" type="search" placeholder="姓名、工号、岗位" @input="emit('update:keyword', ($event.target as HTMLInputElement).value)" /></label>
  </section>
</template>

<style scoped>
.filter-row { display: grid; grid-template-columns: 150px 220px 140px minmax(200px, 1fr); align-items: end; gap: 12px; padding: 16px; }
.filter-row label { display: grid; gap: 6px; color: var(--muted); font-size: 12px; font-weight: 900; }
.filter-row select, .filter-row input { min-height: 38px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 10px; background: #fff; color: var(--ink); font-size: 13px; font-weight: 800; }
.search-field { display: flex; align-items: center; gap: 7px; min-height: 38px; padding: 0 10px; border: 1px solid var(--line); border-radius: 10px; background: #fff; }
.search-field input { min-height: auto; width: 100%; padding: 0; border: 0; outline: 0; }

@media (max-width: 1080px) { .filter-row { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) { .filter-row { grid-template-columns: 1fr; width: 100%; } }
</style>
