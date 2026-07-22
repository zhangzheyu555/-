<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useContextStore, useMenuStore, useSessionStore } from '@/stores'
import { runtimePlatform } from '@/platform'

const session = useSessionStore()
const menu = useMenuStore()
const context = useContextStore()
const username = ref('')
const password = ref('')
const showPassword = ref(false)
const errorMessage = ref('')
const weChatAvailable = runtimePlatform() === 'mp-weixin'

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

async function weChatSignIn(): Promise<void> {
  errorMessage.value = ''
  try {
    const code = await requestWeChatCode()
    await session.loginWithWeChatCode(code)
    menu.rebuild(session.user)
    await context.load(session.user)
    uni.reLaunch({ url: '/pages/home/index' })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '微信登录失败，请稍后重试'
  }
}

function requestWeChatCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    // #ifdef MP-WEIXIN
    uni.login({
      provider: 'weixin',
      success: ({ code }) => code ? resolve(code) : reject(new Error('未获取到微信授权凭据，请重试')),
      fail: () => reject(new Error('微信授权未完成，请重试')),
    })
    // #endif
    // #ifndef MP-WEIXIN
    reject(new Error('请在微信小程序中使用微信一键登录'))
    // #endif
  })
}
</script>

<template>
  <view class="login-page">
    <view class="login-intro">
      <view class="brand-lockup">
        <view class="brand-mark" aria-hidden="true"><text>门</text></view>
        <text class="brand-name">门店经营</text>
      </view>
      <text class="login-intro__eyebrow">AI PROFIT OS · 移动工作台</text>
      <text class="login-intro__title">多门店经营管理</text>
      <text class="login-intro__copy">库存、叫货、巡检、整改与学习任务，统一在这里处理。</text>
    </view>

    <view class="login-sheet">
      <view class="login-sheet__heading">
        <text class="login-sheet__title">登录工作台</text>
        <text class="login-sheet__hint">使用已有账号进入当前职责范围</text>
      </view>
      <view class="login-fields">
        <label class="login-field">
          <text class="login-field__label">账号</text>
          <input v-model="username" class="mobile-input" type="text" placeholder="请输入账号" :disabled="session.submitting" />
        </label>
        <label class="login-field">
          <text class="login-field__label">密码</text>
          <view class="password-field">
            <input
              v-model="password"
              class="mobile-input password-input"
              :password="!showPassword"
              placeholder="请输入密码"
              :disabled="session.submitting"
              @confirm="submit"
            />
            <button class="password-toggle" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
              {{ showPassword ? '隐藏' : '显示' }}
            </button>
          </view>
        </label>
      </view>
      <view v-if="errorMessage" class="login-error" role="alert">{{ errorMessage }}</view>
      <button class="mobile-primary-button" :disabled="session.submitting" :loading="session.submitting" @click="submit">
        登录工作台
      </button>
      <button v-if="weChatAvailable" class="wechat-login-button" :disabled="session.submitting" :loading="session.submitting" @click="weChatSignIn">
        微信一键登录
      </button>
      <text v-if="weChatAvailable" class="wechat-hint">首次使用请先以账号密码登录，并在“我的”中绑定微信。</text>
      <text class="login-security">本机仅保存登录凭据，业务数据始终从服务器读取。</text>
      <text class="login-legal">登录即表示同意企业内部使用规范与隐私保护规则</text>
    </view>
  </view>
</template>

<style scoped lang="scss">
.login-page {
  min-height: 100vh;
  padding: calc(72rpx + env(safe-area-inset-top)) 32rpx calc(40rpx + env(safe-area-inset-bottom));
  background: $mobile-canvas;
}
.login-intro { display: flex; max-width: 680rpx; margin: 34rpx auto 44rpx; flex-direction: column; align-items: flex-start; text-align: left; }
.brand-lockup { display: flex; align-items: center; gap: 16rpx; }
.brand-mark { display: flex; width: 48rpx; height: 48rpx; align-items: center; justify-content: center; color: #ffffff; background: #71bcb4; border-radius: 12rpx; font-size: 26rpx; font-weight: 800; }
.brand-name { color: $mobile-ink; font-size: 38rpx; font-weight: 900; }
.login-intro__eyebrow { margin-top: 48rpx; color: $mobile-orange; font-size: 21rpx; font-weight: 700; letter-spacing: 1rpx; }
.login-intro__title { margin-top: 12rpx; color: $mobile-ink; font-family: $mobile-font-display; font-size: 42rpx; font-weight: 800; line-height: 1.3; }
.login-intro__copy { max-width: 560rpx; margin-top: 14rpx; color: $mobile-muted; font-size: 25rpx; line-height: 1.65; }
.login-sheet { max-width: 760rpx; margin: 0 auto; padding: 36rpx 32rpx; background: #ffffff; border: 1rpx solid $mobile-line; border-radius: 14rpx; box-shadow: 0 12rpx 32rpx rgba(30, 77, 71, 0.06); }
.login-sheet__heading { display: flex; margin-bottom: 32rpx; flex-direction: column; gap: 8rpx; }
.login-sheet__title { color: $mobile-ink; font-size: 36rpx; font-weight: 800; }
.login-sheet__hint { color: $mobile-muted; font-size: 24rpx; }
.login-fields { display: flex; margin-bottom: 28rpx; flex-direction: column; gap: 24rpx; }
.login-field { display: flex; flex-direction: column; gap: 12rpx; }
.login-field__label { color: $mobile-ink; font-size: 25rpx; font-weight: 700; }
.login-error { margin: 0 0 22rpx; padding: 18rpx 22rpx; color: #97362f; background: #fff0ed; border-radius: 16rpx; font-size: 25rpx; line-height: 1.5; }
.password-field { position: relative; }
.password-input { padding-right: 124rpx; }
.password-toggle { position: absolute; top: 2rpx; right: 2rpx; min-width: 112rpx; min-height: 88rpx; margin: 0; padding: 0 18rpx; color: $mobile-orange-dark; background: transparent; border: 0; border-radius: 14rpx; font-size: 24rpx; line-height: 88rpx; }
.password-toggle::after { border: 0; }
.login-sheet > .mobile-primary-button { display: flex; width: 100%; border: 0; }
.login-security { display: block; margin-top: 22rpx; color: $mobile-muted; font-size: 22rpx; line-height: 1.6; text-align: center; }
.wechat-login-button { display: flex; width: 100%; min-height: 88rpx; margin: 18rpx 0 0; color: #fff; background: #07c160; border: 0; border-radius: 14rpx; font-size: 28rpx; font-weight: 700; line-height: 88rpx; }
.wechat-login-button::after { border: 0; }.wechat-hint { display: block; margin-top: 14rpx; color: $mobile-muted; font-size: 21rpx; line-height: 1.55; text-align: center; }
.login-legal { display: block; margin-top: 10rpx; color: #a1a6af; font-size: 20rpx; line-height: 1.5; text-align: center; }
</style>
