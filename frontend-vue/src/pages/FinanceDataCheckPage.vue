<script setup lang="ts">
import { onMounted } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import FinanceDataCheckPanel from '../components/finance/FinanceDataCheckPanel.vue'
import { useFinanceActions } from '../composables/useFinanceActions'
import { useFinanceStore } from '../stores/finance'

const finance = useFinanceStore()
const actions = useFinanceActions()

async function refresh() {
  await finance.load()
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="page-panel finance-business-page">
    <div class="page-head">
      <div>
        <h2>财务数据核对</h2>
      </div>
      <button class="ghost-button" type="button" :disabled="finance.loading" @click="refresh">
        <RefreshCw :size="16" />
        刷新
      </button>
    </div>

    <div v-if="finance.error" class="error-box">{{ finance.error }}</div>
    <div v-if="finance.actionMessage" class="success-box">{{ finance.actionMessage }}</div>

    <div v-if="finance.loading && !finance.dataChecks.length" class="empty-state">正在读取财务数据核对事项...</div>
    <FinanceDataCheckPanel
      v-else
      :items="finance.dataChecks"
      :actioning-id="finance.actioningId"
      @checked="actions.markDataCheckChecked"
      @escalate="actions.escalateDataCheck"
    />
  </section>
</template>

<style scoped>
.finance-business-page {
  display: grid;
  gap: 18px;
}

</style>
