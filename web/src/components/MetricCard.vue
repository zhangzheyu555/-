<template>
  <article class="metric-card">
    <div class="metric-label">{{ label }}</div>
    <div class="metric-value">{{ value }}</div>
    <div class="metric-foot" :class="tone">{{ foot }}</div>
  </article>
</template>

<script setup lang="ts">
defineProps<{
  label: string;
  value: string;
  foot: string;
  tone: 'good' | 'warn' | 'bad' | 'info' | 'neutral';
}>();
</script>
