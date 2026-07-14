<script setup lang="ts">
import { computed, useSlots } from 'vue'
import { useRoute } from 'vue-router'

const props = defineProps<{
  title?: string
  subtitle?: string
}>()

const route = useRoute()
const slots = useSlots()
const resolvedTitle = computed(() => props.title || String(route.meta.title || ''))
const hasActions = computed(() => Boolean(slots.actions || slots.default))
</script>

<template>
  <header class="business-page-header">
    <div class="business-page-heading">
      <h1>{{ resolvedTitle }}</h1>
      <p v-if="subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="hasActions" class="business-page-actions">
      <slot name="actions"><slot /></slot>
    </div>
  </header>
</template>

<style scoped>
.business-page-header {
  display: flex;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  min-height: 42px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.business-page-heading {
  display: grid;
  flex: 1 1 240px;
  min-width: 0;
  gap: 3px;
}

.business-page-heading h1 {
  margin: 0;
  color: var(--ds-ink);
  font-size: 22px;
  font-weight: 700;
  line-height: 1.35;
  letter-spacing: 0;
  text-wrap: balance;
}

.business-page-heading p {
  margin: 0;
  color: var(--ds-muted);
  font-size: 13px;
  font-weight: 500;
  line-height: 1.4;
}

.business-page-actions {
  display: flex;
  flex: 0 1 auto;
  max-width: 100%;
  min-width: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.business-page-actions :deep(button),
.business-page-actions :deep(a) {
  width: auto;
  flex: none;
}

@media (max-width: 640px) {
  .business-page-header {
    flex-direction: column;
    align-items: start;
    gap: 10px;
  }

  .business-page-heading,
  .business-page-actions {
    width: 100%;
    flex: none;
    justify-content: flex-start;
  }
}
</style>
