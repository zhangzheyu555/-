<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { WarehouseItem } from '../../api/warehouse'

const props = defineProps<{
  items: WarehouseItem[]
  selectedItemId: number | null
  categoryLabel: string
  submitting: boolean
}>()

const emit = defineEmits<{
  'update:selectedItemId': [value: number | null]
  submit: [payload: { itemId: number; quantity: number; note?: string }]
}>()

const quantity = ref('')
const note = ref('')
const error = ref('')

const selectedId = computed({
  get: () => props.selectedItemId,
  set: (value: number | null) => emit('update:selectedItemId', value),
})

const selectedItem = computed(() => props.items.find((item) => item.id === selectedId.value) || null)

watch(
  () => props.items.map((item) => item.id).join(','),
  () => {
    if (selectedId.value && !props.items.some((item) => item.id === selectedId.value)) {
      selectedId.value = props.items[0]?.id || null
    }
  },
)

watch(
  () => props.selectedItemId,
  () => {
    error.value = ''
  },
)

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit ? ` ${unit}` : ''}`
}

function submit() {
  error.value = ''
  const itemId = selectedId.value
  const requestedQuantity = Number(quantity.value)
  if (!itemId) {
    error.value = '请选择商品'
    return
  }
  if (!Number.isFinite(requestedQuantity) || requestedQuantity <= 0) {
    error.value = '叫货数量必须大于 0'
    return
  }
  emit('submit', { itemId, quantity: requestedQuantity, note: note.value.trim() || undefined })
  quantity.value = ''
  note.value = ''
}
</script>

<template>
  <div class="content-card requisition-form">
    <div class="table-heading">
      <div>
        <h3>向公司仓库叫货</h3>
        <span>商品选择跟随当前分类：{{ categoryLabel }}</span>
      </div>
    </div>
    <form class="form-grid" @submit.prevent="submit">
      <label>
        商品
        <select v-model.number="selectedId" :disabled="!items.length">
          <option v-if="!items.length" :value="null">当前分类下没有可叫货商品</option>
          <option v-for="item in items" :key="item.id" :value="item.id">
            {{ item.name }} · 公司仓库可配送 {{ qty(item.warehouseAvailableQuantity, item.unit) }}
          </option>
        </select>
      </label>
      <label>
        公司仓库可配送
        <input :value="selectedItem ? qty(selectedItem.warehouseAvailableQuantity, selectedItem.unit) : '-'" disabled />
      </label>
      <label>
        叫货数量
        <input v-model="quantity" type="number" min="0.01" step="0.01" placeholder="输入需要数量" />
      </label>
      <label>
        备注
        <input v-model="note" placeholder="例如：周末备货" />
      </label>
      <div v-if="error" class="form-error inline">{{ error }}</div>
      <button class="primary-button submit-inline" type="submit" :disabled="submitting || !items.length">
        {{ submitting ? '正在提交...' : '提交叫货' }}
      </button>
    </form>
  </div>
</template>
