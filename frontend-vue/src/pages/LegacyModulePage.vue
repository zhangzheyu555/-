<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => String(route.meta.title || '旧版功能'))
</script>

<template>
  <section class="page-panel legacy-module-page">
    <div class="page-head">
      <div>
        <h2>{{ title }}</h2>
      </div>
    </div>
    <section class="content-card">
      <div class="empty-state">
        <b>暂无内容</b>
      </div>
    </section>
  </section>
</template>

<style scoped>
.legacy-module-page {
  display: grid;
  gap: 18px;
}

</style>
