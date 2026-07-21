<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileTrainingProgressReport } from '@/api/business'
import { canUseMobileCapability, useSessionStore } from '@/stores'
import type { TrainingProgressReport } from '@/types/business'

const session = useSessionStore()
const rows = ref<TrainingProgressReport[]>([])
const loading = ref(false)
const message = ref('')
const canRead = computed(() => canUseMobileCapability(session.user, 'trainingProgress'))
const completed = computed(() => rows.value.filter((row) => row.completed).length)
const learnerCount = computed(() => new Set(rows.value.map((row) => row.userId)).size)

onShow(() => { void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (!canRead.value || loading.value) return
  loading.value = true
  message.value = ''
  try { rows.value = await getMobileTrainingProgressReport() }
  catch (cause) { message.value = Number((cause as { status?: number })?.status) === 403 ? '当前账号无权查看本店培训进度。' : '培训进度暂时无法加载，请稍后重试。' }
  finally { loading.value = false }
}
function percent(value: number) { return `${Math.round(Number(value || 0))}%` }
</script>

<template>
  <view class="page">
    <view class="head"><view><text class="eyebrow">店长工作台</text><text class="title">培训进度</text></view><button :loading="loading" :disabled="loading" @click="refresh">刷新</button></view>
    <view v-if="!canRead" class="state">本页仅向具备培训统计权限的店长开放。</view>
    <template v-else>
      <view class="summary"><view><text>{{ learnerCount }}</text><text>学习员工</text></view><view><text>{{ completed }}</text><text>已完成课程</text></view><view><text>{{ rows.length }}</text><text>课程记录</text></view></view>
      <view v-if="message" class="notice">{{ message }}</view>
      <view v-if="!rows.length && !loading" class="state">当前门店暂无培训学习记录。</view>
      <view v-for="row in rows" :key="`${row.userId}-${row.videoId}`" class="card">
        <view class="row"><text class="name">{{ row.userName }}</text><text :class="['status', { done: row.completed }]">{{ row.completed ? '已完成' : '学习中' }}</text></view>
        <text class="course">{{ row.videoTitle }}</text><text class="copy">{{ row.storeName || row.storeId || '当前门店' }} · {{ row.videoCategory || '培训课程' }}</text>
        <view class="progress"><view :style="{ width: `${Math.min(100, Math.max(0, Number(row.percent || 0)))}%` }"/></view>
        <view class="row"><text class="copy">已学习 {{ Math.round(Number(row.watchedSeconds || 0)) }} 秒</text><text class="percent">{{ percent(row.percent) }}</text></view>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:24rpx;background:#f2f6f5;color:#202124}.head,.row{display:flex;align-items:center;justify-content:space-between;gap:14rpx}.eyebrow,.title,.name,.course,.copy,.status{display:block}.eyebrow,.copy{color:#71807d;font-size:24rpx}.title{margin-top:6rpx;font-size:40rpx;font-weight:750}.head button{margin:0;background:#fff;color:#1f5752}.summary{display:grid;grid-template-columns:repeat(3,1fr);gap:12rpx;margin-top:20rpx}.summary view,.card,.state,.notice{padding:22rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.summary text{display:block}.summary text:first-child{font-size:34rpx;font-weight:750}.summary text:last-child{margin-top:8rpx;color:#71807d;font-size:22rpx}.card{margin-top:16rpx}.name,.course{font-size:29rpx;font-weight:700}.course{margin-top:14rpx}.status{padding:6rpx 12rpx;background:#fff4d9;color:#826625;border-radius:10rpx;font-size:22rpx}.status.done{background:#e5f4eb;color:#24663e}.progress{overflow:hidden;height:12rpx;margin:18rpx 0 12rpx;background:#e7eeec;border-radius:99rpx}.progress view{height:100%;background:#27655f;border-radius:99rpx}.percent{color:#27655f;font-size:24rpx;font-weight:700}.state,.notice{margin-top:18rpx;text-align:center;color:#71807d}.notice{color:#9a493f;background:#fff4f1}
</style>
