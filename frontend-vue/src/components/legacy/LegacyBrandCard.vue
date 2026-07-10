<script setup lang="ts">
defineProps<{
  name: string
  count: string
  net: string
  revenue: string
  rate: string
  color: string
  selected?: boolean
  dim?: boolean
}>()
</script>

<template>
  <button
    class="legacy-brand-card-base"
    :class="{ selected, dim }"
    :style="{ background: color }"
    type="button"
  >
    <span v-if="selected" class="legacy-brand-selected">查看中</span>
    <div class="legacy-brand-name">{{ name }}</div>
    <div class="legacy-brand-count">{{ count }}</div>
    <div class="legacy-brand-row">
      <div>
        <div class="legacy-brand-big">{{ net }}</div>
        <div class="legacy-brand-label">净利</div>
      </div>
      <div class="legacy-brand-meta">
        营收<br>
        <b>{{ revenue }}</b><br>
        利率 {{ rate }}
      </div>
    </div>
  </button>
</template>

<style scoped>
.legacy-brand-selected {
  position: absolute;
  top: 12px;
  right: 14px;
  border-radius: 99px;
  background: rgba(255, 255, 255, 0.28);
  padding: 3px 9px;
  font-size: 11px;
  font-weight: 800;
}

.legacy-brand-name {
  font-size: 15px;
  font-weight: 900;
}

.legacy-brand-count,
.legacy-brand-label,
.legacy-brand-meta {
  opacity: 0.88;
  font-size: 11px;
  font-weight: 600;
}

.legacy-brand-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  margin-top: 14px;
}

.legacy-brand-big {
  font-size: 23px;
  font-weight: 900;
  line-height: 1.1;
}

.legacy-brand-meta {
  text-align: right;
  font-size: 12px;
}

.legacy-brand-meta b {
  font-size: 16px;
}
</style>

