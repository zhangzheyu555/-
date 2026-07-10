<script setup lang="ts">
import StatusBadge from '../common/StatusBadge.vue'
import type { OperationsHealthItem } from '../../stores/operations'

defineProps<{
  items: OperationsHealthItem[]
}>()

function tone(status: string) {
  if (status === '正常' || status === '已处理') return 'ok'
  if (status === '需要检查') return 'warn'
  if (status === '数据异常') return 'bad'
  return 'muted'
}
</script>

<template>
  <section class="content-card">
    <div class="table-heading">
      <div>
        <h3>数据健康</h3>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>检查项</th>
            <th>当前状态</th>
            <th>影响范围</th>
            <th>最近检查时间</th>
            <th>建议动作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td><b>{{ item.name }}</b></td>
            <td><StatusBadge :label="item.status" :tone="tone(item.status)" /></td>
            <td>{{ item.scope }}</td>
            <td>{{ item.checkedAt }}</td>
            <td>{{ item.action }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
