<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow, onUnload } from '@dcloudio/uni-app'
import { getMobileTrainingVideos, reportMobileTrainingProgress } from '../../api/business'
import { prepareProtectedMedia, type PreparedMedia } from '../../platform'
import { canUseMobileCapability, useSessionStore } from '../../stores'
import type { TrainingVideo } from '../../types/business'

const session = useSessionStore()
const videos = ref<TrainingVideo[]>([])
const activeVideo = ref<TrainingVideo | null>(null)
const prepared = ref<PreparedMedia | null>(null)
const mediaUrl = ref('')
const position = ref(0)
const duration = ref(0)
const loading = ref(false)
const preparing = ref(false)
const reporting = ref(false)
const errorMessage = ref('')
const actionMessage = ref('')
const playbackRetryCount = ref(0)
const resumeAt = ref(0)

const canLearn = computed(() => canUseMobileCapability(session.user, 'learning'))
const enabledVideos = computed(() => videos.value.filter((video) => video.enabled))

onShow(() => {
  if (!canLearn.value) return denyAndReturn()
  void refresh()
})
onUnload(() => releaseMedia())
onPullDownRefresh(async () => {
  await refresh()
  uni.stopPullDownRefresh()
})

async function refresh() {
  if (!canLearn.value || loading.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    videos.value = await getMobileTrainingVideos()
    if (activeVideo.value) {
      activeVideo.value = videos.value.find((video) => video.id === activeVideo.value?.id) || null
    }
  } catch (error) {
    errorMessage.value = friendlyError(error, '培训视频暂时无法加载，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function play(video: TrainingVideo) {
  if (preparing.value) return
  preparing.value = true
  errorMessage.value = ''
  actionMessage.value = ''
  releaseMedia()
  try {
    prepared.value = await prepareProtectedMedia(video.id)
    mediaUrl.value = prepared.value.url
    activeVideo.value = video
    position.value = Number(video.myLastPosition || 0)
    resumeAt.value = position.value
    duration.value = Number(video.durationSeconds || 0)
    playbackRetryCount.value = 0
  } catch (error) {
    errorMessage.value = friendlyError(error, '视频加载失败，请检查网络后重试。')
  } finally {
    preparing.value = false
  }
}

async function handlePlaybackError() {
  if (!activeVideo.value || preparing.value) return
  if (playbackRetryCount.value >= 1) {
    errorMessage.value = '视频播放失败，请检查网络后重新打开。'
    return
  }
  playbackRetryCount.value += 1
  preparing.value = true
  const videoId = activeVideo.value.id
  resumeAt.value = position.value
  releaseMedia()
  try {
    prepared.value = await prepareProtectedMedia(videoId)
    mediaUrl.value = prepared.value.url
    actionMessage.value = '播放连接已更新，将从当前位置继续。'
  } catch (error) {
    errorMessage.value = friendlyError(error, '视频播放连接更新失败，请重新打开视频。')
  } finally {
    preparing.value = false
  }
}

function updateProgress(event: unknown) {
  const detail = (event as { detail?: { currentTime?: number; duration?: number } })?.detail
  position.value = Number(detail?.currentTime || 0)
  duration.value = Number(detail?.duration || duration.value || 0)
}

async function reportProgress(completed = false) {
  if (!activeVideo.value || reporting.value || duration.value <= 0) return
  reporting.value = true
  try {
    const result = await reportMobileTrainingProgress(activeVideo.value.id, {
      positionSeconds: completed ? duration.value : position.value,
      durationSeconds: duration.value,
    })
    activeVideo.value.myLastPosition = result.lastPosition
    activeVideo.value.myWatchedSeconds = result.watchedSeconds
    activeVideo.value.myPercent = result.percent
    activeVideo.value.myCompleted = result.completed
    actionMessage.value = result.completed ? '学习已完成，进度已保存。' : `学习进度已保存：${Math.round(result.percent)}%`
  } catch (error) {
    errorMessage.value = friendlyError(error, '学习进度未保存，请恢复网络后重试。')
  } finally {
    reporting.value = false
  }
}

function releaseMedia() {
  prepared.value?.revoke?.()
  prepared.value = null
  mediaUrl.value = ''
}

function formatDuration(seconds: number | undefined) {
  const total = Math.max(0, Math.round(Number(seconds || 0)))
  const minutes = Math.floor(total / 60)
  const remain = total % 60
  return `${minutes}:${String(remain).padStart(2, '0')}`
}

function friendlyError(error: unknown, fallback: string) {
  const status = Number((error as { status?: number })?.status || 0)
  if (status === 403) return '当前账号没有培训学习权限。'
  if (status === 401) return '登录已过期，请重新登录。'
  return fallback
}

function denyAndReturn() {
  uni.showToast({ title: '培训视频仅向员工开放', icon: 'none' })
  setTimeout(() => uni.reLaunch({ url: '/pages/home/index' }), 500)
}
</script>

<template>
  <view class="page">
    <view class="page-head">
      <view><text class="eyebrow">员工学习</text><text class="title">培训视频</text></view>
      <button class="ghost-button" :loading="loading" :disabled="loading || !canLearn" @click="refresh">刷新</button>
    </view>

    <view v-if="!canLearn" class="state-card">培训视频仅向员工开放，并需具备后端学习权限。</view>
    <template v-else>
      <view v-if="errorMessage" class="message error">{{ errorMessage }}</view>
      <view v-if="actionMessage" class="message success">{{ actionMessage }}</view>

      <view v-if="activeVideo && mediaUrl" class="player-card">
        <text class="player-title">{{ activeVideo.title }}</text>
        <text class="muted">{{ activeVideo.courseTitle || activeVideo.category || '培训课程' }}</text>
        <video
          class="video"
          :src="mediaUrl"
          :initial-time="resumeAt"
          :header="prepared?.headers || {}"
          :http-cache="false"
          controls
          @timeupdate="updateProgress"
          @pause="reportProgress(false)"
          @ended="reportProgress(true)"
          @error="handlePlaybackError"
        />
        <view class="progress-row">
          <text>{{ formatDuration(position) }} / {{ formatDuration(duration) }}</text>
          <text>{{ Math.round(activeVideo.myPercent || 0) }}%</text>
        </view>
        <button class="save-button" :loading="reporting" :disabled="reporting" @click="reportProgress(false)">保存学习进度</button>
      </view>

      <view class="section-head"><text class="section-title">可学习课程</text><text class="muted">{{ enabledVideos.length }} 个视频</text></view>
      <view v-if="loading && !enabledVideos.length" class="state-card">正在读取培训内容…</view>
      <view v-else-if="!enabledVideos.length" class="state-card">暂无已发布培训视频</view>
      <view v-for="video in enabledVideos" :key="video.id" class="video-card">
        <view class="video-copy">
          <view class="card-head">
            <text class="card-title">{{ video.title }}</text>
            <text class="progress-chip" :class="{ done: video.myCompleted }">{{ video.myCompleted ? '已完成' : `${Math.round(video.myPercent || 0)}%` }}</text>
          </view>
          <text class="muted">{{ video.courseTitle || video.category || '培训课程' }} · {{ formatDuration(video.durationSeconds) }}</text>
          <text v-if="video.description" class="description">{{ video.description }}</text>
        </view>
        <button class="play-button" :loading="preparing && activeVideo?.id === video.id" :disabled="preparing" @click="play(video)">
          {{ video.myPercent > 0 && !video.myCompleted ? '继续学习' : '开始学习' }}
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; box-sizing: border-box; padding: 24rpx; background: #f4f6f2; color: #172019; }
.page-head, .section-head, .card-head, .progress-row { display: flex; align-items: center; justify-content: space-between; gap: 18rpx; }
.page-head { margin-bottom: 24rpx; }
.eyebrow, .title, .player-title, .muted, .section-title, .card-title, .description { display: block; }
.eyebrow { color: #657168; font-size: 24rpx; letter-spacing: 2rpx; }
.title { margin-top: 6rpx; font-size: 44rpx; font-weight: 700; }
.player-card, .video-card, .state-card { margin-bottom: 18rpx; padding: 24rpx; border: 1px solid #dce2db; border-radius: 22rpx; background: #fff; box-shadow: 0 8rpx 24rpx rgba(27,45,32,.045); }
.player-title, .section-title, .card-title { font-size: 30rpx; font-weight: 700; }
.video { width: 100%; height: 390rpx; margin-top: 18rpx; border-radius: 18rpx; background: #101511; }
.progress-row { padding: 16rpx 2rpx 6rpx; color: #566259; font-size: 24rpx; }
.section-head { margin: 32rpx 2rpx 16rpx; }
.muted { margin-top: 6rpx; color: #6b746d; font-size: 23rpx; line-height: 1.5; }
.description { margin-top: 12rpx; color: #4d5850; font-size: 24rpx; line-height: 1.55; }
.progress-chip { flex-shrink: 0; padding: 7rpx 12rpx; border-radius: 12rpx; background: #fff0d8; color: #8a5b18; font-size: 22rpx; }
.progress-chip.done { background: #e7f3ea; color: #20623c; }
.play-button, .save-button, .ghost-button { min-height: 88rpx; line-height: 88rpx; border-radius: 18rpx; font-size: 27rpx; }
.play-button, .save-button { margin-top: 18rpx; background: #1f6741; color: #fff; }
.ghost-button { min-width: 136rpx; background: #fff; color: #264c36; border: 1px solid #cbd6cd; }
.message { margin-bottom: 18rpx; padding: 18rpx 20rpx; border-radius: 14rpx; font-size: 25rpx; }
.message.error { background: #fff0ed; color: #963b30; }
.message.success { background: #eaf5ed; color: #24663e; }
.state-card { text-align: center; color: #657168; }
button::after { border: 0; }
</style>
