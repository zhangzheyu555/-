<script setup lang="ts">
import { computed } from 'vue'
import { RefreshCcw, Save } from 'lucide-vue-next'
import InspectionAttachmentUploader from './InspectionAttachmentUploader.vue'
import StatusBadge from '../common/StatusBadge.vue'
import type { InspectionRecord, InspectionRecordPayload } from '../../api/inspection'
import { inspectionScoreView, INSPECTION_MAX_SCORE } from '../../utils/inspectionScore'

const props = defineProps<{
  records: InspectionRecord[]
  draft: InspectionRecordPayload
  actioningId?: string
}>()

const emit = defineEmits<{
  updateDraft: [patch: Partial<InspectionRecordPayload>]
  submit: []
  clear: []
  error: [message: string]
}>()

const recentRecords = computed(() => props.records.slice(0, 12))

function updateText(field: keyof InspectionRecordPayload, event: Event) {
  emit('updateDraft', { [field]: (event.target as HTMLInputElement | HTMLTextAreaElement).value })
}

function updateNumber(field: keyof InspectionRecordPayload, event: Event) {
  const value = (event.target as HTMLInputElement).value
  emit('updateDraft', { [field]: value === '' ? undefined : Number(value) })
}

function attachmentCount(record: InspectionRecord) {
  return countJsonArray(record.photosJson)
}

function recordScore(record: InspectionRecord) {
  return inspectionScoreView(record)
}

function issueCount(record: InspectionRecord) {
  return countJsonArray(record.redlinesJson)
}

function countJsonArray(value?: string) {
  if (!value) return 0
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.length : 0
  } catch {
    return value.trim() && value.trim() !== '[]' ? 1 : 0
  }
}
</script>

<template>
  <section class="inspection-panel">
    <div class="inspection-panel-head">
      <div>
        <h3>巡店记录</h3>
      </div>
    </div>

    <form class="record-form" @submit.prevent="$emit('submit')">
      <label>
        门店 ID
        <input :value="draft.storeId" placeholder="例如 rg1" @input="updateText('storeId', $event)" />
      </label>
      <label>
        巡店日期
        <input type="date" :value="draft.inspectionDate" @input="updateText('inspectionDate', $event)" />
      </label>
      <label>
        督导
        <input :value="draft.inspector" placeholder="例如 督导" @input="updateText('inspector', $event)" />
      </label>
      <label>
        品牌
          <input :value="draft.brand" placeholder="例如 茹菓" @input="updateText('brand', $event)" />
      </label>
      <label>
        满分
        <input type="number" :value="INSPECTION_MAX_SCORE" readonly aria-readonly="true" />
      </label>
      <label>
        总评分
        <input type="number" min="0" step="0.01" :value="draft.score" @input="updateNumber('score', $event)" />
      </label>
      <label>
        卫生评分
        <input type="number" min="0" step="0.01" :value="draft.hygieneScore" placeholder="例如 25" @input="updateNumber('hygieneScore', $event)" />
      </label>
      <label>
        服务评分
        <input type="number" min="0" step="0.01" :value="draft.serviceScore" placeholder="例如 25" @input="updateNumber('serviceScore', $event)" />
      </label>
      <label>
        出品评分
        <input type="number" min="0" step="0.01" :value="draft.productScore" placeholder="例如 25" @input="updateNumber('productScore', $event)" />
      </label>
      <label>
        陈列评分
        <input type="number" min="0" step="0.01" :value="draft.displayScore" placeholder="例如 25" @input="updateNumber('displayScore', $event)" />
      </label>
      <label class="wide">
        问题说明
        <textarea
          :value="draft.issueDescription"
          rows="3"
          placeholder="填写发现的问题，例如后厨地面清洁不到位、物料摆放不规范"
          @input="updateText('issueDescription', $event)"
        />
      </label>
      <label class="wide">
        整改要求
        <textarea
          :value="draft.rectificationRequirement"
          rows="3"
          placeholder="填写整改要求，例如今天闭店前完成清洁并上传整改图片"
          @input="updateText('rectificationRequirement', $event)"
        />
      </label>
        <label class="wide">
          图片/附件
          <InspectionAttachmentUploader
            :model-value="draft.photosJson"
            :store-id="draft.storeId"
            :business-id="draft.storeId ? `inspection-${draft.storeId}-draft` : 'inspection-draft'"
            @update:model-value="(value) => $emit('updateDraft', { photosJson: value })"
          @error="$emit('error', $event)"
        />
      </label>
      <label>
        问题明细 JSON
        <textarea
          :value="draft.redlinesJson"
          rows="3"
          :disabled="Boolean(draft.issueDescription || draft.rectificationRequirement)"
          placeholder='例如 [{"content":"后厨地面需清理","deadline":"今天"}]'
          @input="updateText('redlinesJson', $event)"
        />
      </label>
      <label>
        扣分说明 JSON
        <textarea
          :value="draft.deductionsJson"
          rows="3"
          :disabled="Boolean(draft.hygieneScore || draft.serviceScore || draft.productScore || draft.displayScore)"
          placeholder='例如 [{"project":"卫生","score":5}]'
          @input="updateText('deductionsJson', $event)"
        />
      </label>
      <label class="wide">
        巡店说明
        <textarea :value="draft.note" rows="3" placeholder="填写现场情况、整改要求或复查意见" @input="updateText('note', $event)" />
      </label>
      <div class="form-actions wide">
        <button class="ghost-button" type="button" @click="$emit('clear')">
          <RefreshCcw :size="16" />
          清空表单
        </button>
        <button class="primary-button submit-inline" type="submit" :disabled="actioningId === 'inspection-record-create'">
          <Save :size="16" />
          保存巡店记录
        </button>
      </div>
    </form>

    <div class="table-heading">
      <div>
        <h3>近期巡店记录</h3>
      </div>
    </div>

    <div v-if="!recentRecords.length" class="empty-state compact">当前没有巡店记录。</div>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>门店</th>
            <th>巡店日期</th>
            <th>督导</th>
            <th>评分</th>
            <th>问题/图片</th>
            <th>整改状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="record in recentRecords" :key="record.id">
            <td>
              <b>{{ record.storeName || record.storeCode || record.storeId }}</b>
              <small>{{ record.brandName || record.brand || '督导巡店' }}</small>
            </td>
            <td>{{ record.inspectionDate }}</td>
            <td>{{ record.inspector || '督导' }}</td>
            <td :title="recordScore(record).error">{{ recordScore(record).scoreText }}</td>
            <td>
              <b>{{ issueCount(record) }} 个问题</b>
              <small>{{ attachmentCount(record) }} 张图片/附件</small>
            </td>
            <td>
              <StatusBadge :label="recordScore(record).resultText" :tone="recordScore(record).tone === 'ok' ? 'ok' : 'warn'" />
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.inspection-panel {
  display: grid;
  gap: 12px;
}

.inspection-panel-head h3 {
  margin: 0;
  font-size: 18px;
}

.record-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #fff;
}

.record-form label {
  display: grid;
  gap: 7px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
}

.record-form input,
.record-form select,
.record-form textarea {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  padding: 10px 12px;
  outline: none;
}

.record-form input,
.record-form select {
  min-height: 42px;
}

.record-form input:focus,
.record-form select:focus,
.record-form textarea:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(118, 189, 184, 0.14);
}

.wide,
.form-actions {
  grid-column: 1 / -1;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

@media (max-width: 760px) {
  .record-form {
    grid-template-columns: 1fr;
  }
}
</style>
