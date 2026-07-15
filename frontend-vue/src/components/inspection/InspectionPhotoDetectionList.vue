<script setup lang="ts">
import { AlertTriangle, CheckCircle2, LoaderCircle, RotateCw, Trash2, XCircle } from 'lucide-vue-next'
import type { InspectionDetectionItem, InspectionDetectionResult } from '../../api/inspection'
import type { InspectionDraftPhoto } from '../../composables/useInspectionDraft'

const props = defineProps<{
  photos: InspectionDraftPhoto[]
  saving: boolean
  photoHref: (photo: InspectionDraftPhoto) => string
  detectionCount: (result?: InspectionDetectionResult) => number
  detectionClauseId: (result?: InspectionDetectionResult) => number | undefined
  detectionClauseLabel: (result?: InspectionDetectionResult) => string
  detectionFinalDeduction: (result?: InspectionDetectionResult) => number
  detectionConfidence: (result?: InspectionDetectionResult) => number | undefined
  confidenceText: (value?: number) => string
  detectionLabel: (item: InspectionDetectionItem) => string
  formatScore: (value: number | null | undefined) => string
  reviewBusy: (photo: InspectionDraftPhoto) => boolean
}>()

const emit = defineEmits<{
  remove: [index: number]
  retry: [photo: InspectionDraftPhoto]
  confirm: [photo: InspectionDraftPhoto]
  dismiss: [photo: InspectionDraftPhoto]
  undo: [photo: InspectionDraftPhoto]
}>()
</script>

<template>
  <div v-if="!props.photos.length" class="inspection-detection-empty">
    <AlertTriangle :size="20" />
    <div><b>待识别</b><span>选择现场照片后，系统会先保存附件，再调用本地模型生成标注图和扣分建议。</span></div>
  </div>
  <div v-else class="inspection-detection-list" aria-live="polite">
    <article v-for="(photo, index) in props.photos" :key="photo.attachmentId || photo.url || photo.fileName" class="inspection-detection-item">
      <header>
        <div><b>{{ photo.fileName || '现场照片' }}</b><a :href="props.photoHref(photo)" target="_blank" rel="noreferrer">查看原图</a></div>
        <button class="icon-button" type="button" aria-label="移除照片" @click="emit('remove', index)"><Trash2 :size="14" /></button>
      </header>

      <div v-if="photo.detectionStatus === 'detecting'" class="inspection-detection-progress">
        <LoaderCircle :size="20" class="spin" /><div><b>模型识别中</b><span>正在生成标注图片和问题建议，请稍候。</span></div>
      </div>
      <div v-else-if="photo.detectionStatus === 'failed'" class="inspection-detection-failed">
        <XCircle :size="20" /><div><b>识别服务不可用</b><span>{{ photo.detectionError || '本次识别失败，不能按合格处理。' }}</span></div>
        <button class="secondary-button" type="button" @click="emit('retry', photo)"><RotateCw :size="15" />重新识别</button>
      </div>
      <div v-else-if="photo.detection" class="inspection-detection-result">
        <div class="inspection-detection-preview">
          <img v-if="photo.detection.annotated_image" :src="photo.detection.annotated_image" :alt="`${photo.fileName} 模型标注结果`" />
          <div v-else class="inspection-preview-missing">模型未返回标注图</div>
        </div>
        <div class="inspection-detection-content">
          <div class="inspection-model-summary">
            <span class="detection-count" :class="{ clear: props.detectionCount(photo.detection) === 0 }">{{ props.detectionCount(photo.detection) ? `发现 ${props.detectionCount(photo.detection)} 个疑似问题` : '未识别到明显问题' }}</span>
            <span>模型结论：{{ photo.detection.auto_status || photo.detection.review_status || '待人工确认' }}</span>
          </div>
          <div v-if="props.detectionCount(photo.detection)" class="inspection-detection-rule">
            <div class="inspection-clause-match" :class="{ unmatched: !props.detectionClauseId(photo.detection) }"><span>匹配正式条款</span><b>{{ props.detectionClauseLabel(photo.detection) }}</b></div>
            <dl class="inspection-deduction-metrics">
              <div><dt>200分制建议扣分</dt><dd>{{ props.detectionClauseId(photo.detection) ? `${props.formatScore(props.detectionFinalDeduction(photo.detection))} 分` : '待匹配' }}</dd></div>
              <div><dt>识别置信度</dt><dd>{{ props.confidenceText(props.detectionConfidence(photo.detection)) }}</dd></div>
            </dl>
            <p class="inspection-model-only-hint">模型仅建议；最终扣分由服务端按正式条款规则计算，需督导确认。</p>
          </div>
          <ul v-if="photo.detection.detections?.length" class="inspection-model-issues">
            <li v-for="(item, detectionIndex) in photo.detection.detections" :key="`${item.class_name}-${detectionIndex}`"><b>{{ props.detectionLabel(item) }}</b><span>置信度 {{ props.confidenceText(item.confidence) }}</span><span>{{ item.on_floor ? '地面区域' : item.class_name === 'corner_dust' ? '边角区域' : '现场区域' }}</span></li>
          </ul>
          <p v-if="photo.detection.deduction_content" class="inspection-model-advice">{{ photo.detection.deduction_content }}</p>
          <div class="inspection-review-actions">
            <span v-if="photo.reviewStatus === 'pending'" class="review-pending">模型结果仅供参考，请督导确认</span>
            <span v-else-if="photo.reviewStatus === 'accepted'" class="review-confirmed"><CheckCircle2 :size="15" />督导已确认，保存后按条款扣分</span>
            <span v-else class="review-dismissed"><CheckCircle2 :size="15" />督导已确认无问题</span>
            <button v-if="props.detectionCount(photo.detection) > 0 && photo.reviewStatus === 'pending'" class="primary-button" type="button" :disabled="!props.detectionClauseId(photo.detection) || props.reviewBusy(photo) || props.saving" :title="props.detectionClauseId(photo.detection) ? '确认后将在保存时由服务端计算扣分' : '尚未匹配正式条款，不能直接确认'" @click="emit('confirm', photo)">确认问题并加入扣分</button>
            <button v-if="photo.reviewStatus === 'pending'" class="secondary-button" type="button" :disabled="props.reviewBusy(photo) || props.saving" @click="emit('dismiss', photo)">{{ props.detectionCount(photo.detection) > 0 ? '人工确认无问题' : '确认未发现问题' }}</button>
            <button v-else class="secondary-button" type="button" :disabled="props.reviewBusy(photo) || props.saving" @click="emit('undo', photo)">撤销本次确认</button>
          </div>
        </div>
      </div>
    </article>
  </div>
  <div v-if="props.photos.length" class="inspection-assistant-note"><AlertTriangle :size="15" />模型仅提供疑似问题；确认请求不会提交前端计算的最终扣分，正式分值由服务端按条款规则生成。</div>
</template>

<style>
.inspection-detection-empty,.inspection-detection-progress,.inspection-detection-failed,.inspection-assistant-note{display:flex;align-items:center;gap:10px;padding:12px;border-radius:8px}.inspection-detection-empty{border:1px dashed var(--ds-line-strong);background:#fff;color:var(--primary-dark)}.inspection-detection-empty b,.inspection-detection-empty span,.inspection-detection-progress b,.inspection-detection-progress span,.inspection-detection-failed b,.inspection-detection-failed span{display:block}.inspection-detection-empty span,.inspection-detection-progress span,.inspection-detection-failed span{margin-top:3px;color:var(--muted);font-size:12px;line-height:1.5}.inspection-detection-list{display:grid;gap:12px}.inspection-detection-item{overflow:hidden;border:1px solid var(--line);border-radius:10px;background:#fff}.inspection-detection-item>header{display:flex;min-width:0;align-items:center;justify-content:space-between;gap:10px;padding:10px 12px;border-bottom:1px solid var(--line)}.inspection-detection-item>header>div{min-width:0}.inspection-detection-item>header b{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.inspection-detection-item>header a{color:var(--primary-dark);font-size:12px;font-weight:700}.inspection-detection-progress,.inspection-detection-failed{min-height:88px;border-radius:0}.inspection-detection-progress{color:var(--primary-dark)}.inspection-detection-failed{background:var(--ds-danger-soft);color:var(--bad)}.inspection-detection-failed>div{min-width:0;flex:1}.spin{animation:inspection-photo-spin .9s linear infinite}@keyframes inspection-photo-spin{to{transform:rotate(360deg)}}.inspection-detection-result{display:grid;grid-template-columns:minmax(260px,.9fr) minmax(320px,1.1fr);min-width:0}.inspection-detection-preview{min-height:250px;background:#edf2f1}.inspection-detection-preview img{display:block;width:100%;height:100%;min-height:250px;max-height:420px;object-fit:contain}.inspection-preview-missing{display:grid;min-height:250px;place-items:center;color:var(--muted)}.inspection-detection-content{min-width:0;padding:14px}.inspection-model-summary{display:flex;gap:8px;flex-wrap:wrap}.inspection-model-summary span,.inspection-model-issues li{padding:5px 8px;border-radius:6px;background:var(--ds-surface-muted);color:var(--ds-secondary);font-size:12px;font-weight:700}.inspection-model-summary .detection-count{background:var(--ds-warning-soft);color:var(--warn)}.inspection-model-summary .detection-count.clear{background:var(--ds-success-soft);color:var(--good)}.inspection-detection-rule{display:grid;gap:10px;margin-top:12px;padding:12px;border:1px solid var(--line);border-radius:8px;background:#fbfdfd}.inspection-clause-match{display:grid;gap:3px}.inspection-clause-match span,.inspection-deduction-metrics dt{color:var(--muted);font-size:12px}.inspection-clause-match b{color:var(--ink);font-size:14px}.inspection-clause-match.unmatched b{color:var(--bad)}.inspection-deduction-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin:0}.inspection-deduction-metrics>div{min-width:0;padding:8px;border-radius:6px;background:var(--ds-surface-muted)}.inspection-deduction-metrics dt,.inspection-deduction-metrics dd{margin:0}.inspection-deduction-metrics dd{margin-top:4px;color:var(--ink);font-weight:900;font-variant-numeric:tabular-nums}.inspection-model-only-hint{margin:0;color:#77440d;font-size:12px;line-height:1.5}.inspection-model-issues{display:grid;gap:6px;margin:12px 0;padding:0;list-style:none}.inspection-model-issues li{display:flex;align-items:center;justify-content:space-between;gap:8px}.inspection-model-issues li b{color:var(--ink)}.inspection-model-advice{margin:12px 0;color:var(--ink);line-height:1.6;overflow-wrap:anywhere}.inspection-review-actions{display:flex;align-items:center;justify-content:flex-start;gap:8px;flex-wrap:wrap;padding-top:12px;border-top:1px solid var(--line)}.inspection-review-actions>span{display:inline-flex;align-items:center;gap:5px;margin-right:auto;font-size:12px;font-weight:800}.review-pending{color:var(--warn)}.review-confirmed{color:var(--bad)}.review-dismissed{color:var(--good)}.inspection-assistant-note{margin-top:12px;background:var(--ds-warning-soft);color:#77440d;font-size:12px;font-weight:700}@media (max-width:860px){.inspection-detection-result{grid-template-columns:1fr}.inspection-deduction-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
