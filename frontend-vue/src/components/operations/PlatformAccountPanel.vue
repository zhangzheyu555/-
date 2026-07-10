<script setup lang="ts">
import StatusBadge from '../common/StatusBadge.vue'
import type { PlatformAccountItem } from '../../stores/operations'

defineProps<{
  items: PlatformAccountItem[]
}>()

function tone(status: string) {
  if (status === '正常') return 'ok'
  if (status.includes('异常')) return 'bad'
  return 'warn'
}
</script>

<template>
  <section class="content-card">
    <div class="table-heading">
      <div>
        <h3>平台账号</h3>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>平台名称</th>
            <th>门店</th>
            <th>登录状态</th>
            <th>最近同步</th>
            <th>异常说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td><b>{{ item.platformName }}</b></td>
            <td>{{ item.storeName }}</td>
            <td><StatusBadge :label="item.loginStatus" :tone="tone(item.loginStatus)" /></td>
            <td>{{ item.syncedAt }}</td>
            <td>{{ item.issue }}</td>
          </tr>
          <tr v-if="!items.length">
            <td colspan="5" class="empty-cell">当前没有平台账号记录。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
