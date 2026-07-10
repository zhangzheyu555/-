<script setup lang="ts">
import StatusBadge from '../common/StatusBadge.vue'
import type { RoleTodoItem } from '../../api/todos'

defineProps<{
  items: RoleTodoItem[]
}>()
</script>

<template>
  <section class="content-card">
    <div class="table-heading">
      <div>
        <h3>已处理复盘</h3>
        <span>只回看运营处理结果，详细日志到操作日志查看。</span>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>事项</th>
            <th>来源</th>
            <th>门店</th>
            <th>月份</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td><b>{{ item.title }}</b><small>{{ item.summary }}</small></td>
            <td>{{ item.sourceModule || item.dataSource || '运营中心' }}</td>
            <td>{{ item.storeName || item.storeId || '全部门店' }}</td>
            <td>{{ item.month || '-' }}</td>
            <td><StatusBadge label="已处理" tone="ok" /></td>
          </tr>
          <tr v-if="!items.length">
            <td colspan="5" class="empty-cell">当前没有已处理运营事项。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
