<script setup lang="ts">
import { computed, reactive } from 'vue'

const form = reactive({
  purchaseGrossKg: 10,
  meatRate: 68,
  cupUsageGram: 85,
  plannedCups: 100,
  lossRate: 5,
})

const netMeatKg = computed(() => form.purchaseGrossKg * (form.meatRate / 100))
const availableCups = computed(() => {
  if (!form.cupUsageGram) return 0
  return (netMeatKg.value * 1000) / form.cupUsageGram
})
const requiredGrossKg = computed(() => {
  if (!form.meatRate || !form.cupUsageGram) return 0
  const netKg = (form.plannedCups * form.cupUsageGram) / 1000
  return (netKg / (form.meatRate / 100)) * (1 + form.lossRate / 100)
})

function numberText(value: number, digits = 2) {
  return Number.isFinite(value) ? value.toFixed(digits) : '0.00'
}
</script>

<template>
  <section class="content-card analysis-panel">
    <div class="table-heading">
      <div>
        <h3>数据分析</h3>
      </div>
    </div>

    <div class="analysis-grid">
      <div class="analysis-form">
        <label>
          采购毛重（kg）
          <input v-model.number="form.purchaseGrossKg" type="number" min="0" step="0.01" />
        </label>
        <label>
          出肉率（%）
          <input v-model.number="form.meatRate" type="number" min="1" max="100" step="0.1" />
        </label>
        <label>
          单杯用量（g）
          <input v-model.number="form.cupUsageGram" type="number" min="1" step="1" />
        </label>
        <label>
          计划售卖杯数
          <input v-model.number="form.plannedCups" type="number" min="0" step="1" />
        </label>
        <label>
          损耗预留（%）
          <input v-model.number="form.lossRate" type="number" min="0" step="0.1" />
        </label>
      </div>

      <div class="analysis-results">
        <div class="result-card">
          <span>可用净料</span>
          <b>{{ numberText(netMeatKg) }} kg</b>
        </div>
        <div class="result-card">
          <span>预计可售杯数</span>
          <b>{{ numberText(availableCups, 0) }} 杯</b>
        </div>
        <div class="result-card highlight">
          <span>建议采购毛重</span>
          <b>{{ numberText(requiredGrossKg) }} kg</b>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.analysis-panel {
  display: grid;
  gap: 16px;
}

.analysis-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(260px, 0.9fr);
  gap: 16px;
}

.analysis-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.analysis-form label {
  display: grid;
  gap: 7px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
}

.analysis-form input {
  width: 100%;
  min-height: 42px;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  outline: none;
}

.analysis-form input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(238, 126, 62, 0.14);
}

.analysis-results {
  display: grid;
  gap: 10px;
}

.result-card {
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.result-card span {
  display: block;
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.result-card b {
  display: block;
  margin: 6px 0 4px;
  font-size: 25px;
}

.result-card.highlight {
  border-color: rgba(238, 126, 62, 0.3);
  background: var(--primary-soft);
}

@media (max-width: 900px) {
  .analysis-grid,
  .analysis-form {
    grid-template-columns: 1fr;
  }
}
</style>
