<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/common/PageHeader.vue'
import SecondaryNavigation from '../components/common/SecondaryNavigation.vue'
import SearchInput from '../components/common/SearchInput.vue'
import ActionConfirmDialog from '../components/ui/ActionConfirmDialog.vue'
import ModalFooter from '../components/ui/ModalFooter.vue'
import UiButton from '../components/ui/UiButton.vue'
import UnsavedChangesDialog from '../components/ui/UnsavedChangesDialog.vue'
import {
  BookOpen, CheckCircle2, ClipboardCheck, Download, FileQuestion, Files, FolderTree,
  GraduationCap, Library, ListChecks, Play, Plus, RefreshCw, Send,
  Trash2, UserCheck, X,
} from 'lucide-vue-next'
import {
  deleteExamQuestionCategory,
  downloadExamResults,
  getAssignedExamPaper,
  getExamCampaign,
  getExamCenterOverview,
  getExamPaperForEdit,
  getExamQuestionBank,
  getExamQuestionCategories,
  getExamReviewDetail,
  getExamReviews,
  getExamResults,
  getExamWrongQuestions,
  getTrainingCourses,
  getTrainingMaterials,
  markExamWrongQuestion,
  publishExam,
  saveExamPaper,
  saveExamQuestionBankItem,
  saveExamQuestionCategory,
  saveTrainingCourse,
  saveTrainingMaterial,
  submitAssignedExam,
  submitExamReview,
  type ExamAssignment,
  type ExamCampaignDetail,
  type ExamCenterOverview,
  type ExamPaper,
  type ExamPaperSavePayload,
  type ExamQuestionBankItem,
  type ExamQuestionCategory,
  type ExamQuestionInput,
  type ExamResultRecord,
  type ExamReviewDetail,
  type ExamReviewTask,
  type ExamWrongQuestion,
  type TrainingCourse,
  type TrainingMaterialRecord,
} from '../api/exams'
import { useAuthStore } from '../stores/auth'

type ViewKey = 'mine' | 'courses' | 'materials' | 'questions' | 'categories' | 'papers' | 'campaigns' | 'grading' | 'results' | 'wrongs'
type EditorKey = 'course' | 'material' | 'category' | 'question' | 'paper' | 'publish' | null

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const overview = ref<ExamCenterOverview | null>(null)
const courses = ref<TrainingCourse[]>([])
const materials = ref<TrainingMaterialRecord[]>([])
const categories = ref<ExamQuestionCategory[]>([])
const questions = ref<ExamQuestionBankItem[]>([])
const reviews = ref<ExamReviewTask[]>([])
const results = ref<ExamResultRecord[]>([])
const wrongQuestions = ref<ExamWrongQuestion[]>([])
const selectedCampaign = ref<ExamCampaignDetail | null>(null)
const reviewDetail = ref<ExamReviewDetail | null>(null)

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const success = ref('')
const editor = ref<EditorKey>(null)
const activeView = ref<ViewKey>('mine')
const questionKeyword = ref('')
const questionCategoryId = ref<number | undefined>()
const selectedBankIds = ref<number[]>([])
const targetMode = ref<'scope' | 'users'>('scope')
const editorSnapshot = ref('')
const reviewSnapshot = ref('')
const pendingDismiss = ref<'editor' | 'review' | 'exam' | null>(null)
const pendingCategoryDelete = ref<ExamQuestionCategory | null>(null)

const activeAssignment = ref<ExamAssignment | null>(null)
const activePaper = ref<ExamPaper | null>(null)
const answers = reactive<Record<number, string>>({})
const submitting = ref(false)
const switchCount = ref(0)
const violated = ref(false)

const courseForm = reactive(emptyCourse())
const materialForm = reactive(emptyMaterial())
const categoryForm = reactive(emptyCategory())
const questionForm = reactive(emptyBankQuestion())
const paperForm = reactive<ExamPaperSavePayload>(emptyPaper())
const publishForm = reactive({
  paperId: 0,
  title: '',
  startAt: toInputDateTime(new Date()),
  dueAt: toInputDateTime(new Date(Date.now() + 7 * 86400000)),
  storeIds: [] as string[],
  targetRoles: ['EMPLOYEE', 'STORE_MANAGER'] as string[],
  userIds: [] as number[],
})

function currentEditorState() {
  if (editor.value === 'course') return courseForm
  if (editor.value === 'material') return materialForm
  if (editor.value === 'category') return categoryForm
  if (editor.value === 'question') return questionForm
  if (editor.value === 'paper') return { ...paperForm, selectedBankIds: selectedBankIds.value }
  if (editor.value === 'publish') return { ...publishForm, targetMode: targetMode.value }
  return null
}

const editorDirty = computed(() => Boolean(
  editor.value && editorSnapshot.value && JSON.stringify(currentEditorState()) !== editorSnapshot.value,
))
const reviewDirty = computed(() => Boolean(
  reviewDetail.value && reviewSnapshot.value && JSON.stringify(reviewDetail.value) !== reviewSnapshot.value,
))
const examDirty = computed(() => Object.values(answers).some((value) => String(value || '').trim()))

const canManage = computed(() => Boolean(overview.value?.canManage))
const companyView = computed(() => overview.value?.accessMode === 'COMPANY')
const assignments = computed(() => overview.value?.assignments || [])
const campaigns = computed(() => overview.value?.campaigns || [])
const papers = computed(() => overview.value?.papers || [])
const myAssignments = computed(() => assignments.value.filter((item) => item.userId === auth.user?.id))
const visibleQuestionBank = computed(() => questions.value.filter((item) => {
  if (questionCategoryId.value && item.categoryId !== questionCategoryId.value) return false
  const keyword = questionKeyword.value.trim().toLowerCase()
  return !keyword || item.questionText.toLowerCase().includes(keyword) || item.questionCode.toLowerCase().includes(keyword)
}))
const stores = computed(() => {
  const values = new Map<string, string>()
  for (const candidate of overview.value?.candidates || []) values.set(candidate.storeId, candidate.storeName)
  return Array.from(values.entries()).map(([id, name]) => ({ id, name }))
})
const metrics = computed(() => ({
  active: campaigns.value.filter((item) => item.statusLabel === '进行中').length,
  assigned: campaigns.value.reduce((sum, item) => sum + item.assignedCount, 0),
  completed: campaigns.value.reduce((sum, item) => sum + item.completedCount, 0),
  passed: campaigns.value.reduce((sum, item) => sum + item.passedCount, 0),
  pendingReview: reviews.value.length,
  average: weightedAverage(campaigns.value.map((item) => ({ value: item.averageScore, weight: item.completedCount }))),
}))
const modules = computed(() => {
  const common: Array<{ key: ViewKey; label: string; icon: typeof Play; badge?: number }> = [
    { key: 'mine' as const, label: '我的考试', icon: Play },
    { key: 'courses' as const, label: '培训课程', icon: GraduationCap },
    { key: 'materials' as const, label: '学习资料', icon: Library },
  ]
  if (canManage.value || companyView.value) common.push(
    { key: 'questions', label: '题库', icon: FileQuestion },
    { key: 'categories', label: '题目分类', icon: FolderTree },
    { key: 'papers', label: '试卷与组卷', icon: Files },
    { key: 'campaigns', label: '考试发布', icon: UserCheck },
  )
  if (canManage.value) common.push({ key: 'grading', label: '阅卷中心', icon: ClipboardCheck, badge: reviews.value.length })
  common.push({ key: 'results', label: '成绩统计', icon: ListChecks })
  common.push({ key: 'wrongs', label: '错题本', icon: BookOpen })
  return common
})

function emptyCourse() {
  return { id: undefined as number | undefined, courseCode: '', title: '', category: '', description: '', coverUrl: '', durationMinutes: 30, requiredRoleScope: 'EMPLOYEE,STORE_MANAGER', enabled: true, sortOrder: 0, materialIds: [] as number[] }
}
function emptyMaterial() {
  return { id: undefined as number | undefined, materialCode: '', title: '', category: '', imageText: '', content: '', enabled: true, sortOrder: 0 }
}
function emptyCategory() {
  return { id: undefined as number | undefined, categoryCode: '', categoryName: '', description: '', enabled: true, sortOrder: 0 }
}
function emptyBankQuestion() {
  return { id: undefined as number | undefined, questionCode: '', categoryId: undefined as number | undefined, questionType: 'SINGLE_CHOICE' as ExamQuestionBankItem['questionType'], questionText: '', optionsText: '', standardAnswer: '', answerAnalysis: '', acceptKeywords: '', difficulty: 'MEDIUM' as ExamQuestionBankItem['difficulty'], defaultScore: 10, enabled: true }
}
function emptyPaper(): ExamPaperSavePayload {
  return { paperCode: '', paperName: '', roleScope: 'EMPLOYEE,STORE_MANAGER', passScore: 80, enabled: true, questions: [] }
}
function emptyPaperQuestion(): ExamQuestionInput {
  return { questionType: 'SINGLE_CHOICE', questionText: '', options: ['', ''], standardAnswer: '', acceptKeywords: '', score: 10 }
}

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await getExamCenterOverview()
    const loaders: Array<Promise<unknown>> = [loadLearning(), loadResults(), loadWrongQuestions()]
    if (overview.value.canManage || overview.value.accessMode === 'COMPANY') loaders.push(loadQuestionBank())
    if (overview.value.canManage) loaders.push(loadReviews())
    await Promise.allSettled(loaders)
    const requested = String(route.query.view || '') as ViewKey
    if (modules.value.some((item) => item.key === requested)) activeView.value = requested
    if (!modules.value.some((item) => item.key === activeView.value)) activeView.value = 'mine'
  } catch (reason) {
    overview.value = null
    error.value = displayError(reason, '培训考试加载失败，请重新加载。')
  } finally {
    loading.value = false
  }
}

async function loadLearning() {
  const settled = await Promise.allSettled([getTrainingCourses(), getTrainingMaterials()])
  if (settled[0].status === 'fulfilled') courses.value = settled[0].value
  if (settled[1].status === 'fulfilled') materials.value = settled[1].value
}
async function loadQuestionBank() {
  const settled = await Promise.allSettled([getExamQuestionCategories(), getExamQuestionBank()])
  if (settled[0].status === 'fulfilled') categories.value = settled[0].value
  if (settled[1].status === 'fulfilled') questions.value = settled[1].value
}
async function loadReviews() { reviews.value = await getExamReviews() }
async function loadResults() { results.value = await getExamResults() }
async function loadWrongQuestions() { wrongQuestions.value = await getExamWrongQuestions() }

function setView(key: ViewKey) {
  activeView.value = key
  void router.replace({ query: { ...route.query, view: key } })
}

function selectView(key: string) {
  setView(key as ViewKey)
}

function showEditor(key: Exclude<EditorKey, null>) {
  editor.value = key
  editorSnapshot.value = JSON.stringify(currentEditorState())
}

function closeEditorNow() {
  editor.value = null
  editorSnapshot.value = ''
}

function requestCloseEditor() {
  if (saving.value) return
  if (editorDirty.value) pendingDismiss.value = 'editor'
  else closeEditorNow()
}

function closeReviewNow() {
  reviewDetail.value = null
  reviewSnapshot.value = ''
}

function requestCloseReview() {
  if (saving.value) return
  if (reviewDirty.value) pendingDismiss.value = 'review'
  else closeReviewNow()
}

function requestCloseExam() {
  if (submitting.value) return
  if (examDirty.value) pendingDismiss.value = 'exam'
  else closeExam()
}

function discardPendingChanges() {
  const target = pendingDismiss.value
  pendingDismiss.value = null
  if (target === 'editor') closeEditorNow()
  else if (target === 'review') closeReviewNow()
  else if (target === 'exam') closeExam()
}

function handleEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || pendingDismiss.value || pendingCategoryDelete.value) return
  if (editor.value) requestCloseEditor()
  else if (reviewDetail.value) requestCloseReview()
  else if (activeAssignment.value) requestCloseExam()
  else if (selectedCampaign.value) selectedCampaign.value = null
}

function openCourse(item?: TrainingCourse) {
  Object.assign(courseForm, emptyCourse(), item || {})
  showEditor('course')
}
function openMaterial(item?: TrainingMaterialRecord) {
  Object.assign(materialForm, emptyMaterial(), item ? { ...item, imageText: item.imageUrls.join('\n') } : {})
  showEditor('material')
}
function openCategory(item?: ExamQuestionCategory) {
  Object.assign(categoryForm, emptyCategory(), item || {})
  showEditor('category')
}
function openQuestion(item?: ExamQuestionBankItem) {
  Object.assign(questionForm, emptyBankQuestion(), item ? { ...item, optionsText: item.options.join('\n') } : {})
  showEditor('question')
}

async function saveCurrentEditor() {
  if (!editor.value) return
  saving.value = true
  error.value = ''
  try {
    if (editor.value === 'course') {
      await saveTrainingCourse({ ...courseForm })
      await loadLearning()
      success.value = '培训课程已保存。'
    } else if (editor.value === 'material') {
      await saveTrainingMaterial({
        id: materialForm.id, materialCode: materialForm.materialCode, title: materialForm.title,
        category: materialForm.category, imageUrls: splitLines(materialForm.imageText), content: materialForm.content,
        enabled: materialForm.enabled, sortOrder: materialForm.sortOrder,
      })
      await loadLearning()
      success.value = '学习资料已保存。'
    } else if (editor.value === 'category') {
      await saveExamQuestionCategory({ ...categoryForm })
      await loadQuestionBank()
      success.value = '题目分类已保存。'
    } else if (editor.value === 'question') {
      await saveExamQuestionBankItem({
        id: questionForm.id, questionCode: questionForm.questionCode, categoryId: questionForm.categoryId,
        questionType: questionForm.questionType, questionText: questionForm.questionText,
        options: splitLines(questionForm.optionsText), standardAnswer: questionForm.standardAnswer,
        answerAnalysis: questionForm.answerAnalysis, acceptKeywords: questionForm.acceptKeywords,
        difficulty: questionForm.difficulty, defaultScore: Number(questionForm.defaultScore), enabled: questionForm.enabled,
      })
      await loadQuestionBank()
      success.value = '题库题目已保存。'
    }
    closeEditorNow()
  } catch (reason) {
    error.value = displayError(reason, '保存失败，请检查填写内容。')
  } finally {
    saving.value = false
  }
}

function removeCategory(item: ExamQuestionCategory) {
  pendingCategoryDelete.value = item
}

async function confirmRemoveCategory() {
  const item = pendingCategoryDelete.value
  if (!item) return
  pendingCategoryDelete.value = null
  try {
    await deleteExamQuestionCategory(item.id)
    await loadQuestionBank()
  } catch (reason) {
    error.value = displayError(reason, '分类删除失败。')
  }
}

function openCreatePaper() {
  Object.assign(paperForm, emptyPaper())
  selectedBankIds.value = []
  showEditor('paper')
}
async function openEditPaper(paperId: number) {
  try {
    const paper = await getExamPaperForEdit(paperId)
    Object.assign(paperForm, { id: paper.id, paperCode: paper.paperCode, paperName: paper.paperName, roleScope: paper.roleScope || '', passScore: paper.passScore, enabled: paper.enabled, questions: paper.questions.map((q) => ({ questionType: q.questionType, questionText: q.questionText, options: [...q.options], standardAnswer: q.standardAnswer, acceptKeywords: q.acceptKeywords || '', score: q.score })) })
    selectedBankIds.value = []
    showEditor('paper')
  } catch (reason) { error.value = displayError(reason, '试卷读取失败。') }
}
function addSelectedBankQuestions() {
  const existing = new Set(paperForm.questions.map((item) => item.questionText))
  for (const item of questions.value.filter((row) => selectedBankIds.value.includes(row.id))) {
    if (existing.has(item.questionText)) continue
    paperForm.questions.push({ bankQuestionId: item.id, questionType: item.questionType, questionText: item.questionText, options: [...item.options], standardAnswer: item.standardAnswer || '', acceptKeywords: item.acceptKeywords || '', score: item.defaultScore })
  }
  selectedBankIds.value = []
}
async function savePaperEditor() {
  saving.value = true
  error.value = ''
  try {
    await saveExamPaper({ ...paperForm, paperName: paperForm.paperName.trim(), questions: paperForm.questions.map((item) => ({ ...item, questionText: item.questionText.trim(), standardAnswer: item.standardAnswer.trim(), options: item.options.map((value) => value.trim()).filter(Boolean), score: Number(item.score) })) })
    closeEditorNow()
    success.value = '试卷已保存。'
    await loadAll()
  } catch (reason) { error.value = displayError(reason, '试卷保存失败。') } finally { saving.value = false }
}

function openPublish() {
  if (!papers.value.length) { error.value = '请先创建试卷。'; return }
  Object.assign(publishForm, { paperId: papers.value[0].id, title: '', startAt: toInputDateTime(new Date()), dueAt: toInputDateTime(new Date(Date.now() + 7 * 86400000)), storeIds: [], targetRoles: ['EMPLOYEE', 'STORE_MANAGER'], userIds: [] })
  targetMode.value = 'scope'
  showEditor('publish')
}
async function publish() {
  saving.value = true
  error.value = ''
  try {
    await publishExam({ ...publishForm, title: publishForm.title.trim(), storeIds: targetMode.value === 'scope' ? publishForm.storeIds : [], targetRoles: targetMode.value === 'scope' ? publishForm.targetRoles : [], userIds: targetMode.value === 'users' ? publishForm.userIds : [] })
    closeEditorNow()
    success.value = '考试已发布并分配给应考人员。'
    await loadAll()
  } catch (reason) { error.value = displayError(reason, '考试发布失败。') } finally { saving.value = false }
}

async function openCampaign(id: number) {
  try { selectedCampaign.value = await getExamCampaign(id) } catch (reason) { error.value = displayError(reason, '考试详情加载失败。') }
}
async function exportCampaign() {
  if (!selectedCampaign.value) return
  try { await downloadExamResults(selectedCampaign.value.campaign.id, selectedCampaign.value.campaign.title) } catch (reason) { error.value = displayError(reason, '成绩导出失败。') }
}

async function openReview(item: ExamReviewTask) {
  try {
    reviewDetail.value = await getExamReviewDetail(item.attemptId)
    reviewSnapshot.value = JSON.stringify(reviewDetail.value)
  } catch (reason) { error.value = displayError(reason, '阅卷内容加载失败。') }
}
async function completeReview() {
  if (!reviewDetail.value) return
  saving.value = true
  try {
    await submitExamReview(reviewDetail.value.task.attemptId, { reviewNote: reviewDetail.value.reviewNote, answers: reviewDetail.value.answers.map((item) => ({ answerId: item.answerId, awardedScore: Number(item.awardedScore), comment: item.reviewComment })) })
    closeReviewNow()
    success.value = '阅卷已完成，成绩已更新。'
    await Promise.all([loadReviews(), loadResults(), loadAll()])
  } catch (reason) { error.value = displayError(reason, '阅卷提交失败。') } finally { saving.value = false }
}

async function startExam(assignment: ExamAssignment) {
  if (assignment.userId !== auth.user?.id) return
  try {
    activePaper.value = await getAssignedExamPaper(assignment.id)
    activeAssignment.value = assignment
    Object.keys(answers).forEach((key) => delete answers[Number(key)])
    switchCount.value = 0
    violated.value = false
  } catch (reason) { error.value = displayError(reason, '试卷加载失败。') }
}
function closeExam() { if (!submitting.value) { activeAssignment.value = null; activePaper.value = null } }
async function submitExam() {
  if (!activeAssignment.value || !activePaper.value) return
  if (activePaper.value.questions.some((q) => !String(answers[q.id] || '').trim())) { error.value = '请完成全部题目后再提交。'; return }
  submitting.value = true
  try {
    const result = await submitAssignedExam(activeAssignment.value.id, { violated: violated.value, answers: activePaper.value.questions.map((q) => ({ questionId: q.id, userAnswer: answers[q.id] || '' })) })
    success.value = activePaper.value.questions.some((q) => q.questionType === 'ESSAY') ? '考试已提交，主观题等待阅卷。' : `考试已提交，得分 ${formatNumber(result.score)} 分。`
    activeAssignment.value = null
    activePaper.value = null
    await loadAll()
  } catch (reason) { error.value = displayError(reason, '考试提交失败。') } finally { submitting.value = false }
}
async function toggleWrong(item: ExamWrongQuestion) {
  try { await markExamWrongQuestion(item.id, !item.mastered); await loadWrongQuestions() } catch (reason) { error.value = displayError(reason, '错题状态更新失败。') }
}

function handleVisibilityChange() { if (activeAssignment.value && document.hidden) { switchCount.value += 1; if (switchCount.value >= 3) violated.value = true } }
function splitLines(value: string) { return value.split(/\r?\n|[,，]/).map((item) => item.trim()).filter(Boolean) }
function questionTypeLabel(value: string) { return ({ SINGLE_CHOICE: '单选题', TEXT: '填空题', NUMBER: '数字题', ESSAY: '主观题' } as Record<string, string>)[value] || value }
function difficultyLabel(value: string) { return ({ EASY: '简单', MEDIUM: '中等', HARD: '困难' } as Record<string, string>)[value] || value }
function reviewStatusLabel(value: string) { return ({ PENDING: '待阅卷', REVIEWED: '已阅卷', AUTO_GRADED: '自动判分' } as Record<string, string>)[value] || value }
function statusTone(status: string) { return ['COMPLETED', '进行中', 'REVIEWED', 'AUTO_GRADED'].includes(status) ? 'ok' : ['OVERDUE', '已逾期'].includes(status) ? 'bad' : 'warn' }
function formatNumber(value?: number) { return Number(value || 0).toFixed(1).replace(/\.0$/, '') }
function weightedAverage(rows: Array<{ value: number; weight: number }>) { const total = rows.reduce((sum, row) => sum + row.weight, 0); return total ? rows.reduce((sum, row) => sum + row.value * row.weight, 0) / total : 0 }
function displayError(reason: unknown, fallback: string) { return reason instanceof Error && reason.message ? reason.message : fallback }
function toInputDateTime(date: Date) { const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000); return local.toISOString().slice(0, 16) }

onMounted(async () => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  document.addEventListener('keydown', handleEscape)
  await loadAll()
  await nextTick()
})
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  document.removeEventListener('keydown', handleEscape)
})
</script>

<template>
  <section class="page-panel exam-shell">
    <PageHeader title="培训考试" subtitle="课程学习、考试发布与成绩管理">
      <template #actions>
        <button class="button" :disabled="loading" @click="loadAll"><RefreshCw :size="16" />重新加载</button>
      </template>
    </PageHeader>

    <div v-if="error" class="message error">{{ error }}<button @click="error = ''"><X :size="14" /></button></div>
    <div v-if="success" class="message success">{{ success }}<button @click="success = ''"><X :size="14" /></button></div>

    <div v-if="loading && !overview" class="loading">正在读取培训考试数据...</div>
    <template v-else-if="overview">
      <div class="exam-layout">
        <SecondaryNavigation
          :items="modules"
          :model-value="activeView"
          label="培训考试功能"
          @update:model-value="selectView"
        >
          <template #icon="{ item }">
            <component :is="item.icon" :size="17" />
          </template>
        </SecondaryNavigation>

        <main class="exam-workspace">
          <section v-if="activeView === 'mine'" class="workspace-section">
            <div v-if="!myAssignments.length" class="empty">暂无分配给你的考试。</div>
            <div v-else class="paper-cards">
              <article v-for="item in myAssignments" :key="item.id" class="paper-card">
                <div class="paper-icon"><ClipboardCheck :size="22" /></div>
                <div class="paper-title"><b>{{ item.examTitle }}</b><span>{{ item.paperName }}</span></div>
                <dl><div><dt>截止</dt><dd>{{ item.dueAt }}</dd></div><div><dt>状态</dt><dd><i :class="statusTone(item.status)">{{ item.statusLabel }}</i></dd></div></dl>
                <div v-if="item.status === 'COMPLETED'" class="paper-result">{{ formatNumber(item.score) }} 分 · {{ item.passed ? '通过' : '未通过' }}</div>
                <button v-else-if="item.status === 'ASSIGNED'" class="button primary" @click="startExam(item)"><Play :size="15" />开始考试</button>
              </article>
            </div>
          </section>

          <section v-else-if="activeView === 'courses'" class="workspace-section">
            <div class="section-bar"><div><h2>课程列表</h2><span>按岗位组织学习资料</span></div><button v-if="canManage" class="button primary" @click="openCourse()"><Plus :size="15" />新建课程</button></div>
            <div v-if="!courses.length" class="empty">暂无培训课程。</div>
            <div v-else class="course-grid">
              <article v-for="item in courses" :key="item.id" class="course-row">
                <div class="course-cover"><GraduationCap :size="25" /></div>
                <div><b>{{ item.title }}</b><span>{{ item.category || '未分类' }} · {{ item.durationMinutes }} 分钟</span><p>{{ item.description || '暂无课程说明' }}</p></div>
                <div class="course-meta"><span>{{ item.materialCount }} 份资料</span><i :class="item.enabled ? 'ok' : 'muted'">{{ item.enabled ? '启用' : '停用' }}</i></div>
                <button v-if="canManage" class="text-button" @click="openCourse(item)">编辑</button>
              </article>
            </div>
          </section>

          <section v-else-if="activeView === 'materials'" class="workspace-section">
            <div class="section-bar"><div><h2>资料清单</h2></div><button v-if="canManage" class="button primary" @click="openMaterial()"><Plus :size="15" />新增资料</button></div>
            <div class="data-table">
              <div class="table-head material-cols"><span>资料</span><span>分类</span><span>学习人数</span><span>状态</span><span>操作</span></div>
              <div v-for="item in materials" :key="item.id" class="table-row material-cols">
                <div><b>{{ item.title }}</b><small>{{ item.materialCode }}</small></div><span>{{ item.category }}</span><span>{{ item.learnedCount }} 人</span><i :class="item.enabled ? 'ok' : 'muted'">{{ item.enabled ? '启用' : '停用' }}</i><button v-if="canManage" class="text-button" @click="openMaterial(item)">编辑</button><span v-else>-</span>
              </div>
              <div v-if="!materials.length" class="empty">暂无学习资料。</div>
            </div>
          </section>

          <section v-else-if="activeView === 'questions'" class="workspace-section">
            <div class="section-bar"><div><h2>题目清单</h2><span>题目独立维护，组卷时复用</span></div><button v-if="canManage" class="button primary" @click="openQuestion()"><Plus :size="15" />新增题目</button></div>
            <div class="filter-row"><SearchInput v-model="questionKeyword" class="question-search" placeholder="搜索题目编号或题干" aria-label="搜索考试题库" /><select v-model="questionCategoryId"><option :value="undefined">全部分类</option><option v-for="item in categories" :key="item.id" :value="item.id">{{ item.categoryName }}</option></select></div>
            <div class="data-table">
              <div class="table-head question-cols"><span>题目</span><span>分类</span><span>题型</span><span>难度</span><span>分值</span><span>使用</span><span>操作</span></div>
              <div v-for="item in visibleQuestionBank" :key="item.id" class="table-row question-cols"><div><b>{{ item.questionText }}</b><small>{{ item.questionCode }}</small></div><span>{{ item.categoryName || '未分类' }}</span><span>{{ questionTypeLabel(item.questionType) }}</span><span>{{ difficultyLabel(item.difficulty) }}</span><span>{{ item.defaultScore }}</span><span>{{ item.usedCount }} 次</span><button v-if="canManage" class="text-button" @click="openQuestion(item)">编辑</button><span v-else>-</span></div>
              <div v-if="!visibleQuestionBank.length" class="empty">暂无符合条件的题目。</div>
            </div>
          </section>

          <section v-else-if="activeView === 'categories'" class="workspace-section">
            <div class="section-bar"><div><h2>分类目录</h2></div><button v-if="canManage" class="button primary" @click="openCategory()"><Plus :size="15" />新增分类</button></div>
            <div class="category-list"><div v-for="item in categories" :key="item.id" class="category-row"><FolderTree :size="18" /><div><b>{{ item.categoryName }}</b><span>{{ item.categoryCode }} · {{ item.questionCount }} 道题</span></div><i :class="item.enabled ? 'ok' : 'muted'">{{ item.enabled ? '启用' : '停用' }}</i><button v-if="canManage" class="text-button" @click="openCategory(item)">编辑</button><button v-if="canManage" class="icon-danger" title="删除分类" @click="removeCategory(item)"><Trash2 :size="15" /></button></div><div v-if="!categories.length" class="empty">暂无题目分类。</div></div>
          </section>

          <section v-else-if="activeView === 'papers'" class="workspace-section">
            <div class="section-bar"><div><h2>试卷列表</h2><span>从题库选题，形成可发布试卷</span></div><button v-if="canManage" class="button primary" @click="openCreatePaper"><Plus :size="15" />新建试卷</button></div>
            <div class="data-table"><div class="table-head paper-cols"><span>试卷</span><span>题数</span><span>通过分</span><span>状态</span><span>操作</span></div><div v-for="item in papers" :key="item.id" class="table-row paper-cols"><div><b>{{ item.paperName }}</b><small>{{ item.paperCode }}</small></div><span>{{ item.questionCount }} 题</span><span>{{ item.passScore }} 分</span><i :class="item.enabled ? 'ok' : 'muted'">{{ item.enabled ? '启用' : '停用' }}</i><button v-if="canManage" class="text-button" @click="openEditPaper(item.id)">编辑组卷</button><span v-else>查看</span></div><div v-if="!papers.length" class="empty">暂无试卷。</div></div>
          </section>

          <section v-else-if="activeView === 'campaigns'" class="workspace-section">
            <div class="section-bar"><div><h2>考试安排</h2><span>按门店、角色或人员分配</span></div><button v-if="canManage" class="button primary" @click="openPublish"><Plus :size="15" />发布考试</button></div>
            <div class="metric-strip"><div><span>进行中</span><b>{{ metrics.active }}</b></div><div><span>应考</span><b>{{ metrics.assigned }}</b></div><div><span>完成</span><b>{{ metrics.completed }}</b></div><div><span>通过</span><b>{{ metrics.passed }}</b></div><div><span>平均分</span><b>{{ formatNumber(metrics.average) }}</b></div></div>
            <div class="data-table"><button v-for="item in campaigns" :key="item.id" class="table-row campaign-cols campaign-button" @click="openCampaign(item.id)"><div><b>{{ item.title }}</b><small>{{ item.paperName }}</small></div><i :class="statusTone(item.statusLabel)">{{ item.statusLabel }}</i><span>{{ item.completedCount }}/{{ item.assignedCount }} 完成</span><span>{{ item.passedCount }} 人通过</span><span>{{ item.dueAt }}</span></button><div v-if="!campaigns.length" class="empty">暂无考试发布记录。</div></div>
          </section>

          <section v-else-if="activeView === 'grading'" class="workspace-section">
            <div class="section-bar"><div><h2>待阅试卷</h2><span>只显示含主观题的待阅卷试卷</span></div></div>
            <div class="data-table"><div class="table-head review-cols"><span>考试</span><span>应考人</span><span>门店</span><span>自动得分</span><span>提交时间</span><span>操作</span></div><div v-for="item in reviews" :key="item.attemptId" class="table-row review-cols"><div><b>{{ item.examTitle || item.paperName }}</b><small>{{ item.paperName }}</small></div><span>{{ item.examineeName }}</span><span>{{ item.storeName || '-' }}</span><span>{{ item.autoScore }}</span><span>{{ item.submittedAt }}</span><button class="button small primary" @click="openReview(item)">开始阅卷</button></div><div v-if="!reviews.length" class="empty">暂无待阅卷试卷。</div></div>
          </section>

          <section v-else-if="activeView === 'results'" class="workspace-section">
            <div class="section-bar"><div><h2>成绩汇总</h2><span>{{ companyView ? '全公司成绩' : overview.accessMode === 'STORE' ? '本门店成绩' : '我的成绩' }}</span></div></div>
            <div class="data-table"><div class="table-head result-cols"><span>考试</span><span>应考人</span><span>门店</span><span>得分</span><span>结果</span><span>判分</span><span>提交时间</span></div><div v-for="item in results" :key="item.attemptId" class="table-row result-cols"><div><b>{{ item.examTitle || item.paperName }}</b><small>{{ item.paperName }}</small></div><span>{{ item.examineeName }}</span><span>{{ item.storeName || '-' }}</span><b>{{ item.score }}</b><i :class="item.passed ? 'ok' : 'bad'">{{ item.passed ? '通过' : '未通过' }}</i><span>{{ reviewStatusLabel(item.reviewStatus) }}</span><span>{{ item.submittedAt }}</span></div><div v-if="!results.length" class="empty">暂无成绩记录。</div></div>
          </section>

          <section v-else class="workspace-section">
            <div class="section-bar"><div><h2>错题复习</h2><span>复习本人未答对的题目</span></div></div>
            <div class="wrong-list"><article v-for="item in wrongQuestions" :key="item.id" :class="{ mastered: item.mastered }"><header><b>{{ item.questionText }}</b><span>{{ item.paperName }}</span></header><dl><div><dt>你的答案</dt><dd>{{ item.userAnswer || '未作答' }}</dd></div><div><dt>标准答案</dt><dd>{{ item.standardAnswer || '由阅卷人判定' }}</dd></div><div v-if="item.answerAnalysis"><dt>解析</dt><dd>{{ item.answerAnalysis }}</dd></div></dl><button class="button small" @click="toggleWrong(item)"><CheckCircle2 :size="14" />{{ item.mastered ? '继续练习' : '标记已掌握' }}</button></article><div v-if="!wrongQuestions.length" class="empty">暂无错题。</div></div>
          </section>
        </main>
      </div>
    </template>

    <div v-if="editor" class="overlay" role="dialog" aria-modal="true" aria-label="培训考试编辑" @click.self="requestCloseEditor">
      <form v-if="editor === 'course'" class="drawer" @submit.prevent="saveCurrentEditor"><header><h3>{{ courseForm.id ? '编辑课程' : '新建课程' }}</h3><UiButton variant="ghost" icon-only aria-label="关闭课程编辑" title="关闭" @click="requestCloseEditor"><template #icon><X :size="18" /></template></UiButton></header><div class="form-grid"><label>课程名称<input v-model.trim="courseForm.title" required /></label><label>课程编号<input v-model.trim="courseForm.courseCode" placeholder="留空自动生成" /></label><label>分类<input v-model.trim="courseForm.category" /></label><label>学习时长（分钟）<input v-model.number="courseForm.durationMinutes" type="number" min="0" /></label><label class="wide">适用角色<input v-model.trim="courseForm.requiredRoleScope" /></label><label class="wide">课程说明<textarea v-model.trim="courseForm.description" rows="4" /></label><fieldset class="wide checkbox-field"><legend>课程资料</legend><label v-for="item in materials" :key="item.id"><input v-model="courseForm.materialIds" type="checkbox" :value="item.id" />{{ item.title }}</label></fieldset><label>排序<input v-model.number="courseForm.sortOrder" type="number" /></label><label class="switch-label"><input v-model="courseForm.enabled" type="checkbox" />启用课程</label></div><ModalFooter><UiButton variant="secondary" @click="requestCloseEditor">取消</UiButton><UiButton variant="primary" type="submit" :loading="saving">保存课程</UiButton></ModalFooter></form>

      <form v-else-if="editor === 'material'" class="drawer" @submit.prevent="saveCurrentEditor"><header><h3>{{ materialForm.id ? '编辑资料' : '新增资料' }}</h3><UiButton variant="ghost" icon-only aria-label="关闭资料编辑" title="关闭" @click="requestCloseEditor"><template #icon><X :size="18" /></template></UiButton></header><div class="form-grid"><label>资料名称<input v-model.trim="materialForm.title" required /></label><label>资料编号<input v-model.trim="materialForm.materialCode" placeholder="留空自动生成" /></label><label>分类<input v-model.trim="materialForm.category" required /></label><label>排序<input v-model.number="materialForm.sortOrder" type="number" /></label><label class="wide">图片或附件地址（每行一个）<textarea v-model.trim="materialForm.imageText" rows="3" /></label><label class="wide">学习内容<textarea v-model.trim="materialForm.content" rows="8" /></label><label class="switch-label"><input v-model="materialForm.enabled" type="checkbox" />启用资料</label></div><ModalFooter><UiButton variant="secondary" @click="requestCloseEditor">取消</UiButton><UiButton variant="primary" type="submit" :loading="saving">保存资料</UiButton></ModalFooter></form>

      <form v-else-if="editor === 'category'" class="drawer narrow" @submit.prevent="saveCurrentEditor"><header><h3>{{ categoryForm.id ? '编辑分类' : '新增分类' }}</h3><UiButton variant="ghost" icon-only aria-label="关闭分类编辑" title="关闭" @click="requestCloseEditor"><template #icon><X :size="18" /></template></UiButton></header><div class="form-grid"><label>分类名称<input v-model.trim="categoryForm.categoryName" required /></label><label>分类编号<input v-model.trim="categoryForm.categoryCode" placeholder="留空自动生成" /></label><label class="wide">说明<textarea v-model.trim="categoryForm.description" rows="3" /></label><label>排序<input v-model.number="categoryForm.sortOrder" type="number" /></label><label class="switch-label"><input v-model="categoryForm.enabled" type="checkbox" />启用分类</label></div><ModalFooter><UiButton variant="secondary" @click="requestCloseEditor">取消</UiButton><UiButton variant="primary" type="submit" :loading="saving">保存分类</UiButton></ModalFooter></form>

      <form v-else-if="editor === 'question'" class="drawer" @submit.prevent="saveCurrentEditor"><header><h3>{{ questionForm.id ? '编辑题目' : '新增题目' }}</h3><UiButton variant="ghost" icon-only aria-label="关闭题目编辑" title="关闭" @click="requestCloseEditor"><template #icon><X :size="18" /></template></UiButton></header><div class="form-grid"><label>题目编号<input v-model.trim="questionForm.questionCode" placeholder="留空自动生成" /></label><label>题目分类<select v-model="questionForm.categoryId"><option :value="undefined">未分类</option><option v-for="item in categories" :key="item.id" :value="item.id">{{ item.categoryName }}</option></select></label><label>题型<select v-model="questionForm.questionType"><option value="SINGLE_CHOICE">单选题</option><option value="TEXT">填空题</option><option value="NUMBER">数字题</option><option value="ESSAY">主观题</option></select></label><label>难度<select v-model="questionForm.difficulty"><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></select></label><label class="wide">题干<textarea v-model.trim="questionForm.questionText" rows="4" required /></label><label v-if="questionForm.questionType === 'SINGLE_CHOICE'" class="wide">选项（每行一个）<textarea v-model.trim="questionForm.optionsText" rows="5" /></label><label class="wide">{{ questionForm.questionType === 'ESSAY' ? '参考答案' : '标准答案' }}<textarea v-model.trim="questionForm.standardAnswer" rows="3" /></label><label class="wide">答案解析<textarea v-model.trim="questionForm.answerAnalysis" rows="3" /></label><label>默认分值<input v-model.number="questionForm.defaultScore" type="number" min="0.01" step="0.01" /></label><label>可接受关键词<input v-model.trim="questionForm.acceptKeywords" /></label><label class="switch-label"><input v-model="questionForm.enabled" type="checkbox" />启用题目</label></div><ModalFooter><UiButton variant="secondary" @click="requestCloseEditor">取消</UiButton><UiButton variant="primary" type="submit" :loading="saving">保存题目</UiButton></ModalFooter></form>

      <form v-else-if="editor === 'paper'" class="drawer wide-drawer" @submit.prevent="savePaperEditor"><header><h3>{{ paperForm.id ? '编辑试卷' : '新建试卷' }}</h3><UiButton variant="ghost" icon-only aria-label="关闭试卷编辑" title="关闭" @click="requestCloseEditor"><template #icon><X :size="18" /></template></UiButton></header><div class="paper-builder"><section class="paper-settings"><div class="form-grid"><label>试卷名称<input v-model.trim="paperForm.paperName" required /></label><label>试卷编号<input v-model.trim="paperForm.paperCode" placeholder="留空自动生成" /></label><label>通过分数<input v-model.number="paperForm.passScore" type="number" min="0" /></label><label>适用角色<input v-model.trim="paperForm.roleScope" /></label></div><div class="bank-picker"><h4>从题库选题</h4><div class="bank-options"><label v-for="item in questions" :key="item.id"><input v-model="selectedBankIds" type="checkbox" :value="item.id" /><span>{{ item.questionText }}</span><em>{{ item.defaultScore }} 分</em></label></div><button type="button" class="button" @click="addSelectedBankQuestions">加入试卷</button></div></section><section class="paper-question-list"><div class="paper-question-head"><h4>试卷题目（{{ paperForm.questions.length }}）</h4><button type="button" class="text-button" @click="paperForm.questions.push(emptyPaperQuestion())">手工加题</button></div><article v-for="(item, index) in paperForm.questions" :key="index"><div class="question-order">{{ index + 1 }}</div><div class="question-fields"><select v-model="item.questionType"><option value="SINGLE_CHOICE">单选题</option><option value="TEXT">填空题</option><option value="NUMBER">数字题</option><option value="ESSAY">主观题</option></select><textarea v-model.trim="item.questionText" rows="2" placeholder="题干" /><textarea v-if="item.questionType === 'SINGLE_CHOICE'" :value="item.options.join('\n')" rows="3" placeholder="选项，每行一个" @input="item.options = ($event.target as HTMLTextAreaElement).value.split(/\r?\n/)" /><input v-model.trim="item.standardAnswer" placeholder="标准或参考答案" /><input v-model.number="item.score" type="number" min="0.01" step="0.01" /></div><button type="button" class="icon-danger" @click="paperForm.questions.splice(index, 1)"><Trash2 :size="15" /></button></article></section></div><ModalFooter><template #info><label class="switch-label"><input v-model="paperForm.enabled" type="checkbox" />启用试卷</label></template><UiButton variant="secondary" @click="requestCloseEditor">取消</UiButton><UiButton variant="primary" type="submit" :disabled="!paperForm.questions.length" :loading="saving">保存试卷</UiButton></ModalFooter></form>

<form v-else class="drawer" @submit.prevent="publish"><header><h3>发布考试</h3><UiButton variant="ghost" icon-only aria-label="关闭考试发布" title="关闭" @click="requestCloseEditor"><template #icon><X :size="18" /></template></UiButton></header><div class="form-grid"><label class="wide">考试名称<input v-model.trim="publishForm.title" required /></label><label>试卷<select v-model.number="publishForm.paperId"><option v-for="item in papers" :key="item.id" :value="item.id">{{ item.paperName }}</option></select></label><label>分配方式<select v-model="targetMode"><option value="scope">按门店和角色</option><option value="users">指定人员</option></select></label><label>开始时间<input v-model="publishForm.startAt" type="datetime-local" required /></label><label>截止时间<input v-model="publishForm.dueAt" type="datetime-local" required /></label><fieldset v-if="targetMode === 'scope'" class="wide checkbox-field"><legend>应考门店</legend><label v-for="item in stores" :key="item.id"><input v-model="publishForm.storeIds" type="checkbox" :value="item.id" />{{ item.name }}</label></fieldset><fieldset v-if="targetMode === 'scope'" class="wide checkbox-field"><legend>应考角色</legend><label><input v-model="publishForm.targetRoles" type="checkbox" value="EMPLOYEE" />学员</label><label><input v-model="publishForm.targetRoles" type="checkbox" value="STORE_MANAGER" />店长</label><label><input v-model="publishForm.targetRoles" type="checkbox" value="OPERATIONS" />运营</label></fieldset><fieldset v-else class="wide checkbox-field people"><legend>应考人员</legend><label v-for="item in overview?.candidates || []" :key="item.userId"><input v-model="publishForm.userIds" type="checkbox" :value="item.userId" />{{ item.displayName }} · {{ item.departmentName || item.roleLabel }} · {{ item.storeName }}</label></fieldset></div><ModalFooter><UiButton variant="secondary" @click="requestCloseEditor">取消</UiButton><UiButton variant="primary" type="submit" :loading="saving"><template #icon><Send :size="15" /></template>确认发布</UiButton></ModalFooter></form>
    </div>

    <div v-if="selectedCampaign" class="overlay" role="dialog" aria-modal="true" aria-label="考试安排详情" @click.self="selectedCampaign = null"><section class="drawer"><header><div><h3>{{ selectedCampaign.campaign.title }}</h3><span>{{ selectedCampaign.campaign.paperName }}</span></div><UiButton variant="ghost" icon-only aria-label="关闭考试安排详情" title="关闭" @click="selectedCampaign = null"><template #icon><X :size="18" /></template></UiButton></header><div class="detail-actions"><button v-if="overview?.canExport" type="button" class="button" @click="exportCampaign"><Download :size="15" />导出成绩</button></div><div class="data-table"><div class="table-head detail-cols"><span>应考人</span><span>门店</span><span>状态</span><span>成绩</span></div><div v-for="item in selectedCampaign.assignments" :key="item.id" class="table-row detail-cols"><b>{{ item.examineeName }}</b><span>{{ item.storeName }}</span><i :class="statusTone(item.status)">{{ item.statusLabel }}</i><span>{{ item.score == null ? '-' : `${item.score} 分` }}</span></div></div></section></div>

    <div v-if="reviewDetail" class="overlay" role="dialog" aria-modal="true" aria-label="考试阅卷" @click.self="requestCloseReview"><form class="drawer wide-drawer" @submit.prevent="completeReview"><header><div><h3>{{ reviewDetail.task.examineeName }} · 阅卷</h3><span>{{ reviewDetail.task.examTitle || reviewDetail.task.paperName }}</span></div><UiButton variant="ghost" icon-only aria-label="关闭阅卷" title="关闭" @click="requestCloseReview"><template #icon><X :size="18" /></template></UiButton></header><div class="review-answer-list"><article v-for="(item, index) in reviewDetail.answers" :key="item.answerId"><h4>{{ index + 1 }}. {{ item.questionText }} <span>{{ item.maxScore }} 分</span></h4><dl><div><dt>考生答案</dt><dd>{{ item.userAnswer || '未作答' }}</dd></div><div><dt>参考答案</dt><dd>{{ item.standardAnswer || '由阅卷人判断' }}</dd></div></dl><div class="review-score"><label>得分<input v-model.number="item.awardedScore" type="number" min="0" :max="item.maxScore" step="0.01" /></label><label>评语<input v-model.trim="item.reviewComment" /></label></div></article><label class="review-note">阅卷备注<textarea v-model.trim="reviewDetail.reviewNote" rows="3" /></label></div><ModalFooter><UiButton variant="secondary" @click="requestCloseReview">取消</UiButton><UiButton variant="primary" type="submit" :loading="saving">完成阅卷</UiButton></ModalFooter></form></div>

    <div v-if="activeAssignment && activePaper" class="overlay exam-taking" role="dialog" aria-modal="true" aria-label="考试作答"><form class="take-panel" @submit.prevent="submitExam"><header><div><h3>{{ activeAssignment.examTitle }}</h3><span>{{ activePaper.paperName }} · 通过分 {{ activePaper.passScore }}</span></div><UiButton variant="ghost" icon-only aria-label="暂时退出考试" title="关闭" :disabled="submitting" @click="requestCloseExam"><template #icon><X :size="18" /></template></UiButton></header><div class="switch-warning" :class="{ danger: violated }">考试期间请勿切换页面；已切换 {{ switchCount }}/3 次。</div><div class="take-list"><fieldset v-for="(item, index) in activePaper.questions" :key="item.id"><legend>{{ index + 1 }}. {{ item.questionText }}（{{ item.score }} 分）</legend><label v-for="option in item.options" v-if="item.questionType === 'SINGLE_CHOICE'" :key="option" class="choice"><input v-model="answers[item.id]" type="radio" :name="`question-${item.id}`" :value="option" />{{ option }}</label><textarea v-else-if="item.questionType === 'ESSAY'" v-model.trim="answers[item.id]" rows="5" placeholder="请输入答案" /><input v-else v-model.trim="answers[item.id]" :type="item.questionType === 'NUMBER' ? 'number' : 'text'" placeholder="请输入答案" /></fieldset></div><ModalFooter><UiButton variant="secondary" :disabled="submitting" @click="requestCloseExam">暂不作答</UiButton><UiButton variant="primary" type="submit" :loading="submitting"><template #icon><Send :size="15" /></template>提交考试</UiButton></ModalFooter></form></div>

    <UnsavedChangesDialog
      :open="Boolean(pendingDismiss)"
      :title="pendingDismiss === 'exam' ? '要暂时退出考试吗？' : '有尚未保存的内容'"
      :message="pendingDismiss === 'exam' ? '已填写的答案不会提交，重新进入考试后需要再次填写。' : '关闭后，本次填写的内容将不会保留。'"
      :keep-label="pendingDismiss === 'exam' ? '继续作答' : '继续填写'"
      :discard-label="pendingDismiss === 'exam' ? '退出考试' : '放弃修改'"
      @keep-editing="pendingDismiss = null"
      @discard="discardPendingChanges"
    />
    <ActionConfirmDialog
      :open="Boolean(pendingCategoryDelete)"
      title="确认删除题目分类？"
      :message="pendingCategoryDelete ? `删除“${pendingCategoryDelete.categoryName}”后无法恢复，请确认该分类不再使用。` : ''"
      cancel-label="取消"
      confirm-label="确认删除"
      confirm-variant="danger"
      @cancel="pendingCategoryDelete = null"
      @confirm="confirmRemoveCategory"
    />
  </section>
</template>

<style scoped>
.exam-shell{display:flex;flex-direction:column;gap:12px;min-width:1000px;color:#20242c}.exam-head{display:flex;align-items:center;justify-content:space-between}.exam-head h2{margin:0;font-size:22px}.exam-head span,.section-bar span{display:block;margin-top:3px;color:#7a8290;font-size:12px}.button{height:34px;display:inline-flex;align-items:center;justify-content:center;gap:6px;padding:0 13px;border:1px solid #d8dde5;border-radius:5px;background:#fff;color:#363c46;cursor:pointer}.button:hover{border-color:#76bdb8;color:#285f5c}.button.primary{border-color:#76bdb8;background:#76bdb8;color:#fff}.button.small{height:29px;padding:0 9px;font-size:12px}.button:disabled{opacity:.55;cursor:not-allowed}.message{display:flex;align-items:center;justify-content:space-between;padding:9px 12px;border-radius:4px;font-size:13px}.message button,.drawer header>button,.take-panel header>button{border:0;background:transparent;cursor:pointer}.message.error{background:#fff0ee;color:#b13a2e}.message.success{background:#edf8f1;color:#267448}.loading,.empty{padding:38px;text-align:center;color:#8a919c;background:#fff;border:1px solid #e5e8ed}.exam-layout{display:grid;grid-template-columns:178px minmax(0,1fr);min-height:620px;background:#fff;border:1px solid #e2e6eb}.exam-nav{padding:10px 0;border-right:1px solid #e5e8ed;background:#fafbfc}.exam-nav button{width:100%;height:42px;display:flex;align-items:center;gap:10px;padding:0 15px;border:0;border-left:3px solid transparent;background:transparent;color:#5e6673;cursor:pointer;text-align:left}.exam-nav button:hover{background:#f7faf9;color:#285f5c}.exam-nav button.active{border-left-color:#76bdb8;background:#e9f6f5;color:#285f5c;font-weight:700}.exam-nav em{margin-left:auto;min-width:20px;padding:2px 5px;border-radius:10px;background:#e84b3c;color:#fff;font-size:11px;text-align:center}.exam-workspace{min-width:0;background:#fff}.workspace-section{padding:18px}.section-bar{min-height:40px;display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:14px}.section-bar h3{margin:0;font-size:16px}.paper-cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.paper-card{display:grid;grid-template-columns:40px minmax(0,1fr);gap:10px;padding:14px;border:1px solid #e0e4e9;border-radius:6px}.paper-icon,.course-cover{display:flex;align-items:center;justify-content:center;width:40px;height:40px;border-radius:5px;background:#e9f6f5;color:#285f5c}.paper-title b,.course-row b,.table-row b{display:block;font-size:14px}.paper-title span,.course-row span,.table-row small{display:block;margin-top:3px;color:#858c96;font-size:11px}.paper-card dl{grid-column:1/-1;display:grid;grid-template-columns:1fr 1fr;margin:4px 0}.paper-card dl div{display:flex;flex-direction:column;gap:3px}.paper-card dt{color:#858c96;font-size:11px}.paper-card dd{margin:0;font-size:12px}.paper-card .button,.paper-result{grid-column:1/-1}.paper-result{padding:8px;background:#f4f7f5;color:#28764c;text-align:center;font-weight:700}.course-grid{display:flex;flex-direction:column;border-top:1px solid #e7eaee}.course-row{display:grid;grid-template-columns:45px minmax(0,1fr) 150px 60px;gap:12px;align-items:center;padding:14px 4px;border-bottom:1px solid #e7eaee}.course-row p{margin:5px 0 0;color:#616975;font-size:12px}.course-meta{display:flex;align-items:flex-end;flex-direction:column;gap:6px}.data-table{border:1px solid #e1e5ea}.table-head,.table-row{display:grid;align-items:center;gap:12px;padding:10px 12px}.table-head{background:#f5f7f9;color:#68717e;font-size:12px;font-weight:700}.table-row{min-height:48px;border-top:1px solid #e7eaee;font-size:12px}.table-row:first-of-type{border-top:0}.material-cols{grid-template-columns:minmax(220px,2fr) 1fr 100px 80px 70px}.question-cols{grid-template-columns:minmax(320px,3fr) 110px 80px 70px 55px 55px 60px}.paper-cols{grid-template-columns:minmax(260px,2fr) 80px 80px 70px 100px}.review-cols{grid-template-columns:minmax(210px,2fr) 100px 130px 80px 140px 90px}.result-cols{grid-template-columns:minmax(210px,2fr) 90px 130px 60px 70px 80px 140px}.campaign-cols{grid-template-columns:minmax(230px,2fr) 75px 100px 100px 150px}.detail-cols{grid-template-columns:1fr 1.2fr 80px 80px}.campaign-button{width:100%;border-left:0;border-right:0;border-bottom:0;background:#fff;text-align:left;cursor:pointer}.campaign-button:hover{background:#f7faf9}.filter-row{display:flex;gap:8px;margin-bottom:12px}.filter-row>.question-search{flex:1}.filter-row select{min-width:180px}.category-list{border-top:1px solid #e5e8ed}.category-row{display:grid;grid-template-columns:28px minmax(0,1fr) 60px 50px 32px;align-items:center;gap:8px;padding:12px 8px;border-bottom:1px solid #e5e8ed}.category-row span{display:block;color:#838a94;font-size:11px}.text-button{padding:0;border:0;background:transparent;color:#285f5c;cursor:pointer}.icon-danger{width:30px;height:30px;display:inline-flex;align-items:center;justify-content:center;border:0;background:transparent;color:#b84b42;cursor:pointer}.ok,.bad,.warn,.muted{font-style:normal;font-weight:700}.ok{color:#278052}.bad{color:#bf4137}.warn{color:#b5701f}.muted{color:#9298a1}.metric-strip{display:grid;grid-template-columns:repeat(5,1fr);margin-bottom:14px;border:1px solid #e1e5ea}.metric-strip div{padding:10px 14px;border-right:1px solid #e1e5ea}.metric-strip div:last-child{border-right:0}.metric-strip span{display:block;color:#7b838e;font-size:11px}.metric-strip b{display:block;margin-top:3px;font-size:20px}.wrong-list{display:flex;flex-direction:column;gap:10px}.wrong-list article{padding:14px;border:1px solid #f0c4c8;background:#fff0f1}.wrong-list article.mastered{border-color:#bfe3cf;background:#edf8f2;opacity:.72}.wrong-list header{display:flex;justify-content:space-between;gap:20px}.wrong-list header span{color:#858c96;font-size:11px}.wrong-list dl{display:grid;gap:5px;margin:10px 0}.wrong-list dl div{display:grid;grid-template-columns:80px 1fr}.wrong-list dt{color:#858c96}.wrong-list dd{margin:0}.overlay{position:fixed;z-index:80;inset:0;display:flex;align-items:stretch;justify-content:flex-end;background:rgba(25,30,38,.42)}.drawer{width:min(720px,80vw);display:flex;flex-direction:column;background:#fff;box-shadow:-12px 0 32px rgba(0,0,0,.12)}.drawer.narrow{width:560px}.drawer.wide-drawer{width:min(1120px,92vw)}.drawer header,.take-panel header{min-height:62px;display:flex;align-items:center;justify-content:space-between;padding:0 20px;border-bottom:1px solid #e4e7eb}.drawer header h3,.take-panel header h3{margin:0;font-size:17px}.drawer header span,.take-panel header span{display:block;margin-top:3px;color:#7d8590;font-size:12px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;padding:20px;overflow-y:auto}.form-grid label,.review-score label,.review-note{display:flex;flex-direction:column;gap:6px;color:#4e5662;font-size:12px}.form-grid .wide{grid-column:1/-1}.form-grid input,.form-grid select,.form-grid textarea,.question-fields input,.question-fields select,.question-fields textarea,.review-score input,.review-note textarea,.take-list input,.take-list textarea{width:100%;box-sizing:border-box;padding:9px 10px;border:1px solid #d8dde4;border-radius:4px;background:#fff;font:inherit;outline:0}.form-grid input:focus,.form-grid select:focus,.form-grid textarea:focus,.question-fields input:focus,.question-fields textarea:focus{border-color:#76bdb8;box-shadow:0 0 0 2px #e9f6f5}.checkbox-field{display:flex;flex-wrap:wrap;gap:9px 18px;padding:12px;border:1px solid #dfe3e8}.checkbox-field legend{padding:0 5px}.checkbox-field label{display:flex;flex-direction:row;align-items:center}.checkbox-field input,.switch-label input{width:auto}.switch-label{display:flex!important;flex-direction:row!important;align-items:center!important;gap:8px!important}.drawer footer,.take-panel footer{display:flex;align-items:center;justify-content:flex-end;gap:10px;margin-top:auto;padding:13px 20px;border-top:1px solid #e4e7eb}.paper-builder{display:grid;grid-template-columns:360px minmax(0,1fr);min-height:0;flex:1}.paper-settings{padding:16px;border-right:1px solid #e4e7eb;overflow-y:auto}.paper-settings .form-grid{padding:0}.bank-picker{margin-top:18px}.bank-picker h4,.paper-question-head h4{margin:0 0 9px}.bank-options{max-height:300px;overflow:auto;border:1px solid #dfe3e8}.bank-options label{display:grid;grid-template-columns:18px 1fr 45px;gap:7px;padding:8px;border-bottom:1px solid #edf0f2;font-size:12px}.bank-options em{font-style:normal;color:#7d8590}.paper-question-list{padding:16px;overflow-y:auto}.paper-question-head{display:flex;align-items:center;justify-content:space-between}.paper-question-list article{display:grid;grid-template-columns:30px minmax(0,1fr) 32px;gap:8px;padding:12px 0;border-top:1px solid #e5e8ed}.question-order{font-weight:800;color:#285f5c}.question-fields{display:grid;grid-template-columns:120px 1fr 90px;gap:8px}.question-fields textarea{grid-column:1/-1}.review-answer-list{padding:18px;overflow:auto}.review-answer-list article{padding:13px;border:1px solid #e0e4e9;margin-bottom:10px}.review-answer-list h4{margin:0 0 10px}.review-answer-list h4 span{float:right;color:#285f5c}.review-answer-list dl{margin:0 0 10px}.review-answer-list dl div{display:grid;grid-template-columns:80px 1fr;padding:5px 0}.review-answer-list dt{color:#7f8792}.review-answer-list dd{margin:0}.review-score{display:grid;grid-template-columns:120px 1fr;gap:10px}.review-note{margin-top:12px}.detail-actions{display:flex;justify-content:flex-end;padding:12px 20px}.exam-taking{align-items:center;justify-content:center}.take-panel{width:min(960px,92vw);max-height:90vh;display:flex;flex-direction:column;background:#fff}.switch-warning{padding:9px 20px;background:#fff8e8;color:#8c6415}.switch-warning.danger{background:#fff0ee;color:#b23d33}.take-list{padding:18px;overflow:auto}.take-list fieldset{margin:0 0 12px;padding:14px;border:1px solid #dfe3e8}.take-list legend{padding:0 6px;font-weight:700}.choice{display:flex;align-items:center;gap:8px;padding:7px}.choice input{width:auto}.take-list input,.take-list textarea{margin-top:8px}.people{max-height:260px;overflow:auto}
</style>

<style scoped>
.exam-shell {
  min-width: 0;
  gap: 16px;
}

.overlay {
  z-index: var(--ds-z-modal, 1400);
}

.exam-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 18px;
  min-height: 0;
  border: 0;
  background: transparent;
}

.exam-workspace {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #e2e6eb;
  background: #fff;
}

.section-bar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 16px;
}

.section-bar > div {
  min-width: 0;
}

.section-bar h2 {
  margin: 0;
  font-size: 16px;
  line-height: 1.4;
  white-space: nowrap;
}

.section-bar > .button {
  width: auto;
  flex: none;
}

.data-table {
  max-width: 100%;
  overflow-x: auto;
}

@media (max-width: 640px) {
  .workspace-section {
    padding: 14px;
  }

  .section-bar {
    grid-template-columns: 1fr;
    align-items: start;
  }

  .section-bar > .button {
    justify-self: start;
  }

  .paper-cards,
  .metric-strip {
    grid-template-columns: 1fr;
  }
}
</style>
