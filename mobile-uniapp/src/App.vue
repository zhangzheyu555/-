<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { AUTH_EXPIRED_EVENT } from '@/platform'
import { useContextStore, useMenuStore, useSessionStore } from '@/stores'

const session = useSessionStore()
const menu = useMenuStore()
const context = useContextStore()

onLaunch(async () => {
  uni.$on(AUTH_EXPIRED_EVENT, () => {
    session.clear()
    menu.clear()
    context.clear()
  })

  if (!await session.restore()) return
  menu.rebuild(session.user)
  await context.load(session.user)

  const pages = getCurrentPages()
  const currentRoute = pages[pages.length - 1]?.route || ''
  if (currentRoute === 'pages/login/index') {
    uni.reLaunch({ url: '/pages/home/index' })
  }
})
</script>

<style lang="scss">
page {
  min-height: 100%;
  box-sizing: border-box;
  padding-bottom: calc(180rpx + env(safe-area-inset-bottom));
  color: $mobile-ink;
  background: $mobile-canvas;
  font-family: $mobile-font-body;
  font-size: 28rpx;
  -webkit-font-smoothing: antialiased;
}

view, text, input, textarea, button { box-sizing: border-box; }

/*
 * 小程序原生输入控件在不同机型上的默认高度并不一致。这里建立统一的
 * 表单基线，避免页面私有样式遗漏高度或 box-sizing 后，动态明细出现
 * 输入框贴边、互相覆盖或被安全区裁切的情况。
 */
input:not([type='checkbox']):not([type='radio']),
textarea {
  display: block;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  min-height: 88rpx !important;
  box-sizing: border-box !important;
}

textarea {
  min-height: 160rpx !important;
  line-height: 1.55;
}

picker {
  display: block;
  min-width: 0;
  max-width: 100%;
}

picker > view {
  display: flex !important;
  align-items: center;
  width: 100%;
  min-width: 0;
  min-height: 88rpx !important;
  box-sizing: border-box;
}

input::placeholder,
textarea::placeholder { color: #9aa5a2; }
/*
 * 全局按钮基线：各业务页只负责主次/危险语义，不再各自定义高度、圆角和
 * 原生边框，避免同一操作区的按钮看起来大小不一。
 */
button, .tap-target { min-height: 80rpx; }
button {
  display: inline-flex;
  max-width: 100%;
  min-width: 0;
  margin: 0;
  padding: 0 24rpx;
  align-items: center;
  justify-content: center;
  color: #1f5752;
  background: #ffffff;
  border: 1rpx solid #cfe1dd;
  border-radius: 12rpx;
  font-family: inherit;
  font-size: 26rpx;
  font-weight: 650;
  line-height: 1.25;
  white-space: normal;
}

button::after { border: 0; }
button[disabled] { opacity: 0.48; }
button:active:not([disabled]) { opacity: 0.76; }

/* 筛选与页签是紧凑型控件，保持比表单操作按钮更低的高度。 */
.tab-row button,
.filter-row button,
.tabs button,
.status-tab,
.step-button { min-height: 64rpx; padding: 0 18rpx; font-size: 24rpx; }

/* 统一业务语义；页面可覆盖颜色，但不应重新定义按钮结构。 */
button.primary,
button.primary-button,
button.filter-primary,
button.mobile-primary-button { color: #fff; background: #27655f; border-color: #27655f; }

button.plain,
button.secondary,
button.secondary-button,
button.mobile-ghost-button { color: #1f5752; background: #e6f3f1; border-color: #bed9d4; }

button.danger { color: #963b30; background: #fff0ed; border-color: #f2c8c0; }

.mobile-page {
  width: min(100%, 1000rpx);
  min-height: 100vh;
  margin: 0 auto;
  padding: 28rpx 24rpx calc(40rpx + env(safe-area-inset-bottom));
}

.mobile-stack { display: flex; flex-direction: column; gap: 24rpx; }
.mobile-section-title { color: $mobile-ink; font-size: 30rpx; font-weight: 750; }
.mobile-muted { color: $mobile-muted; font-size: 25rpx; line-height: 1.6; }

.mobile-input {
  width: 100%;
  min-height: 92rpx;
  padding: 0 28rpx;
  color: $mobile-ink;
  background: $mobile-surface-strong;
  border: 1rpx solid transparent;
  border-radius: 20rpx;
  font-size: 30rpx;
}

.mobile-input:focus { border-color: $mobile-green; background: #ffffff; }

.mobile-primary-button {
  min-height: 92rpx;
  margin: 0;
  color: #ffffff;
  background: $mobile-green;
  border-radius: 22rpx;
  font-size: 30rpx;
  font-weight: 750;
  line-height: 92rpx;
}

.mobile-primary-button::after { border: 0; }
.mobile-primary-button[disabled] { opacity: 0.52; }
</style>
