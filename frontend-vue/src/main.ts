import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import './styles/base.css'
import './styles/design-system.css'
import './styles/responsive.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia).use(router)
useAuthStore(pinia).bindSessionInvalidation()
app.mount('#app')
