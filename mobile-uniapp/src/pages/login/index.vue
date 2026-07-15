<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useContextStore, useMenuStore, useSessionStore } from '@/stores'

const session = useSessionStore()
const menu = useMenuStore()
const context = useContextStore()
const username = ref('')
const password = ref('')
const errorMessage = ref('')

onShow(async () => {
  if (!session.user && session.token) await session.restore()
  if (session.isAuthenticated) uni.reLaunch({ url: '/pages/home/index' })
})

async function submit(): Promise<void> {
  errorMessage.value = ''
  if (!username.value.trim() || !password.value) {
    errorMessage.value = '请输入账号和密码'
    return
  }
  try {
    await session.login({ username: username.value.trim(), password: password.value })
    menu.rebuild(session.user)
    await context.load(session.user)
    password.value = ''
    uni.reLaunch({ url: '/pages/home/index' })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败，请稍后重试'
  }
}
</script>

<template>
  <view class="login-page">
    <view class="login-mark" aria-hidden="true">
      <view class="login-mark__line" />
      <text class="login-mark__name">AI PROFIT OS</text>
    </view>

    <view class="login-intro">
      <text class="login-intro__eyebrow">移动工作台</text>
      <text class="login-intro__title">把今天的门店任务，稳稳接住</text>
      <text class="login-intro__copy">库存、叫货、巡检和学习共用正式账号与数据权限。</text>
    </view>

    <view class="login-sheet">
      <view class="login-sheet__heading">
        <text class="login-sheet__title">账号登录</text>
        <text class="login-sheet__hint">使用现有 AI Profit OS 账号</text>
      </view>
      <view class="login-fields">
        <label class="login-field">
          <text class="login-field__label">账号</text>
          <input v-model="username" class="mobile-input" type="text" placeholder="请输入账号" :disabled="session.submitting" />
        </label>
        <label class="login-field">
          <text class="login-field__label">密码</text>
          <input v-model="password" class="mobile-input" type="password" placeholder="请输入密码" :disabled="session.submitting" @confirm="submit" />
        </label>
      </view>
      <view v-if="errorMessage" class="login-error" role="alert">{{ errorMessage }}</view>
      <button class="mobile-primary-button" :disabled="session.submitting" :loading="session.submitting" @click="submit">
        登录移动工作台
      </button>
      <text class="login-security">本机仅保存登录凭据，业务数据始终从服务器读取。</text>
    </view>
  </view>
</template>

<style scoped lang="scss">
.login-page {
  min-height: 100vh;
  padding: calc(80rpx + env(safe-area-inset-top)) 36rpx calc(48rpx + env(safe-area-inset-bottom));
  background:
    radial-gradient(circle at 90% 5%, rgba(47, 95, 167, 0.12), transparent 30%),
    linear-gradient(150deg, #eef5f1 0%, #f7f9f8 58%, #edf2ef 100%);
}
.login-mark { display: flex; align-items: center; gap: 16rpx; }
.login-mark__line { width: 48rpx; height: 8rpx; background: $mobile-green; border-radius: 99rpx; }
.login-mark__name { color: $mobile-ink; font-size: 22rpx; font-weight: 800; letter-spacing: 3rpx; }
.login-intro { display: flex; max-width: 620rpx; margin: 76rpx 0 56rpx; flex-direction: column; }
.login-intro__eyebrow { color: $mobile-green; font-size: 24rpx; font-weight: 750; letter-spacing: 4rpx; }
.login-intro__title { margin-top: 18rpx; color: $mobile-ink; font-family: $mobile-font-display; font-size: 56rpx; font-weight: 800; line-height: 1.2; letter-spacing: -2rpx; }
.login-intro__copy { margin-top: 22rpx; color: $mobile-muted; font-size: 27rpx; line-height: 1.7; }
.login-sheet { max-width: 760rpx; margin: 0 auto; padding: 40rpx 34rpx; background: rgba(255, 255, 255, 0.94); border: 1rpx solid rgba(214, 225, 219, 0.9); border-radius: 36rpx 36rpx 36rpx 12rpx; box-shadow: 0 28rpx 70rpx rgba(23, 48, 40, 0.12); }
.login-sheet__heading { display: flex; margin-bottom: 32rpx; flex-direction: column; gap: 8rpx; }
.login-sheet__title { color: $mobile-ink; font-size: 36rpx; font-weight: 800; }
.login-sheet__hint { color: $mobile-muted; font-size: 24rpx; }
.login-fields { display: flex; margin-bottom: 28rpx; flex-direction: column; gap: 24rpx; }
.login-field { display: flex; flex-direction: column; gap: 12rpx; }
.login-field__label { color: $mobile-ink; font-size: 25rpx; font-weight: 700; }
.login-error { margin: 0 0 22rpx; padding: 18rpx 22rpx; color: #97362f; background: #fff0ed; border-radius: 16rpx; font-size: 25rpx; line-height: 1.5; }
.login-security { display: block; margin-top: 22rpx; color: $mobile-muted; font-size: 22rpx; line-height: 1.6; text-align: center; }
</style>

