<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ClipboardCheck, RefreshCw } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { getInspectionRecords, type InspectionRecord } from '../../api/inspection'
import { inspectionScoreView } from '../../utils/inspectionScore'

const props = defineProps<{ storeId: string }>()
const router = useRouter()
const loading = ref(false)
const error = ref('')
const records = ref<InspectionRecord[]>([])

const latest = computed(() => records.value[0] || null)
const deductions = computed<Record<string, unknown>[]>(() => parseArray(latest.value?.deductionsJson))
const deductionCount = computed(() => deductions.value.length)
const scoreView = computed(() => inspectionScoreView(latest.value))
const mainIssue = computed(() => {
  const first = deductions.value[0]
  if (first) {
    for (const key of ['problem_description', 'problemDescription', 'deduction_content', 'standard_title', 'title']) {
      const value = String(first[key] || '').trim()
      if (value) return value
    }
  }
  return latest.value?.note?.trim() || '未填写问题摘要'
})

function parseArray(value?: string) {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.filter((item) => item && typeof item === 'object') : []
  } catch {
    return []
  }
}

async function loadLatest() {
  if (!props.storeId) {
    records.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    const rows = await getInspectionRecords({ storeId: props.storeId })
    records.value = [...rows].sort((a, b) => {
      const dateDiff = String(b.inspectionDate || '').localeCompare(String(a.inspectionDate || ''))
      return dateDiff || String(b.id).localeCompare(String(a.id))
    })
  } catch {
    error.value = '最近巡查加载失败。'
  } finally {
    loading.value = false
  }
}

function openDetail() {
  if (!latest.value) return
  void router.push({
    path: '/inspection',
    query: { tab: 'records', storeId: props.storeId, recordId: latest.value.id },
  })
}

watch(() => props.storeId, () => void loadLatest(), { immediate: true })
</script>

<template>
  <section class="content-card latest-inspection-card">
    <div class="inspection-heading">
      <div class="inspection-title"><ClipboardCheck :size="19" /><h3>最近督导巡查</h3></div>
      <button v-if="error" class="mini-button" type="button" :disabled="loading" @click="loadLatest">
        <RefreshCw :size="14" />重试
      </button>
    </div>
    <div v-if="loading" class="inspection-empty">正在读取最近巡查...</div>
    <div v-else-if="error" class="inspection-error">{{ error }}</div>
    <div v-else-if="!latest" class="inspection-empty">该门店暂无督导巡查记录</div>
    <div v-else class="inspection-result">
      <div><span>最近巡查日期</span><b>{{ latest.inspectionDate }}</b></div>
      <div><span>督导</span><b>{{ latest.inspector || '未填写' }}</b></div>
      <div><span>得分</span><b :title="scoreView.error">{{ scoreView.scoreText }}</b></div>
      <div><span>结果</span><b class="result-badge" :class="scoreView.tone" :title="scoreView.error">{{ scoreView.resultText }}</b></div>
      <div><span>扣分项</span><b>{{ deductionCount }} 项</b></div>
      <div><span>巡查记录</span><b>{{ records.length }} 次</b></div>
      <div class="issue"><span>主要问题</span><b>{{ mainIssue }}</b></div>
      <button class="mini-button detail-button" type="button" @click="openDetail">查看巡查详情</button>
    </div>
  </section>
</template>

<style scoped>
.latest-inspection-card { width: 100%; max-width: 100%; min-width: 0; padding: 16px 18px; border-radius: 8px; }
.inspection-heading, .inspection-title { display: flex; align-items: center; justify-content: space-between; gap: 9px; }
.inspection-title { justify-content: flex-start; }
.inspection-title h3 { margin: 0; font-size: 18px; }
.inspection-result { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(120px, 100%), 1fr)); min-width: 0; gap: 12px; align-items: end; margin-top: 14px; }
.inspection-result > div { min-width: 0; }
.inspection-result span { display: block; color: var(--muted); font-size: 12px; font-weight: 800; }
.inspection-result b { display: block; margin-top: 5px; overflow-wrap: anywhere; font-size: 14px; }
.inspection-result .issue { grid-column: 1 / -1; }
.result-badge { width: max-content; padding: 4px 8px; border-radius: 999px; }
.result-badge.ok { color: var(--good); background: rgba(34, 197, 94, 0.1); }
.result-badge.bad { color: var(--bad); background: rgba(220, 38, 38, 0.08); }
.result-badge.review { color: var(--warn); background: var(--ds-warning-soft); }
.detail-button { grid-column: 1 / -1; width: auto; max-width: 100%; justify-self: start; white-space: normal; }
.inspection-empty, .inspection-error { margin-top: 12px; padding: 12px; font-size: 13px; }
.inspection-empty { color: var(--muted); background: #f7f8fa; }
.inspection-error { color: var(--bad); background: rgba(220, 38, 38, 0.06); }
</style>
