<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CheckCircle2 } from 'lucide-vue-next'
import {
  getTrainingLearningRecords,
  getTrainingMaterials,
  markTrainingMaterialLearned,
  type TrainingLearningRecord,
  type TrainingMaterial,
} from '../../api/operations'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const keyword = ref('')
const activeCategory = ref('全部')
const materials = ref<TrainingMaterial[]>([])
const records = ref<TrainingLearningRecord[]>([])
const loading = ref(false)
const actioningId = ref<number | null>(null)
const error = ref('')
const message = ref('')

const categories = computed(() => ['全部', ...Array.from(new Set(materials.value.map((item) => item.category)))])
const filteredMaterials = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return materials.value.filter((item) => {
    const matchCategory = activeCategory.value === '全部' || item.category === activeCategory.value
    const matchKeyword = !text || `${item.title} ${item.content} ${item.category}`.toLowerCase().includes(text)
    return matchCategory && matchKeyword
  })
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [materialList, recordList] = await Promise.all([
      getTrainingMaterials(),
      getTrainingLearningRecords().catch(() => [] as TrainingLearningRecord[]),
    ])
    materials.value = materialList
    records.value = recordList
  } catch (err) {
    error.value = err instanceof Error ? err.message : '培训资料加载失败'
  } finally {
    loading.value = false
  }
}

async function markLearned(id: number) {
  actioningId.value = id
  error.value = ''
  message.value = ''
  try {
    materials.value = await markTrainingMaterialLearned(id)
    records.value = await getTrainingLearningRecords().catch(() => records.value)
    message.value = '已记录学习状态'
  } catch (err) {
    error.value = err instanceof Error ? err.message : '学习状态保存失败'
  } finally {
    actioningId.value = null
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="content-card training-panel">
    <div class="table-heading">
      <div>
        <h3>新人培训</h3>
      </div>
    </div>

    <div v-if="error" class="error-box">{{ error }}</div>
    <div v-if="message" class="success-box">{{ message }}</div>

    <div class="training-toolbar">
      <div class="training-tabs">
        <button
          v-for="category in categories"
          :key="category"
          class="mini-button"
          :class="{ primary: activeCategory === category }"
          type="button"
          @click="activeCategory = category"
        >
          {{ category }}
        </button>
      </div>
      <input v-model.trim="keyword" type="search" placeholder="搜索培训内容、分类或标准" />
    </div>

    <div v-if="loading" class="empty-state compact">正在读取培训资料...</div>
    <div v-else class="training-grid">
      <article v-for="item in filteredMaterials" :key="item.id" class="training-card">
        <div class="training-images">
          <img v-for="image in item.imageUrls" :key="image" :src="image" :alt="item.title" loading="lazy" />
        </div>
        <div class="training-content">
          <div class="training-meta">
            <span>{{ item.category }}</span>
            <b v-if="item.learned"><CheckCircle2 :size="15" /> 已学习</b>
          </div>
          <h4>{{ item.title }}</h4>
          <p>{{ item.content || '暂无内容。' }}</p>
          <button class="mini-button primary" type="button" :disabled="item.learned || actioningId === item.id" @click="markLearned(item.id)">
            {{ item.learned ? `已学习 ${item.learnedAt || ''}` : '标记已学习' }}
          </button>
        </div>
      </article>
    </div>

    <div v-if="!loading && !filteredMaterials.length" class="empty-state compact">没有匹配的培训资料。</div>

    <section v-if="auth.role !== 'STORE_MANAGER'" class="learning-records">
      <div class="table-heading">
        <div>
          <h3>学习记录</h3>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>学习时间</th>
              <th>人员</th>
              <th>资料</th>
              <th>门店</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in records" :key="record.id">
              <td>{{ record.learnedAt }}</td>
              <td>{{ record.userName }}</td>
              <td>{{ record.materialTitle }}</td>
              <td>{{ record.storeId || '-' }}</td>
              <td>{{ record.learned ? '已学习' : '待学习' }}</td>
            </tr>
            <tr v-if="!records.length">
              <td colspan="5" class="empty-cell">暂无学习记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<style scoped>
.training-panel {
  display: grid;
  gap: 16px;
}

.training-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.training-tabs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.training-toolbar input {
  min-width: min(340px, 100%);
  min-height: 40px;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 9px 12px;
  outline: none;
}

.training-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.training-card {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #fff;
}

.training-images {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  background: var(--line);
  aspect-ratio: 16 / 6;
}

.training-images img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  background: #f7f8fa;
}

.training-content {
  display: grid;
  gap: 10px;
  padding: 14px;
}

.training-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.training-meta span {
  color: var(--primary);
  font-size: 12px;
  font-weight: 900;
}

.training-meta b {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--good);
  font-size: 12px;
}

.training-content h4 {
  margin: 0;
  font-size: 16px;
}

.training-content p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.65;
}

.learning-records {
  display: grid;
  gap: 10px;
}

@media (max-width: 900px) {
  .training-grid {
    grid-template-columns: 1fr;
  }

  .training-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .training-toolbar input {
    width: 100%;
  }
}
</style>
