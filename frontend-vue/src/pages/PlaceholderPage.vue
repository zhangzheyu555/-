<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => String(route.meta.title || '模块占位'))
</script>

<template>
  <section class="page-panel">
    <div class="page-head">
      <div>
        <h2>{{ title }}</h2>
      </div>
    </div>
    <div class="empty-state">
      <b>暂无内容</b>
    </div>
  </section>
</template>
