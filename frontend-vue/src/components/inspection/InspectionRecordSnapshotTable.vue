<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ChevronDown, LoaderCircle, XCircle } from 'lucide-vue-next'
import type { InspectionItemResult, InspectionRecord } from '../../api/inspection'
import type { InspectionDraftPhoto } from '../../composables/useInspectionDraft'

type DetailPhotoStatus = 'loading' | 'ready' | 'forbidden' | 'missing' | 'failed'

interface DetailPhotoState {
  status: DetailPhotoStatus
  url?: string
}

const props = defineProps<{
  record: InspectionRecord
  items: InspectionItemResult[]
  heading: string
  emptyText: string
  formatScore: (value?: number) => string
  itemDeduction: (item: InspectionItemResult) => number
  riskLabel: (value?: string) => string
  clausePhotos: (record: InspectionRecord, item: InspectionItemResult) => InspectionDraftPhoto[]
  clauseEvidenceStatus: (record: InspectionRecord, item: InspectionItemResult) => string
  photoState: (photo: InspectionDraftPhoto) => DetailPhotoState
  photoMessage: (photo: InspectionDraftPhoto) => string
}>()

const emit = defineEmits<{
  preview: [photo: InspectionDraftPhoto, event: MouseEvent]
  retry: [photo: InspectionDraftPhoto]
  imageError: [photo: InspectionDraftPhoto]
}>()

const expanded = ref(false)
const snapshotLabel = computed(() => props.heading.includes('完整标准快照')
  ? `完整标准快照（${props.items.length}条）`
  : `历史条款快照（${props.items.length}条）`)
const toggleLabel = computed(() => `${expanded.value ? '收起' : '查看'}${snapshotLabel.value}`)

watch(() => props.record.id, () => {
  expanded.value = false
})

function preview(photo: InspectionDraftPhoto, event: MouseEvent) {
  emit('preview', photo, event)
}
</script>

<template>
  <section class="inspection-detail-section snapshot-disclosure" role="region" :aria-label="snapshotLabel">
    <template v-if="props.items.length">
      <button
        class="snapshot-disclosure-toggle"
        type="button"
        :aria-label="toggleLabel"
        :aria-expanded="expanded"
        aria-controls="inspection-record-snapshot"
        @click="expanded = !expanded"
      >
        <span>
          <b>{{ props.heading }}</b>
          <small>完整条款仅用于历史审计，默认收起。</small>
        </span>
        <span class="snapshot-disclosure-action">
          {{ expanded ? '收起' : '展开查看' }}
          <ChevronDown :size="18" :class="{ expanded }" />
        </span>
      </button>
      <div v-if="expanded" id="inspection-record-snapshot" class="snapshot-disclosure-content">
        <div class="inspection-table-wrap">
        <table class="inspection-table snapshot-table" :aria-label="`${props.heading}条款`">
        <thead><tr><th>条款</th><th class="r">标准分</th><th class="r">实得分</th><th class="r">实际扣分</th><th>扣分原因</th><th>现场证据</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="item in props.items" :key="`snapshot-${item.standardItemId}`">
            <td class="snapshot-clause">
              <b>{{ item.title || item.description || '—' }}</b>
              <small>{{ item.categoryName || item.dimension || '未分类' }} · {{ item.code || '未编号' }} · {{ props.riskLabel(item.riskLevel) }}</small>
            </td>
            <td class="r">{{ props.formatScore(item.standardScore) }}</td>
            <td class="r snapshot-actual-score">实得 {{ props.formatScore(item.actualScore) }} / {{ props.formatScore(item.standardScore) }}</td>
            <td class="r"><span :class="{ 'snapshot-deducted': props.itemDeduction(item) > 0 }">{{ props.itemDeduction(item) > 0 ? `扣 ${props.formatScore(props.itemDeduction(item))} 分` : '未扣分' }}</span></td>
            <td>{{ item.deductionReason || '—' }}</td>
            <td>
              <div v-if="props.clausePhotos(props.record, item).length" class="inspection-evidence-list">
                <article v-for="photo in props.clausePhotos(props.record, item)" :key="`${item.standardItemId}-${photo.attachmentId || photo.fileName}`" class="inspection-evidence-item">
                  <button
                    class="inspection-evidence-thumb"
                    type="button"
                    :disabled="props.photoState(photo).status !== 'ready'"
                    :aria-label="`预览 ${photo.fileName || '现场证据'}`"
                    @click="preview(photo, $event)"
                  >
                    <img
                      v-if="props.photoState(photo).status === 'ready' && props.photoState(photo).url"
                      :src="props.photoState(photo).url"
                      :alt="`${photo.fileName || '现场证据'} 缩略图`"
                      @error="emit('imageError', photo)"
                    />
                    <LoaderCircle v-else-if="props.photoState(photo).status === 'loading'" class="spin" :size="18" />
                    <XCircle v-else :size="18" />
                  </button>
                  <span>
                    <b :title="photo.fileName">{{ photo.fileName || '现场照片' }}</b>
                    <small v-if="props.photoMessage(photo)" :class="`evidence-${props.photoState(photo).status}`">{{ props.photoMessage(photo) }}</small>
                  </span>
                  <button
                    v-if="['failed', 'missing'].includes(props.photoState(photo).status) && photo.attachmentId"
                    class="evidence-retry"
                    type="button"
                    @click="emit('retry', photo)"
                  >重试</button>
                </article>
              </div>
              <small v-else class="evidence-unlinked">未关联证据</small>
            </td>
            <td><span class="evidence-status" :class="{ effective: props.itemDeduction(item) > 0, pending: props.clauseEvidenceStatus(props.record, item).includes('未计分'), unlinked: props.clauseEvidenceStatus(props.record, item) === '未关联证据' }">{{ props.clauseEvidenceStatus(props.record, item) }}</span></td>
          </tr>
        </tbody>
        </table>
        </div>
      </div>
    </template>
    <template v-else>
      <h4>{{ props.heading }}</h4>
      <div class="empty-state compact">{{ props.emptyText }}</div>
    </template>
  </section>
</template>

<style scoped>
.snapshot-disclosure { padding-top: 12px; border-top: 1px solid var(--line); }
.snapshot-disclosure > h4 { margin: 0 0 8px; font-size: 14px; }
.snapshot-disclosure-toggle { display: flex; width: 100%; min-height: 58px; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 9px; background: var(--ds-surface-muted); color: var(--ink); text-align: left; cursor: pointer; }
.snapshot-disclosure-toggle:hover, .snapshot-disclosure-toggle:focus-visible { border-color: var(--primary); background: var(--primary-soft); }
.snapshot-disclosure-toggle > span:first-child { display: grid; min-width: 0; gap: 3px; }
.snapshot-disclosure-toggle b { font-size: 14px; }
.snapshot-disclosure-toggle small { color: var(--muted); font-size: 12px; }
.snapshot-disclosure-action { display: inline-flex; flex: none; align-items: center; gap: 5px; color: var(--primary-dark); font-size: 12px; font-weight: 800; white-space: nowrap; }
.snapshot-disclosure-action svg { transition: transform .18s ease; }
.snapshot-disclosure-action svg.expanded { transform: rotate(180deg); }
.snapshot-disclosure-content { padding-top: 10px; }
.inspection-table-wrap { width: 100%; overflow-x: auto; }
.inspection-table { width: 100%; min-width: 760px; border-collapse: collapse; }
.inspection-table .r { text-align: right; }
.snapshot-table { min-width: 1240px; }
.snapshot-clause { min-width: 180px; }
.snapshot-clause b, .snapshot-clause small { display: block; }
.snapshot-clause small { margin-top: 3px; color: var(--muted); font-size: 12px; }
.snapshot-actual-score, .snapshot-deducted { font-variant-numeric: tabular-nums; white-space: nowrap; }
.snapshot-deducted { color: var(--bad); font-weight: 800; }
.inspection-evidence-list { display: grid; min-width: 210px; gap: 7px; }
.inspection-evidence-item { display: grid; grid-template-columns: 50px minmax(0, 1fr) auto; align-items: center; gap: 7px; min-height: 54px; padding: 5px; border: 1px solid var(--line); border-radius: 7px; background: #fff; }
.inspection-evidence-thumb { display: grid; width: 50px; height: 42px; place-items: center; overflow: hidden; padding: 0; border: 0; border-radius: 5px; background: var(--ds-surface-muted); color: var(--muted); }
.inspection-evidence-thumb:not(:disabled):hover, .inspection-evidence-thumb:not(:disabled):focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
.inspection-evidence-thumb:disabled { cursor: default; opacity: 1; }
.inspection-evidence-thumb img { display: block; width: 100%; height: 100%; object-fit: cover; }
.inspection-evidence-item > span { display: grid; min-width: 0; gap: 2px; }
.inspection-evidence-item b, .inspection-evidence-item small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.inspection-evidence-item b { font-size: 12px; }
.inspection-evidence-item small { color: var(--muted); font-size: 12px; line-height: 1.5; }
.inspection-evidence-item .evidence-forbidden, .inspection-evidence-item .evidence-missing, .inspection-evidence-item .evidence-failed { color: var(--bad); }
.evidence-retry { min-height: 28px; padding: 0 6px; border: 0; border-radius: 5px; background: transparent; color: var(--primary-dark); font-size: 12px; font-weight: 700; }
.evidence-retry:hover, .evidence-retry:focus-visible { background: var(--primary-soft); }
.evidence-unlinked { color: var(--warn); font-size: 12px; font-weight: 700; }
.evidence-status { display: inline-flex; max-width: 230px; padding: 4px 7px; border-radius: 5px; background: var(--ds-surface-muted); color: var(--ds-secondary); font-size: 12px; font-weight: 700; line-height: 1.45; }
.evidence-status.effective { background: var(--ds-danger-soft); color: var(--bad); }
.evidence-status.pending, .evidence-status.unlinked { background: var(--ds-warning-soft); color: #77440d; }
.spin { animation: inspection-snapshot-spin 0.9s linear infinite; }
@keyframes inspection-snapshot-spin { to { transform: rotate(360deg); } }
@media (max-width: 720px) {
  .snapshot-disclosure-toggle { min-height: 64px; align-items: flex-start; }
}
@media (prefers-reduced-motion: reduce) {
  .snapshot-disclosure-action svg, .spin { transition: none; animation: none; }
}
</style>
