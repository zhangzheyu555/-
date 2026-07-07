<template>
  <section class="panel">
    <div class="panel-head">
      <h2>督导巡店 · 卫生复合识别</h2>
      <span>上传巡检照片，自动识别地面垃圾、污点和角落积灰</span>
    </div>

    <div class="upload-row">
      <input ref="fileInput" type="file" accept="image/*" multiple @change="onFilesChosen" />
      <button class="primary-button" type="button" :disabled="detecting" @click="fileInput?.click()">
        <ImagePlus />
        {{ detecting ? `识别中 ${doneCount}/${items.length}` : '选择照片识别' }}
      </button>
      <button v-if="items.length" type="button" :disabled="detecting" @click="clearAll">清空结果</button>
    </div>

    <p v-if="error" class="detect-error">{{ error }}</p>
    <p v-if="!items.length && !error" class="detect-hint">
      识别由本地训练的卫生检测模型完成，检出问题会用红圈标注并给出扣分建议。
    </p>

    <div v-for="item in items" :key="item.id" class="detect-card">
      <div class="detect-image">
        <img v-if="item.result" :src="item.result.annotated_image" :alt="item.name" />
        <div v-else class="detect-loading">{{ item.error ? '识别失败' : '识别中…' }}</div>
      </div>
      <div class="detect-info">
        <div class="detect-title">
          <strong>{{ item.name }}</strong>
          <StatusTag
            v-if="item.result"
            :label="item.result.review_status"
            :tone="item.result.passed ? 'good' : 'bad'"
          />
        </div>
        <template v-if="item.result">
          <p>{{ item.result.auto_status }}</p>
          <p v-if="item.result.detection_count">检出：{{ item.result.detection_summary }}</p>
          <p v-if="item.result.deduction_content">
            扣分项：{{ item.result.deduction_project }} ｜ {{ item.result.deduction_content }} ｜
            <strong>{{ item.result.deduction_score }} 分</strong>
          </p>
        </template>
        <p v-if="item.error" class="detect-error">{{ item.error }}</p>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ImagePlus } from 'lucide-vue-next';
import StatusTag from '../components/StatusTag.vue';
import { detectInspectionPhoto, type InspectionDetectResult } from '../services/api';

interface DetectItem {
  id: number;
  name: string;
  result: InspectionDetectResult | null;
  error: string;
}

const fileInput = ref<HTMLInputElement | null>(null);
const items = ref<DetectItem[]>([]);
const detecting = ref(false);
const error = ref('');

const doneCount = computed(() => items.value.filter((item) => item.result || item.error).length);

async function onFilesChosen(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = '';
  if (!files.length || detecting.value) return;

  error.value = '';
  detecting.value = true;
  items.value = files.map((file, index) => ({ id: Date.now() + index, name: file.name, result: null, error: '' }));

  for (let i = 0; i < files.length; i += 1) {
    const item = items.value[i];
    try {
      item.result = await detectInspectionPhoto(files[i]);
    } catch (err: any) {
      item.error = err?.response?.data?.message ?? '识别失败，请确认识别服务已启动';
    }
  }
  detecting.value = false;
}

function clearAll() {
  items.value = [];
  error.value = '';
}
</script>

<style scoped>
.upload-row {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.upload-row input[type='file'] {
  display: none;
}

.detect-hint {
  color: var(--text-secondary, #667085);
}

.detect-error {
  color: #d92d20;
}

.detect-card {
  display: flex;
  gap: 16px;
  padding: 14px 0;
  border-top: 1px solid var(--border-color, #eaecf0);
}

.detect-image img {
  width: 260px;
  max-height: 340px;
  object-fit: contain;
  border-radius: 8px;
  background: #f4f4f5;
}

.detect-loading {
  width: 260px;
  height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #f4f4f5;
  color: #667085;
}

.detect-info {
  flex: 1;
  min-width: 0;
}

.detect-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.detect-info p {
  margin: 4px 0;
}
</style>
