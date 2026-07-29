import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { installRuntimeErrorDialogs } from './errors/appErrorDialog'
import { installLegacyErrorDialogAdapter } from './errors/legacyErrorDialogAdapter'
import { useAuthStore } from './stores/auth'
import './styles/base.css'
import './styles/design-system.css'
import './styles/responsive.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia).use(router).use(ElementPlus, { locale: zhCn })
installRuntimeErrorDialogs(app)
useAuthStore(pinia).bindSessionInvalidation()
app.mount('#app')
installLegacyErrorDialogAdapter()
