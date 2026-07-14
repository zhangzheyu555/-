<script setup lang="ts">
import { ExternalLink } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'

const platforms = [
  { name: '美团', status: '未配置' },
  { name: '饿了么', status: '正常' },
  { name: '抖音', status: '未配置' },
  { name: '京东', status: '未配置' },
]
</script>

<template>
  <section class="page-panel platform-page">
    <PageHeader />

    <div class="platform-grid">
      <article v-for="platform in platforms" :key="platform.name" class="content-card platform-card">
        <ExternalLink :size="22" />
        <h3>{{ platform.name }}</h3>
        <span class="status-badge" :class="platform.status === '正常' ? 'ok' : 'warn'">{{ platform.status }}</span>
      </article>
    </div>
  </section>
</template>

<style scoped>
.platform-page {
  display: grid;
  gap: 18px;
}

.platform-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.platform-card {
  display: grid;
  align-content: start;
  gap: 10px;
}

.platform-card h3 {
  margin: 0;
}

@media (max-width: 1000px) {
  .platform-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .platform-grid {
    grid-template-columns: 1fr;
  }
}
</style>
