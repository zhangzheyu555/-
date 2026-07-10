<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  hint?: string
}>()
</script>

<template>
  <article class="legacy-card legacy-kpi">
    <div class="lab">{{ label }}</div>
    <div class="val">{{ value }}</div>
    <small v-if="hint">{{ hint }}</small>
  </article>
</template>

