<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  archiveKnowledgeBaseDocument,
  downloadKnowledgeBaseDocument,
  knowledgeBaseDocuments,
  publishKnowledgeBaseDocument,
  searchKnowledgeBaseSummary,
  uploadKnowledgeBaseDocument,
  type KnowledgeBaseDocument,
  type KnowledgeBaseTopicSearchResult,
  type KnowledgeBaseVisibility,
} from '../api/knowledgeBase'
import { ApiError } from '../api/http'
import KnowledgeDocumentList from '../components/knowledge-base/KnowledgeDocumentList.vue'
import KnowledgeSearchPanel from '../components/knowledge-base/KnowledgeSearchPanel.vue'
import KnowledgeUploadForm from '../components/knowledge-base/KnowledgeUploadForm.vue'
import type { KnowledgeBaseUploadFormModel } from '../components/knowledge-base/models'
import { PERMISSIONS } from '../permissions/permissions'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const query = ref('')
const searching = ref(false)
const searchMessage = ref('')
const searchSummary = ref('')
const searchTopics = ref<KnowledgeBaseTopicSearchResult[]>([])
const records = ref<KnowledgeBaseDocument[]>([])
const loadingRecords = ref(false)
const saving = ref(false)
const file = ref<File | null>(null)
const uploadResetKey = ref(0)
const error = ref('')
const success = ref('')
const busyDocumentId = ref<number | null>(null)
const canManage = computed(() => auth.hasPermission(PERMISSIONS.KNOWLEDGE_BASE_MANAGE))
const isBoss = computed(() => auth.role === 'BOSS')
const form = ref<KnowledgeBaseUploadFormModel>({
  title: '',
  category: '门店运营',
  visibility: (auth.role === 'SUPERVISOR' ? 'STORE' : 'TENANT') as KnowledgeBaseVisibility,
  roleScopes: [] as string[],
  storeScopesText: '',
  topicMode: 'NEW',
  topicId: null,
  topicName: '',
  relationType: 'ORIGINAL',
  predecessorDocumentId: null,
})

watch(isBoss, (boss) => {
  if (!boss && form.value.visibility !== 'STORE') form.value.visibility = 'STORE'
}, { immediate: true })

watch(() => form.value.visibility, (visibility) => {
  if (visibility !== 'ROLE') form.value.roleScopes = []
  if (visibility !== 'STORE') form.value.storeScopesText = ''
})

async function loadRecords() {
  if (!canManage.value || loadingRecords.value) return
  loadingRecords.value = true
  try {
    records.value = await knowledgeBaseDocuments()
  } catch (reason) {
    error.value = message(reason)
  } finally {
    loadingRecords.value = false
  }
}

async function search() {
  error.value = ''
  success.value = ''
  const value = query.value.trim()
  if (value.length < 2) {
    searchTopics.value = []
    searchSummary.value = ''
    searchMessage.value = '请输入至少两个字符再检索。'
    return
  }
  searching.value = true
  searchMessage.value = ''
  try {
    const response = await searchKnowledgeBaseSummary(value)
    searchTopics.value = response.topics
    searchSummary.value = response.summary || ''
    searchMessage.value = searchTopics.value.length ? '' : '未找到你有权限查看的相关资料。'
  } catch (reason) {
    searchTopics.value = []
    searchSummary.value = ''
    error.value = message(reason)
  } finally {
    searching.value = false
  }
}

function chooseFile(event: Event) {
  const selected = (event.target as HTMLInputElement).files?.[0] || null
  file.value = selected
  if (selected && !form.value.title.trim()) form.value.title = selected.name.replace(/\.[^.]+$/, '')
}

async function upload() {
  error.value = ''
  success.value = ''
  if (!file.value) {
    error.value = '请先选择资料文件。'
    return
  }
  if (form.value.topicMode === 'NEW' && !form.value.topicName.trim()) {
    error.value = '请填写知识主题名称。'
    return
  }
  if (form.value.topicMode === 'EXISTING' && !form.value.topicId) {
    error.value = '请选择要归入的知识主题。'
    return
  }
  if (form.value.topicMode === 'EXISTING' && !form.value.predecessorDocumentId) {
    error.value = '请选择关联的已发布版本。'
    return
  }
  if (form.value.visibility === 'ROLE' && !form.value.roleScopes.length) {
    error.value = '按角色发布时请至少选择一个角色。'
    return
  }
  const storeScopes = splitScopes(form.value.storeScopesText)
  if (form.value.visibility === 'STORE' && !storeScopes.length) {
    error.value = '按门店发布时请填写至少一个门店编号。'
    return
  }
  saving.value = true
  try {
    const document = await uploadKnowledgeBaseDocument({
      file: file.value,
      title: form.value.title,
      category: form.value.category,
      visibility: form.value.visibility,
      roleScopes: form.value.visibility === 'ROLE' ? form.value.roleScopes : [],
      storeScopes: form.value.visibility === 'STORE' ? storeScopes : [],
      topicId: form.value.topicMode === 'EXISTING' ? form.value.topicId ?? undefined : undefined,
      topicName: form.value.topicMode === 'NEW' ? form.value.topicName : undefined,
      relationType: form.value.relationType,
      predecessorDocumentId: form.value.topicMode === 'EXISTING'
        ? form.value.predecessorDocumentId ?? undefined
        : undefined,
    })
    success.value = `已归入“${document.topicName}”第 ${document.versionNo} 版，并完成 ${document.chunkCount} 段索引，请确认后发布。`
    resetForm()
    await loadRecords()
  } catch (reason) {
    error.value = message(reason)
  } finally {
    saving.value = false
  }
}

async function publish(document: KnowledgeBaseDocument) {
  busyDocumentId.value = document.id
  error.value = ''
  try {
    await publishKnowledgeBaseDocument(document.id)
    success.value = `“${document.title}”已发布。`
    await loadRecords()
  } catch (reason) {
    error.value = message(reason)
  } finally {
    busyDocumentId.value = null
  }
}

async function archive(document: KnowledgeBaseDocument) {
  if (!window.confirm(`确认下架“${document.title}”？下架后普通用户将无法检索。`)) return
  busyDocumentId.value = document.id
  error.value = ''
  try {
    await archiveKnowledgeBaseDocument(document.id)
    success.value = `“${document.title}”已下架。`
    await loadRecords()
  } catch (reason) {
    error.value = message(reason)
  } finally {
    busyDocumentId.value = null
  }
}

async function download(document: KnowledgeBaseDocument) {
  busyDocumentId.value = document.id
  error.value = ''
  try {
    await downloadKnowledgeBaseDocument(document.id, document.originalFileName)
  } catch (reason) {
    error.value = message(reason)
  } finally {
    busyDocumentId.value = null
  }
}

function resetForm() {
  file.value = null
  uploadResetKey.value += 1
  form.value = {
    title: '',
    category: '门店运营',
    visibility: auth.role === 'SUPERVISOR' ? 'STORE' : 'TENANT',
    roleScopes: [],
    storeScopesText: '',
    topicMode: 'NEW',
    topicId: null,
    topicName: '',
    relationType: 'ORIGINAL',
    predecessorDocumentId: null,
  }
}

function splitScopes(value: string) {
  return value.split(/[,，;；\s]+/).map((item) => item.trim()).filter(Boolean)
}

function message(reason: unknown) {
  return reason instanceof ApiError ? reason.message : '操作未完成，请稍后重试。'
}

onMounted(() => void loadRecords())
</script>

<template>
  <section class="knowledge-base-page page-shell">
    <header class="page-head">
      <div>
        <p class="eyebrow">内部资料检索</p>
        <h1>知识库</h1>
      </div>
    </header>

    <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
    <p v-if="success" class="notice notice-success" role="status">{{ success }}</p>

    <KnowledgeSearchPanel
      :query="query"
      :searching="searching"
      :message="searchMessage"
      :summary="searchSummary"
      :topics="searchTopics"
      @update:query="query = $event"
      @search="search"
    />

    <template v-if="canManage">
      <section class="knowledge-layout">
        <KnowledgeUploadForm
          :key="uploadResetKey"
          v-model="form"
          :records="records"
          :file="file"
          :saving="saving"
          :is-boss="isBoss"
          @choose-file="chooseFile"
          @submit="upload"
        />
        <KnowledgeDocumentList
          :records="records"
          :loading="loadingRecords"
          :busy-document-id="busyDocumentId"
          @publish="publish"
          @archive="archive"
          @download="download"
        />
      </section>
    </template>
  </section>
</template>

<style scoped>
.knowledge-base-page { display: grid; gap: 18px; padding-bottom: 32px; }
.page-head { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; }
.eyebrow { margin: 0 0 5px; color: var(--ds-primary, #126c68); font-size: 13px; font-weight: 800; letter-spacing: .06em; }
.page-head h1 { margin: 0; color: var(--ds-text, #183434); }
.knowledge-layout { display: grid; grid-template-columns: minmax(300px, .85fr) minmax(440px, 1.4fr); gap: 18px; align-items: start; }
.notice { margin: 0; border-radius: 8px; padding: 10px 13px; font-size: 14px; }.notice-error { border: 1px solid #f2c9c9; background: #fff4f4; color: #a13131; }.notice-success { border: 1px solid #b7e1cf; background: #f0fbf6; color: #087447; }
@media (max-width: 960px) { .knowledge-layout { grid-template-columns: 1fr; } }
@media (max-width: 620px) { .page-head { display: block; } }
</style>
