<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Archive, Download, FileSearch, FileUp, Search } from 'lucide-vue-next'
import {
  archiveKnowledgeBaseDocument,
  downloadKnowledgeBaseDocument,
  knowledgeBaseDocuments,
  publishKnowledgeBaseDocument,
  searchKnowledgeBase,
  uploadKnowledgeBaseDocument,
  type KnowledgeBaseDocument,
  type KnowledgeBaseSearchResult,
  type KnowledgeBaseVisibility,
} from '../api/knowledgeBase'
import { ApiError } from '../api/http'
import { PERMISSIONS } from '../permissions/permissions'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const query = ref('')
const searching = ref(false)
const searchMessage = ref('')
const results = ref<KnowledgeBaseSearchResult[]>([])
const records = ref<KnowledgeBaseDocument[]>([])
const loadingRecords = ref(false)
const saving = ref(false)
const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const error = ref('')
const success = ref('')
const busyDocumentId = ref<number | null>(null)
const canManage = computed(() => auth.hasPermission(PERMISSIONS.KNOWLEDGE_BASE_MANAGE))
const isBoss = computed(() => auth.role === 'BOSS')
const form = ref({
  title: '',
  category: '门店运营',
  visibility: (auth.role === 'SUPERVISOR' ? 'STORE' : 'TENANT') as KnowledgeBaseVisibility,
  roleScopes: [] as string[],
  storeScopesText: '',
})

const roles = [
  { code: 'EMPLOYEE', label: '员工' },
  { code: 'STORE_MANAGER', label: '店长' },
  { code: 'SUPERVISOR', label: '督导' },
  { code: 'WAREHOUSE', label: '仓库管理员' },
  { code: 'FINANCE', label: '财务' },
  { code: 'BOSS', label: '老板' },
]

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
    results.value = []
    searchMessage.value = '请输入至少两个字符再检索。'
    return
  }
  searching.value = true
  searchMessage.value = ''
  try {
    results.value = await searchKnowledgeBase(value)
    searchMessage.value = results.value.length ? '' : '未找到你有权限查看的相关资料。'
  } catch (reason) {
    results.value = []
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
    })
    success.value = `已建立“${document.title}”草稿并完成 ${document.chunkCount} 段本地向量索引，请确认后发布。`
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
  if (fileInput.value) fileInput.value.value = ''
  form.value = {
    title: '',
    category: '门店运营',
    visibility: auth.role === 'SUPERVISOR' ? 'STORE' : 'TENANT',
    roleScopes: [],
    storeScopesText: '',
  }
}

function splitScopes(value: string) {
  return value.split(/[,，;；\s]+/).map((item) => item.trim()).filter(Boolean)
}

function scopeLabel(document: KnowledgeBaseDocument) {
  if (document.visibility === 'TENANT') return '全企业可检索'
  if (document.visibility === 'ROLE') return `角色：${document.roleScopes.join('、') || '-'}`
  return `门店：${document.storeScopes.join('、') || '-'}`
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
        <p>检索已发布且符合本人角色、门店范围的资料；原始 Word、Excel 文件不会发送到外部模型。</p>
      </div>
    </header>

    <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
    <p v-if="success" class="notice notice-success" role="status">{{ success }}</p>

    <section class="content-card search-card" aria-labelledby="knowledge-search-title">
      <div class="section-title">
        <FileSearch :size="20" aria-hidden="true" />
        <div><h2 id="knowledge-search-title">检索资料</h2><p>输入制度、流程、商品、异常处理等关键词，结果会显示文件与工作表/正文来源。</p></div>
      </div>
      <form class="search-form" @submit.prevent="search">
        <label class="sr-only" for="knowledge-query">检索内容</label>
        <input id="knowledge-query" v-model="query" maxlength="300" placeholder="例如：门店交接班流程" autocomplete="off">
        <button type="submit" :disabled="searching"><Search :size="17" />{{ searching ? '检索中…' : '检索' }}</button>
      </form>
      <p v-if="searchMessage" class="empty-copy">{{ searchMessage }}</p>
      <div v-if="results.length" class="search-results" aria-live="polite">
        <article v-for="result in results" :key="`${result.documentId}-${result.sourceLocator}-${result.excerpt}`" class="search-result">
          <div class="result-meta"><strong>{{ result.title }}</strong><span>{{ result.category }}</span><span>{{ result.sourceLocator }}</span><small>匹配度 {{ Math.round(result.score * 100) }}%</small></div>
          <p>{{ result.excerpt }}</p>
        </article>
      </div>
    </section>

    <template v-if="canManage">
      <section class="knowledge-layout">
        <form class="content-card upload-card" @submit.prevent="upload">
          <div class="section-title"><FileUp :size="20" aria-hidden="true" /><div><h2>上传资料</h2><p>支持 .docx、.xlsx、.xls、.csv、.txt，单个文件不超过 20MB。</p></div></div>
          <label>资料文件<input ref="fileInput" type="file" accept=".docx,.xlsx,.xls,.csv,.txt" @change="chooseFile"></label>
          <small v-if="file">已选择：{{ file.name }}（{{ formatBytes(file.size) }}）</small>
          <label>资料标题<input v-model.trim="form.title" maxlength="200" placeholder="留空时使用文件名"></label>
          <label>资料分类<input v-model.trim="form.category" maxlength="64" placeholder="例如：门店运营"></label>
          <label>适用范围
            <select v-model="form.visibility" :disabled="!isBoss">
              <option value="TENANT">全企业</option>
              <option value="ROLE">指定角色</option>
              <option value="STORE">指定门店</option>
            </select>
          </label>
          <p v-if="!isBoss" class="scope-help">督导只能上传和发布本人数据范围内的门店资料。</p>
          <fieldset v-if="form.visibility === 'ROLE'" class="role-scopes"><legend>适用角色</legend><label v-for="role in roles" :key="role.code" class="check-option"><input v-model="form.roleScopes" type="checkbox" :value="role.code">{{ role.label }}</label></fieldset>
          <label v-if="form.visibility === 'STORE'">门店编号
            <input v-model.trim="form.storeScopesText" maxlength="1000" placeholder="多个门店用逗号隔开，例如 store-a,store-b">
          </label>
          <button class="primary-button" type="submit" :disabled="saving"><FileUp :size="17" />{{ saving ? '正在建立索引…' : '上传并建立向量索引' }}</button>
        </form>

        <section class="content-card documents-card" aria-labelledby="knowledge-documents-title">
          <div class="section-title"><Archive :size="20" aria-hidden="true" /><div><h2 id="knowledge-documents-title">资料管理</h2><p>资料先作为草稿保存，发布后才可被符合范围的账号检索。</p></div></div>
          <p v-if="loadingRecords" class="empty-copy">正在加载资料…</p>
          <p v-else-if="!records.length" class="empty-copy">暂无可管理的资料。</p>
          <article v-for="document in records" :key="document.id" class="document-row">
            <div class="document-main"><div class="document-title"><strong>{{ document.title }}</strong><span :class="['status-tag', document.status.toLowerCase()]">{{ statusLabel(document.status) }}</span></div><p>{{ document.category }} · {{ document.originalFileName }} · {{ formatBytes(document.fileSize) }}</p><small>{{ scopeLabel(document) }} · {{ document.chunkCount }} 段索引 · 更新于 {{ formatDate(document.updatedAt) }}</small></div>
            <div class="document-actions">
              <button type="button" :disabled="busyDocumentId === document.id" @click="download(document)"><Download :size="15" />下载</button>
              <button v-if="document.status === 'DRAFT'" type="button" class="publish" :disabled="busyDocumentId === document.id" @click="publish(document)">发布</button>
              <button v-if="document.status !== 'ARCHIVED'" type="button" class="archive" :disabled="busyDocumentId === document.id" @click="archive(document)">下架</button>
            </div>
          </article>
        </section>
      </section>
    </template>
  </section>
</template>

<style scoped>
.knowledge-base-page { display: grid; gap: 18px; padding-bottom: 32px; }
.page-head { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; }
.eyebrow { margin: 0 0 5px; color: var(--ds-primary, #126c68); font-size: 13px; font-weight: 800; letter-spacing: .06em; }
.page-head h1, .section-title h2 { margin: 0; color: var(--ds-text, #183434); }
.page-head p:not(.eyebrow), .section-title p, .document-main p, .document-main small, .empty-copy, .scope-help { color: var(--ds-muted, #607576); }
.page-head p:not(.eyebrow) { margin: 7px 0 0; max-width: 760px; }
.content-card { border: 1px solid var(--ds-line, #dbe8e6); border-radius: 12px; background: #fff; padding: 20px; box-shadow: 0 8px 24px rgba(20, 71, 68, .035); }
.section-title { display: flex; align-items: flex-start; gap: 10px; color: var(--ds-primary, #126c68); }
.section-title h2 { font-size: 18px; }
.section-title p { margin: 4px 0 0; font-size: 13px; }
.search-form { display: flex; gap: 10px; margin-top: 18px; }
.search-form input, .upload-card input, .upload-card select { min-width: 0; width: 100%; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; background: #fff; color: var(--ds-text, #183434); font: inherit; }
.search-form input { min-height: 42px; padding: 0 13px; }
.search-form button, .primary-button, .document-actions button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; border-radius: 8px; border: 1px solid var(--ds-line, #dbe8e6); background: #fff; color: var(--ds-primary, #126c68); font: inherit; font-weight: 800; cursor: pointer; }
.search-form button { min-width: 94px; padding: 0 14px; }
.search-form button:hover, .document-actions button:hover { background: #eff9f7; }
button:disabled { cursor: not-allowed; opacity: .55; }
.search-results { display: grid; gap: 10px; margin-top: 16px; }
.search-result { border-left: 3px solid var(--ds-primary, #126c68); border-radius: 6px; background: #f7fbfa; padding: 12px 14px; }
.result-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; color: var(--ds-muted, #607576); font-size: 12px; }
.result-meta strong { color: var(--ds-text, #183434); font-size: 14px; }
.result-meta span { padding: 2px 7px; border-radius: 20px; background: #e7f3f0; }
.result-meta small { margin-left: auto; color: var(--ds-primary, #126c68); font-weight: 800; }
.search-result p { margin: 8px 0 0; white-space: pre-wrap; line-height: 1.65; color: #354d4d; }
.knowledge-layout { display: grid; grid-template-columns: minmax(300px, .85fr) minmax(440px, 1.4fr); gap: 18px; align-items: start; }
.upload-card { display: grid; gap: 13px; }
.upload-card > label { display: grid; gap: 6px; color: var(--ds-text, #183434); font-size: 13px; font-weight: 800; }
.upload-card input, .upload-card select { min-height: 40px; padding: 0 10px; font-weight: 400; }
.upload-card input[type='file'] { padding: 7px 10px; }
.upload-card small { overflow-wrap: anywhere; color: var(--ds-muted, #607576); }
.role-scopes { display: flex; flex-wrap: wrap; gap: 9px 14px; margin: 0; padding: 11px; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; }
.role-scopes legend { padding: 0 4px; color: var(--ds-muted, #607576); font-size: 12px; }
.check-option { display: inline-flex !important; width: auto !important; align-items: center; gap: 6px; font-weight: 500 !important; }
.check-option input { width: 15px !important; min-height: 15px !important; padding: 0 !important; }
.scope-help { margin: -4px 0 0; font-size: 12px; }
.primary-button { min-height: 42px; border-color: var(--ds-primary, #126c68); background: var(--ds-primary, #126c68); color: #fff; }
.documents-card { display: grid; gap: 13px; }
.document-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 12px; border-top: 1px solid var(--ds-line, #dbe8e6); padding-top: 14px; }
.document-main { min-width: 0; }
.document-title { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.document-title strong { overflow-wrap: anywhere; color: var(--ds-text, #183434); }
.document-main p, .document-main small { display: block; margin: 5px 0 0; font-size: 12px; overflow-wrap: anywhere; }
.status-tag { padding: 2px 8px; border-radius: 20px; font-size: 12px; font-weight: 800; }
.status-tag.draft { color: #9a6200; background: #fff4d9; }.status-tag.published { color: #09744e; background: #e2f6ec; }.status-tag.archived { color: #627170; background: #edf1f0; }
.document-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 7px; }
.document-actions button { min-height: 32px; padding: 0 10px; font-size: 12px; }.document-actions .publish { border-color: var(--ds-primary, #126c68); background: var(--ds-primary, #126c68); color: #fff; }.document-actions .archive { color: #a23f3f; }
.notice { margin: 0; border-radius: 8px; padding: 10px 13px; font-size: 14px; }.notice-error { border: 1px solid #f2c9c9; background: #fff4f4; color: #a13131; }.notice-success { border: 1px solid #b7e1cf; background: #f0fbf6; color: #087447; }
.empty-copy { margin: 14px 0 0; font-size: 14px; }.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 960px) { .knowledge-layout { grid-template-columns: 1fr; }.document-row { grid-template-columns: 1fr; }.document-actions { justify-content: flex-start; } }
@media (max-width: 620px) { .page-head { display: block; }.content-card { padding: 15px; }.search-form { flex-direction: column; }.search-form button { min-height: 40px; }.result-meta small { margin-left: 0; }.document-actions button { flex: 1; } }
</style>
