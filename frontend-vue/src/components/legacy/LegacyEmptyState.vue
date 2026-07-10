<script setup lang="ts">
defineProps<{
  title?: string
  text?: string
}>()
</script>

<template>
  <div class="legacy-empty">
    <b v-if="title">{{ title }}</b>
    <span>{{ text || '暂无数据' }}</span>
  </div>
</template>

