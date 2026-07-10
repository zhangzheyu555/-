<script setup lang="ts">
import StatusBadge from '../common/StatusBadge.vue'
import type { OperationsImportItem } from '../../stores/operations'

defineProps<{
  items: OperationsImportItem[]
}>()

function tone(status: string) {
  if (status.includes('异常') || status.includes('失败')) return 'bad'
  if (status.includes('待') || status.includes('检查')) return 'warn'
  if (status.includes('已')) return 'ok'
  return 'muted'
}
</script>

<template>
  <section class="content-card">
    <div class="table-heading">
      <div>
        <h3>数据导入</h3>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>导入类型</th>
            <th>文件/来源</th>
            <th>月份</th>
            <th>门店</th>
            <th>状态</th>
            <th>错误说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td><b>{{ item.importType }}</b></td>
            <td>{{ item.source }}</td>
            <td>{{ item.month || '-' }}</td>
            <td>{{ item.storeName || '全部门店' }}</td>
            <td><StatusBadge :label="item.status" :tone="tone(item.status)" /></td>
            <td>{{ item.error || '-' }}</td>
          </tr>
          <tr v-if="!items.length">
            <td colspan="6" class="empty-cell">当前没有数据导入提醒。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
