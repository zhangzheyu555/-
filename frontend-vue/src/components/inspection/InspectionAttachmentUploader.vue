<script setup lang="ts">
import { computed, ref } from 'vue'
import { Paperclip, Trash2, Upload } from 'lucide-vue-next'
import { uploadInspectionAttachment, type InspectionAttachment } from '../../api/inspection'

const props = defineProps<{
  modelValue?: string
  businessId?: string
  storeId?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  error: [message: string]
}>()

const uploading = ref(false)

const attachments = computed<InspectionAttachment[]>(() => parseAttachments(props.modelValue))

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (!files.length) return
  if (!props.storeId?.trim()) {
    emit('error', '请先选择门店后再上传附件')
    return
  }
  uploading.value = true
  try {
    const uploaded: InspectionAttachment[] = []
    for (const file of files) {
      uploaded.push(await uploadInspectionAttachment(file, props.storeId, props.businessId || 'inspection-draft'))
    }
    emit('update:modelValue', JSON.stringify([...attachments.value, ...uploaded]))
  } catch (error) {
    emit('error', error instanceof Error ? error.message : '图片上传失败，请稍后重试')
  } finally {
    uploading.value = false
  }
}

function removeAttachment(index: number) {
  const next = attachments.value.filter((_, itemIndex) => itemIndex !== index)
  emit('update:modelValue', JSON.stringify(next))
}

function parseAttachments(value?: string): InspectionAttachment[] {
  if (!value || !value.trim() || value.trim() === '[]') return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function fileSize(value?: number) {
  const size = Number(value || 0)
  if (!size) return ''
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <div class="attachment-uploader" :aria-busy="uploading">
    <label class="upload-button" :class="{ 'upload-button--busy': uploading }">
      <input
        type="file"
        accept="image/*,.pdf,.doc,.docx,.xls,.xlsx"
        capture="environment"
        multiple
        :disabled="uploading"
        aria-label="上传巡检图片或附件"
        @change="onFileChange"
      />
      <Upload :size="16" />
      {{ uploading ? '正在上传...' : '上传图片/附件' }}
    </label>
    <div v-if="attachments.length" class="attachment-list">
      <div v-for="(item, index) in attachments" :key="`${item.id || item.fileName}-${index}`" class="attachment-item">
        <Paperclip :size="15" />
        <span>{{ item.fileName }}</span>
        <small>{{ fileSize(item.fileSize) }}</small>
        <button class="icon-button" type="button" @click="removeAttachment(index)" aria-label="删除附件">
          <Trash2 :size="14" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.attachment-uploader {
  display: grid;
  gap: 8px;
}

.upload-button {
  position: relative;
  width: fit-content;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 44px;
  padding: 8px 12px;
  border: 1px solid var(--primary);
  border-radius: 10px;
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-weight: 900;
  cursor: pointer;
}

.upload-button input {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

.upload-button:focus-within {
  outline: 3px solid rgba(39, 107, 101, 0.22);
  outline-offset: 2px;
}

.upload-button--busy {
  cursor: wait;
}

.attachment-list {
  display: grid;
  gap: 6px;
}

.attachment-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fafbfc;
  color: var(--ink);
  font-size: 13px;
}

.attachment-item span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-item small {
  color: var(--muted);
}

.icon-button {
  display: inline-flex;
  flex: 0 0 44px;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  color: var(--bad);
}
</style>
