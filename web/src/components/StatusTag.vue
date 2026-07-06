<template>
  <span class="status-tag" :class="tone">{{ label }}</span>
</template>

<script setup lang="ts">
defineProps<{
  label: string;
  tone: 'good' | 'warn' | 'bad' | 'info' | 'neutral';
}>();
</script>
