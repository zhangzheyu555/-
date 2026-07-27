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
        maxlength="1000"
        placeholder="写清楚整改要求、责任人或复查时间"
        @input="updateNote"
      />
      <small class="inspection-note-count">当前 {{ note.length }}/1000 字</small>
    </label>
    <p v-if="saveBlockedReason" class="inspection-save-hint" role="status">
      {{ saveBlockedReason }}
    </p>
    <div class="inspection-form-actions">
      <button class="secondary-button" type="button" :disabled="saving || uploading" @click="emit('reset')">清空表单</button>
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
.inspection-note-count { justify-self: end; color: var(--muted); font-size: 12px; }
.inspection-save-hint { margin: 10px 0 0; padding: 9px 11px; border-radius: 8px; background: var(--ds-warning-soft, #fff7e7); color: #8a5a00; font-size: 13px; }
.inspection-note-card .inspection-form-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 12px; }
@media (max-width: 640px) {
  .inspection-note-card .inspection-form-actions { display: grid; grid-template-columns: 1fr 1fr; }
  .inspection-note-card .inspection-form-actions button { width: 100%; min-height: 44px; }
}
</style>
