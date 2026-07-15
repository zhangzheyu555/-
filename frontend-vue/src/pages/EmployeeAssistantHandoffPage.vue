<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CheckCircle2, ClipboardList, RefreshCw, Send } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import UiButton from '../components/ui/UiButton.vue'
import { useAuthStore } from '../stores/auth'
import {
  claimEmployeeAssistantHandoff,
  closeEmployeeAssistantHandoff,
  managedEmployeeAssistantHandoffs,
  replyEmployeeAssistantHandoff,
  type EmployeeAssistantHandoff,
} from '../api/employeeAssistant'

const auth = useAuthStore()
const handoffs = ref<EmployeeAssistantHandoff[]>([])
const loading = ref(true)
const workingId = ref('')
const activeId = ref('')
const resolution = ref('')
const pageError = ref('')
const notice = ref('')

const active = computed(() => handoffs.value.find((item) => item.id === activeId.value) || null)

onMounted(() => { void load() })

async function load() {
  loading.value = true
  pageError.value = ''
  try {
    handoffs.value = await managedEmployeeAssistantHandoffs()
    if (!activeId.value && handoffs.value[0]) select(handoffs.value[0])
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '人工事项加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function select(record: EmployeeAssistantHandoff) {
  activeId.value = record.id
  resolution.value = record.resolution || ''
  notice.value = ''
}

async function claim(record: EmployeeAssistantHandoff) {
  await action(record, () => claimEmployeeAssistantHandoff(record.id), '已领取人工事项。')
}

async function reply(record: EmployeeAssistantHandoff) {
  await action(record, () => replyEmployeeAssistantHandoff(record.id, resolution.value), '已回复人工事项。')
}

async function close(record: EmployeeAssistantHandoff) {
  await action(record, () => closeEmployeeAssistantHandoff(record.id, resolution.value), '已关闭人工事项。')
}

async function action(record: EmployeeAssistantHandoff, request: () => Promise<EmployeeAssistantHandoff>, success: string) {
  if (workingId.value) return
  workingId.value = record.id
  pageError.value = ''
  try {
    const result = await request()
    const index = handoffs.value.findIndex((item) => item.id === result.id)
    if (index >= 0) handoffs.value.splice(index, 1, result)
    select(result)
    notice.value = success
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '人工事项操作失败，请稍后重试。'
  } finally {
    workingId.value = ''
  }
}

function canHandle(record: EmployeeAssistantHandoff) {
  return record.handledBy === auth.user?.id && ['CLAIMED', 'IN_PROGRESS'].includes(record.status)
}

function statusLabel(status: EmployeeAssistantHandoff['status']) {
  return ({ OPEN: '待领取', CLAIMED: '已领取', IN_PROGRESS: '处理中', CLOSED: '已关闭', EXPIRED: '已过期' })[status] || status
}
</script>

<template>
  <section class="employee-handoff-page">
    <PageHeader subtitle="仅授权的运营或督导可查看门店范围内的人工事项；列表只显示已脱敏问题。">
      <template #actions><UiButton :loading="loading" @click="load"><template #icon><RefreshCw :size="16" /></template>刷新</UiButton></template>
    </PageHeader>
    <p v-if="pageError" class="error-box" role="alert">{{ pageError }}</p>
    <p v-if="notice" class="notice-box" role="status">{{ notice }}</p>

    <section class="handoff-layout">
      <aside class="content-card handoff-list"><div class="section-heading"><ClipboardList :size="19" /><div><h2>人工事项</h2><p>按创建时间倒序。</p></div></div><div v-if="loading" class="empty-state">正在加载人工事项…</div><div v-else-if="!handoffs.length" class="empty-state">暂无可处理的人工事项。</div><button v-for="record in handoffs" v-else :key="record.id" class="handoff-item" :class="{ active: record.id === activeId }" type="button" @click="select(record)"><strong>{{ record.category }}</strong><span :class="['status-tag', record.status.toLowerCase()]">{{ statusLabel(record.status) }}</span><p>{{ record.question }}</p><small>{{ record.storeId || '未关联门店' }} · {{ record.id }}</small></button></aside>
      <section class="content-card detail-panel"><div v-if="!active" class="empty-state">选择一条人工事项查看处理内容。</div><template v-else><div class="detail-heading"><div><div class="row-title"><h2>{{ active.category }}</h2><span :class="['status-tag', active.status.toLowerCase()]">{{ statusLabel(active.status) }}</span></div><p>发起人：{{ active.requestedByName || '员工' }} · 门店：{{ active.storeId || '未关联门店' }}</p></div></div><div class="question-box"><span>脱敏问题</span><p>{{ active.question }}</p></div><div v-if="active.resolution" class="resolution-box"><span>处理结论</span><p>{{ active.resolution }}</p></div><template v-if="active.status === 'OPEN'"><p class="hint">领取后可回复和关闭。结论不会回写到原始聊天记录。</p><UiButton variant="primary" :loading="workingId === active.id" @click="claim(active)"><template #icon><CheckCircle2 :size="16" /></template>领取事项</UiButton></template><template v-else-if="canHandle(active)"><label>处理结论<textarea v-model="resolution" rows="6" maxlength="2000" placeholder="填写已向员工说明的处理结论" /></label><div class="detail-actions"><UiButton :loading="workingId === active.id" @click="reply(active)"><template #icon><Send :size="16" /></template>保存回复</UiButton><UiButton variant="primary" :loading="workingId === active.id" @click="close(active)"><template #icon><CheckCircle2 :size="16" /></template>关闭事项</UiButton></div></template><p v-else class="hint">该事项由 {{ active.handledByName || '其他处理人' }} 处理，当前仅可查看脱敏进度。</p></template></section>
    </section>
  </section>
</template>

<style scoped>
.employee-handoff-page { display: grid; gap: 18px; }.error-box, .notice-box { margin: 0; padding: 12px 14px; border-radius: 8px; font-size: 14px; }.error-box { border: 1px solid #efc9cf; background: #fff5f5; color: #9f2734; }.notice-box { border: 1px solid #bfe5d8; background: #f1fbf7; color: #276b65; }.handoff-layout { display: grid; grid-template-columns: minmax(280px, .75fr) minmax(0, 1.45fr); gap: 18px; align-items: start; }.handoff-list { display: grid; gap: 8px; padding: 14px; }.section-heading { display: flex; gap: 9px; align-items: flex-start; padding: 6px; color: var(--ds-primary-hover); }.section-heading h2, .detail-heading h2 { margin: 0; color: var(--ds-ink); font-size: 17px; }.section-heading p, .detail-heading p { margin: 3px 0 0; color: var(--ds-muted); font-size: 12px; line-height: 1.5; }.handoff-item { display: grid; grid-template-columns: 1fr auto; gap: 6px; width: 100%; padding: 12px; border: 1px solid transparent; border-radius: 8px; background: transparent; color: inherit; text-align: left; cursor: pointer; }.handoff-item:hover, .handoff-item.active { border-color: #bfe0dc; background: #f1fbf7; }.handoff-item strong { color: var(--ds-ink); font-size: 13px; }.handoff-item p, .handoff-item small { grid-column: 1 / -1; margin: 0; color: var(--ds-secondary); font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; }.handoff-item small { color: var(--ds-muted); font-size: 11px; }.status-tag { align-self: start; padding: 3px 7px; border-radius: 999px; font-size: 11px; font-weight: 800; }.status-tag.open { background: #fff1db; color: #9a6814; }.status-tag.claimed, .status-tag.in_progress { background: #e8f4f3; color: #276b65; }.status-tag.closed { background: #e7f7ef; color: #237153; }.status-tag.expired { background: #edf2f3; color: #566f6b; }.detail-panel { display: grid; gap: 16px; min-height: 430px; }.detail-heading { padding-bottom: 14px; border-bottom: 1px solid var(--ds-line); }.row-title { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }.question-box, .resolution-box { display: grid; gap: 7px; padding: 13px; border: 1px solid var(--ds-line); border-radius: 8px; background: #fbfcfc; }.question-box span, .resolution-box span, label { color: var(--ds-secondary); font-size: 13px; font-weight: 800; }.question-box p, .resolution-box p { margin: 0; white-space: pre-wrap; color: var(--ds-ink); font-size: 14px; line-height: 1.6; }.resolution-box { background: #f1fbf7; }.hint { margin: 0; color: var(--ds-muted); font-size: 13px; line-height: 1.5; }label { display: grid; gap: 7px; }textarea { width: 100%; min-width: 0; border: 1px solid var(--ds-line); border-radius: 8px; padding: 10px 11px; background: #fff; color: var(--ds-ink); font: inherit; font-weight: 400; line-height: 1.5; resize: vertical; }textarea:focus { outline: 3px solid rgba(39, 107, 101, .16); border-color: var(--ds-primary); }.detail-actions { display: flex; justify-content: flex-end; gap: 10px; flex-wrap: wrap; }.empty-state { display: grid; min-height: 180px; place-items: center; color: var(--ds-muted); text-align: center; font-size: 14px; }
@media (max-width: 860px) { .handoff-layout { grid-template-columns: 1fr; }.handoff-list { max-height: 330px; overflow-y: auto; } }@media (max-width: 560px) { .detail-actions { flex-direction: column; }.detail-actions :deep(.ui-button) { width: 100%; } }
</style>
