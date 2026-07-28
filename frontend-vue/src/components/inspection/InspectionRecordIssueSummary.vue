<script setup lang="ts">
import { LoaderCircle, XCircle } from 'lucide-vue-next'
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
  formatScore: (value?: number) => string
  itemDeduction: (item: InspectionItemResult) => number
  riskLabel: (value?: string) => string
  clausePhotos: (record: InspectionRecord, item: InspectionItemResult) => InspectionDraftPhoto[]
  photoState: (photo: InspectionDraftPhoto) => DetailPhotoState
  photoMessage: (photo: InspectionDraftPhoto) => string
}>()

const emit = defineEmits<{
  preview: [photo: InspectionDraftPhoto, event: MouseEvent]
  retry: [photo: InspectionDraftPhoto]
  imageError: [photo: InspectionDraftPhoto]
}>()

function heading() {
  return `本次发现的问题（${props.items.length}项）`
}
</script>

<template>
  <section class="inspection-priority-issues" role="region" :aria-label="heading()">
    <header class="priority-issues-head">
      <div>
        <span>优先处理</span>
        <h4>{{ heading() }}</h4>
      </div>
      <p>扣分、红线及人工标记的问题集中展示，全部历史条款可在下方展开审计。</p>
    </header>

    <div v-if="!props.items.length" class="priority-issues-empty">本次巡检未记录问题。</div>
    <div v-else class="priority-issues-list">
      <article v-for="item in props.items" :key="`priority-${item.standardItemId}`" class="priority-issue-card">
        <div class="priority-issue-content">
          <div class="priority-issue-line">
            <div class="priority-issue-code">
              <b>{{ item.code || '未编号' }}</b>
              <span>{{ props.riskLabel(item.riskLevel) }}</span>
            </div>
            <strong :class="{ deducted: props.itemDeduction(item) > 0 }">
              {{ props.itemDeduction(item) > 0 ? `扣 ${props.formatScore(props.itemDeduction(item))} 分` : '已标记问题' }}
            </strong>
          </div>
          <h5>{{ item.title || item.description || '未命名条款' }}</h5>
          <p>{{ item.deductionReason || '未填写问题原因' }}</p>
        </div>

        <div class="priority-issue-evidence">
          <span>关联证据</span>
          <div v-if="props.clausePhotos(props.record, item).length" class="priority-evidence-list">
            <article
              v-for="photo in props.clausePhotos(props.record, item)"
              :key="`${item.standardItemId}-${photo.attachmentId || photo.fileName}`"
              class="priority-evidence-item"
            >
              <button
                type="button"
                class="priority-evidence-thumb"
                :disabled="props.photoState(photo).status !== 'ready'"
                :aria-label="`预览 ${photo.fileName || '现场证据'}`"
                @click="emit('preview', photo, $event)"
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
                <small v-if="props.photoMessage(photo)" :class="`evidence-${props.photoState(photo).status}`">
                  {{ props.photoMessage(photo) }}
                </small>
              </span>
              <button
                v-if="['failed', 'missing'].includes(props.photoState(photo).status) && photo.attachmentId"
                class="priority-evidence-retry"
                type="button"
                @click="emit('retry', photo)"
              >重试</button>
            </article>
          </div>
          <small v-else class="priority-evidence-unlinked">未关联证据</small>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.inspection-priority-issues { margin-bottom: 14px; padding: 13px 14px; border: 1px solid rgba(217, 119, 6, .28); border-radius: 10px; background: var(--ds-warning-soft, #fff8e8); }
.priority-issues-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 10px; }
.priority-issues-head > div { display: grid; gap: 3px; }
.priority-issues-head span { color: var(--warn); font-size: 12px; font-weight: 800; }
.priority-issues-head h4 { margin: 0; color: var(--ink); font-size: 16px; }
.priority-issues-head p { max-width: 520px; margin: 0; color: var(--muted); font-size: 12px; line-height: 1.55; text-align: right; }
.priority-issues-list { display: grid; gap: 9px; }
.priority-issue-card { display: grid; grid-template-columns: minmax(0, 1fr) minmax(230px, .55fr); gap: 14px; padding: 11px 12px; border: 1px solid rgba(217, 119, 6, .2); border-radius: 9px; background: #fff; }
.priority-issue-content { display: grid; min-width: 0; align-content: start; gap: 5px; }
.priority-issue-line { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.priority-issue-code { display: flex; min-width: 0; align-items: center; gap: 7px; }
.priority-issue-code > b { color: var(--ink); font-size: 13px; }
.priority-issue-code > span { display: inline-flex; min-height: 22px; align-items: center; padding: 2px 7px; border-radius: 999px; background: var(--ds-surface-muted); color: var(--muted); font-size: 11px; font-weight: 800; }
.priority-issue-line > strong { flex: none; color: var(--muted); font-size: 14px; white-space: nowrap; }
.priority-issue-line > strong.deducted { color: var(--bad); }
.priority-issue-content h5 { margin: 0; color: var(--ink); font-size: 14px; }
.priority-issue-content p { margin: 0; color: var(--ink); font-size: 13px; line-height: 1.55; }
.priority-issue-evidence { display: grid; min-width: 0; align-content: start; gap: 6px; }
.priority-issue-evidence > span { color: var(--muted); font-size: 12px; font-weight: 800; }
.priority-evidence-list { display: grid; gap: 6px; }
.priority-evidence-item { display: grid; grid-template-columns: 56px minmax(0, 1fr) auto; align-items: center; gap: 8px; min-height: 54px; }
.priority-evidence-thumb { display: grid; width: 56px; height: 48px; place-items: center; overflow: hidden; padding: 0; border: 0; border-radius: 6px; background: var(--ds-surface-muted); color: var(--muted); }
.priority-evidence-thumb:not(:disabled):hover, .priority-evidence-thumb:not(:disabled):focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
.priority-evidence-thumb:disabled { cursor: default; opacity: 1; }
.priority-evidence-thumb img { display: block; width: 100%; height: 100%; object-fit: cover; }
.priority-evidence-item > span { display: grid; min-width: 0; gap: 2px; }
.priority-evidence-item b, .priority-evidence-item small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.priority-evidence-item b { font-size: 12px; }
.priority-evidence-item small { color: var(--muted); font-size: 11px; }
.priority-evidence-item .evidence-forbidden, .priority-evidence-item .evidence-missing, .priority-evidence-item .evidence-failed { color: var(--bad); }
.priority-evidence-retry { min-height: 28px; padding: 0 6px; border: 0; border-radius: 5px; background: transparent; color: var(--primary-dark); font-size: 12px; font-weight: 700; }
.priority-evidence-unlinked { color: var(--warn); font-size: 12px; font-weight: 700; }
.priority-issues-empty { color: var(--muted); font-size: 13px; }
.spin { animation: priority-evidence-spin .9s linear infinite; }
@keyframes priority-evidence-spin { to { transform: rotate(360deg); } }
@media (max-width: 720px) {
  .inspection-priority-issues { padding: 12px; }
  .priority-issues-head, .priority-issue-card { grid-template-columns: 1fr; }
  .priority-issues-head { display: grid; }
  .priority-issues-head p { max-width: none; text-align: left; }
  .priority-issue-card { display: grid; }
}
@media (prefers-reduced-motion: reduce) {
  .spin { animation: none; }
}
</style>
