<template>
  <div class="table-wrap">
    <table class="data-table">
      <thead>
        <tr>
          <th v-for="column in columns" :key="column.key" :class="{ right: column.align === 'right' }">
            {{ column.label }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="String(row[rowKey])">
          <td v-for="column in columns" :key="column.key" :class="{ right: column.align === 'right' }">
            <slot :name="column.key" :row="row">{{ row[column.key] }}</slot>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-if="!rows.length" class="empty-state">暂无数据</div>
  </div>
</template>

<script setup lang="ts">
export interface TableColumn {
  key: string;
  label: string;
  align?: 'left' | 'right';
}

defineProps<{
  columns: TableColumn[];
  rows: Record<string, unknown>[];
  rowKey: string;
}>();
</script>
