<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  getExamAttempts,
  getExamPaper,
  getExamPapers,
  submitExamAttempt,
  type ExamAttempt,
  type ExamPaper,
  type StoreInfo,
} from '../../api/operations'
import { useAuthStore } from '../../stores/auth'

defineProps<{
  stores: StoreInfo[]
}>()

const auth = useAuthStore()
const papers = ref<ExamPaper[]>([])
const activePaper = ref<ExamPaper | null>(null)
const attempts = ref<ExamAttempt[]>([])
const answers = reactive<Record<number, string>>({})
const examineeName = ref(auth.user?.displayName || '')
const selectedStoreId = ref('')
const violated = ref(false)
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const message = ref('')
const lastAttempt = ref<ExamAttempt | null>(null)

const answeredCount = computed(() => activePaper.value?.questions.filter((item) => answers[item.id]).length || 0)
const questionCount = computed(() => activePaper.value?.questions.length || 0)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [paperList, attemptList] = await Promise.all([getExamPapers(), getExamAttempts()])
    papers.value = paperList
    attempts.value = attemptList
    if (paperList.length && !activePaper.value) {
      await selectPaper(paperList[0].id)
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '考试系统加载失败'
  } finally {
    loading.value = false
  }
}

async function selectPaper(id: number) {
  error.value = ''
  activePaper.value = await getExamPaper(id)
  Object.keys(answers).forEach((key) => delete answers[Number(key)])
  lastAttempt.value = null
}

async function submit() {
  if (!activePaper.value) return
  submitting.value = true
  error.value = ''
  message.value = ''
  try {
    if (!examineeName.value.trim()) {
      throw new Error('请填写考试人姓名')
    }
    if (auth.role !== 'STORE_MANAGER' && !selectedStoreId.value) {
      throw new Error('请选择考试门店')
    }
    const result = await submitExamAttempt({
      paperId: activePaper.value.id,
      examineeName: examineeName.value,
      storeId: selectedStoreId.value || undefined,
      violated: violated.value,
      answers: activePaper.value.questions.map((question) => ({
        questionId: question.id,
        userAnswer: answers[question.id] || '',
      })),
    })
    lastAttempt.value = result
    message.value = result.passed ? `考试已提交，得分 ${result.score}，已通过。` : `考试已提交，得分 ${result.score}，未达到通过线。`
    attempts.value = await getExamAttempts()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '考试提交失败'
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="content-card exam-panel">
    <div class="table-heading">
      <div>
        <h3>考试系统</h3>
      </div>
      <select v-if="papers.length" :value="activePaper?.id" @change="selectPaper(Number(($event.target as HTMLSelectElement).value))">
        <option v-for="paper in papers" :key="paper.id" :value="paper.id">{{ paper.paperName }}</option>
      </select>
    </div>

    <div v-if="error" class="error-box">{{ error }}</div>
    <div v-if="message" class="success-box">{{ message }}</div>

    <div class="exam-summary">
      <div>
        <span>当前试卷</span>
        <b>{{ activePaper?.paperName || '暂无试卷' }}</b>
      </div>
      <div>
        <span>通过分</span>
        <b>{{ activePaper?.passScore || 0 }} 分</b>
      </div>
      <div>
        <span>已作答</span>
        <b>{{ answeredCount }}/{{ questionCount }}</b>
      </div>
    </div>

    <div class="exam-meta">
      <label>
        考试人
        <input v-model.trim="examineeName" placeholder="请输入考试人姓名" />
      </label>
      <label v-if="auth.role !== 'STORE_MANAGER'">
        所属门店
        <select v-model="selectedStoreId">
          <option value="">请选择门店</option>
          <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }}</option>
        </select>
      </label>
      <label class="checkbox-line">
        <input v-model="violated" type="checkbox" />
        本次考试存在违规情况
      </label>
    </div>

    <div v-if="loading" class="empty-state compact">正在读取考试数据...</div>
    <div v-else-if="!activePaper" class="empty-state compact">暂无可用试卷。</div>
    <div v-else class="exam-list">
      <article v-for="(question, index) in activePaper.questions" :key="question.id" class="exam-card">
        <div class="exam-title">
          <b>{{ index + 1 }}. {{ question.questionText }}</b>
          <span>{{ question.score }} 分</span>
        </div>
        <label v-for="option in question.options" :key="option" class="exam-option">
          <input v-model="answers[question.id]" type="radio" :name="String(question.id)" :value="option" />
          <span>{{ option }}</span>
        </label>
      </article>
    </div>

    <button class="primary-button exam-submit" type="button" :disabled="submitting || answeredCount < questionCount" @click="submit">
      提交考试并保存成绩
    </button>

    <section v-if="lastAttempt" class="attempt-detail">
      <h4>本次考试结果</h4>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>题目</th>
              <th>作答</th>
              <th>结果</th>
              <th>得分</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="answer in lastAttempt.answers" :key="answer.questionId">
              <td>{{ answer.questionText }}</td>
              <td>{{ answer.userAnswer || '-' }}</td>
              <td>{{ answer.correct ? '正确' : '错误' }}</td>
              <td>{{ answer.score }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="attempt-history">
      <div class="table-heading">
        <div>
          <h3>历史考试成绩</h3>
          <span>运营和老板可查看全部，店长只看本门店。</span>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>提交时间</th>
              <th>考试人</th>
              <th>试卷</th>
              <th>门店</th>
              <th>得分</th>
              <th>结果</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="attempt in attempts" :key="attempt.id">
              <td>{{ attempt.submittedAt }}</td>
              <td>{{ attempt.examineeName }}</td>
              <td>{{ attempt.paperName }}</td>
              <td>{{ attempt.storeName || attempt.storeId || '-' }}</td>
              <td><b>{{ attempt.score }}</b></td>
              <td>{{ attempt.passed ? '通过' : '未通过' }}</td>
            </tr>
            <tr v-if="!attempts.length">
              <td colspan="6" class="empty-cell">暂无考试记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<style scoped>
.exam-panel {
  display: grid;
  gap: 16px;
}

.table-heading select,
.exam-meta input,
.exam-meta select {
  min-height: 40px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  padding: 8px 10px;
}

.exam-summary,
.exam-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.exam-summary div,
.exam-meta label {
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.exam-summary span,
.exam-meta label {
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.exam-summary b {
  display: block;
  margin-top: 5px;
  font-size: 20px;
}

.exam-meta label {
  display: grid;
  gap: 7px;
}

.exam-meta .checkbox-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.exam-list {
  display: grid;
  gap: 12px;
}

.exam-card {
  display: grid;
  gap: 10px;
  padding: 15px;
  border: 1px solid var(--line);
  border-radius: 12px;
}

.exam-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.exam-title span {
  color: var(--primary);
  font-size: 12px;
  font-weight: 900;
}

.exam-option {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 14px;
  font-weight: 900;
}

.exam-submit {
  width: auto;
  justify-self: start;
}

.attempt-detail,
.attempt-history {
  display: grid;
  gap: 10px;
}

.attempt-detail h4 {
  margin: 0;
}

@media (max-width: 800px) {
  .exam-summary,
  .exam-meta {
    grid-template-columns: 1fr;
  }

  .exam-title {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
