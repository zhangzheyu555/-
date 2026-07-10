<script setup lang="ts">
import { computed } from 'vue'
import type { WarehouseItem, WarehouseItemCategory } from '../../api/warehouse'

const props = defineProps<{
  categories: WarehouseItemCategory[]
  items: WarehouseItem[]
  selected: string
}>()

const emit = defineEmits<{
  select: [value: string]
}>()

const defaultCategories = ['器材1', '器材2', '水果', '包装', '茶叶', '抹布+工作服', '奶制品']

interface CategoryRow {
  value: string
  name: string
  count: number
  depth: number
}

function flatten(categories: WarehouseItemCategory[], depth = 0): CategoryRow[] {
  return categories
    .filter((category) => category.enabled !== false)
    .flatMap((category) => [
      {
        value: `id:${category.id}`,
        name: category.name,
        count: props.items.filter((item) => String(item.categoryId || '') === String(category.id)).length,
        depth,
      },
      ...flatten(category.children || [], depth + 1),
    ])
}

const rows = computed<CategoryRow[]>(() => {
  const categoryRows = flatten(props.categories)
  if (categoryRows.length) {
    return [{ value: 'all', name: '全部类别', count: props.items.length, depth: 0 }, ...categoryRows]
  }
  return [
    { value: 'all', name: '全部类别', count: props.items.length, depth: 0 },
    ...defaultCategories.map((name) => ({
      value: `name:${name}`,
      name,
      count: props.items.filter((item) => (item.categoryName || item.category || '未分类') === name).length,
      depth: 0,
    })),
  ]
})
</script>

<template>
  <div class="side-card category-filter">
    <div class="section-title compact">
      <h3>商品分类</h3>
    </div>
    <button
      v-for="row in rows"
      :key="row.value"
      class="category-button"
      :class="{ active: selected === row.value }"
      type="button"
      @click="emit('select', row.value)"
    >
      <span :style="{ paddingLeft: `${row.depth * 12}px` }">{{ row.name }}</span>
      <b>{{ row.count }}</b>
    </button>
  </div>
</template>
