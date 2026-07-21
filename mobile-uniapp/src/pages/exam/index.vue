<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import {
  getMobileExamOverview,
  getMobileExamPaper,
  getMobileExamResults,
  getMobileWrongQuestions,
  markMobileWrongQuestionMastered,
  submitMobileExam,
} from '../../api/business'
import SafeActionBar from '../../components/SafeActionBar.vue'
import { canUseMobileCapability, useSessionStore } from '../../stores'
import type { ExamAssignment, ExamPaper, ExamResult, WrongQuestion } from '../../types/business'

const session = useSessionStore()
const assignments = ref<ExamAssignment[]>([])
const results = ref<ExamResult[]>([])
const wrongQuestions = ref<WrongQuestion[]>([])
const activeAssignment = ref<ExamAssignment | null>(null)
const paper = ref<ExamPaper | null>(null)
const answers = ref<Record<number, string>>({})
const loading = ref(false)
const loadingPaper = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const actionMessage = ref('')
const markingWrongId = ref<number | null>(null)

const canLearn = computed(() => canUseMobileCapability(session.user, 'exam'))
const pendingAssignments = computed(() => assignments.value.filter((assignment) => assignment.status !== 'COMPLETED'))
const answeredCount = computed(() => paper.value?.questions.filter((question) => String(answers.value[question.id] || '').trim()).length || 0)
const unansweredCount = computed(() => Math.max(0, (paper.value?.questions.length || 0) - answeredCount.value))

onShow(() => {
  if (!canLearn.value) return denyAndReturn()
  void refresh()
})
onPullDownRefresh(async () => {
  await refresh()
  uni.stopPullDownRefresh()
})

async function refresh() {
  if (!canLearn.value || loading.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [overview, history, wrongs] = await Promise.all([getMobileExamOverview(), getMobileExamResults(), getMobileWrongQuestions()])
    assignments.value = overview.assignments || []
    results.value = history || []
    wrongQuestions.value = wrongs || []
  } catch (error) {
    errorMessage.value = friendlyError(error, '考试数据暂时无法加载，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function toggleMastered(item: WrongQuestion) {
  if (markingWrongId.value !== null) return
  markingWrongId.value = item.id
  errorMessage.value = ''
  actionMessage.value = ''
  try {
    const result = await markMobileWrongQuestionMastered(item.id, !item.mastered)
    item.mastered = result.mastered
    actionMessage.value = result.mastered ? '已标记掌握，复习记录已更新。' : '已重新加入待复习错题。'
  } catch (error) {
    errorMessage.value = friendlyError(error, '错题状态更新失败，请稍后重试。')
  } finally {
    markingWrongId.value = null
  }
}

async function startExam(assignment: ExamAssignment) {
  if (loadingPaper.value || !canStartExam(assignment)) return
  loadingPaper.value = true
  errorMessage.value = ''
  actionMessage.value = ''
  try {
    paper.value = await getMobileExamPaper(assignment.id)
    activeAssignment.value = assignment
    answers.value = {}
    uni.pageScrollTo({ scrollTop: 0, duration: 220 })
  } catch (error) {
    errorMessage.value = friendlyError(error, '试卷加载失败，请确认考试时间和账号权限。')
  } finally {
    loadingPaper.value = false
  }
}

function canStartExam(assignment: ExamAssignment) {
  return assignment.status === 'ASSIGNED'
}

function assignmentActionText(assignment: ExamAssignment) {
  const labels: Record<string, string> = {
    NOT_STARTED: '尚未开始',
    OVERDUE: '已逾期',
    REVIEW_PENDING: '等待阅卷',
    RETAKE_PENDING: '等待重考',
  }
  return canStartExam(assignment) ? '开始答题' : labels[assignment.status] || assignment.statusLabel
}

function setChoice(questionId: number, event: unknown) {
  answers.value[questionId] = String((event as { detail?: { value?: string } })?.detail?.value || '')
}

function setAnswer(questionId: number, event: unknown) {
  answers.value[questionId] = String((event as { detail?: { value?: string } })?.detail?.value || '')
}

async function submitExam() {
  if (!paper.value || !activeAssignment.value || submitting.value) return
  if (unansweredCount.value > 0) {
    errorMessage.value = `还有 ${unansweredCount.value} 题未作答，请完成全部题目后再交卷。`
    return
  }
  if (!(await confirmAction('提交后不能修改，确认交卷吗？'))) return

  submitting.value = true
  errorMessage.value = ''
  actionMessage.value = ''
  try {
    const attempt = await submitMobileExam(activeAssignment.value.id, {
      violated: false,
      answers: paper.value.questions.map((question) => ({
        questionId: question.id,
        userAnswer: String(answers.value[question.id] || '').trim(),
      })),
    })
    actionMessage.value = `交卷成功，当前得分 ${attempt.score} 分，${attempt.passed ? '已通过' : '请查看结果与复习建议'}。`
    activeAssignment.value = null
    paper.value = null
    answers.value = {}
    await refresh()
  } catch (error) {
    errorMessage.value = friendlyError(error, '交卷失败，请刷新考试状态后重试。')
  } finally {
    submitting.value = false
  }
}

function closePaper() {
  activeAssignment.value = null
  paper.value = null
  answers.value = {}
}

function confirmAction(content: string) {
  return new Promise<boolean>((resolve) => {
    uni.showModal({ title: '确认交卷', content, confirmText: '交卷', success: (result) => resolve(Boolean(result.confirm)), fail: () => resolve(false) })
  })
}

function friendlyError(error: unknown, fallback: string) {
  const status = Number((error as { status?: number })?.status || 0)
  if (status === 403) return '当前账号无权查看或提交这场考试。'
  if (status === 409) return '考试状态已发生变化，请刷新后查看。'
  if (status === 401) return '登录已过期，请重新登录。'
  return fallback
}

function denyAndReturn() {
  uni.showToast({ title: '考试中心仅向员工开放', icon: 'none' })
  setTimeout(() => uni.reLaunch({ url: '/pages/home/index' }), 500)
}
</script>

<template>
  <view class="page" :class="{ 'with-action': Boolean(paper) }">
    <view class="page-head">
      <view><text class="eyebrow">员工学习</text><text class="title">考试中心</text></view>
      <button class="ghost-button" :loading="loading" :disabled="loading || !canLearn" @click="refresh">刷新</button>
    </view>

    <view v-if="!canLearn" class="state-card">考试中心仅向员工开放，并需具备后端学习权限。</view>
    <template v-else>
      <view v-if="errorMessage" class="message error">{{ errorMessage }}</view>
      <view v-if="actionMessage" class="message success">{{ actionMessage }}</view>

      <template v-if="paper && activeAssignment">
        <view class="paper-head">
          <view>
            <text class="paper-title">{{ activeAssignment.examTitle || paper.paperName }}</text>
            <text class="muted">及格 {{ paper.passScore }} 分 · 已答 {{ answeredCount }}/{{ paper.questions.length }}</text>
          </view>
          <button class="close-button" @click="closePaper">退出答题</button>
        </view>

        <view v-for="(question, index) in paper.questions" :key="question.id" class="question-card">
          <view class="question-head"><text class="question-number">{{ index + 1 }}</text><text class="question-score">{{ question.score }} 分</text></view>
          <text class="question-text">{{ question.questionText }}</text>
          <radio-group v-if="question.questionType === 'SINGLE_CHOICE'" class="options" @change="setChoice(question.id, $event)">
            <label v-for="option in question.options" :key="option" class="option-row">
              <radio :value="option" :checked="answers[question.id] === option" color="#27655f" />
              <text>{{ option }}</text>
            </label>
          </radio-group>
          <input
            v-else-if="question.questionType === 'NUMBER'"
            class="answer-input"
            type="digit"
            :value="answers[question.id] || ''"
            placeholder="请输入答案"
            :adjust-position="true"
            :cursor-spacing="150"
            @input="setAnswer(question.id, $event)"
          >
          <textarea
            v-else
            class="answer-area"
            :value="answers[question.id] || ''"
            maxlength="1000"
            placeholder="请输入答案"
            :adjust-position="true"
            :cursor-spacing="150"
            @input="setAnswer(question.id, $event)"
          />
        </view>
      </template>

      <template v-else>
        <view class="section-head"><text class="section-title">待完成考试</text><text class="muted">{{ pendingAssignments.length }} 场</text></view>
        <view v-if="loading && !assignments.length" class="state-card">正在读取考试安排…</view>
        <view v-else-if="!pendingAssignments.length" class="state-card">当前没有待完成考试</view>
        <view v-for="assignment in pendingAssignments" :key="assignment.id" class="assignment-card">
          <view class="card-head"><text class="card-title">{{ assignment.examTitle }}</text><text class="status-chip">{{ assignment.statusLabel }}</text></view>
          <text class="muted">{{ assignment.paperName }}</text>
          <text class="due">截止时间：{{ assignment.dueAt }}</text>
          <button
            class="start-button"
            :loading="loadingPaper && activeAssignment?.id === assignment.id"
            :disabled="loadingPaper || !canStartExam(assignment)"
            @click="startExam(assignment)"
          >
            {{ assignmentActionText(assignment) }}
          </button>
        </view>

        <view class="section-head history-head"><text class="section-title">我的成绩</text><text class="muted">{{ results.length }} 次</text></view>
        <view v-if="!results.length && !loading" class="state-card">暂无考试成绩</view>
        <view v-for="result in results.slice(0, 30)" :key="result.attemptId" class="result-card">
          <view><text class="card-title">{{ result.examTitle || result.paperName }}</text><text class="muted">{{ result.submittedAt }} · {{ result.reviewStatus }}</text></view>
          <view class="result-score" :class="{ passed: result.passed }"><text>{{ result.score }}</text><text class="score-label">{{ result.passed ? '通过' : '未通过' }}</text></view>
        </view>

        <view class="section-head history-head"><text class="section-title">错题复习</text><text class="muted">待掌握 {{ wrongQuestions.filter((item) => !item.mastered).length }} 题</text></view>
        <view v-if="!wrongQuestions.length && !loading" class="state-card">暂无错题记录</view>
        <view v-for="item in wrongQuestions.slice(0, 50)" :key="item.id" class="wrong-card" :class="{ mastered: item.mastered }">
          <view class="card-head"><text class="card-title">{{ item.paperName }}</text><text class="status-chip">{{ item.mastered ? '已掌握' : '待复习' }}</text></view>
          <text class="question-text">{{ item.questionText }}</text>
          <view class="answer-review"><text>你的答案：{{ item.userAnswer || '未作答' }}</text><text>标准答案：{{ item.standardAnswer }}</text><text v-if="item.answerAnalysis">解析：{{ item.answerAnalysis }}</text></view>
          <button class="master-button" :loading="markingWrongId === item.id" :disabled="markingWrongId !== null" @click="toggleMastered(item)">{{ item.mastered ? '重新加入复习' : '标记为已掌握' }}</button>
        </view>
      </template>
    </template>

    <SafeActionBar
      v-if="paper && activeAssignment"
      primary-text="确认交卷"
      secondary-text="退出答题"
      :loading="submitting"
      :disabled="submitting"
      @primary="submitExam"
      @secondary="closePaper"
    />
  </view>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; box-sizing: border-box; padding: 24rpx; background: #f2f6f5; color: #1c1d22; }
.page.with-action { padding-bottom: calc(160rpx + env(safe-area-inset-bottom)); }
.page-head, .section-head, .paper-head, .question-head, .card-head, .result-card { display: flex; align-items: center; justify-content: space-between; gap: 18rpx; }
.page-head { margin-bottom: 24rpx; }
.eyebrow, .title, .section-title, .paper-title, .muted, .question-text, .card-title, .due, .score-label { display: block; }
.eyebrow { color: #71807d; font-size: 24rpx; letter-spacing: 0; }
.title { margin-top: 6rpx; font-size: 38rpx; font-weight: 700; }
.section-head { margin: 28rpx 2rpx 14rpx; }
.section-title, .paper-title, .card-title { font-size: 30rpx; font-weight: 700; }
.paper-head, .question-card, .assignment-card, .result-card, .wrong-card, .state-card { margin-bottom: 18rpx; padding: 24rpx; border: 1px solid #d9e6e3; border-radius: 16rpx; background: #fff; box-shadow: 0 8rpx 24rpx rgba(37,39,45,.045); }
.question-number { display: flex; width: 54rpx; height: 54rpx; align-items: center; justify-content: center; border-radius: 50%; background: #27655f; color: #fff; font-weight: 700; }
.question-score { color: #637067; font-size: 24rpx; }
.question-text { margin: 20rpx 0; font-size: 29rpx; font-weight: 650; line-height: 1.55; }
.options { display: flex; flex-direction: column; gap: 12rpx; }
.option-row { display: flex; min-height: 88rpx; box-sizing: border-box; align-items: center; gap: 16rpx; padding: 12rpx 18rpx; border: 1px solid #d9e6e3; border-radius: 16rpx; background: #f8faf8; font-size: 26rpx; }
.answer-input, .answer-area { width: 100%; box-sizing: border-box; border: 1px solid #d9e6e3; border-radius: 16rpx; background: #f8faf8; font-size: 27rpx; }
.answer-input { min-height: 88rpx; padding: 0 18rpx; }
.answer-area { min-height: 180rpx; padding: 18rpx; }
.status-chip { flex-shrink: 0; padding: 7rpx 12rpx; border-radius: 12rpx; background: #fff0d8; color: #8a5b18; font-size: 22rpx; }
.muted { margin-top: 7rpx; color: #71807d; font-size: 23rpx; line-height: 1.5; }
.due { margin-top: 14rpx; color: #8a5b18; font-size: 24rpx; }
.start-button, .close-button, .ghost-button { min-height: 88rpx; line-height: 88rpx; border-radius: 16rpx; font-size: 27rpx; }
.start-button { margin-top: 18rpx; background: #27655f; color: #fff; }
.close-button { flex-shrink: 0; padding: 0 20rpx; background: #e6f3f1; color: #27655f; }
.ghost-button { min-width: 136rpx; background: #fff; color: #27655f; border: 1px solid #d9e6e3; }
.history-head { margin-top: 40rpx; }
.result-score { min-width: 100rpx; color: #9a4236; text-align: center; font-size: 38rpx; font-weight: 750; }
.result-score.passed { color: #27655f; }
.wrong-card.mastered { opacity: .72; }
.answer-review { padding: 18rpx; border-radius: 12rpx; background: #f7faf9; color: #4f5661; font-size: 24rpx; line-height: 1.65; }
.answer-review text { display: block; }
.master-button { min-height: 80rpx; margin-top: 16rpx; line-height: 80rpx; border-radius: 14rpx; background: #e6f3f1; color: #27655f; font-size: 25rpx; }
.score-label { font-size: 21rpx; }
.message { margin-bottom: 18rpx; padding: 18rpx 20rpx; border-radius: 14rpx; font-size: 25rpx; }
.message.error { background: #fff0ed; color: #963b30; }
.message.success { background: #eaf5ed; color: #24663e; }
.state-card { text-align: center; color: #71807d; }
button::after { border: 0; }
</style>
