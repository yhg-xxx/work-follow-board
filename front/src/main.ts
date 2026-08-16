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

// 与 index.html 内联脚本保持一致；SPA 接管后再确认一次，避免状态漂移。
// 存储/媒体查询在隐私模式或受限 WebView 下可能不可用，必须兜底，
// 否则此处抛异常会阻断 app.mount() 导致整页白屏。
function readStoredTheme(): 'light' | 'dark' | null {
  try {
    const s = localStorage.getItem('tmo-theme')
    return s === 'light' || s === 'dark' ? s : null
  } catch {
    return null
  }
}
function systemPrefersDark(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  )
}
applyTheme(readStoredTheme() || (systemPrefersDark() ? 'dark' : 'light'))

const app = createApp(App)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
