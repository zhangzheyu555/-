<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { Download, FilePlus2, GraduationCap, Play, Plus, RefreshCw, Send, Trash2, X } from 'lucide-vue-next'
import {
  downloadExamResults,
  getAssignedExamPaper,
  getExamCampaign,
  getExamCenterOverview,
  getExamPaperForEdit,
  publishExam,
  saveExamPaper,
  submitAssignedExam,
  type ExamAssignment,
  type ExamCampaignDetail,
  type ExamCenterOverview,
  type ExamPaper,
  type ExamPaperSavePayload,
  type ExamQuestionInput,
} from '../api/exams'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const overview = ref<ExamCenterOverview | null>(null)
const selectedDetail = ref<ExamCampaignDetail | null>(null)
const loading = ref(false)
const detailLoading = ref(false)
const saving = ref(false)
const error = ref('')
const successMessage = ref('')
const paperEditorOpen = ref(false)
const publishEditorOpen = ref(false)
const targetMode = ref<'scope' | 'users'>('scope')

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

const activeAssignment = ref<ExamAssignment | null>(null)
const activePaper = ref<ExamPaper | null>(null)
const answers = reactive<Record<number, string>>({})
const submitting = ref(false)
const switchCount = ref(0)
const violated = ref(false)

const canManage = computed(() => Boolean(overview.value?.canManage))
const isCompanyView = computed(() => overview.value?.accessMode === 'COMPANY')
const isStoreView = computed(() => overview.value?.accessMode === 'STORE')
const pageModeLabel = computed(() => {
  if (canManage.value) return '考试管理'
  if (isCompanyView.value) return '全公司只读'
  if (isStoreView.value) return '本门店进度'
  return '我的考试'
})
const campaigns = computed(() => overview.value?.campaigns || [])
const assignments = computed(() => overview.value?.assignments || [])
const myAssignments = computed(() => assignments.value.filter((item) => item.userId === auth.user?.id))
const stores = computed(() => {
  const values = new Map<string, string>()
  for (const candidate of overview.value?.candidates || []) values.set(candidate.storeId, candidate.storeName)
  return Array.from(values.entries()).map(([id, name]) => ({ id, name }))
})
const metrics = computed(() => {
  const rows = campaigns.value
  return {
    active: rows.filter((item) => item.statusLabel === '进行中').length,
    assigned: rows.reduce((sum, item) => sum + item.assignedCount, 0),
    completed: rows.reduce((sum, item) => sum + item.completedCount, 0),
    passed: rows.reduce((sum, item) => sum + item.passedCount, 0),
    overdue: rows.reduce((sum, item) => sum + item.overdueCount, 0),
    average: weightedAverage(rows.map((item) => ({ value: item.averageScore, weight: item.completedCount }))),
  }
})

function emptyQuestion(): ExamQuestionInput {
  return {
    questionType: 'SINGLE_CHOICE',
    questionText: '',
    options: ['', ''],
    standardAnswer: '',
    acceptKeywords: '',
    score: 10,
  }
}

function emptyPaper(): ExamPaperSavePayload {
  return {
    paperCode: '',
    paperName: '',
    roleScope: 'EMPLOYEE,STORE_MANAGER',
    passScore: 80,
    enabled: true,
    questions: [emptyQuestion()],
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await getExamCenterOverview()
    if (publishForm.paperId === 0 && overview.value.papers.length) {
      publishForm.paperId = overview.value.papers[0].id
    }
  } catch (reason) {
    overview.value = null
    error.value = displayError(reason, '考试中心加载失败，请刷新后重试。')
  } finally {
    loading.value = false
  }
}

function openCreatePaper() {
  delete paperForm.id
  Object.assign(paperForm, emptyPaper())
  paperEditorOpen.value = true
}

async function openEditPaper(paperId: number) {
  error.value = ''
  try {
    const paper = await getExamPaperForEdit(paperId)
    Object.assign(paperForm, {
      id: paper.id,
      paperCode: paper.paperCode,
      paperName: paper.paperName,
      roleScope: paper.roleScope || '',
      passScore: paper.passScore,
      enabled: paper.enabled,
      questions: paper.questions.map((question) => ({
        questionType: question.questionType,
        questionText: question.questionText,
        options: [...question.options],
        standardAnswer: question.standardAnswer,
        acceptKeywords: question.acceptKeywords || '',
        score: question.score,
      })),
    })
    paperEditorOpen.value = true
  } catch (reason) {
    error.value = displayError(reason, '试卷读取失败。')
  }
}

function addQuestion() {
  paperForm.questions.push(emptyQuestion())
}

function removeQuestion(index: number) {
  if (paperForm.questions.length === 1) return
  paperForm.questions.splice(index, 1)
}

function updateOptions(question: ExamQuestionInput, value: string) {
  question.options = value.split(/\r?\n/)
}

async function savePaper() {
  saving.value = true
  error.value = ''
  successMessage.value = ''
  try {
    await saveExamPaper({
      ...paperForm,
      paperCode: paperForm.paperCode?.trim() || undefined,
      paperName: paperForm.paperName.trim(),
      questions: paperForm.questions.map((item) => ({
        ...item,
        questionText: item.questionText.trim(),
        standardAnswer: item.standardAnswer.trim(),
        acceptKeywords: item.acceptKeywords?.trim(),
        options: item.options.map((value) => value.trim()).filter(Boolean),
        score: Number(item.score || 0),
      })),
    })
    paperEditorOpen.value = false
    successMessage.value = '试卷已保存。'
    await load()
  } catch (reason) {
    error.value = displayError(reason, '试卷保存失败。')
  } finally {
    saving.value = false
  }
}

function openPublish() {
  if (!overview.value?.papers.length) {
    error.value = '请先新建一份试卷。'
    return
  }
  Object.assign(publishForm, {
    paperId: overview.value.papers[0].id,
    title: '',
    startAt: toInputDateTime(new Date()),
    dueAt: toInputDateTime(new Date(Date.now() + 7 * 86400000)),
    storeIds: [],
    targetRoles: ['EMPLOYEE', 'STORE_MANAGER'],
    userIds: [],
  })
  targetMode.value = 'scope'
  publishEditorOpen.value = true
}

async function publish() {
  saving.value = true
  error.value = ''
  successMessage.value = ''
  try {
    await publishExam({
      paperId: publishForm.paperId,
      title: publishForm.title.trim(),
      startAt: publishForm.startAt,
      dueAt: publishForm.dueAt,
      storeIds: targetMode.value === 'scope' ? publishForm.storeIds : [],
      targetRoles: targetMode.value === 'scope' ? publishForm.targetRoles : [],
      userIds: targetMode.value === 'users' ? publishForm.userIds : [],
    })
    publishEditorOpen.value = false
    successMessage.value = '考试已发布并分配到应考账号。'
    await load()
  } catch (reason) {
    error.value = displayError(reason, '考试发布失败。')
  } finally {
    saving.value = false
  }
}

async function openCampaign(campaignId: number) {
  detailLoading.value = true
  error.value = ''
  try {
    selectedDetail.value = await getExamCampaign(campaignId)
  } catch (reason) {
    error.value = displayError(reason, '考试成绩加载失败。')
  } finally {
    detailLoading.value = false
  }
}

async function exportResults() {
  if (!selectedDetail.value) return
  error.value = ''
  try {
    await downloadExamResults(selectedDetail.value.campaign.id, selectedDetail.value.campaign.title)
  } catch (reason) {
    error.value = displayError(reason, '考试成绩导出失败。')
  }
}

async function startExam(assignment: ExamAssignment) {
  if (assignment.userId !== auth.user?.id) return
  error.value = ''
  try {
    activePaper.value = await getAssignedExamPaper(assignment.id)
    activeAssignment.value = assignment
    Object.keys(answers).forEach((key) => delete answers[Number(key)])
    switchCount.value = 0
    violated.value = false
  } catch (reason) {
    error.value = displayError(reason, '试卷加载失败。')
  }
}

function closeExam() {
  if (submitting.value) return
  activeAssignment.value = null
  activePaper.value = null
}

async function submitExam() {
  if (!activeAssignment.value || !activePaper.value) return
  const unanswered = activePaper.value.questions.some((question) => !String(answers[question.id] || '').trim())
  if (unanswered) {
    error.value = '请完成全部题目后再提交。'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const result = await submitAssignedExam(activeAssignment.value.id, {
      violated: violated.value,
      answers: activePaper.value.questions.map((question) => ({
        questionId: question.id,
        userAnswer: answers[question.id] || '',
      })),
    })
    successMessage.value = result.passed
      ? `考试已提交，得分 ${formatNumber(result.score)}，已通过。`
      : `考试已提交，得分 ${formatNumber(result.score)}，未通过。`
    closeExam()
    await load()
  } catch (reason) {
    error.value = displayError(reason, '考试提交失败。')
  } finally {
    submitting.value = false
  }
}

function handleVisibilityChange() {
  if (!activeAssignment.value || !document.hidden) return
  switchCount.value += 1
  if (switchCount.value >= 3) violated.value = true
}

function statusTone(status: string) {
  if (status === 'COMPLETED' || status === '进行中') return 'ok'
  if (status === 'OVERDUE' || status === '已逾期') return 'bad'
  if (status === 'ASSIGNED' || status === 'NOT_STARTED' || status === '未开始') return 'warn'
  return 'info'
}

function formatNumber(value?: number) {
  return Number(value || 0).toFixed(1).replace(/\.0$/, '')
}

function formatRate(value?: number) {
  return `${formatNumber(value)}%`
}

function displayError(reason: unknown, fallback: string) {
  return reason instanceof Error && reason.message ? reason.message : fallback
}

function weightedAverage(rows: Array<{ value: number; weight: number }>) {
  const weight = rows.reduce((sum, row) => sum + row.weight, 0)
  if (!weight) return 0
  return rows.reduce((sum, row) => sum + row.value * row.weight, 0) / weight
}

function toInputDateTime(date: Date) {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000)
  return local.toISOString().slice(0, 16)
}

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  void load()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

<template>
  <section class="legacy-page exam-center-page">
    <div class="legacy-page-head">
      <div class="exam-page-title">
        <GraduationCap :size="24" />
        <div><h2>培训考试</h2><span>{{ pageModeLabel }}</span></div>
      </div>
      <div class="legacy-head-actions">
        <button v-if="canManage" class="legacy-button" type="button" @click="openCreatePaper">
          <FilePlus2 :size="16" />新建试卷
        </button>
        <button v-if="canManage" class="legacy-button primary-action" type="button" @click="openPublish">
          <Plus :size="16" />发布考试
        </button>
        <button class="legacy-button" type="button" :disabled="loading" @click="load">
          <RefreshCw :size="16" />刷新
        </button>
      </div>
    </div>

    <div v-if="error" class="error-box page-message">{{ error }}</div>
    <div v-if="successMessage" class="success-box page-message">{{ successMessage }}</div>

    <div v-if="loading && !overview" class="legacy-card exam-loading">正在读取考试数据...</div>
    <template v-else-if="overview">
      <section class="exam-kpis legacy-card">
        <div><span>进行中</span><b>{{ metrics.active }}</b><small>场</small></div>
        <div><span>应考人数</span><b>{{ metrics.assigned }}</b><small>人</small></div>
        <div><span>已完成</span><b>{{ metrics.completed }}</b><small>人</small></div>
        <div><span>已通过</span><b>{{ metrics.passed }}</b><small>人</small></div>
        <div :class="{ danger: metrics.overdue }"><span>逾期</span><b>{{ metrics.overdue }}</b><small>人</small></div>
        <div><span>平均分</span><b>{{ formatNumber(metrics.average) }}</b><small>分</small></div>
      </section>

      <section v-if="myAssignments.length" class="legacy-card exam-section">
        <div class="section-heading"><h3>我的考试</h3></div>
        <div class="assignment-list">
          <article v-for="item in myAssignments" :key="item.id" class="assignment-row">
            <div><b>{{ item.examTitle }}</b><span>{{ item.paperName }}</span></div>
            <div><span>截止时间</span><b>{{ item.dueAt }}</b></div>
            <div><span>状态</span><b><em class="status-badge" :class="statusTone(item.status)">{{ item.statusLabel }}</em></b></div>
            <div v-if="item.status === 'COMPLETED'"><span>成绩</span><b>{{ formatNumber(item.score) }} 分 · {{ item.passed ? '通过' : '未通过' }}</b></div>
            <button v-else-if="item.status === 'ASSIGNED'" class="mini-button primary" type="button" @click="startExam(item)">
              <Play :size="14" />开始考试
            </button>
          </article>
        </div>
      </section>

      <section class="legacy-card exam-section">
        <div class="section-heading"><h3>{{ isStoreView ? '本店考试进度' : '考试安排' }}</h3></div>
        <div v-if="!campaigns.length" class="exam-empty">暂无考试安排。</div>
        <div v-else class="campaign-list">
          <button v-for="campaign in campaigns" :key="campaign.id" class="campaign-row" type="button" @click="openCampaign(campaign.id)">
            <div class="campaign-main"><b>{{ campaign.title }}</b><span>{{ campaign.paperName }}</span></div>
            <div><span>状态</span><b><em class="status-badge" :class="statusTone(campaign.statusLabel)">{{ campaign.statusLabel }}</em></b></div>
            <div><span>完成</span><b>{{ campaign.completedCount }}/{{ campaign.assignedCount }} · {{ formatRate(campaign.completionRate) }}</b></div>
            <div><span>通过</span><b>{{ campaign.passedCount }} 人 · {{ formatRate(campaign.passRate) }}</b></div>
            <div :class="{ danger: campaign.overdueCount }"><span>逾期</span><b>{{ campaign.overdueCount }} 人</b></div>
            <div><span>截止时间</span><b>{{ campaign.dueAt }}</b></div>
          </button>
        </div>
      </section>

      <section v-if="selectedDetail" class="legacy-card exam-section">
        <div class="section-heading detail-heading">
          <div><h3>{{ selectedDetail.campaign.title }}</h3></div>
          <button v-if="overview.canExport" class="mini-button" type="button" @click="exportResults"><Download :size="14" />导出成绩</button>
        </div>
        <div v-if="detailLoading" class="exam-empty">正在读取成绩...</div>
        <div v-else-if="!selectedDetail.assignments.length" class="exam-empty">暂无应考人员。</div>
        <div v-else class="result-list">
          <div v-for="item in selectedDetail.assignments" :key="item.id" class="result-row">
            <div><b>{{ item.examineeName }}</b><span>{{ item.storeName }}</span></div>
            <span>{{ item.statusLabel }}</span>
            <span>{{ item.completedAt || item.dueAt }}</span>
            <b>{{ item.score === undefined ? '-' : `${formatNumber(item.score)} 分` }}</b>
            <em class="status-badge" :class="item.passed === true ? 'ok' : item.passed === false ? 'bad' : statusTone(item.status)">
              {{ item.passed === true ? '通过' : item.passed === false ? '未通过' : item.statusLabel }}
            </em>
          </div>
        </div>
      </section>

      <section v-if="canManage" class="legacy-card exam-section">
        <div class="section-heading"><h3>试卷管理</h3></div>
        <div v-if="!overview.papers.length" class="exam-empty">暂无试卷。</div>
        <div v-else class="paper-list">
          <div v-for="paper in overview.papers" :key="paper.id" class="paper-row">
            <div><b>{{ paper.paperName }}</b><span>{{ paper.paperCode }}</span></div>
            <span>{{ paper.questionCount }} 题</span>
            <span>通过 {{ formatNumber(paper.passScore) }} 分</span>
            <em class="status-badge" :class="paper.enabled ? 'ok' : 'bad'">{{ paper.enabled ? '启用' : '停用' }}</em>
            <button class="mini-button" type="button" @click="openEditPaper(paper.id)">编辑</button>
          </div>
        </div>
      </section>
    </template>

    <section v-if="activeAssignment && activePaper" class="exam-overlay">
      <form class="exam-take-panel" @submit.prevent="submitExam">
        <div class="drawer-head">
          <div><h3>{{ activeAssignment.examTitle }}</h3><span>{{ activePaper.paperName }} · 通过分 {{ activePaper.passScore }}</span></div>
          <button class="icon-button" type="button" aria-label="关闭考试" :disabled="submitting" @click="closeExam"><X :size="18" /></button>
        </div>
        <div class="switch-warning" :class="{ danger: violated }">切出页面 {{ switchCount }}/3 次；达到 3 次将记为违规。</div>
        <div class="question-list">
          <fieldset v-for="(question, index) in activePaper.questions" :key="question.id" class="take-question">
            <legend>{{ index + 1 }}. {{ question.questionText }}（{{ question.score }} 分）</legend>
            <label v-for="option in question.options" v-if="question.questionType === 'SINGLE_CHOICE'" :key="option" class="choice-option">
              <input v-model="answers[question.id]" type="radio" :name="`question-${question.id}`" :value="option" />
              <span>{{ option }}</span>
            </label>
            <input v-else v-model.trim="answers[question.id]" class="answer-input" :type="question.questionType === 'NUMBER' ? 'number' : 'text'" placeholder="请输入答案" />
          </fieldset>
        </div>
        <div class="exam-submit-bar">
          <button class="ghost-button" type="button" :disabled="submitting" @click="closeExam">暂不作答</button>
          <button class="primary-button inline-primary" type="submit" :disabled="submitting"><Send :size="16" />{{ submitting ? '正在提交' : '提交考试' }}</button>
        </div>
      </form>
    </section>

    <div v-if="paperEditorOpen" class="drawer-backdrop" @click.self="paperEditorOpen = false">
      <form class="exam-drawer paper-drawer" @submit.prevent="savePaper">
        <div class="drawer-head"><h3>{{ paperForm.id ? '编辑试卷' : '新建试卷' }}</h3><button class="icon-button" type="button" @click="paperEditorOpen = false"><X :size="18" /></button></div>
        <div class="drawer-body">
          <div class="form-row two-columns">
            <label>试卷名称<input v-model.trim="paperForm.paperName" placeholder="例如：食品安全月度考试" /></label>
            <label>试卷编号<input v-model.trim="paperForm.paperCode" placeholder="留空自动生成" /></label>
            <label>通过分数<input v-model.number="paperForm.passScore" type="number" min="0" step="0.5" /></label>
            <label>适用角色<input v-model.trim="paperForm.roleScope" placeholder="EMPLOYEE,STORE_MANAGER" /></label>
          </div>
          <div class="question-editor-head"><b>题目</b><button class="mini-button" type="button" @click="addQuestion"><Plus :size="14" />添加题目</button></div>
          <article v-for="(question, index) in paperForm.questions" :key="index" class="question-editor">
            <div class="question-number"><b>第 {{ index + 1 }} 题</b><button class="icon-button" type="button" title="删除题目" :disabled="paperForm.questions.length === 1" @click="removeQuestion(index)"><Trash2 :size="15" /></button></div>
            <div class="form-row two-columns">
              <label>题型<select v-model="question.questionType"><option value="SINGLE_CHOICE">单选题</option><option value="TEXT">文字题</option><option value="NUMBER">数字题</option></select></label>
              <label>分值<input v-model.number="question.score" type="number" min="0.5" step="0.5" /></label>
              <label class="full-row">题目内容<textarea v-model.trim="question.questionText" rows="2" /></label>
              <label v-if="question.questionType === 'SINGLE_CHOICE'" class="full-row">选项（每行一个）<textarea :value="question.options.join('\n')" rows="4" @input="updateOptions(question, ($event.target as HTMLTextAreaElement).value)" /></label>
              <label>标准答案<input v-model.trim="question.standardAnswer" /></label>
              <label>可接受关键词<input v-model.trim="question.acceptKeywords" placeholder="多个关键词用逗号分隔" /></label>
            </div>
          </article>
        </div>
        <div class="drawer-actions"><button class="ghost-button" type="button" @click="paperEditorOpen = false">取消</button><button class="primary-button inline-primary" type="submit" :disabled="saving">{{ saving ? '正在保存' : '保存试卷' }}</button></div>
      </form>
    </div>

    <div v-if="publishEditorOpen" class="drawer-backdrop" @click.self="publishEditorOpen = false">
      <form class="exam-drawer" @submit.prevent="publish">
        <div class="drawer-head"><h3>发布考试</h3><button class="icon-button" type="button" @click="publishEditorOpen = false"><X :size="18" /></button></div>
        <div class="drawer-body">
          <div class="form-row">
            <label>考试名称<input v-model.trim="publishForm.title" placeholder="例如：7 月食品安全考试" /></label>
            <label>试卷<select v-model.number="publishForm.paperId"><option v-for="paper in overview?.papers" :key="paper.id" :value="paper.id">{{ paper.paperName }}</option></select></label>
            <label>开始时间<input v-model="publishForm.startAt" type="datetime-local" /></label>
            <label>截止时间<input v-model="publishForm.dueAt" type="datetime-local" /></label>
          </div>
          <div class="target-tabs"><button type="button" :class="{ active: targetMode === 'scope' }" @click="targetMode = 'scope'">按门店和角色</button><button type="button" :class="{ active: targetMode === 'users' }" @click="targetMode = 'users'">指定人员</button></div>
          <template v-if="targetMode === 'scope'">
            <div class="picker-block"><b>应考门店</b><label v-for="store in stores" :key="store.id"><input v-model="publishForm.storeIds" type="checkbox" :value="store.id" /><span>{{ store.name }}</span></label><span v-if="!stores.length" class="picker-empty">暂无绑定考试账号的门店。</span></div>
            <div class="picker-block"><b>应考角色</b><label><input v-model="publishForm.targetRoles" type="checkbox" value="EMPLOYEE" /><span>员工</span></label><label><input v-model="publishForm.targetRoles" type="checkbox" value="STORE_MANAGER" /><span>店长</span></label><label><input v-model="publishForm.targetRoles" type="checkbox" value="SUPERVISOR" /><span>督导</span></label><label><input v-model="publishForm.targetRoles" type="checkbox" value="WAREHOUSE" /><span>仓库管理员</span></label></div>
          </template>
          <div v-else class="picker-block candidate-picker"><b>应考人员</b><label v-for="candidate in overview?.candidates" :key="candidate.userId"><input v-model="publishForm.userIds" type="checkbox" :value="candidate.userId" /><span>{{ candidate.displayName }} · {{ candidate.storeName }} · {{ candidate.roleLabel }}</span></label><span v-if="!overview?.candidates.length" class="picker-empty">暂无可分配考试的账号。</span></div>
        </div>
        <div class="drawer-actions"><button class="ghost-button" type="button" @click="publishEditorOpen = false">取消</button><button class="primary-button inline-primary" type="submit" :disabled="saving">{{ saving ? '正在发布' : '发布考试' }}</button></div>
      </form>
    </div>
  </section>
</template>

<style scoped>
.exam-center-page { gap: 12px; }
.exam-page-title, .section-heading, .detail-heading, .question-editor-head, .question-number, .drawer-head, .drawer-actions, .exam-submit-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.exam-page-title { justify-content: flex-start; }
.exam-page-title h2, .section-heading h3, .drawer-head h3 { margin: 0; }
.exam-page-title span { color: var(--muted); font-size: 12px; }
.primary-action, .inline-primary { border: 0; background: var(--primary); color: #fff; }
.inline-primary { width: auto; margin: 0; }
.page-message { margin: 0; }
.exam-loading { color: var(--muted); text-align: center; }
.exam-kpis { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); padding: 0; overflow: hidden; }
.exam-kpis > div { min-width: 0; padding: 13px 14px; border-right: 1px solid var(--line); background: #f8f9fa; }
.exam-kpis > div:last-child { border-right: 0; }
.exam-kpis span, .exam-kpis small, .campaign-row span, .assignment-row span, .paper-row > span, .result-row span, .drawer-head span { color: var(--muted); font-size: 12px; }
.exam-kpis b { display: inline-block; margin: 3px 5px 0 0; font-size: 24px; }
.danger b, .danger { color: var(--bad) !important; }
.exam-section { display: grid; gap: 10px; padding: 15px; border-radius: 8px; }
.section-heading h3 { font-size: 17px; }
.assignment-list, .campaign-list, .paper-list, .result-list { display: grid; gap: 0; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
.assignment-row, .campaign-row, .paper-row, .result-row { display: grid; align-items: center; gap: 10px; padding: 11px 12px; border: 0; border-bottom: 1px solid var(--line); background: #fff; color: var(--ink); text-align: left; }
.assignment-row:last-child, .campaign-row:last-child, .paper-row:last-child, .result-row:last-child { border-bottom: 0; }
.assignment-row { grid-template-columns: minmax(180px, 1.5fr) minmax(150px, 1fr) minmax(100px, .7fr) minmax(160px, 1fr) auto; }
.campaign-row { grid-template-columns: minmax(200px, 1.5fr) repeat(5, minmax(105px, .8fr)); cursor: pointer; }
.campaign-row:hover { background: var(--primary-soft); }
.campaign-main span, .assignment-row > div > span, .paper-row > div > span, .result-row > div > span { display: block; }
.paper-row { grid-template-columns: minmax(220px, 1.5fr) repeat(3, minmax(90px, .7fr)) auto; }
.result-row { grid-template-columns: minmax(180px, 1.2fr) minmax(80px, .6fr) minmax(150px, 1fr) minmax(70px, .5fr) auto; }
.exam-empty { padding: 24px 12px; color: var(--muted); text-align: center; }
.drawer-backdrop, .exam-overlay { position: fixed; inset: 0; z-index: 55; display: flex; justify-content: flex-end; background: rgba(20, 24, 32, .38); }
.exam-overlay { z-index: 60; align-items: center; justify-content: center; padding: 20px; }
.exam-drawer { display: flex; flex-direction: column; width: min(560px, 100vw); height: 100%; background: #fff; box-shadow: -12px 0 32px rgba(22, 26, 34, .18); }
.paper-drawer { width: min(760px, 100vw); }
.drawer-head, .drawer-actions { flex-shrink: 0; padding: 15px 18px; border-bottom: 1px solid var(--line); }
.drawer-actions { justify-content: flex-end; border-top: 1px solid var(--line); border-bottom: 0; }
.drawer-body { display: grid; align-content: start; gap: 14px; min-height: 0; overflow-y: auto; padding: 18px; }
.form-row { display: grid; gap: 12px; }
.two-columns { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.form-row label { display: grid; gap: 6px; color: var(--muted); font-size: 12px; font-weight: 800; }
.form-row input, .form-row select, .form-row textarea, .answer-input { width: 100%; min-height: 40px; padding: 9px 10px; border: 1px solid var(--line); border-radius: 7px; background: #fff; color: var(--ink); outline: 0; resize: vertical; }
.form-row input:focus, .form-row select:focus, .form-row textarea:focus, .answer-input:focus { border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft); }
.full-row { grid-column: 1 / -1; }
.question-editor { display: grid; gap: 10px; padding: 12px; border: 1px solid var(--line); border-radius: 7px; }
.target-tabs { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; padding: 4px; border-radius: 7px; background: #f2f3f5; }
.target-tabs button { min-height: 36px; border: 0; border-radius: 5px; background: transparent; font-weight: 800; }
.target-tabs button.active { background: #fff; color: var(--primary); box-shadow: 0 1px 4px rgba(20, 24, 32, .12); }
.picker-block { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; max-height: 260px; overflow-y: auto; padding: 12px; border: 1px solid var(--line); border-radius: 7px; }
.picker-block > b, .picker-empty { grid-column: 1 / -1; }
.picker-block label { display: flex; align-items: center; gap: 8px; min-width: 0; font-size: 13px; }
.picker-block label span { min-width: 0; overflow-wrap: anywhere; }
.candidate-picker { grid-template-columns: 1fr; }
.picker-empty { color: var(--muted); font-size: 13px; }
.exam-take-panel { display: flex; flex-direction: column; width: min(860px, 100%); max-height: calc(100vh - 40px); border-radius: 8px; background: #fff; overflow: hidden; }
.switch-warning { padding: 8px 18px; background: #fff7ed; color: var(--warn); font-size: 12px; font-weight: 700; }
.switch-warning.danger { background: #fff4f2; }
.question-list { display: grid; gap: 12px; min-height: 0; overflow-y: auto; padding: 16px 18px; }
.take-question { display: grid; gap: 8px; margin: 0; padding: 12px; border: 1px solid var(--line); border-radius: 7px; }
.take-question legend { padding: 0 5px; font-weight: 800; }
.choice-option { display: flex; align-items: center; gap: 8px; padding: 7px 9px; border: 1px solid var(--line); border-radius: 6px; }
.exam-submit-bar { flex-shrink: 0; justify-content: flex-end; padding: 12px 18px; border-top: 1px solid var(--line); }

@media (max-width: 900px) {
  .exam-kpis { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .exam-kpis > div:nth-child(3n) { border-right: 0; }
  .exam-kpis > div:nth-child(-n + 3) { border-bottom: 1px solid var(--line); }
  .assignment-row, .campaign-row, .paper-row, .result-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .assignment-row > div:first-child, .campaign-main, .paper-row > div:first-child, .result-row > div:first-child { grid-column: 1 / -1; }
}

@media (max-width: 520px) {
  .exam-page-title { align-items: flex-start; }
  .legacy-head-actions { display: grid; grid-template-columns: 1fr 1fr; width: 100%; }
  .legacy-head-actions .legacy-button { width: 100%; }
  .legacy-head-actions .legacy-button:last-child { grid-column: 1 / -1; }
  .exam-kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .exam-kpis > div { border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); }
  .exam-kpis > div:nth-child(even) { border-right: 0; }
  .exam-kpis > div:nth-last-child(-n + 2) { border-bottom: 0; }
  .assignment-row, .campaign-row, .paper-row, .result-row, .two-columns, .picker-block { grid-template-columns: 1fr; }
  .assignment-row > *, .campaign-row > *, .paper-row > *, .result-row > *, .picker-block > b, .picker-empty { grid-column: 1; }
  .drawer-body { padding: 14px; }
  .exam-overlay { align-items: stretch; padding: 0; }
  .exam-take-panel { width: 100%; max-height: 100dvh; border-radius: 0; }
  .exam-submit-bar .ghost-button, .exam-submit-bar .primary-button { flex: 1; }
}
</style>
