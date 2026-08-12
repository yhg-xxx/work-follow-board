import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 官方深色变量：由 <html>.dark 触发，与我们的 [data-theme=dark] 叠加
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'
// 主题初始化（读取持久化 / 系统偏好并落到 <html>）；必须在挂载前执行
import { applyTheme } from './composables/useTheme'
// 业务令牌（base.css + main.css）必须最后引入，以覆盖 Element Plus 默认色
import './assets/main.css'

// 与 index.html 内联脚本保持一致；SPA 接管后再确认一次，避免状态漂移
applyTheme(
  (localStorage.getItem('tmo-theme') as 'light' | 'dark') ||
    (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
)

const app = createApp(App)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
