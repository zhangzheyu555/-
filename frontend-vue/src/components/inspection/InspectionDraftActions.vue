<script setup lang="ts">
defineProps<{
  note: string
  saving: boolean
  uploading: boolean
  saveBlockedReason: string
}>()

const emit = defineEmits<{
  'update:note': [value: string]
  reset: []
  save: []
}>()

function updateNote(event: Event) {
  emit('update:note', (event.target as HTMLTextAreaElement).value)
}
</script>

<template>
  <section class="content-card inspection-note-card">
    <label>
      <span>整改要求 / 备注</span>
      <textarea
        :value="note"
        rows="3"
        placeholder="写清楚整改要求、责任人或复查时间"
        @input="updateNote"
      />
    </label>
    <div class="inspection-form-actions">
      <button class="secondary-button" type="button" @click="emit('reset')">清空表单</button>
      <button
        class="primary-button"
        type="button"
        :disabled="saving || uploading || Boolean(saveBlockedReason)"
        :title="saveBlockedReason"
        @click="emit('save')"
      >
        {{ saving ? '保存中...' : '保存巡检' }}
      </button>
    </div>
  </section>
</template>

<style>
.inspection-note-card { overflow: hidden; }
.inspection-note-card label { display: grid; gap: 6px; }
.inspection-note-card label span { color: var(--muted); font-size: 12px; font-weight: 800; }
.inspection-note-card textarea { resize: vertical; }
.inspection-note-card .inspection-form-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 12px; }
</style>
