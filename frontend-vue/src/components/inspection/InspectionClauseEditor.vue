<script setup lang="ts">
import type { InspectionCategoryCode, InspectionItemResult } from '../../api/inspection'
import type { InspectionDraftPhoto } from '../../composables/useInspectionDraft'
import type { InspectionStandardGroup } from '../../data/inspectionStandards'

interface CategoryScoreView { code: string; score: number }

const props = defineProps<{
  groups: InspectionStandardGroup[]
  categoryScores: CategoryScoreView[]
  photos: InspectionDraftPhoto[]
  standardReady: boolean
  itemsForCategory: (categoryCode: InspectionCategoryCode) => InspectionItemResult[]
  safeNumber: (value: unknown) => number
  riskLabel: (riskLevel?: string) => string
  itemDeduction: (item: InspectionItemResult) => number
  itemNeedsRectification: (item: InspectionItemResult) => boolean
  itemPhotoSelected: (item: InspectionItemResult, attachmentId?: number) => boolean
  itemAfterPhotoSelected: (item: InspectionItemResult, attachmentId?: number) => boolean
}>()

const emit = defineEmits<{
  toggleRedline: [item: InspectionItemResult, checked: boolean]
  normalizeScore: [item: InspectionItemResult]
  togglePhoto: [item: InspectionItemResult, attachmentId: number | undefined, checked: boolean]
  toggleAfterPhoto: [item: InspectionItemResult, attachmentId: number | undefined, checked: boolean]
}>()
</script>

<template>
  <section v-for="group in props.groups" :key="group.categoryCode" class="content-card inspection-clause-editor" :class="{ 'is-readonly': !props.standardReady }" :data-category="group.categoryCode">
    <div class="clause-editor-head"><div><span>{{ group.dim }}标准</span><h3>{{ group.items.length }} 条完整检查项</h3></div><strong>{{ props.categoryScores.find((item) => item.code === group.categoryCode)?.score || 0 }} / {{ group.fullScore }} 分（200分制）</strong></div>
    <div class="clause-table-wrap"><table class="clause-table"><thead><tr><th>条款编号</th><th>检查内容 / 方法</th><th>风险</th><th class="r">标准分</th><th>实际分</th><th>扣分原因</th><th>现场照片</th></tr></thead><tbody>
      <template v-for="item in props.itemsForCategory(group.categoryCode)" :key="item.standardItemId">
        <tr :class="[`risk-${String(item.riskLevel || 'NORMAL').toLowerCase()}`, { deducted: props.itemDeduction(item) > 0 || item.issueFound }]">
          <td><b>{{ item.code || '—' }}</b></td><td><b>{{ item.title }}</b><small v-if="item.checkMethod">检查方法：{{ item.checkMethod }}</small></td><td><span class="risk-chip" :class="String(item.riskLevel || 'NORMAL').toLowerCase()">{{ props.riskLabel(item.riskLevel) }}</span></td><td class="r">{{ props.safeNumber(item.standardScore) }}</td>
          <td><label v-if="item.riskLevel === 'RED'" class="redline-found-toggle"><input type="checkbox" :checked="item.issueFound" :disabled="!props.standardReady" @change="emit('toggleRedline', item, ($event.target as HTMLInputElement).checked)" />{{ item.issueFound ? '发现问题' : '未命中' }}</label><input v-else v-model.number="item.actualScore" class="item-score-input" type="number" min="0" :max="props.safeNumber(item.standardScore)" step="0.01" :aria-label="`${item.code || item.title}实际分`" :data-standard-code="item.code" :disabled="!props.standardReady" @change="emit('normalizeScore', item)" /><small v-if="props.itemDeduction(item) > 0" class="deduction-value">扣 {{ props.itemDeduction(item) }} 分</small></td>
          <td><input v-model.trim="item.deductionReason" class="item-reason-input" :class="{ invalid: (item.issueFound || props.itemDeduction(item) > 0) && !item.deductionReason?.trim() }" :placeholder="item.issueFound || props.itemDeduction(item) > 0 ? '必填：写清现场问题' : '无扣分'" :aria-label="`${item.code || item.title}扣分原因`" :disabled="!props.standardReady" @blur="emit('normalizeScore', item)" /></td>
          <td><div v-if="props.photos.some((photo) => photo.attachmentId)" class="item-photo-options"><label v-for="photo in props.photos.filter((row) => row.attachmentId)" :key="photo.attachmentId"><input type="checkbox" :checked="props.itemPhotoSelected(item, photo.attachmentId)" :disabled="!props.standardReady" @change="emit('togglePhoto', item, photo.attachmentId, ($event.target as HTMLInputElement).checked)" />问题：{{ photo.fileName }}</label><label v-for="photo in props.photos.filter((row) => row.attachmentId)" :key="`after-${photo.attachmentId}`"><input type="checkbox" :checked="props.itemAfterPhotoSelected(item, photo.attachmentId)" :disabled="!props.standardReady" @change="emit('toggleAfterPhoto', item, photo.attachmentId, ($event.target as HTMLInputElement).checked)" />整改后：{{ photo.fileName }}</label></div><small v-else>未关联照片</small></td>
        </tr>
        <tr v-if="props.itemNeedsRectification(item)" class="rectification-row"><td colspan="7"><div class="rectification-fields"><label><span>负责人</span><input v-model.trim="item.responsiblePerson" :disabled="!props.standardReady" placeholder="整改负责人" /></label><label><span>整改期限</span><input v-model="item.rectificationDeadline" :disabled="!props.standardReady" type="date" /></label><label><span>整改状态</span><select v-model="item.rectificationStatus" :disabled="!props.standardReady"><option value="待整改">待整改</option><option value="整改中">整改中</option><option value="待复核">待复核</option><option value="已完成">已完成</option></select></label><label><span>复核结果</span><input v-model.trim="item.reviewResult" :disabled="!props.standardReady" placeholder="待复核或填写结果" /></label></div></td></tr>
      </template>
    </tbody></table></div>
  </section>
</template>

<style>
.inspection-clause-editor{overflow:hidden}.clause-editor-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px}.clause-editor-head span{display:block;color:var(--muted);font-size:12px;font-weight:800}.clause-editor-head h3{margin:0;font-size:18px;font-weight:900}.clause-editor-head strong{color:var(--ink);font-size:18px;font-variant-numeric:tabular-nums;white-space:nowrap}.clause-table-wrap{width:100%;overflow-x:auto}.clause-table{width:100%;min-width:1260px;border-collapse:collapse}.clause-table .r{text-align:right}.clause-table th:nth-child(2),.clause-table td:nth-child(2),.clause-table th:nth-child(6),.clause-table td:nth-child(6){white-space:normal}.clause-table td{vertical-align:top}.clause-table td small{display:block;margin-top:4px;color:var(--muted);line-height:1.45}.inspection-clause-editor.is-readonly input,.inspection-clause-editor.is-readonly select{cursor:not-allowed}.inspection-clause-editor.is-readonly .clause-editor-head::after{content:'只读核对';padding:3px 8px;border-radius:999px;background:var(--ds-warning-soft);color:#77440d;font-size:12px;font-weight:800}.clause-table tr.risk-red.deducted,.clause-table tr.risk-red:has(.redline-found-toggle input:checked){background:var(--ds-danger-soft)}.clause-table tr.risk-yellow.deducted{background:var(--ds-warning-soft)}.risk-chip{display:inline-flex;align-items:center;min-height:24px;padding:2px 8px;border-radius:999px;background:var(--ds-surface-muted);color:var(--muted);font-size:12px;font-weight:800}.risk-chip.red{background:var(--ds-danger-soft);color:var(--bad)}.risk-chip.yellow{background:var(--ds-warning-soft);color:var(--warn)}.item-score-input{width:86px;min-height:36px;text-align:right;font-variant-numeric:tabular-nums}.item-reason-input{width:100%;min-width:210px}.item-reason-input.invalid{border-color:var(--bad);background:var(--ds-danger-soft)}.deduction-value{color:var(--bad)!important;font-weight:800}.redline-found-toggle,.item-photo-options label{display:flex;align-items:center;gap:6px;color:var(--ink);font-size:12px;font-weight:700}.redline-found-toggle{min-height:36px;white-space:nowrap}.item-photo-options{display:grid;gap:5px;min-width:190px;max-height:116px;overflow-y:auto}.rectification-row td{padding-top:8px;background:var(--ds-surface-muted)}.rectification-fields{display:grid;grid-template-columns:repeat(4,minmax(170px,1fr));gap:10px}.rectification-fields label{display:grid;gap:5px}.rectification-fields span{color:var(--muted);font-size:12px;font-weight:700}
</style>
