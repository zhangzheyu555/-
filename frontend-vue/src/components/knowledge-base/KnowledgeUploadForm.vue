<script setup lang="ts">
import { computed, watch } from 'vue'
import { FileUp } from 'lucide-vue-next'
import type { KnowledgeBaseDocument } from '../../api/knowledgeBase'
import type { KnowledgeBaseUploadFormModel } from './models'

const props = defineProps<{
  records: KnowledgeBaseDocument[]
  file: File | null
  saving: boolean
  isBoss: boolean
}>()

const model = defineModel<KnowledgeBaseUploadFormModel>({ required: true })
const emit = defineEmits<{
  chooseFile: [event: Event]
  submit: []
}>()

const roles = [
  { code: 'EMPLOYEE', label: '员工' },
  { code: 'STORE_MANAGER', label: '店长' },
  { code: 'SUPERVISOR', label: '督导' },
  { code: 'WAREHOUSE', label: '仓库管理员' },
  { code: 'FINANCE', label: '财务' },
  { code: 'BOSS', label: '老板' },
]

const topics = computed(() => {
  const values = new Map<number, string>()
  props.records.forEach((record) => values.set(record.topicId, record.topicName))
  return [...values.entries()]
    .map(([id, name]) => ({ id, name }))
    .sort((left, right) => left.name.localeCompare(right.name, 'zh-CN'))
})

const predecessors = computed(() => props.records
  .filter((record) => record.topicId === model.value.topicId && record.status === 'PUBLISHED')
  .sort((left, right) => right.versionNo - left.versionNo))

watch(() => model.value.topicMode, (mode) => {
  if (mode === 'NEW') {
    model.value.topicId = null
    model.value.relationType = 'ORIGINAL'
    model.value.predecessorDocumentId = null
  } else {
    model.value.topicName = ''
    model.value.relationType = 'SUPPLEMENTS'
  }
})

watch(() => model.value.topicId, () => {
  if (model.value.topicMode !== 'EXISTING') return
  model.value.predecessorDocumentId = predecessors.value[0]?.id ?? null
})
</script>

<template>
  <form class="upload-panel" @submit.prevent="emit('submit')">
    <div class="section-title">
      <FileUp :size="20" aria-hidden="true" />
      <div>
        <h2>上传资料</h2>
        <p>原文件独立留存，通过知识主题和版本关系完成内容整合。</p>
      </div>
    </div>

    <label>资料文件
      <input
        type="file"
        accept=".doc,.docx,.xlsx,.xls,.csv,.txt,.mp4"
        @change="emit('chooseFile', $event)"
      >
    </label>
    <small v-if="file">已选择：{{ file.name }}</small>

    <label>资料标题
      <input v-model.trim="model.title" maxlength="200" placeholder="留空时使用文件名">
    </label>
    <label>资料分类
      <input v-model.trim="model.category" maxlength="64" placeholder="例如：门店运营">
    </label>

    <fieldset class="segmented-field">
      <legend>知识主题</legend>
      <label><input v-model="model.topicMode" type="radio" value="NEW">新建主题</label>
      <label><input v-model="model.topicMode" type="radio" value="EXISTING" :disabled="!topics.length">归入已有主题</label>
    </fieldset>
    <label v-if="model.topicMode === 'NEW'">主题名称
      <input v-model.trim="model.topicName" maxlength="200" placeholder="例如：门店闭店流程">
    </label>
    <template v-else>
      <label>已有主题
        <select v-model="model.topicId" required>
          <option :value="null" disabled>请选择知识主题</option>
          <option v-for="topic in topics" :key="topic.id" :value="topic.id">{{ topic.name }}</option>
        </select>
      </label>
      <label>内容关系
        <select v-model="model.relationType">
          <option value="SUPPLEMENTS">补充现有内容</option>
          <option value="REPLACES">替代旧版本</option>
        </select>
      </label>
      <label>关联版本
        <select v-model="model.predecessorDocumentId" required>
          <option :value="null" disabled>请选择已发布资料</option>
          <option v-for="document in predecessors" :key="document.id" :value="document.id">
            第 {{ document.versionNo }} 版 · {{ document.title }}
          </option>
        </select>
      </label>
      <p v-if="model.relationType === 'REPLACES'" class="scope-help">
        新版本发布后，被替代的已发布版本会自动下架。
      </p>
    </template>

    <label>适用范围
      <select v-model="model.visibility" :disabled="!isBoss">
        <option value="TENANT">全企业</option>
        <option value="ROLE">指定角色</option>
        <option value="STORE">指定门店</option>
      </select>
    </label>
    <p v-if="!isBoss" class="scope-help">督导只能上传和发布本人数据范围内的门店资料。</p>
    <fieldset v-if="model.visibility === 'ROLE'" class="role-scopes">
      <legend>适用角色</legend>
      <label v-for="role in roles" :key="role.code" class="check-option">
        <input v-model="model.roleScopes" type="checkbox" :value="role.code">{{ role.label }}
      </label>
    </fieldset>
    <label v-if="model.visibility === 'STORE'">门店编号
      <input v-model.trim="model.storeScopesText" maxlength="1000" placeholder="多个门店用逗号隔开">
    </label>

    <button class="primary-button" type="submit" :disabled="saving">
      <FileUp :size="17" />{{ saving ? '正在建立索引…' : '上传并建立索引' }}
    </button>
  </form>
</template>

<style scoped>
.upload-panel { display: grid; gap: 13px; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; padding: 20px; background: #fff; box-shadow: 0 8px 24px rgba(20, 71, 68, .035); }
.section-title { display: flex; align-items: flex-start; gap: 10px; color: var(--ds-primary, #126c68); }
.section-title h2 { margin: 0; color: var(--ds-text, #183434); font-size: 18px; }
.section-title p { margin: 4px 0 0; color: var(--ds-muted, #607576); font-size: 13px; }
.upload-panel > label { display: grid; gap: 6px; color: var(--ds-text, #183434); font-size: 13px; font-weight: 800; }
.upload-panel input, .upload-panel select { min-width: 0; width: 100%; min-height: 40px; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; padding: 0 10px; background: #fff; color: var(--ds-text, #183434); font: inherit; font-weight: 400; }
.upload-panel input[type='file'] { padding: 7px 10px; }
.upload-panel small { overflow-wrap: anywhere; color: var(--ds-muted, #607576); }
.segmented-field, .role-scopes { display: flex; flex-wrap: wrap; gap: 9px 14px; margin: 0; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; padding: 11px; }
.segmented-field legend, .role-scopes legend { padding: 0 4px; color: var(--ds-muted, #607576); font-size: 12px; }
.segmented-field label, .check-option { display: inline-flex; align-items: center; gap: 6px; color: var(--ds-text, #183434); font-size: 13px; }
.segmented-field input, .check-option input { width: 15px; min-height: 15px; padding: 0; }
.scope-help { margin: -4px 0 0; color: var(--ds-muted, #607576); font-size: 12px; }
.primary-button { display: inline-flex; min-height: 42px; align-items: center; justify-content: center; gap: 6px; border: 1px solid var(--ds-primary, #126c68); border-radius: 8px; background: var(--ds-primary, #126c68); color: #fff; font: inherit; font-weight: 800; cursor: pointer; }
.primary-button:disabled { cursor: not-allowed; opacity: .55; }
@media (max-width: 620px) { .upload-panel { padding: 15px; } }
</style>
