<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileRectificationReviews, getMyMobileRectifications, reviewMobileRectification, submitMobileRectification, uploadMobileRectificationEvidence } from '@/api/business'
import ProtectedAttachmentList, { type ProtectedAttachment } from '@/components/ProtectedAttachmentList.vue'
import { normalizeRole } from '@/permissions'
import { chooseImages } from '@/platform'
import { canUseMobileCapability, useSessionStore } from '@/stores'
import type { InspectionRectification } from '@/types/business'

const session = useSessionStore()
const tasks = ref<InspectionRectification[]>([])
const loading = ref(false)
const acting = ref('')
const error = ref('')
const notice = ref('')
const notes = ref<Record<string, string>>({})
const evidence = ref<Record<string, number[]>>({})
const isReviewer = computed(() => normalizeRole(session.user?.role) === 'SUPERVISOR')
const canUse = computed(() => canUseMobileCapability(session.user, 'rectification'))

onShow(() => { void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (!canUse.value || loading.value) return
  loading.value = true; error.value = ''
  try { tasks.value = isReviewer.value ? await getMobileRectificationReviews() : await getMyMobileRectifications() }
  catch (cause) { error.value = Number((cause as { status?: number })?.status) === 403 ? '当前账号无权访问这些整改记录。' : '整改任务暂时无法加载，请检查网络后重试。' }
  finally { loading.value = false }
}

async function addEvidence(task: InspectionRectification) {
  if (acting.value) return
  acting.value = task.recordId; error.value = ''
  try { const files = await chooseImages({ count: 3, source: 'both' }); for (const file of files) { const uploaded = await uploadMobileRectificationEvidence(task.recordId, file.path); evidence.value[task.recordId] = [...(evidence.value[task.recordId] || task.evidenceAttachmentIds || []), uploaded.attachmentId] }; notice.value = '证据已上传，提交后进入运营复核。' }
  catch { error.value = '证据上传失败，请检查网络后重试。' }
  finally { acting.value = '' }
}

async function submit(task: InspectionRectification) {
  const note = String(notes.value[task.recordId] || '').trim(); const attachmentIds = evidence.value[task.recordId] || task.evidenceAttachmentIds || []
  if (!note || !attachmentIds.length) { error.value = '请填写整改说明并上传至少一张整改现场照片。'; return }
  acting.value = task.recordId; error.value = ''
  try { await submitMobileRectification(task.recordId, { note, attachmentIds }); notice.value = '整改已提交，等待运营复核。'; await refresh() }
  catch (cause) { error.value = Number((cause as { status?: number })?.status) === 409 ? '整改状态已变化，请刷新后重试。' : '整改提交失败，请检查填写内容后重试。' }
  finally { acting.value = '' }
}

async function review(task: InspectionRectification, decision: 'APPROVED' | 'REJECTED') {
  if (decision === 'APPROVED' && !task.evidenceAttachmentIds?.length) {
    error.value = '未找到整改现场照片，不能通过复核。'
    return
  }
  const note = String(notes.value[task.recordId] || '').trim(); if (!note) { error.value = '请填写复核备注。'; return }
  acting.value = task.recordId; error.value = ''
  try { await reviewMobileRectification(task.recordId, { decision, note }); notice.value = decision === 'APPROVED' ? '整改已通过复核。' : '整改已驳回，店长可补充证据后再次提交。'; await refresh() }
  catch { error.value = '复核未完成，请刷新后重试。' }
  finally { acting.value = '' }
}

function reviewAttachments(task: InspectionRectification): ProtectedAttachment[] {
  return (task.evidenceAttachmentIds || []).map((attachmentId, index) => ({
    id: attachmentId,
    fileName: `整改现场照片 ${index + 1}`,
    contentType: 'image/*',
    path: `/api/storage/attachments/${attachmentId}`,
  }))
}
</script>

<template>
  <view class="page"><view class="head"><view><text class="eyebrow">{{ isReviewer ? '运营复核' : '门店整改' }}</text><text class="title">整改与复核</text></view><button :loading="loading" :disabled="loading" @click="refresh">刷新</button></view>
    <view v-if="!canUse" class="state">当前账号暂无整改处理权限。</view><template v-else><view v-if="error" class="message error">{{ error }}</view><view v-if="notice" class="message ok">{{ notice }}</view><view v-if="loading" class="state">正在读取整改任务…</view><view v-else-if="!tasks.length" class="state">当前没有需要处理的整改记录</view>
      <view v-for="task in tasks" :key="task.recordId" class="card"><text class="card-title">{{ task.storeName || task.storeId }} · {{ task.statusLabel || task.status }}</text><text class="copy">{{ task.requirement || '请按巡检要求完成整改。' }}</text><text class="copy">巡检日期：{{ task.inspectionDate || '—' }}</text><view v-if="isReviewer" class="evidence-panel"><text class="evidence-title">整改现场照片（{{ task.evidenceAttachmentIds?.length || 0 }}）</text><ProtectedAttachmentList :items="reviewAttachments(task)" @error="error = $event"/><text v-if="!task.evidenceAttachmentIds?.length" class="evidence-missing">未提交整改照片，不能通过复核。</text></view><textarea v-model="notes[task.recordId]" class="note" maxlength="4000" :placeholder="isReviewer ? '填写通过或驳回的复核备注' : '填写整改说明'" :disabled="Boolean(acting)" />
        <template v-if="!isReviewer && ['PENDING_SUBMISSION','REJECTED'].includes(task.status)"><button class="plain" :loading="acting === task.recordId" :disabled="Boolean(acting)" @click="addEvidence(task)">拍照或从相册上传证据</button><text class="copy">已关联 {{ (evidence[task.recordId] || task.evidenceAttachmentIds || []).length }} 张证据</text><button class="primary" :loading="acting === task.recordId" :disabled="Boolean(acting)" @click="submit(task)">提交整改</button></template>
        <template v-else-if="isReviewer && task.status === 'PENDING_REVIEW'"><button class="primary" :loading="acting === task.recordId" :disabled="Boolean(acting)" @click="review(task, 'APPROVED')">通过复核</button><button class="plain" :loading="acting === task.recordId" :disabled="Boolean(acting)" @click="review(task, 'REJECTED')">驳回整改</button></template></view></template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:28rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));background:#f2f6f5;color:#1c1d22}.head{display:flex;align-items:center;justify-content:space-between;margin-bottom:30rpx}.eyebrow,.title,.card-title,.copy,.evidence-title,.evidence-missing{display:block}.eyebrow{color:#71807d;font-size:23rpx;letter-spacing: 0}.title{margin-top:6rpx;font-size: 38rpx;font-weight:750}.head button,.card button{min-height:88rpx;border-radius: 16rpx;font-size:27rpx}.head button{min-width:128rpx;margin:0;background:#fff;color:#1f5752;border:1rpx solid #d9e6e3}.head button::after,.card button::after{border:0}.card,.state{margin-top:16rpx;padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius: 16rpx}.card-title{font-size:29rpx;font-weight:700}.copy{margin-top:9rpx;color:#71807d;font-size:24rpx;line-height:1.55}.evidence-panel{margin-top:18rpx;padding:18rpx;border:1rpx solid #d9e6e3;border-radius:14rpx;background:#f8faf9}.evidence-title{font-size:25rpx;font-weight:700}.evidence-missing{margin-top:12rpx;color:#963b30;font-size:24rpx}.note{width:100%;min-height:120rpx;box-sizing:border-box;margin-top:18rpx;padding:16rpx;border:1rpx solid #d9e6e3;border-radius:14rpx;background:#fff;font-size:25rpx}.primary{margin-top:16rpx;background:#27655f;color:#fff}.plain{margin-top:16rpx;background:#e6f3f1;color:#27655f}.state{text-align:center;color:#71807d}.message{margin-bottom:16rpx;padding:18rpx;border-radius:14rpx;font-size:25rpx}.error{background:#fff0ed;color:#963b30}.ok{background:#eaf5ed;color:#24663e}
</style>
