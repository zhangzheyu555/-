<script setup lang="ts">
import { Archive, Download } from 'lucide-vue-next'
import type { KnowledgeBaseDocument } from '../../api/knowledgeBase'

defineProps<{
  records: KnowledgeBaseDocument[]
  loading: boolean
  busyDocumentId: number | null
}>()

const emit = defineEmits<{
  publish: [document: KnowledgeBaseDocument]
  archive: [document: KnowledgeBaseDocument]
  download: [document: KnowledgeBaseDocument]
}>()

function relationLabel(document: KnowledgeBaseDocument) {
  if (document.relationType === 'REPLACES') return '替代版本'
  if (document.relationType === 'SUPPLEMENTS') return '补充版本'
  return '原始版本'
}

function statusLabel(status: string) {
  return status === 'PUBLISHED' ? '已发布' : status === 'ARCHIVED' ? '已下架' : '草稿'
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(value: string | null) {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

function scopeLabel(document: KnowledgeBaseDocument) {
  if (document.visibility === 'TENANT') return '全企业可检索'
  if (document.visibility === 'ROLE') return `角色：${document.roleScopes.join('、') || '-'}`
  return `门店：${document.storeScopes.join('、') || '-'}`
}
</script>

<template>
  <section class="documents-panel" aria-labelledby="knowledge-documents-title">
    <div class="section-title">
      <Archive :size="20" aria-hidden="true" />
      <div>
        <h2 id="knowledge-documents-title">资料管理</h2>
        <p>同一主题保留独立版本和来源，替代版本发布后自动下架旧版。</p>
      </div>
    </div>
    <p v-if="loading" class="empty-copy">正在加载资料…</p>
    <p v-else-if="!records.length" class="empty-copy">暂无可管理的资料。</p>
    <article v-for="document in records" :key="document.id" class="document-row">
      <div class="document-main">
        <div class="document-title">
          <strong>{{ document.title }}</strong>
          <span :class="['status-tag', document.status.toLowerCase()]">{{ statusLabel(document.status) }}</span>
        </div>
        <p>
          {{ document.topicName }} · 第 {{ document.versionNo }} 版 · {{ relationLabel(document) }}
        </p>
        <p>{{ document.category }} · {{ document.originalFileName }} · {{ formatBytes(document.fileSize) }}</p>
        <small>
          {{ scopeLabel(document) }} · {{ document.chunkCount }} 段索引 · 更新于 {{ formatDate(document.updatedAt) }}
        </small>
      </div>
      <div class="document-actions">
        <button type="button" :disabled="busyDocumentId === document.id" title="下载原始资料" @click="emit('download', document)">
          <Download :size="15" /><span>下载</span>
        </button>
        <button
          v-if="document.status === 'DRAFT'"
          type="button"
          class="publish"
          :disabled="busyDocumentId === document.id"
          @click="emit('publish', document)"
        >发布</button>
        <button
          v-if="document.status !== 'ARCHIVED'"
          type="button"
          class="archive"
          :disabled="busyDocumentId === document.id"
          @click="emit('archive', document)"
        >下架</button>
      </div>
    </article>
  </section>
</template>

<style scoped>
.documents-panel { display: grid; gap: 13px; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; padding: 20px; background: #fff; box-shadow: 0 8px 24px rgba(20, 71, 68, .035); }
.section-title { display: flex; align-items: flex-start; gap: 10px; color: var(--ds-primary, #126c68); }
.section-title h2 { margin: 0; color: var(--ds-text, #183434); font-size: 18px; }
.section-title p { margin: 4px 0 0; color: var(--ds-muted, #607576); font-size: 13px; }
.document-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 12px; border-top: 1px solid var(--ds-line, #dbe8e6); padding-top: 14px; }
.document-main { min-width: 0; }
.document-title { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.document-title strong { overflow-wrap: anywhere; color: var(--ds-text, #183434); }
.document-main p, .document-main small, .empty-copy { display: block; margin: 5px 0 0; overflow-wrap: anywhere; color: var(--ds-muted, #607576); font-size: 12px; }
.status-tag { border-radius: 12px; padding: 2px 8px; font-size: 12px; font-weight: 800; }
.status-tag.draft { color: #9a6200; background: #fff4d9; }
.status-tag.published { color: #09744e; background: #e2f6ec; }
.status-tag.archived { color: #627170; background: #edf1f0; }
.document-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 7px; }
.document-actions button { display: inline-flex; min-height: 32px; align-items: center; justify-content: center; gap: 6px; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; padding: 0 10px; background: #fff; color: var(--ds-primary, #126c68); font: inherit; font-size: 12px; font-weight: 800; cursor: pointer; }
.document-actions button:hover { background: #eff9f7; }
.document-actions button:disabled { cursor: not-allowed; opacity: .55; }
.document-actions .publish { border-color: var(--ds-primary, #126c68); background: var(--ds-primary, #126c68); color: #fff; }
.document-actions .archive { color: #a23f3f; }
.empty-copy { margin-top: 14px; font-size: 14px; }
@media (max-width: 960px) {
  .document-row { grid-template-columns: 1fr; }
  .document-actions { justify-content: flex-start; }
}
@media (max-width: 620px) {
  .documents-panel { padding: 15px; }
  .document-actions button { flex: 1; }
}
</style>
