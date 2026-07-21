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
  color: $mobile-ink;
  background: $mobile-canvas;
  font-family: $mobile-font-body;
  font-size: 28rpx;
  -webkit-font-smoothing: antialiased;
}

view, text, input, textarea, button { box-sizing: border-box; }
button, .tap-target { min-height: 88rpx; }
button { font-family: inherit; }

.mobile-page {
  width: min(100%, 1000rpx);
  min-height: 100vh;
  margin: 0 auto;
  padding: 24rpx 32rpx calc(48rpx + env(safe-area-inset-bottom));
}

.mobile-stack { display: flex; flex-direction: column; gap: 24rpx; }
.mobile-section-title { color: $mobile-ink; font-size: 30rpx; font-weight: 750; }
.mobile-muted { color: $mobile-muted; font-size: 25rpx; line-height: 1.6; }

.mobile-input {
  width: 100%;
  min-height: 92rpx;
  padding: 0 28rpx;
  color: $mobile-ink;
  background: $mobile-surface;
  border: 2rpx solid $mobile-line;
  border-radius: 16rpx;
  font-size: 30rpx;
}

.mobile-input:focus { border-color: $mobile-orange; background: #ffffff; box-shadow: 0 0 0 4rpx rgba(39, 101, 95, 0.08); }

.mobile-primary-button {
  min-height: 92rpx;
  margin: 0;
  color: #ffffff;
  background: $mobile-orange;
  border-radius: 14rpx;
  font-size: 30rpx;
  font-weight: 750;
  line-height: 92rpx;
}

.mobile-primary-button::after { border: 0; }
.mobile-primary-button[disabled] { opacity: 0.52; }

.mobile-ghost-button {
  min-height: 88rpx;
  margin: 0;
  padding: 0 28rpx;
  color: $mobile-orange-dark;
  background: $mobile-surface;
  border: 2rpx solid #bed9d4;
  border-radius: 14rpx;
  font-size: 26rpx;
  font-weight: 700;
  line-height: 84rpx;
}

.mobile-ghost-button::after { border: 0; }

.mobile-feedback { transition: opacity 120ms ease; }
.mobile-feedback:active { opacity: 0.7; }
</style>
