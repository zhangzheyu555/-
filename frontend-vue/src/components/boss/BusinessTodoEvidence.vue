<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { http } from '../../api/http'
import type { BusinessTodo } from '../../api/todos'

const props = defineProps<{ todo: BusinessTodo }>()
const imageUrl = ref('')

const attachment = computed(() => props.todo.actions
  .flatMap((action) => action.attachments || [])
  .find((item) => String(item.contentType || '').startsWith('image/')))

function releaseImage() {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = ''
}

async function loadImage() {
  releaseImage()
  const item = attachment.value
  if (!item) return
  try {
    const path = item.downloadUrl
      || `/api/todos/${encodeURIComponent(props.todo.id)}/attachments/${encodeURIComponent(item.id)}`
    const response = await http.get<Blob>(path, { responseType: 'blob' })
    imageUrl.value = URL.createObjectURL(response.data)
  } catch (error) {
    console.error('[Boss] evidence preview load failed', { todoId: props.todo.id, attachmentId: item.id, error })
  }
}

watch(() => [props.todo.id, attachment.value?.id], loadImage, { immediate: true })
onBeforeUnmount(releaseImage)
</script>

<template>
  <button v-if="imageUrl" class="evidence-thumbnail" type="button" :title="attachment?.fileName || '现场凭证'">
    <img :src="imageUrl" :alt="attachment?.fileName || '现场凭证'" />
  </button>
</template>

<style scoped>
.evidence-thumbnail {
  width: 48px;
  height: 38px;
  overflow: hidden;
  padding: 0;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: #f7f8fa;
}

.evidence-thumbnail img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
