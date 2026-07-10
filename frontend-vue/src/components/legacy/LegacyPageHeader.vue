<script setup lang="ts">
defineProps<{
  title: string
}>()
</script>

<template>
  <div class="legacy-page-head">
    <h2>{{ title }}</h2>
    <div class="legacy-head-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

