<script setup lang="ts">
defineProps<{
  label: string
  tone?: 'ok' | 'warn' | 'bad' | 'info' | 'muted'
}>()
</script>

<template>
  <span class="status-badge" :class="tone || 'muted'">{{ label }}</span>
</template>
