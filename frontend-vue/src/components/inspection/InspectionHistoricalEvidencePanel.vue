<script setup lang="ts">
import { ImagePlus, Link2, LoaderCircle, XCircle } from 'lucide-vue-next'
import type { InspectionDetectionResult } from '../../api/inspection'
import type { InspectionDraftPhoto } from '../../composables/useInspectionDraft'

type DetailPhotoStatus = 'loading' | 'ready' | 'forbidden' | 'missing' | 'failed'

interface DetailPhotoState {
  status: DetailPhotoStatus
  url?: string
}

const props = defineProps<{
  unlinkedPhotos: InspectionDraftPhoto[]
  pendingAiPhotos: InspectionDraftPhoto[]
  canSupplement: boolean
  candidatesLoading: boolean
  isExplicitlyLinked: (photo: InspectionDraftPhoto) => boolean
  hasValidHistoricalEvidence: (photo: InspectionDraftPhoto) => boolean
  actionLabel: (photo: InspectionDraftPhoto) => string
  unlinkedMessage: (photo: InspectionDraftPhoto) => string
  photoState: (photo: InspectionDraftPhoto) => DetailPhotoState
  photoMessage: (photo: InspectionDraftPhoto) => string
  aiStatus: (photo: InspectionDraftPhoto) => string
  detectionKey: (result?: InspectionDetectionResult) => string
  detectionClauseLabel: (result?: InspectionDetectionResult) => string
  detectionCount: (result?: InspectionDetectionResult) => number
  detectionConfidenceText: (result?: InspectionDetectionResult) => string
  canConfirmAiDecision: (photo: InspectionDraftPhoto) => boolean
  isAiDecisionBusy: (photo: InspectionDraftPhoto) => boolean
}>()

const emit = defineEmits<{
  supplement: [photo?: InspectionDraftPhoto]
  preview: [photo: InspectionDraftPhoto, event: MouseEvent]
  retry: [photo: InspectionDraftPhoto]
  imageError: [photo: InspectionDraftPhoto]
  confirmAiDecision: [photo: InspectionDraftPhoto]
}>()

function preview(photo: InspectionDraftPhoto, event: MouseEvent) {
  emit('preview', photo, event)
}
</script>

<template>
  <section v-if="props.unlinkedPhotos.length" class="inspection-detail-section">
    <h4>未关联现场证据</h4>
    <p class="inspection-evidence-note">以下图片未在任何条款的图片关联中出现，系统不会自动归因或影响扣分；请由老板或督导人工选择历史条款。</p>
    <div class="inspection-evidence-list unlinked-evidence-list">
      <article v-for="photo in props.unlinkedPhotos" :key="`unlinked-${photo.attachmentId || photo.fileName}`" class="inspection-evidence-item">
        <span class="inspection-evidence-thumb" :aria-label="`${photo.fileName || '未关联证据'} 尚未关联历史条款，不能预览原图`">
          <XCircle :size="18" />
        </span>
        <span><b>{{ photo.fileName || '现场照片' }}</b><small class="evidence-unlinked">{{ props.unlinkedMessage(photo) }}</small></span>
        <button
          v-if="props.canSupplement"
          class="evidence-associate"
          type="button"
          :disabled="props.candidatesLoading"
          @click="emit('supplement', photo)"
        >
          <Link2 v-if="props.hasValidHistoricalEvidence(photo)" :size="14" />
          <ImagePlus v-else :size="14" />
          {{ props.candidatesLoading ? '正在核验证据' : props.actionLabel(photo) }}
        </button>
        <span v-else class="evidence-status unlinked">{{ props.unlinkedMessage(photo) }}</span>
      </article>
    </div>
  </section>

  <section v-if="props.pendingAiPhotos.length" class="inspection-detail-section">
    <h4>AI 待确认识别结果（不计分）</h4>
    <p class="inspection-evidence-note">AI 建议独立展示；未匹配或待确认的结果不会写入本次得分、扣分明细或历史快照。</p>
    <div class="inspection-detail-detections">
      <article v-for="photo in props.pendingAiPhotos" :key="`ai-${props.detectionKey(photo.detection) || photo.attachmentId || photo.fileName}`" class="inspection-detail-detection ai-pending-card">
        <button
          v-if="props.isExplicitlyLinked(photo)"
          class="inspection-evidence-thumb"
          type="button"
          :disabled="props.photoState(photo).status !== 'ready'"
          :aria-label="`预览 ${photo.fileName || 'AI识别图片'}`"
          @click="preview(photo, $event)"
        >
          <img
            v-if="props.photoState(photo).status === 'ready' && props.photoState(photo).url"
            :src="props.photoState(photo).url"
            :alt="`${photo.fileName || 'AI识别图片'} 缩略图`"
            @error="emit('imageError', photo)"
          />
          <LoaderCircle v-else-if="props.photoState(photo).status === 'loading'" class="spin" :size="18" />
          <XCircle v-else :size="18" />
        </button>
        <span v-else class="inspection-evidence-thumb" :aria-label="`${photo.fileName || 'AI识别图片'} 尚未关联历史条款，不能预览原图`"><XCircle :size="18" /></span>
        <div>
          <span>模型识别结果 · {{ photo.fileName || '现场图片' }}</span>
          <b>{{ props.detectionClauseLabel(photo.detection) }}</b>
          <small>{{ props.detectionCount(photo.detection) ? `识别到 ${props.detectionCount(photo.detection)} 个疑似问题` : '未识别到明确问题' }} · 置信度 {{ props.detectionConfidenceText(photo.detection) }}</small>
          <small class="inspection-model-only-hint">{{ props.aiStatus(photo) }}</small>
          <small v-if="props.isExplicitlyLinked(photo) && props.photoMessage(photo)" :class="`evidence-${props.photoState(photo).status}`">{{ props.photoMessage(photo) }}</small>
          <small v-else-if="!props.isExplicitlyLinked(photo)" class="evidence-unlinked">待人工关联历史条款，不能预览原图</small>
        </div>
        <div class="inspection-detail-decision">
          <span class="decision-pending">{{ props.aiStatus(photo) }}</span>
          <button
            v-if="props.canConfirmAiDecision(photo)"
            class="primary-button"
            type="button"
            :disabled="props.isAiDecisionBusy(photo)"
            @click="emit('confirmAiDecision', photo)"
          >人工确认并计分</button>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.inspection-evidence-list { display: grid; min-width: 210px; gap: 7px; }
.inspection-evidence-item { display: grid; grid-template-columns: 50px minmax(0, 1fr) auto; align-items: center; gap: 7px; min-height: 54px; padding: 5px; border: 1px solid var(--line); border-radius: 7px; background: #fff; }
.inspection-evidence-thumb { display: grid; width: 50px; height: 42px; place-items: center; overflow: hidden; padding: 0; border: 0; border-radius: 5px; background: var(--ds-surface-muted); color: var(--muted); }
.inspection-evidence-thumb:not(:disabled):hover, .inspection-evidence-thumb:not(:disabled):focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
.inspection-evidence-thumb:disabled { cursor: default; opacity: 1; }
.inspection-evidence-thumb img { display: block; width: 100%; height: 100%; object-fit: cover; }
.inspection-evidence-item > span { display: grid; min-width: 0; gap: 2px; }
.inspection-evidence-item b, .inspection-evidence-item small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.inspection-evidence-item b { font-size: 12px; }
.inspection-evidence-item small, .inspection-evidence-note { color: var(--muted); font-size: 12px; line-height: 1.5; }
.inspection-evidence-item .evidence-forbidden, .inspection-evidence-item .evidence-missing, .inspection-evidence-item .evidence-failed { color: var(--bad); }
.evidence-associate { display: inline-flex; align-items: center; justify-content: center; gap: 4px; min-height: 28px; padding: 0 7px; border: 1px solid var(--primary); border-radius: 5px; background: var(--primary-soft); color: var(--primary-dark); font-size: 12px; font-weight: 800; white-space: nowrap; }
.evidence-associate:hover, .evidence-associate:focus-visible { background: #d8efed; }
.evidence-unlinked { color: var(--warn); font-size: 12px; font-weight: 700; }
.evidence-status { display: inline-flex; max-width: 230px; padding: 4px 7px; border-radius: 5px; background: var(--ds-surface-muted); color: var(--ds-secondary); font-size: 12px; font-weight: 700; line-height: 1.45; }
.evidence-status.unlinked { background: var(--ds-warning-soft); color: #77440d; }
.inspection-evidence-note { margin: -2px 0 10px; }
.unlinked-evidence-list { grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); }
.unlinked-evidence-list .evidence-status { justify-self: end; }
.inspection-detail-detections { display: grid; gap: 8px; margin-top: 12px; }
.inspection-detail-detection { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 8px; background: #fbfdfd; }
.inspection-detail-detection > div:first-child { display: grid; min-width: 0; gap: 3px; }
.inspection-detail-detection span, .inspection-detail-detection small { color: var(--muted); font-size: 12px; }
.inspection-detail-decision { display: flex; flex: none; align-items: center; gap: 8px; }
.ai-pending-card { display: grid; grid-template-columns: 50px minmax(0, 1fr) auto; }
.decision-pending { color: var(--muted); }
.inspection-model-only-hint { color: #8a570b !important; }
.spin { animation: inspection-evidence-spin 0.9s linear infinite; }
@keyframes inspection-evidence-spin { to { transform: rotate(360deg); } }
@media (max-width: 860px) {
  .inspection-detail-detection { align-items: flex-start; flex-direction: column; }
  .ai-pending-card { grid-template-columns: 50px minmax(0, 1fr); }
  .ai-pending-card .inspection-detail-decision { grid-column: 1 / -1; }
}
</style>
