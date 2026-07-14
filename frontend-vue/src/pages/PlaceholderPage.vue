<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../components/common/PageHeader.vue'

const route = useRoute()
const title = computed(() => String(route.meta.title || '模块占位'))
</script>

<template>
  <section class="page-panel">
    <PageHeader :title="title" />
    <div class="empty-state">
      <b>暂无内容</b>
    </div>
  </section>
</template>
