<script setup lang="ts">
import { ref } from 'vue'
import { AlertTriangle, ArrowRight, GraduationCap } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { getBossExamSummary, type BossExamSummary } from '../../api/exams'

const router = useRouter()
const summary = ref<BossExamSummary | null>(null)
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    summary.value = await getBossExamSummary()
  } catch (reason) {
    summary.value = null
    error.value = reason instanceof Error ? reason.message : '考试概览加载失败，请刷新后重试。'
  } finally {
    loading.value = false
  }
}

function formatNumber(value?: number) {
  return Number(value || 0).toFixed(1).replace(/\.0$/, '')
}

function openExamCenter() {
  void router.push('/exam-center')
}

defineExpose({ load })
</script>

<template>
  <section class="boss-exam-overview">
    <div class="boss-exam-head">
      <div class="boss-exam-title">
        <GraduationCap :size="20" />
        <h3>培训考试</h3>
      </div>
      <button class="mini-button" type="button" @click="openExamCenter">
        查看考试中心
        <ArrowRight :size="15" />
      </button>
    </div>

    <div v-if="error" class="error-box compact-error">{{ error }}</div>
    <div v-else-if="loading && !summary" class="exam-empty">正在读取考试概览...</div>
    <div v-else-if="!summary || (!summary.activeExamCount && !summary.assignedCount)" class="exam-empty">
      当前没有进行中的考试。
    </div>
    <template v-else>
      <div class="exam-metrics">
        <div><span>进行中</span><b>{{ summary.activeExamCount }}</b><small>场考试</small></div>
        <div><span>应考人数</span><b>{{ summary.assignedCount }}</b><small>人</small></div>
        <div><span>完成情况</span><b>{{ summary.completedCount }}</b><small>{{ formatNumber(summary.completionRate) }}%</small></div>
        <div><span>通过情况</span><b>{{ summary.passedCount }}</b><small>{{ formatNumber(summary.passRate) }}%</small></div>
        <div :class="{ danger: summary.overdueCount > 0 }"><span>逾期未参加</span><b>{{ summary.overdueCount }}</b><small>人</small></div>
        <div><span>平均分</span><b>{{ formatNumber(summary.averageScore) }}</b><small>分</small></div>
      </div>

      <div v-if="summary.riskStores.length" class="exam-risks">
        <div class="risk-caption"><AlertTriangle :size="16" />需要关注的门店</div>
        <div v-for="store in summary.riskStores.slice(0, 5)" :key="store.storeId" class="risk-row">
          <b>{{ store.storeName }}</b>
          <span>完成 {{ formatNumber(store.completionRate) }}%</span>
          <span>通过 {{ formatNumber(store.passRate) }}%</span>
          <span v-if="store.overdueCount" class="risk-overdue">逾期 {{ store.overdueCount }} 人</span>
          <div class="risk-tags">
            <em v-for="risk in store.risks" :key="risk">{{ risk }}</em>
          </div>
        </div>
      </div>
      <div v-else class="exam-ok">当前门店考试进度正常。</div>
    </template>
  </section>
</template>

<style scoped>
.boss-exam-overview {
  display: grid;
  gap: 12px;
  padding: 16px 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.boss-exam-head,
.boss-exam-title,
.risk-caption {
  display: flex;
  align-items: center;
}

.boss-exam-head {
  justify-content: space-between;
  gap: 12px;
}

.boss-exam-title,
.risk-caption {
  gap: 8px;
}

.boss-exam-title h3 {
  margin: 0;
  font-size: 18px;
}

.exam-metrics {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}

.exam-metrics > div {
  min-width: 0;
  padding: 12px;
  border-right: 1px solid var(--line);
  background: #f8f9fa;
}

.exam-metrics > div:last-child {
  border-right: 0;
}

.exam-metrics span,
.exam-metrics small {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.exam-metrics b {
  display: inline-block;
  margin: 3px 5px 0 0;
  font-size: 24px;
  line-height: 1.15;
}

.exam-metrics small {
  display: inline;
}

.exam-metrics .danger b,
.risk-overdue {
  color: var(--bad);
}

.exam-risks {
  display: grid;
  gap: 0;
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}

.risk-caption {
  padding: 9px 12px;
  background: #fff7ed;
  color: var(--warn);
  font-size: 13px;
  font-weight: 800;
}

.risk-row {
  display: grid;
  grid-template-columns: minmax(150px, 1.4fr) repeat(3, minmax(90px, .7fr)) minmax(180px, 1.5fr);
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-top: 1px solid var(--line);
  font-size: 13px;
}

.risk-row > span {
  color: var(--muted);
}

.risk-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.risk-tags em {
  padding: 2px 7px;
  border-radius: 999px;
  background: #fff4f2;
  color: var(--bad);
  font-size: 11px;
  font-style: normal;
  font-weight: 700;
}

.exam-empty,
.exam-ok {
  padding: 15px;
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  color: var(--muted);
  font-size: 13px;
  text-align: center;
}

.exam-ok {
  border-style: solid;
  background: #eaf8f0;
  color: var(--good);
}

.compact-error {
  margin: 0;
}

@media (max-width: 900px) {
  .exam-metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .exam-metrics > div:nth-child(3) {
    border-right: 0;
  }

  .exam-metrics > div:nth-child(-n + 3) {
    border-bottom: 1px solid var(--line);
  }

  .risk-row {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .risk-row b,
  .risk-tags {
    grid-column: 1 / -1;
  }
}

@media (max-width: 520px) {
  .boss-exam-head {
    align-items: stretch;
    flex-direction: column;
  }

  .boss-exam-head .mini-button {
    width: 100%;
  }

  .exam-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .exam-metrics > div {
    border-right: 1px solid var(--line);
    border-bottom: 1px solid var(--line);
  }

  .exam-metrics > div:nth-child(even) {
    border-right: 0;
  }

  .exam-metrics > div:nth-last-child(-n + 2) {
    border-bottom: 0;
  }

  .risk-row {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
