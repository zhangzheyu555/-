<script setup lang="ts">
import { Download } from 'lucide-vue-next'

defineProps<{
  label: string
  disabled?: boolean
}>()

defineEmits<{
  download: []
}>()
</script>

<template>
  <button class="mini-button" type="button" :disabled="disabled" @click="$emit('download')">
    {{ label }}
    <Download :size="14" />
  </button>
</template>

<style scoped>
.mini-button {
  white-space: nowrap;
}
</style>
