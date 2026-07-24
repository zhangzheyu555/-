<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Archive, Download, FileSearch, FileUp, RefreshCw, Search } from 'lucide-vue-next'
import {
  archiveKnowledgeBaseDocument,
  availableKnowledgeBaseDocuments,
  downloadKnowledgeBaseDocument,
  knowledgeBaseDocuments,
  publishKnowledgeBaseDocument,
  searchKnowledgeBase,
  uploadKnowledgeBaseDocument,
  type AvailableKnowledgeBaseDocument,
  type KnowledgeBaseDocument,
  type KnowledgeBaseSearchResult,
  type KnowledgeBaseVisibility,
} from '../api/knowledgeBase'
import { ApiError } from '../api/http'
import { getStores, type StoreInfo } from '../api/operations'
import { PERMISSIONS } from '../permissions/permissions'
import { useAuthStore } from '../stores/auth'

const MAX_UPLOAD_BYTES = 50 * 1024 * 1024
const auth = useAuthStore()
const query = ref('')
const searching = ref(false)
const searchMessage = ref('')
const results = ref<KnowledgeBaseSearchResult[]>([])
const availableRecords = ref<AvailableKnowledgeBaseDocument[]>([])
const loadingAvailableRecords = ref(false)
const records = ref<KnowledgeBaseDocument[]>([])
const loadingRecords = ref(false)
const savingMode = ref<'publish' | 'draft' | null>(null)
const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const error = ref('')
const success = ref('')
const busyDocumentId = ref<number | null>(null)
const stores = ref<StoreInfo[]>([])
const storesLoaded = ref(false)
const storesLoading = ref(false)
const storesLoadError = ref('')
const storeQuery = ref('')
const isBoss = computed(() => auth.role === 'BOSS')
const isSupervisor = computed(() => auth.role === 'SUPERVISOR')
const canManage = computed(() => (
  (isBoss.value || isSupervisor.value)
  && auth.hasPermission(PERMISSIONS.KNOWLEDGE_BASE_MANAGE)
))
const saving = computed(() => savingMode.value !== null)
let availableRecordsRequest: Promise<void> | null = null
const form = ref({
  title: '',
  category: '门店运营',
  visibility: (auth.role === 'SUPERVISOR' ? 'STORE' : 'TENANT') as KnowledgeBaseVisibility,
  roleScopes: [] as string[],
  storeScopes: [] as string[],
})
const filteredStores = computed(() => {
  const normalizedQuery = storeQuery.value.trim().toLocaleLowerCase()
  if (!normalizedQuery) return stores.value
  return stores.value.filter((store) => [
    store.name,
    store.code,
    store.area,
    store.regionCode,
  ].some((value) => String(value || '').toLocaleLowerCase().includes(normalizedQuery)))
})
const storeSelectionUnavailable = computed(() => (
  form.value.visibility === 'STORE'
  && (!storesLoaded.value || storesLoading.value || Boolean(storesLoadError.value))
))

const roles = [
  { code: 'EMPLOYEE', label: '员工' },
  { code: 'STORE_MANAGER', label: '店长' },
  { code: 'SUPERVISOR', label: '督导' },
  { code: 'WAREHOUSE', label: '仓库管理员' },
  { code: 'FINANCE', label: '财务' },
  { code: 'BOSS', label: '老板' },
]

watch([canManage, isBoss], ([manageable, boss]) => {
  if (manageable && !boss && form.value.visibility !== 'STORE') form.value.visibility = 'STORE'
}, { immediate: true })

watch([() => form.value.visibility, canManage], ([visibility, manageable]) => {
  if (visibility !== 'ROLE') form.value.roleScopes = []
  if (visibility !== 'STORE') {
    form.value.storeScopes = []
    storeQuery.value = ''
    return
  }
  if (!manageable) return
  void loadStores()
}, { immediate: true })

async function loadAvailableRecords(force = false): Promise<void> {
  if (availableRecordsRequest) {
    const activeRequest = availableRecordsRequest
    await activeRequest
    return force ? loadAvailableRecords(false) : undefined
  }
  const request = (async () => {
    loadingAvailableRecords.value = true
    try {
      availableRecords.value = await availableKnowledgeBaseDocuments()
    } catch (reason) {
      error.value = message(reason)
    } finally {
      loadingAvailableRecords.value = false
    }
  })()
  const trackedRequest = request.finally(() => {
    if (availableRecordsRequest === trackedRequest) availableRecordsRequest = null
  })
  availableRecordsRequest = trackedRequest
  return trackedRequest
}

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

async function loadStores(force = false) {
  if (!canManage.value || form.value.visibility !== 'STORE') return
  if (storesLoading.value || (storesLoaded.value && !force)) return
  storesLoading.value = true
  storesLoadError.value = ''
  try {
    stores.value = await getStores({ knowledgeBaseScope: true })
    storesLoaded.value = true
  } catch {
    stores.value = []
    storesLoaded.value = false
    storesLoadError.value = '门店列表加载失败，暂时不能按指定门店提交。请重试。'
  } finally {
    storesLoading.value = false
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
  error.value = ''
  const selected = (event.target as HTMLInputElement).files?.[0] || null
  if (selected && selected.size > MAX_UPLOAD_BYTES) {
    file.value = null
    if (fileInput.value) fileInput.value.value = ''
    error.value = '单个资料文件不能超过 50MB。'
    return
  }
  file.value = selected
  if (selected && !form.value.title.trim()) form.value.title = selected.name.replace(/\.[^.]+$/, '')
}

async function upload(publishNow: boolean) {
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
  if (form.value.visibility === 'STORE' && storeSelectionUnavailable.value) {
    error.value = storesLoadError.value || '门店列表仍在加载，请稍后再提交。'
    return
  }
  if (form.value.visibility === 'STORE' && !form.value.storeScopes.length) {
    error.value = '按门店发布时请至少选择一家门店。'
    return
  }
  if (publishNow && form.value.visibility === 'TENANT' && !confirmTenantPublish()) return

  savingMode.value = publishNow ? 'publish' : 'draft'
  try {
    const uploadedDocument = await uploadKnowledgeBaseDocument({
      file: file.value,
      title: form.value.title,
      category: form.value.category,
      visibility: form.value.visibility,
      roleScopes: form.value.visibility === 'ROLE' ? form.value.roleScopes : [],
      storeScopes: form.value.visibility === 'STORE' ? form.value.storeScopes : [],
      publishNow,
    })
    if (uploadedDocument.status === 'PUBLISHED') {
      success.value = `“${uploadedDocument.title}”已发布，符合范围的账号现在可以查看。`
    } else if (publishNow) {
      error.value = `“${uploadedDocument.title}”已保存为草稿，但未完成发布，请在资料管理中重试。`
    } else {
      success.value = `“${uploadedDocument.title}”已保存为草稿，普通账号暂不可见。`
    }
    resetForm()
    await Promise.all([loadRecords(), loadAvailableRecords(true)])
  } catch (reason) {
    error.value = message(reason)
  } finally {
    savingMode.value = null
  }
}

async function publish(knowledgeDocument: KnowledgeBaseDocument) {
  if (knowledgeDocument.visibility === 'TENANT' && !confirmTenantPublish()) return
  busyDocumentId.value = knowledgeDocument.id
  error.value = ''
  success.value = ''
  try {
    const publishedDocument = await publishKnowledgeBaseDocument(knowledgeDocument.id)
    if (publishedDocument.status === 'PUBLISHED') {
      success.value = `“${knowledgeDocument.title}”已发布，符合范围的账号现在可以查看。`
    } else {
      error.value = `“${knowledgeDocument.title}”仍是草稿，未完成发布，请刷新后重试。`
    }
    await Promise.all([loadRecords(), loadAvailableRecords(true)])
  } catch (reason) {
    error.value = message(reason)
  } finally {
    busyDocumentId.value = null
  }
}

async function archive(knowledgeDocument: KnowledgeBaseDocument) {
  if (!window.confirm(`确认下架“${knowledgeDocument.title}”？下架后符合范围的账号将无法查看、检索或下载。`)) return
  busyDocumentId.value = knowledgeDocument.id
  error.value = ''
  try {
    await archiveKnowledgeBaseDocument(knowledgeDocument.id)
    success.value = `“${knowledgeDocument.title}”已下架。`
    await Promise.all([loadRecords(), loadAvailableRecords(true)])
  } catch (reason) {
    error.value = message(reason)
  } finally {
    busyDocumentId.value = null
  }
}

async function download(knowledgeDocument: Pick<KnowledgeBaseDocument, 'id' | 'originalFileName'>) {
  busyDocumentId.value = knowledgeDocument.id
  error.value = ''
  try {
    await downloadKnowledgeBaseDocument(knowledgeDocument.id, knowledgeDocument.originalFileName)
  } catch (reason) {
    error.value = message(reason)
  } finally {
    busyDocumentId.value = null
  }
}

function selectAllFilteredStores() {
  form.value.storeScopes = Array.from(new Set([
    ...form.value.storeScopes,
    ...filteredStores.value.map((store) => store.id),
  ]))
}

function clearStoreScopes() {
  form.value.storeScopes = []
}

function confirmTenantPublish() {
  return window.confirm(
    '确认发布到全企业？发布后，本企业内拥有知识库权限且未被单独禁止的账号均可查看和检索。',
  )
}

function resetForm() {
  file.value = null
  if (fileInput.value) fileInput.value.value = ''
  form.value = {
    title: '',
    category: '门店运营',
    visibility: auth.role === 'SUPERVISOR' ? 'STORE' : 'TENANT',
    roleScopes: [],
    storeScopes: [],
  }
  storeQuery.value = ''
}

function scopeLabel(knowledgeDocument: KnowledgeBaseDocument) {
  if (knowledgeDocument.visibility === 'TENANT') return '全企业可检索'
  if (knowledgeDocument.visibility === 'ROLE') return `角色：${knowledgeDocument.roleScopes.join('、') || '-'}`
  return `门店：${knowledgeDocument.storeScopes.join('、') || '-'}`
}

function statusLabel(status: string) {
  return status === 'PUBLISHED' ? '已发布' : status === 'ARCHIVED' ? '已下架' : '待发布'
}

function storeRegion(store: StoreInfo) {
  return store.area || store.regionCode || '未配置区域'
}

function storeStatus(status?: string) {
  const normalized = String(status || '').toUpperCase()
  if (['ACTIVE', 'ENABLED', 'OPEN'].includes(normalized)) return '启用'
  if (['INACTIVE', 'DISABLED', 'CLOSED', 'STOPPED'].includes(normalized)) return '停用'
  return status || '状态未配置'
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

function refreshWhenVisible() {
  if (document.visibilityState === 'visible') void loadAvailableRecords()
}

function refreshWhenFocused() {
  void loadAvailableRecords()
}

onMounted(() => {
  void Promise.all([loadAvailableRecords(), loadRecords()])
  window.addEventListener('focus', refreshWhenFocused)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onBeforeUnmount(() => {
  window.removeEventListener('focus', refreshWhenFocused)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
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

    <section class="content-card available-card" aria-labelledby="knowledge-available-title">
      <div class="section-title section-title-with-action">
        <FileSearch :size="20" aria-hidden="true" />
        <div>
          <h2 id="knowledge-available-title">我可查看的资料</h2>
          <p>这里仅显示已经发布且适用于你当前角色和门店范围的资料。</p>
        </div>
        <button type="button" class="refresh-button" :disabled="loadingAvailableRecords" @click="loadAvailableRecords(true)">
          <RefreshCw :size="15" aria-hidden="true" />
          {{ loadingAvailableRecords ? '刷新中…' : '刷新资料' }}
        </button>
      </div>
      <p v-if="loadingAvailableRecords && !availableRecords.length" class="empty-copy">正在加载可查看的资料…</p>
      <p v-else-if="!availableRecords.length" class="empty-copy">暂无已发布且适用于你的资料。</p>
      <article v-for="document in availableRecords" :key="document.id" class="available-document">
        <div class="document-main">
          <strong>{{ document.title }}</strong>
          <p>{{ document.category }} · {{ document.originalFileName }} · {{ formatBytes(document.fileSize) }}</p>
          <small>发布于 {{ formatDate(document.publishedAt) }} · 更新于 {{ formatDate(document.updatedAt) }}</small>
        </div>
        <button
          type="button"
          class="download-button"
          :aria-label="`下载 ${document.title}`"
          :disabled="busyDocumentId === document.id"
          @click="download(document)"
        >
          <Download :size="15" aria-hidden="true" />下载
        </button>
      </article>
    </section>

    <template v-if="canManage">
      <section class="knowledge-layout">
        <form class="content-card upload-card" @submit.prevent="upload(true)">
          <div class="section-title"><FileUp :size="20" aria-hidden="true" /><div><h2>上传资料</h2><p>支持 .doc、.docx、.xlsx、.xls、.csv、.txt、.mp4，单个文件不超过 50MB。</p></div></div>
          <label>资料文件<input ref="fileInput" type="file" accept=".doc,.docx,.xlsx,.xls,.csv,.txt,.mp4" @change="chooseFile"></label>
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
          <fieldset v-if="form.visibility === 'STORE'" class="store-scopes">
            <legend>适用门店</legend>
            <label class="store-search">搜索门店
              <input v-model.trim="storeQuery" type="search" placeholder="输入门店名称、编号或区域">
            </label>
            <div class="store-scope-toolbar">
              <span>已选择 {{ form.storeScopes.length }} 家门店</span>
              <div>
                <button type="button" :disabled="storesLoading || !filteredStores.length" @click="selectAllFilteredStores">全选当前结果</button>
                <button type="button" :disabled="!form.storeScopes.length" @click="clearStoreScopes">清空</button>
              </div>
            </div>
            <p v-if="storesLoading" class="scope-help">正在加载有权选择的门店…</p>
            <div v-else-if="storesLoadError" class="store-load-error" role="alert">
              <span>{{ storesLoadError }}</span>
              <button type="button" @click="loadStores(true)">重新加载</button>
            </div>
            <p v-else-if="!filteredStores.length" class="scope-help">没有符合当前搜索条件的门店。</p>
            <div v-else class="store-options">
              <label v-for="store in filteredStores" :key="store.id" class="store-option">
                <input v-model="form.storeScopes" type="checkbox" :value="store.id">
                <span>
                  <strong>{{ store.name }}</strong>
                  <small>{{ store.code }} · {{ storeRegion(store) }} · {{ storeStatus(store.status) }}</small>
                </span>
              </label>
            </div>
          </fieldset>
          <div class="upload-actions">
            <button class="primary-button" type="submit" :disabled="saving || storeSelectionUnavailable">
              <FileUp :size="17" aria-hidden="true" />{{ savingMode === 'publish' ? '正在上传并发布…' : '上传并发布' }}
            </button>
            <button class="draft-button" type="button" :disabled="saving || storeSelectionUnavailable" @click="upload(false)">
              {{ savingMode === 'draft' ? '正在保存草稿…' : '仅保存草稿' }}
            </button>
          </div>
        </form>

        <section class="content-card documents-card" aria-labelledby="knowledge-documents-title">
          <div class="section-title"><Archive :size="20" aria-hidden="true" /><div><h2 id="knowledge-documents-title">资料管理</h2><p>资料可保存为草稿或直接发布；只有已发布资料才会向符合范围的账号开放。</p></div></div>
          <p v-if="loadingRecords" class="empty-copy">正在加载资料…</p>
          <p v-else-if="!records.length" class="empty-copy">暂无可管理的资料。</p>
          <article v-for="document in records" :key="document.id" class="document-row">
            <div class="document-main"><div class="document-title"><strong>{{ document.title }}</strong><span :class="['status-tag', document.status.toLowerCase()]">{{ statusLabel(document.status) }}</span></div><p>{{ document.category }} · {{ document.originalFileName }} · {{ formatBytes(document.fileSize) }}</p><small>{{ scopeLabel(document) }} · {{ document.chunkCount }} 段索引 · 更新于 {{ formatDate(document.updatedAt) }}</small></div>
            <div class="document-actions">
              <button type="button" :disabled="busyDocumentId === document.id" @click="download(document)"><Download :size="15" />下载</button>
              <button v-if="document.status === 'DRAFT'" type="button" class="publish" :disabled="busyDocumentId === document.id" @click="publish(document)">立即发布</button>
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
.section-title p, .document-main p, .document-main small, .empty-copy, .scope-help { color: var(--ds-muted, #607576); }
.content-card { border: 1px solid var(--ds-line, #dbe8e6); border-radius: 12px; background: #fff; padding: 20px; box-shadow: 0 8px 24px rgba(20, 71, 68, .035); }
.section-title { display: flex; align-items: flex-start; gap: 10px; color: var(--ds-primary, #126c68); }
.section-title-with-action > div { min-width: 0; }
.section-title h2 { font-size: 18px; }
.section-title p { margin: 4px 0 0; font-size: 13px; }
.search-form { display: flex; gap: 10px; margin-top: 18px; }
.search-form input, .upload-card input, .upload-card select { min-width: 0; width: 100%; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; background: #fff; color: var(--ds-text, #183434); font: inherit; }
.search-form input { min-height: 42px; padding: 0 13px; }
.search-form button, .primary-button, .draft-button, .refresh-button, .download-button, .document-actions button, .store-scope-toolbar button, .store-load-error button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; border-radius: 8px; border: 1px solid var(--ds-line, #dbe8e6); background: #fff; color: var(--ds-primary, #126c68); font: inherit; font-weight: 800; cursor: pointer; }
.search-form button { min-width: 94px; padding: 0 14px; }
.search-form button:hover, .draft-button:hover, .refresh-button:hover, .download-button:hover, .document-actions button:hover, .store-scope-toolbar button:hover, .store-load-error button:hover { background: #eff9f7; }
button:disabled { cursor: not-allowed; opacity: .55; }
.search-results { display: grid; gap: 10px; margin-top: 16px; }
.search-result { border-left: 3px solid var(--ds-primary, #126c68); border-radius: 6px; background: #f7fbfa; padding: 12px 14px; }
.result-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; color: var(--ds-muted, #607576); font-size: 12px; }
.result-meta strong { color: var(--ds-text, #183434); font-size: 14px; }
.result-meta span { padding: 2px 7px; border-radius: 20px; background: #e7f3f0; }
.result-meta small { margin-left: auto; color: var(--ds-primary, #126c68); font-weight: 800; }
.search-result p { margin: 8px 0 0; white-space: pre-wrap; line-height: 1.65; color: #354d4d; }
.refresh-button { min-height: 34px; margin-left: auto; padding: 0 11px; white-space: nowrap; }
.available-card { display: grid; gap: 13px; }
.available-document { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 12px; border-top: 1px solid var(--ds-line, #dbe8e6); padding-top: 14px; }
.available-document .document-main > strong { color: var(--ds-text, #183434); overflow-wrap: anywhere; }
.download-button { min-height: 34px; padding: 0 11px; }
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
.store-scopes { display: grid; min-width: 0; gap: 10px; margin: 0; padding: 12px; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; }
.store-scopes legend { padding: 0 4px; color: var(--ds-muted, #607576); font-size: 12px; }
.store-search { display: grid; gap: 6px; color: var(--ds-text, #183434); font-size: 13px; font-weight: 800; }
.store-scope-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: var(--ds-muted, #607576); font-size: 12px; }
.store-scope-toolbar > div { display: flex; flex-wrap: wrap; gap: 6px; }
.store-scope-toolbar button, .store-load-error button { min-height: 30px; padding: 0 9px; font-size: 12px; }
.store-options { display: grid; gap: 7px; max-height: 260px; overflow-y: auto; overscroll-behavior: contain; }
.store-option { display: grid; grid-template-columns: 17px minmax(0, 1fr); align-items: start; gap: 8px; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; padding: 9px; color: var(--ds-text, #183434); cursor: pointer; }
.store-option:has(input:checked) { border-color: var(--ds-primary, #126c68); background: #eff9f7; }
.store-option input { width: 16px !important; min-height: 16px !important; margin: 2px 0 0; padding: 0 !important; }
.store-option span { display: grid; min-width: 0; gap: 2px; }
.store-option strong, .store-option small { overflow-wrap: anywhere; }
.store-option small { color: var(--ds-muted, #607576); font-size: 12px; }
.store-load-error { display: flex; align-items: center; justify-content: space-between; gap: 10px; border-radius: 8px; background: #fff4f4; padding: 9px; color: #a13131; font-size: 12px; }
.upload-actions { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 9px; }
.primary-button { min-height: 42px; border-color: var(--ds-primary, #126c68); background: var(--ds-primary, #126c68); color: #fff; }
.draft-button { min-height: 42px; }
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
@media (max-width: 620px) { .page-head { display: block; }.content-card { padding: 15px; }.search-form { flex-direction: column; }.search-form button { min-height: 40px; }.result-meta small { margin-left: 0; }.section-title-with-action { flex-wrap: wrap; }.refresh-button { margin-left: 30px; }.available-document { grid-template-columns: 1fr; }.download-button { width: 100%; }.store-scope-toolbar, .store-load-error { align-items: stretch; flex-direction: column; }.store-scope-toolbar > div, .store-scope-toolbar button, .store-load-error button { width: 100%; }.upload-actions { grid-template-columns: 1fr; }.document-actions button { flex: 1; } }
</style>
