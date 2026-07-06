<template>
  <section class="view-stack">
    <div class="filter-bar">
      <select v-model="form.storeId">
        <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }} · {{ store.brand }}</option>
      </select>
      <select v-model="form.month">
        <option v-for="month in months" :key="month">{{ month }}</option>
      </select>
      <StatusTag :label="savedLabel" tone="info" />
    </div>

    <div class="entry-grid">
      <section class="panel">
        <div class="panel-head">
          <h2>收入与成本</h2>
          <span>{{ currentStore?.name }}</span>
        </div>
        <div class="form-grid">
          <label v-for="field in incomeFields" :key="field.key">
            <span>{{ field.label }}</span>
            <input v-model.number="form[field.key]" type="number" min="0" />
          </label>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <h2>期间费用</h2>
          <span>{{ form.month }}</span>
        </div>
        <div class="form-grid">
          <label v-for="field in expenseFields" :key="field.key">
            <span>{{ field.label }}</span>
            <input v-model.number="form[field.key]" type="number" min="0" />
          </label>
        </div>
      </section>
    </div>

    <section class="panel">
      <div class="panel-head">
        <h2>实时测算</h2>
        <button class="primary-button" @click="saveDraft">
          <Save />
          保存草稿
        </button>
      </div>
      <div class="metric-grid compact">
        <MetricCard label="实收收入" :value="currency(calc.income)" foot="营业额 - 退款 - 优惠" tone="info" />
        <MetricCard label="成本合计" :value="currency(calc.costSum)" foot="原材料、包材、损耗" tone="neutral" />
        <MetricCard label="费用合计" :value="currency(calc.expenseSum)" foot="房租、人工、水电等" tone="neutral" />
        <MetricCard label="净利润" :value="currency(calc.net)" :foot="percent(calc.margin)" :tone="calc.net >= 0 ? 'good' : 'bad'" />
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { Save } from 'lucide-vue-next';
import MetricCard from '../components/MetricCard.vue';
import StatusTag from '../components/StatusTag.vue';
import { months, stores } from '../data/mock';

type NumberField = 'sales' | 'refund' | 'discount' | 'material' | 'packaging' | 'loss' | 'rent' | 'labor' | 'utility' | 'commission';

const lastSavedAt = ref('');
const form = reactive<Record<NumberField, number> & { storeId: string; month: string }>({
  storeId: stores[0].id,
  month: months[0],
  sales: stores[0].sales,
  refund: 1200,
  discount: 8400,
  material: 132000,
  packaging: 28400,
  loss: 6200,
  rent: 42000,
  labor: 50800,
  utility: 9300,
  commission: 18400
});

const incomeFields: { key: NumberField; label: string }[] = [
  { key: 'sales', label: '营业总收入' },
  { key: 'refund', label: '退款金额' },
  { key: 'discount', label: '优惠金额' },
  { key: 'material', label: '原材料' },
  { key: 'packaging', label: '包材' },
  { key: 'loss', label: '损耗' }
];

const expenseFields: { key: NumberField; label: string }[] = [
  { key: 'rent', label: '房租' },
  { key: 'labor', label: '人工工资' },
  { key: 'utility', label: '水电费' },
  { key: 'commission', label: '平台佣金' }
];

const currentStore = computed(() => stores.find((store) => store.id === form.storeId));
const savedLabel = computed(() => lastSavedAt.value ? `已保存 ${lastSavedAt.value}` : '草稿');
const calc = computed(() => {
  const income = form.sales - form.refund - form.discount;
  const costSum = form.material + form.packaging + form.loss;
  const gross = income - costSum;
  const expenseSum = form.rent + form.labor + form.utility + form.commission;
  const net = gross - expenseSum;
  return { income, costSum, expenseSum, net, margin: income > 0 ? net / income : 0 };
});

function saveDraft() {
  lastSavedAt.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date());
}

function currency(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(value);
}

function percent(value: number) {
  return `${(value * 100).toFixed(1)}%`;
}
</script>
