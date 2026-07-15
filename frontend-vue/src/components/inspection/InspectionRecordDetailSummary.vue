<script setup lang="ts">
import { Download, ImagePlus } from 'lucide-vue-next'
import type { InspectionCategoryCode, InspectionItemResult, InspectionRecord } from '../../api/inspection'
import type { InspectionScoreView } from '../../utils/inspectionScore'

const props = defineProps<{
  record: InspectionRecord
  canSupplement: boolean
  exporting: boolean
  brandName: (record: InspectionRecord) => string
  standardVersion: (record: InspectionRecord) => string
  score: (record: InspectionRecord) => InspectionScoreView
  formatScore: (value: number | null | undefined) => string
  resultIsFailed: (record: InspectionRecord) => boolean
  resultLabel: (record: InspectionRecord) => string
  redLineCount: (record: InspectionRecord) => number
  yellowLineCount: (record: InspectionRecord) => number
  repairStatusLabel: (record: InspectionRecord) => string
  repairStatusTone: (record: InspectionRecord) => string
  hasCategoryScores: (record: InspectionRecord) => boolean
  categoryScore: (record: InspectionRecord, category: InspectionCategoryCode) => number
  hasMigrationAudit: (record: InspectionRecord) => boolean
  migrationAuditText: (record: InspectionRecord) => string
  requiresManualReview: (record: InspectionRecord) => boolean
  deductionItems: InspectionItemResult[]
  itemDeduction: (item: InspectionItemResult) => number
}>()

const emit = defineEmits<{
  supplement: []
  export: []
  close: []
}>()
</script>

<template>
  <header class="inspection-detail-head">
    <div>
      <span class="inspection-section-title">巡检详情</span>
      <h3>{{ props.record.storeName || props.record.storeId }}</h3>
    </div>
    <div class="inspection-detail-actions">
      <button v-if="props.canSupplement" class="secondary-button" type="button" @click="emit('supplement')"><ImagePlus :size="16" />补传并关联证据</button>
      <button class="primary-button" type="button" :disabled="props.exporting" @click="emit('export')"><Download :size="16" />{{ props.exporting ? '正在生成...' : '导出Excel' }}</button>
      <button class="secondary-button" type="button" @click="emit('close')">返回巡检记录</button>
    </div>
  </header>

  <div class="inspection-detail-grid">
    <div><span>门店</span><b>{{ props.record.storeName || props.record.storeId }}</b></div>
    <div><span>品牌</span><b>{{ props.brandName(props.record) || '—' }}</b></div>
    <div><span>巡检日期</span><b>{{ props.record.inspectionDate || '—' }}</b></div>
    <div><span>督导</span><b>{{ props.record.inspector || '—' }}</b></div>
    <div><span>标准版本</span><b>{{ props.standardVersion(props.record) }}</b></div>
    <div><span>满分 / 合格线</span><b>{{ props.formatScore(props.score(props.record).maxScore) }} / {{ props.formatScore(props.score(props.record).passScore) }}</b></div>
    <div><span>得分</span><b :class="{ danger: props.resultIsFailed(props.record) }">{{ props.score(props.record).scoreText }}</b></div>
    <div><span>结果</span><b :class="{ danger: props.resultIsFailed(props.record) }">{{ props.resultLabel(props.record) }}</b></div>
    <div><span>红线 / 黄线</span><b>{{ props.redLineCount(props.record) }} / {{ props.yellowLineCount(props.record) }}</b></div>
    <div><span>历史处理状态</span><b><span class="repair-status" :class="props.repairStatusTone(props.record)">{{ props.repairStatusLabel(props.record) }}</span></b></div>
    <template v-if="props.hasCategoryScores(props.record)">
      <div><span>物料</span><b>{{ props.categoryScore(props.record, 'MATERIAL') }} / 37</b></div>
      <div><span>卫生</span><b>{{ props.categoryScore(props.record, 'HYGIENE') }} / 63</b></div>
      <div><span>服务</span><b>{{ props.categoryScore(props.record, 'SERVICE') }} / 100</b></div>
    </template>
    <div v-if="props.hasMigrationAudit(props.record)"><span>历史迁移审计</span><b>{{ props.migrationAuditText(props.record) }}</b></div>
    <div v-if="props.requiresManualReview(props.record)"><span>自动修复</span><b class="danger">快照不完整，待人工复核</b></div>
  </div>

  <p v-if="!props.score(props.record).valid" class="inspection-repair-note danger">评分数据待修复：{{ props.score(props.record).error }}</p>
  <p v-if="props.record.repairReason" class="inspection-repair-note">修复说明：{{ props.record.repairReason }}</p>

  <section class="inspection-detail-section">
    <h4>扣分项明细</h4>
    <div v-if="!props.deductionItems.length" class="empty-state compact">暂无扣分项。</div>
    <ul v-else class="inspection-detail-list">
      <li v-for="item in props.deductionItems" :key="item.standardItemId">
        {{ item.categoryName || item.dimension }} · {{ item.code || '未编号' }} {{ item.title }}：扣 {{ props.itemDeduction(item) }} 分；{{ item.deductionReason || '未填写扣分原因' }}
      </li>
    </ul>
  </section>
</template>

<style scoped>
.inspection-detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.inspection-detail-head h3 { margin: 0; font-size: 18px; font-weight: 900; }
.inspection-detail-actions { display: flex; align-items: center; gap: 8px; flex: none; }
.inspection-detail-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; margin-bottom: 14px; }
.inspection-detail-grid div { min-height: 66px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 10px; background: #fafbfc; }
.inspection-detail-grid span { display: block; color: var(--muted); font-size: 12px; font-weight: 700; }
.inspection-detail-grid b { display: block; margin-top: 4px; color: var(--ink); font-size: 14px; }
.repair-status { display: inline-flex !important; align-items: center; min-height: 24px; padding: 2px 8px; border-radius: 999px; background: var(--ds-surface-muted); color: var(--ds-secondary) !important; font-size: 12px !important; }
.repair-status.repaired { background: var(--ds-success-soft); color: var(--good) !important; }
.repair-status.review { background: var(--ds-warning-soft); color: #77440d !important; }
.inspection-repair-note { margin: -2px 0 14px; padding: 9px 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--ds-surface-muted); color: var(--ds-secondary); line-height: 1.55; }
.danger { color: var(--bad); }
.inspection-detail-section { padding-top: 12px; border-top: 1px solid var(--line); }
.inspection-detail-section h4 { margin: 0 0 8px; font-size: 14px; }
.inspection-detail-list { margin: 0; padding-left: 18px; color: var(--ink); }
@media (max-width: 720px) {
  .inspection-detail-head { display: grid; }
  .inspection-detail-actions { display: grid; width: 100%; }
}
</style>
