<template>
  <section class="view-stack">
    <div class="filter-bar">
      <select v-model="form.storeId">
        <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }} · {{ store.brandName }}</option>
      </select>
      <select v-model="form.month">
        <option v-for="month in months" :key="month">{{ month }}</option>
      </select>
      <StatusTag :label="savedLabel" tone="info" />
      <span v-if="loading" class="soft-label">读取中</span>
    </div>

    <div v-if="error" class="inline-alert">{{ error }}</div>

    <div class="entry-grid">
      <section class="panel">
        <div class="panel-head">
          <h2>收入与成本</h2>
          <span>{{ currentStore?.name || '未选择门店' }}</span>
        </div>
        <div class="form-grid">
          <label v-for="field in incomeFields" :key="field.key">
            <span>{{ field.label }}</span>
            <input v-model.number="form[field.key]" type="number" min="0" step="0.01" />
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
            <input v-model.number="form[field.key]" type="number" min="0" step="0.01" />
          </label>
        </div>
      </section>
    </div>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>实时测算</h2>
          <span>后端保存字段与这里的测算口径一致</span>
        </div>
        <button class="primary-button" :disabled="saving || !form.storeId" @click="saveEntry">
          <Save />
          {{ saving ? '保存中' : '保存利润数据' }}
        </button>
      </div>
      <div class="metric-grid compact">
        <MetricCard label="实收收入" :value="currency(calc.income)" foot="营业额 - 退款 - 优惠" tone="info" />
        <MetricCard label="成本合计" :value="currency(calc.costSum)" foot="原材料、包材、损耗、其他成本" tone="neutral" />
        <MetricCard label="费用合计" :value="currency(calc.expenseSum)" foot="房租、人工、水电、平台等" tone="neutral" />
        <MetricCard label="净利润" :value="currency(calc.net)" :foot="percent(calc.margin)" :tone="calc.net >= 0 ? 'good' : 'bad'" />
      </div>
      <label class="note-field">
        <span>备注</span>
        <input v-model.trim="form.note" placeholder="例如：本月有一次性房租、设备维修或异常损耗" />
      </label>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { Save } from 'lucide-vue-next';
import MetricCard from '../components/MetricCard.vue';
import StatusTag from '../components/StatusTag.vue';
import {
  fetchFinanceMonths,
  fetchProfitEntry,
  fetchStores,
  saveProfitEntry,
  type ProfitEntry,
  type ProfitEntryPayload,
  type StoreRecord
} from '../services/api';

type NumberField =
  | 'sales'
  | 'refund'
  | 'discount'
  | 'material'
  | 'packaging'
  | 'loss'
  | 'costOther'
  | 'rent'
  | 'labor'
  | 'utility'
  | 'property'
  | 'commission'
  | 'promo'
  | 'repair'
  | 'equip'
  | 'expOther';

type EntryForm = Record<NumberField, number> & {
  storeId: string;
  month: string;
  note: string;
};

const stores = ref<StoreRecord[]>([]);
const months = ref<string[]>([]);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const lastSavedAt = ref('');
const ready = ref(false);
const form = reactive<EntryForm>({
  storeId: '',
  month: currentMonth(),
  note: '',
  ...emptyNumbers()
});

const incomeFields: { key: NumberField; label: string }[] = [
  { key: 'sales', label: '营业总收入' },
  { key: 'refund', label: '退款金额' },
  { key: 'discount', label: '优惠金额' },
  { key: 'material', label: '原材料' },
  { key: 'packaging', label: '包材' },
  { key: 'loss', label: '损耗' },
  { key: 'costOther', label: '其他成本' }
];

const expenseFields: { key: NumberField; label: string }[] = [
  { key: 'rent', label: '房租' },
  { key: 'labor', label: '人工工资' },
  { key: 'utility', label: '水电费' },
  { key: 'property', label: '物业费' },
  { key: 'commission', label: '平台佣金' },
  { key: 'promo', label: '推广费' },
  { key: 'repair', label: '维修费' },
  { key: 'equip', label: '设备费' },
  { key: 'expOther', label: '其他费用' }
];

const currentStore = computed(() => stores.value.find((store) => store.id === form.storeId));
const savedLabel = computed(() => lastSavedAt.value || '未保存');
const calc = computed(() => {
  const income = number(form.sales) - number(form.refund) - number(form.discount);
  const costSum = number(form.material) + number(form.packaging) + number(form.loss) + number(form.costOther);
  const gross = income - costSum;
  const expenseSum = number(form.rent) + number(form.labor) + number(form.utility) + number(form.property)
    + number(form.commission) + number(form.promo) + number(form.repair) + number(form.equip) + number(form.expOther);
  const net = gross - expenseSum;
  return { income, costSum, expenseSum, net, margin: income > 0 ? net / income : 0 };
});

onMounted(loadBaseData);

watch(() => [form.storeId, form.month], () => {
  if (ready.value) {
    loadEntry();
  }
});

async function loadBaseData() {
  loading.value = true;
  error.value = '';
  try {
    const [storeRecords, monthRecords] = await Promise.all([fetchStores(), fetchFinanceMonths()]);
    stores.value = storeRecords;
    months.value = monthRecords;
    ready.value = false;
    form.storeId = storeRecords[0]?.id ?? '';
    form.month = monthRecords[0] ?? currentMonth();
    ready.value = true;
    await loadEntry();
  } catch {
    error.value = '基础数据加载失败，请确认后端服务和登录状态正常。';
  } finally {
    loading.value = false;
  }
}

async function loadEntry() {
  if (!form.storeId || !form.month) {
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const entry = await fetchProfitEntry(form.storeId, form.month);
    applyEntry(entry);
    lastSavedAt.value = '已载入';
  } catch {
    applyEmptyNumbers();
    lastSavedAt.value = '新记录';
  } finally {
    loading.value = false;
  }
}

async function saveEntry() {
  saving.value = true;
  error.value = '';
  try {
    await saveProfitEntry(toPayload());
    lastSavedAt.value = `已保存 ${new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date())}`;
  } catch {
    error.value = '保存失败，请确认账号权限或录入字段。';
  } finally {
    saving.value = false;
  }
}

function applyEntry(entry: ProfitEntry) {
  form.sales = entry.sales;
  form.refund = entry.refund;
  form.discount = entry.discount;
  form.material = entry.material;
  form.packaging = entry.packaging;
  form.loss = entry.loss;
  form.costOther = entry.costOther;
  form.rent = entry.rent;
  form.labor = entry.labor;
  form.utility = entry.utility;
  form.property = entry.property;
  form.commission = entry.commission;
  form.promo = entry.promo;
  form.repair = entry.repair;
  form.equip = entry.equip;
  form.expOther = entry.expOther;
  form.note = entry.note || '';
}

function applyEmptyNumbers() {
  Object.assign(form, emptyNumbers(), { note: '' });
}

function toPayload(): ProfitEntryPayload {
  return {
    storeId: form.storeId,
    month: form.month,
    sales: number(form.sales),
    refund: number(form.refund),
    discount: number(form.discount),
    material: number(form.material),
    packaging: number(form.packaging),
    loss: number(form.loss),
    costOther: number(form.costOther),
    rent: number(form.rent),
    labor: number(form.labor),
    utility: number(form.utility),
    property: number(form.property),
    commission: number(form.commission),
    promo: number(form.promo),
    repair: number(form.repair),
    equip: number(form.equip),
    expOther: number(form.expOther),
    note: form.note
  };
}

function emptyNumbers(): Record<NumberField, number> {
  return {
    sales: 0,
    refund: 0,
    discount: 0,
    material: 0,
    packaging: 0,
    loss: 0,
    costOther: 0,
    rent: 0,
    labor: 0,
    utility: 0,
    property: 0,
    commission: 0,
    promo: 0,
    repair: 0,
    equip: 0,
    expOther: 0
  };
}

function currentMonth() {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

function number(value: number) {
  return Number.isFinite(value) ? value : 0;
}

function currency(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(value);
}

function percent(value: number) {
  return `${(value * 100).toFixed(1)}%`;
}
</script>
