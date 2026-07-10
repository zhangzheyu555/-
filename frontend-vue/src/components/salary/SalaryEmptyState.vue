<script setup lang="ts">
import { Eye, RefreshCw } from 'lucide-vue-next'

defineProps<{
  type: 'no-employee' | 'no-salary'
  canEdit: boolean
  canGenerate: boolean
  previewLoading: boolean
  selectedStoreId: string
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
      <RouterLink class="primary-button" to="/operations/imports">前往员工导入</RouterLink>
    </div>
  </section>
  <section v-else-if="type === 'no-salary'" class="content-card empty-block">
    <h3>当前月份尚未生成工资记录</h3>
    <p v-if="selectedStoreId === 'all'">请先选择具体门店，再预览并生成工资记录。</p>
    <p v-else>请先预览可生成名单，确认后生成本月工资记录。</p>
    <div class="empty-actions">
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
</style>
