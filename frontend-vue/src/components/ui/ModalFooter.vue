<script setup lang="ts">
import { useSlots } from 'vue'

withDefaults(defineProps<{
  sticky?: boolean
}>(), {
  sticky: false,
})

const slots = useSlots()
</script>

<template>
  <footer class="modal-footer" :class="{ 'modal-footer--sticky': sticky }">
    <div v-if="slots.info" class="modal-footer__info"><slot name="info" /></div>
    <div class="modal-footer__actions"><slot /></div>
  </footer>
</template>

<style scoped>
.modal-footer {
  display: flex;
  flex: 0 0 auto;
  min-width: 0;
  min-height: 72px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 14px 20px;
  border-top: 1px solid var(--ds-line, #e1ebe9);
  background: var(--ds-surface, #fff);
}

.modal-footer--sticky {
  position: sticky;
  z-index: 2;
  bottom: 0;
}

.modal-footer__info {
  min-width: 0;
  flex: 1 1 auto;
  color: var(--ds-secondary, #526765);
  font-size: 13px;
  line-height: 1.5;
}

.modal-footer__actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-left: auto;
}

@media (max-width: 640px) {
  .modal-footer {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
    padding: 12px 16px;
  }

  .modal-footer__actions {
    width: 100%;
    flex-wrap: wrap;
  }
}
</style>
