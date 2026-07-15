<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { BookOpenCheck, History, Plus, RefreshCw, RotateCcw, Send } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import UiButton from '../components/ui/UiButton.vue'
import {
  createEmployeeAssistantKnowledge,
  employeeAssistantKnowledge,
  employeeAssistantKnowledgeVersions,
  publishEmployeeAssistantKnowledge,
  rollbackEmployeeAssistantKnowledge,
  updateEmployeeAssistantKnowledge,
  type EmployeeAssistantKnowledge,
  type EmployeeAssistantKnowledgeDraft,
  type EmployeeAssistantKnowledgeVersion,
} from '../api/employeeAssistant'

const records = ref<EmployeeAssistantKnowledge[]>([])
const versions = ref<EmployeeAssistantKnowledgeVersion[]>([])
const loading = ref(true)
const saving = ref(false)
const pageError = ref('')
const notice = ref('')
const editingId = ref<number | null>(null)
const versionForId = ref<number | null>(null)
const form = ref<EmployeeAssistantKnowledgeDraft>(emptyDraft())

const editing = computed(() => records.value.find((item) => item.id === editingId.value) || null)

onMounted(() => { void load() })

async function load() {
  loading.value = true
  pageError.value = ''
  try {
    records.value = await employeeAssistantKnowledge()
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '知识库加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function startNew() {
  editingId.value = null
  form.value = emptyDraft()
  notice.value = ''
}

function edit(record: EmployeeAssistantKnowledge) {
  editingId.value = record.id
  form.value = { category: record.category, title: record.title, keywords: record.keywords, standardAnswer: record.standardAnswer }
  notice.value = record.status === 'PUBLISHED' ? '已发布内容不可直接修改；如需调整，请新建一条草稿后发布。' : ''
}

async function saveDraft() {
  if (saving.value) return
  saving.value = true
  pageError.value = ''
  notice.value = ''
  try {
    const result = editingId.value
      ? await updateEmployeeAssistantKnowledge(editingId.value, form.value)
      : await createEmployeeAssistantKnowledge(form.value)
    replace(result)
    editingId.value = result.id
    notice.value = '知识草稿已保存。发布后才会参与员工助手回答。'
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '知识草稿保存失败，请稍后重试。'
  } finally {
    saving.value = false
  }
}

async function publish(record: EmployeeAssistantKnowledge) {
  if (saving.value) return
  saving.value = true
  pageError.value = ''
  try {
    const result = await publishEmployeeAssistantKnowledge(record.id)
    replace(result)
    notice.value = `“${result.title}”已发布为版本 ${result.currentVersion}。`
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '知识发布失败，请稍后重试。'
  } finally {
    saving.value = false
  }
}

async function showVersions(record: EmployeeAssistantKnowledge) {
  versionForId.value = record.id
  pageError.value = ''
  try {
    versions.value = await employeeAssistantKnowledgeVersions(record.id)
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '知识版本加载失败，请稍后重试。'
  }
}

async function rollback(record: EmployeeAssistantKnowledge, version: EmployeeAssistantKnowledgeVersion) {
  if (saving.value) return
  saving.value = true
  pageError.value = ''
  try {
    const result = await rollbackEmployeeAssistantKnowledge(record.id, version.version)
    replace(result)
    versions.value = await employeeAssistantKnowledgeVersions(record.id)
    notice.value = `已回滚为历史内容，并重新发布为版本 ${result.currentVersion}。`
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '知识回滚失败，请稍后重试。'
  } finally {
    saving.value = false
  }
}

function replace(record: EmployeeAssistantKnowledge) {
  const index = records.value.findIndex((item) => item.id === record.id)
  if (index >= 0) records.value.splice(index, 1, record)
  else records.value.unshift(record)
}

function emptyDraft(): EmployeeAssistantKnowledgeDraft {
  return { category: 'SERVICE', title: '', keywords: '', standardAnswer: '' }
}
</script>

<template>
  <section class="employee-knowledge-page">
    <PageHeader subtitle="仅老板可维护。发布后才会作为员工助手的本地标准话术，版本不可改写。">
      <template #actions>
        <UiButton :loading="loading" @click="load"><template #icon><RefreshCw :size="16" /></template>刷新</UiButton>
        <UiButton variant="primary" @click="startNew"><template #icon><Plus :size="16" /></template>新建草稿</UiButton>
      </template>
    </PageHeader>

    <p class="scope-note"><BookOpenCheck :size="17" />此处只保存已审批标准话术及其版本，不保存员工原始聊天内容。</p>
    <p v-if="pageError" class="error-box" role="alert">{{ pageError }}</p>
    <p v-if="notice" class="notice-box" role="status">{{ notice }}</p>

    <section class="knowledge-layout">
      <form class="content-card editor" @submit.prevent="saveDraft">
        <div class="section-heading"><div><h2>{{ editingId ? '编辑知识草稿' : '新建知识草稿' }}</h2><p>填写至少两个关键词；已发布内容保持不可直接修改。</p></div></div>
        <label>分类<input v-model="form.category" maxlength="64" placeholder="例如：SERVICE" :disabled="editing?.status === 'PUBLISHED'" /></label>
        <label>标题<input v-model="form.title" maxlength="160" placeholder="例如：会员券不能使用" :disabled="editing?.status === 'PUBLISHED'" /></label>
        <label>匹配关键词<textarea v-model="form.keywords" maxlength="1000" rows="3" placeholder="用逗号分隔，例如：会员券，无法使用，核对有效期" :disabled="editing?.status === 'PUBLISHED'" /></label>
        <label>标准答复<textarea v-model="form.standardAnswer" maxlength="4000" rows="8" placeholder="填写可直接给员工使用的中文标准话术" :disabled="editing?.status === 'PUBLISHED'" /></label>
        <div class="form-actions"><UiButton variant="primary" type="submit" :loading="saving" :disabled="editing?.status === 'PUBLISHED'"><template #icon><Send :size="16" /></template>保存草稿</UiButton></div>
      </form>

      <section class="content-card knowledge-list" aria-live="polite">
        <div class="section-heading"><div><h2>知识条目</h2><p>发布条目会优先于模型回答。</p></div></div>
        <div v-if="loading" class="empty-state">正在加载知识条目…</div>
        <div v-else-if="!records.length" class="empty-state">尚无知识条目。先新建一条草稿并发布。</div>
        <article v-for="record in records" :key="record.id" class="knowledge-row">
          <div class="knowledge-main"><div class="row-title"><strong>{{ record.title }}</strong><span :class="['status-tag', record.status.toLowerCase()]">{{ record.status === 'PUBLISHED' ? '已发布' : '草稿' }}</span></div><p>{{ record.category }} · {{ record.keywords }}</p><small>当前版本 {{ record.currentVersion || '—' }}</small></div>
          <div class="row-actions"><UiButton @click="edit(record)">查看</UiButton><UiButton v-if="record.status === 'DRAFT'" variant="primary" :loading="saving" @click="publish(record)">发布</UiButton><UiButton @click="showVersions(record)"><template #icon><History :size="16" /></template>版本</UiButton></div>
          <div v-if="versionForId === record.id" class="version-list"><p v-if="!versions.length">暂无已发布版本。</p><div v-for="version in versions" :key="version.id" class="version-row"><div><strong>版本 {{ version.version }}</strong><span>{{ version.publishAction === 'ROLLBACK' ? '回滚发布' : '正常发布' }}</span><p>{{ version.standardAnswer }}</p></div><UiButton v-if="record.currentVersion !== version.version" :loading="saving" @click="rollback(record, version)"><template #icon><RotateCcw :size="15" /></template>回滚</UiButton></div></div>
        </article>
      </section>
    </section>
  </section>
</template>

<style scoped>
.employee-knowledge-page { display: grid; gap: 18px; }
.scope-note, .notice-box, .error-box { display: flex; align-items: center; gap: 8px; margin: 0; padding: 12px 14px; border-radius: 8px; font-size: 14px; }
.scope-note { border: 1px solid #c9e6df; background: #f1fbf7; color: #285f5c; }
.notice-box { border: 1px solid #bfe5d8; background: #f1fbf7; color: #276b65; }
.error-box { border: 1px solid #efc9cf; background: #fff5f5; color: #9f2734; }
.knowledge-layout { display: grid; grid-template-columns: minmax(280px, .9fr) minmax(420px, 1.4fr); gap: 18px; align-items: start; }
.editor, .knowledge-list { display: grid; gap: 14px; }
.section-heading h2 { margin: 0; color: var(--ds-ink); font-size: 17px; }
.section-heading p { margin: 4px 0 0; color: var(--ds-muted); font-size: 13px; line-height: 1.5; }
label { display: grid; gap: 7px; color: var(--ds-secondary); font-size: 13px; font-weight: 700; }
input, textarea { width: 100%; min-width: 0; border: 1px solid var(--ds-line); border-radius: 8px; padding: 10px 11px; background: #fff; color: var(--ds-ink); font: inherit; font-weight: 400; line-height: 1.5; }
textarea { resize: vertical; }
input:focus, textarea:focus { outline: 3px solid rgba(39, 107, 101, .16); border-color: var(--ds-primary); }
input:disabled, textarea:disabled { background: #f3f6f6; color: #71817e; }
.form-actions { display: flex; justify-content: flex-end; }
.empty-state { display: grid; min-height: 180px; place-items: center; color: var(--ds-muted); text-align: center; font-size: 14px; }
.knowledge-row { display: grid; gap: 12px; padding: 15px 0; border-top: 1px solid var(--ds-line); }
.knowledge-row:first-of-type { border-top: 0; padding-top: 0; }
.knowledge-main { min-width: 0; }
.row-title, .row-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.row-title strong { color: var(--ds-ink); overflow-wrap: anywhere; }
.knowledge-main p, .knowledge-main small { margin: 5px 0 0; color: var(--ds-muted); font-size: 13px; overflow-wrap: anywhere; }
.status-tag { padding: 3px 7px; border-radius: 999px; font-size: 11px; font-weight: 800; }
.status-tag.published { background: #e7f7ef; color: #237153; }.status-tag.draft { background: #edf2f3; color: #566f6b; }
.row-actions { justify-content: flex-end; }.row-actions :deep(.ui-button) { min-width: auto; height: 36px; padding-inline: 11px; font-size: 13px; }
.version-list { display: grid; gap: 8px; padding: 12px; border: 1px solid var(--ds-line); border-radius: 8px; background: #fbfcfc; }
.version-list > p { margin: 0; color: var(--ds-muted); font-size: 13px; }.version-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 9px 0; border-top: 1px solid var(--ds-line); }.version-row:first-of-type { border-top: 0; padding-top: 0; }.version-row strong { color: var(--ds-ink); font-size: 13px; }.version-row span, .version-row p { display: block; margin: 4px 0 0; color: var(--ds-muted); font-size: 12px; line-height: 1.5; white-space: pre-wrap; }
@media (max-width: 900px) { .knowledge-layout { grid-template-columns: 1fr; }.editor { order: 0; }.knowledge-list { order: 1; } }
@media (max-width: 560px) { .row-actions, .version-row { align-items: stretch; flex-direction: column; }.row-actions :deep(.ui-button), .version-row :deep(.ui-button), .form-actions :deep(.ui-button) { width: 100%; } }
</style>
