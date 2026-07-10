<script setup lang="ts">
import { CheckCircle2, RotateCcw, Send } from 'lucide-vue-next'
import InspectionIssueCard from './InspectionIssueCard.vue'
import type { RoleTodoItem } from '../../api/todos'

defineProps<{
  reviews: RoleTodoItem[]
  actioningId?: string
}>()

defineEmits<{
  pass: [item: RoleTodoItem]
  continue: [item: RoleTodoItem]
  escalate: [item: RoleTodoItem]
  open: [item: RoleTodoItem]
}>()
</script>

<template>
  <section class="inspection-panel">
    <div class="inspection-panel-head">
      <div>
        <h3>整改复查</h3>
      </div>
    </div>

    <div v-if="!reviews.length" class="empty-state compact">当前没有待复查整改。</div>

    <div v-else class="inspection-list">
      <article v-for="item in reviews" :key="item.id" class="review-card">
        <InspectionIssueCard
          :item="item"
          :actioning-id="actioningId"
          @open="$emit('open', item)"
          @complete="$emit('pass', item)"
          @escalate="$emit('escalate', item)"
        />
        <div class="review-actions">
          <button class="mini-button primary" type="button" :disabled="actioningId === item.id" @click="$emit('pass', item)">
            通过复查
            <CheckCircle2 :size="14" />
          </button>
          <button class="mini-button" type="button" :disabled="actioningId === item.id" @click="$emit('continue', item)">
            继续整改
            <RotateCcw :size="14" />
          </button>
          <button class="mini-button" type="button" :disabled="actioningId === item.id" @click="$emit('escalate', item)">
            上报老板
            <Send :size="14" />
          </button>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.inspection-panel {
  display: grid;
  gap: 12px;
}

.inspection-panel-head h3 {
  margin: 0;
  font-size: 18px;
}

.inspection-list,
.review-card {
  display: grid;
  gap: 10px;
}

.review-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
  padding: 0 2px 4px;
}

@media (max-width: 720px) {
  .review-actions {
    justify-content: flex-start;
  }
}
</style>
