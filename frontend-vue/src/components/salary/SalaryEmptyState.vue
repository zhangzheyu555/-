<script setup lang="ts">
import { Eye, RefreshCw } from 'lucide-vue-next'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()

defineProps<{
  type: 'no-employee' | 'no-salary'
  canEdit: boolean
  canGenerate: boolean
  previewLoading: boolean
  selectedStoreId: string
  selectedMonth: string
  latestDataMonth: string
}>()

const emit = defineEmits<{
  preview: []
  generate: []
}>()
</script>

<template>
  <section v-if="type === 'no-employee'" class="content-card empty-block">
    <h3>当前范围尚未导入员工</h3>
    <p>请先导入员工，再生成工资记录。</p>
    <div class="empty-actions">
      <RouterLink v-if="auth.role !== 'STORE_MANAGER'" class="primary-button" to="/data-entry">前往数据录入</RouterLink>
      <p v-else class="empty-muted">经营数据由财务统一录入；请联系财务补录经营数据。</p>
    </div>
  </section>
  <section v-else-if="type === 'no-salary'" class="content-card empty-block">
    <h3>{{ selectedMonth }} 工资尚未生成</h3>
    <p v-if="latestDataMonth">{{ latestDataMonth }} 有工资数据。</p>
    <p v-else>数据库尚无任何月份的工资记录。</p>
    <p v-if="selectedStoreId === 'all'">请先选择具体门店，再预览并生成工资记录。</p>
    <p v-else>请先预览可生成名单，确认后生成本月工资记录。</p>
    <div class="empty-actions">
      <RouterLink v-if="latestDataMonth" class="ghost-button" :to="{ path: '/salary', query: { month: latestDataMonth } }">查看 {{ latestDataMonth }}</RouterLink>
      <button v-if="canEdit && selectedStoreId !== 'all'" class="primary-button" :disabled="previewLoading || !canGenerate" @click="emit('preview')"><Eye :size="16" /> 预览生成名单</button>
      <button v-if="canEdit && selectedStoreId !== 'all'" class="primary-button submit-inline" :disabled="!canGenerate" @click="emit('generate')"><RefreshCw :size="16" /> 生成本月工资记录</button>
    </div>
  </section>
</template>

<style scoped>
.empty-block { text-align: center; padding: 48px 20px; }
.empty-block h3 { margin: 0 0 8px; font-size: 18px; }
.empty-block p { color: var(--muted); margin: 0 0 20px; }
.empty-actions { display: flex; justify-content: center; gap: 10px; flex-wrap: wrap; }
.empty-actions a { text-decoration: none; }
.empty-muted { color: var(--ds-muted, #888); font-size: 14px; }
</style>
